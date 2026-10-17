package dev.chiller.sessions

import dev.chiller.contracts.EventStore
import dev.chiller.contracts.SessionInfo
import dev.chiller.contracts.StoredEvent
import dev.chiller.contracts.AgentMessage
import dev.chiller.contracts.ToolCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class SessionExportTest {
    @Test fun markdownOrdersMessagesAndIncludesActivityDiffWithSafeFence() = runTest {
        val store = MemoryEvents(listOf(
            event(2, "message", buildJsonObject { put("role", "assistant"); put("content", "done") }),
            event(1, "message", buildJsonObject { put("role", "user"); put("content", "please edit") }),
            event(3, "activity", buildJsonObject {
                put("name", "workspace.edit"); put("target", "src/a.kt"); put("status", "completed")
                put("diff", "@@ -1 +1 @@\n-old\n+new\n```\n context")
            }),
        ))
        val markdown = SessionExporter(store).markdown("s")
        assertTrue(markdown.indexOf("### User") < markdown.indexOf("### Assistant"))
        assertContains(markdown, "Status: **completed**")
        assertContains(markdown, "-old")
        assertContains(markdown, "+new")
        assertContains(markdown, "````diff\n")
        assertContains(markdown, "\n````")
    }

    @Test fun jsonIsVersionedRedactedAndOmitsReplayAtEveryDepth() = runTest {
        val store = MemoryEvents(listOf(event(1, "message", buildJsonObject {
            put("role", "assistant")
            put("content", "Use ordinary variable named apiKeyHolder; Authorization: Bearer abc.def.secret")
            put("replay", "private chain of thought")
            put("metadata", buildJsonObject { put("apiKey", "sk-nested-secret"); put("reasoning", "private too") })
            put("beforeArtifact", "artifact-before")
            put("size", 123)
        }), event(2, "activity", buildJsonObject {
            put("name", "provider.call")
            put("output", "{\"credentials\":{\"clientSecret\":\"nested-tool-secret\"},\"message\":\"Authorization: Bearer nestedBearer\"}")
        })))

        val exported = SessionExporter(store).json("s")
        val root = Json.parseToJsonElement(exported).jsonObject
        assertEquals(1, root.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("s", root.getValue("sessionId").jsonPrimitive.content)
        val payload = root.getValue("events").jsonArray[0].jsonObject.getValue("payload").jsonObject
        assertFalse("replay" in payload)
        assertFalse("replay" in payload.getValue("metadata").jsonObject)
        assertEquals("[REDACTED]", payload.getValue("metadata").jsonObject.getValue("apiKey").jsonPrimitive.content)
        val content = payload.getValue("content").jsonPrimitive.content
        assertContains(content, "apiKeyHolder")
        assertFalse("abc.def.secret" in content)
        assertContains(content, "Bearer [REDACTED]")
        val output = root.getValue("events").jsonArray[1].jsonObject.getValue("payload").jsonObject.getValue("output").jsonPrimitive.content
        val toolJson = Json.parseToJsonElement(output).jsonObject
        assertEquals("[REDACTED]", toolJson.getValue("credentials").jsonObject.getValue("clientSecret").jsonPrimitive.content)
        assertFalse("nestedBearer" in toolJson.getValue("message").jsonPrimitive.content)
        assertContains(root.getValue("redactionNotice").jsonPrimitive.content, "cannot guarantee")
        assertEquals("artifact-before", root.getValue("artifactRefs").jsonArray.single().jsonObject.getValue("artifactId").jsonPrimitive.content)
    }

    @Test fun markdownUsesStoredAssistantOriginAndKeepsLegacyFallback() = runTest {
        val store = MemoryEvents(listOf(
            assistant(1, "deepseek-flash", "MODEL"),
            assistant(2, "other-model:26b", "MODEL"),
            assistant(3, null, "OFFLINE"),
            assistant(4, null, "APPLICATION"),
            event(5, "message", buildJsonObject { put("role", "assistant"); put("content", "legacy") }),
        ))
        val markdown = SessionExporter(store).markdown("s")
        assertContains(markdown, "### Assistant — DeepSeek V4.1 Flash")
        assertContains(markdown, "### Assistant — other-model:26b")
        assertContains(markdown, "### Assistant — Chiller · offline")
        assertContains(markdown, "### Assistant — Chiller")
        assertContains(markdown, "### Assistant\n")
    }

    @Test fun jsonPreservesOriginWhileExcludingReplay() = runTest {
        val exported = SessionExporter(MemoryEvents(listOf(assistant(1, "deepseek-flash", "MODEL")))).json("s")
        val payload = Json.parseToJsonElement(exported).jsonObject
            .getValue("events").jsonArray.single().jsonObject.getValue("payload").jsonObject
        assertEquals("MODEL", payload.getValue("origin").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("deepseek-flash", payload.getValue("origin").jsonObject.getValue("modelId").jsonPrimitive.content)
        assertFalse("replay" in payload)
        assertFalse("synthetic private reasoning" in exported)
    }

    @Test fun commonAssignmentSyntaxIsRedactedWithoutChangingUnrelatedCode() = runTest {
        val store = MemoryEvents(listOf(event(1, "message", buildJsonObject {
            put("role", "user")
            put("content", "val ordinary = \"hello\"\nexport API_KEY='this-is-secret'\nkey_name()")
        })))
        val text = SessionExporter(store).json("s")
        assertFalse("this-is-secret" in text)
        assertContains(text, "val ordinary")
        assertContains(text, "key_name()")
    }

    @Test fun markdownExportsProjectedTranscriptAndHidesActivitiesFromDeletedTurns() = runTest {
        val call = ToolCall("old-call", "workspace.read", "{}")
        val store = MemoryEvents(listOf(
            message(0, AgentMessage("user", "private deleted prompt")),
            message(1, AgentMessage("assistant", toolCalls = listOf(call))),
            event(2, "activity", buildJsonObject { put("name", "workspace.read"); put("target", "private.txt"); put("status", "completed"); put("output", "deleted tool output"); put("toolCallId", "old-call") }),
            message(3, AgentMessage("tool", "deleted result", toolCallId = "old-call")),
            message(4, AgentMessage("assistant", "deleted answer")),
            event(5, "history.remove_turn", buildJsonObject { put("start", 0); put("end", 4); put("expectedCount", 4) }),
            message(6, AgentMessage("user", "visible prompt")),
            message(7, AgentMessage("assistant", "visible answer")),
        ))
        val markdown = SessionExporter(store).markdown("s")
        assertFalse("private deleted prompt" in markdown)
        assertFalse("deleted tool output" in markdown)
        assertFalse("deleted answer" in markdown)
        assertContains(markdown, "visible prompt")
        assertContains(markdown, "visible answer")
    }

    @Test fun reusedProviderCallIdDoesNotExposeDeletedActivity() = runTest {
        val call = ToolCall("reused", "read_file", "{}")
        fun activity(seq: Long, id: String, output: String) = event(seq, "activity", buildJsonObject {
            put("id", id); put("name", "read_file"); put("toolCallId", "reused")
            put("status", "completed"); put("output", output)
        })
        val store = MemoryEvents(listOf(
            message(0, AgentMessage("user", "deleted")),
            message(1, AgentMessage("assistant", toolCalls = listOf(call))),
            activity(2, "old-operation", "PRIVATE OLD ACTIVITY"),
            message(3, AgentMessage("tool", "old", toolCallId = "reused")),
            event(4, "history.remove_turn", buildJsonObject { put("start", 0); put("end", 3); put("expectedCount", 3) }),
            message(5, AgentMessage("user", "new")),
            message(6, AgentMessage("assistant", toolCalls = listOf(call))),
            activity(7, "new-operation", "VISIBLE NEW ACTIVITY"),
            message(8, AgentMessage("tool", "new", toolCallId = "reused")),
        ))
        val markdown = SessionExporter(store).markdown("s")
        assertFalse("PRIVATE OLD ACTIVITY" in markdown)
        assertContains(markdown, "VISIBLE NEW ACTIVITY")
    }

    @Test fun jsonLabelsRawJournalAndRetainsLogicalDeletionEvents() = runTest {
        val store = MemoryEvents(listOf(
            message(0, AgentMessage("user", "historical text retained in journal")),
            event(1, "history.remove_turn", buildJsonObject { put("start", 0); put("end", 1); put("expectedCount", 1) }),
        ))
        val json = SessionExporter(store).json("s")
        assertContains(json, "Raw append-only journal")
        assertContains(json, "historical text retained in journal")
        assertContains(json, "history.remove_turn")
    }

    @Test fun markdownAppliesTruncationAtAUserTurnBoundary() = runTest {
        val store = MemoryEvents(listOf(
            message(0, AgentMessage("user", "kept")),
            message(1, AgentMessage("assistant", "kept answer")),
            message(2, AgentMessage("user", "discarded later turn")),
            event(3, "history.truncate", buildJsonObject { put("count", 2); put("expectedCount", 3) }),
        ))
        val markdown = SessionExporter(store).markdown("s")
        assertContains(markdown, "kept answer")
        assertFalse("discarded later turn" in markdown)
    }

    private fun event(seq: Long, type: String, payload: JsonObject) = StoredEvent(
        sessionId = "s", seq = seq, timestamp = "2026-10-17T00:00:00Z", type = type, payload = payload,
    )

    private fun assistant(seq: Long, modelId: String?, kind: String) = event(seq, "message", buildJsonObject {
        put("role", "assistant")
        put("content", "reply $seq")
        put("origin", buildJsonObject {
            put("kind", kind)
            if (modelId != null) put("modelId", modelId)
        })
        put("replay", "synthetic private reasoning")
    })

    private fun message(seq: Long, message: AgentMessage) = event(seq, "message", Json.encodeToJsonElement(AgentMessage.serializer(), message).jsonObject)

    private class MemoryEvents(private val events: List<StoredEvent>) : EventStore {
        override suspend fun append(sessionId: String, type: String, payload: JsonObject): StoredEvent = error("unused")
        override suspend fun read(sessionId: String): List<StoredEvent> = events
        override suspend fun list(): List<SessionInfo> = emptyList()
    }
}
