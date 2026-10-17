package dev.chiller.app.di

import dev.chiller.contracts.AgentMessage
import dev.chiller.contracts.ComposerAttachment
import dev.chiller.contracts.ToolCall
import dev.chiller.contracts.StoredEvent
import dev.chiller.contracts.projectConversation
import dev.chiller.contracts.projectConversationEvents
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class MessageHistoryActionsTest {
    private val history = listOf(
        AgentMessage("user", "first"),
        AgentMessage("assistant", "checking", toolCalls = listOf(ToolCall("call-1", "read_file", "{}"))),
        AgentMessage("tool", "file contents", toolCallId = "call-1"),
        AgentMessage("assistant", "done"),
        AgentMessage("user", "second"),
        AgentMessage("assistant", "answer"),
        AgentMessage("user", "third"),
    )

    @Test fun `delete selection resolves complete enclosing user turn including tool pair`() {
        val plan = assertNotNull(MessageHistoryActions.planDeleteTurn(history, 2))
        assertEquals("history.remove_turn", plan.eventType)
        assertEquals(0, plan.start)
        assertEquals(4, plan.end)
        assertEquals(history.size, plan.expectedCount)
    }

    @Test fun `delete preserves complete remaining turns and rejects dangling tools`() {
        assertNotNull(MessageHistoryActions.planDeleteTurn(history, 5))
        val dangling = listOf(AgentMessage("assistant", toolCalls = listOf(ToolCall("lost", "read", "{}"))), AgentMessage("user", "next"))
        assertNull(MessageHistoryActions.planDeleteTurn(dangling, 1))
    }

    @Test fun `invalid and pre-user selections cannot form deletion`() {
        assertNull(MessageHistoryActions.planDeleteTurn(emptyList(), 0))
        assertNull(MessageHistoryActions.planDeleteTurn(history, -1))
        assertNull(MessageHistoryActions.planDeleteTurn(history, history.size))
        assertNull(MessageHistoryActions.planDeleteTurn(listOf(AgentMessage("assistant", "orphan")), 0))
    }

    @Test fun `edit replaces selected user text retains attachments and drops later turns`() {
        val file = ComposerAttachment("a".repeat(64), "notes.txt", "text/plain", 5)
        val original = listOf(AgentMessage("user", "old", attachments = listOf(file)), AgentMessage("assistant", "old answer"), AgentMessage("user", "later"))
        val edit = assertNotNull(MessageHistoryActions.planEditTurn(original, 0, "revised"))
        assertEquals("history.replace_turn", edit.eventType)
        assertEquals(0, edit.index)
        assertEquals(3, edit.expectedCount)
        assertEquals("revised", edit.text)
        assertEquals("revised", edit.replacement.content)
        assertEquals(listOf(file), edit.replacement.attachments)
        assertEquals(listOf(AgentMessage("user", "revised", attachments = listOf(file))), edit.messages)
    }

    @Test fun `edit only accepts user messages and preserves prior complete tool groups`() {
        val edit = assertNotNull(MessageHistoryActions.planEditTurn(history, 4, "changed"))
        assertEquals(history.take(4) + AgentMessage("user", "changed"), edit.messages)
        assertNull(MessageHistoryActions.planEditTurn(history, 3, "changed"))
        assertNull(MessageHistoryActions.planEditTurn(history, -1, "changed"))
    }

    @Test fun `edit cannot retain half a tool exchange or create an empty unattached turn`() {
        val incomplete = listOf(AgentMessage("assistant", toolCalls = listOf(ToolCall("pending", "read", "{}"))), AgentMessage("user", "edit me"))
        assertNull(MessageHistoryActions.planEditTurn(incomplete, 1, "edited"))
        assertNull(MessageHistoryActions.planEditTurn(listOf(AgentMessage("user", "text")), 0, "  "))
    }

    @Test fun `replace event projects atomically to edited user and preserves edit provenance`() {
        val first = StoredEvent(sessionId = "s", seq = 0, type = "message", payload = buildJsonObject { put("role", "user"); put("content", "before") })
        val assistant = StoredEvent(sessionId = "s", seq = 1, type = "message", payload = buildJsonObject { put("role", "assistant"); put("content", "old response") })
        val replacement = AgentMessage("user", "after", attachments = listOf(ComposerAttachment("b".repeat(64), "f.txt", "text/plain", 2)))
        val edit = StoredEvent(sessionId = "s", seq = 2, type = "history.replace_turn", payload = buildJsonObject {
            put("index", 0); put("expectedCount", 2)
            put("message", kotlinx.serialization.json.Json.encodeToJsonElement(AgentMessage.serializer(), replacement))
        })
        assertEquals(listOf(replacement), projectConversation(listOf(first, assistant, edit)))
        val projectedEvent = projectConversationEvents(listOf(first, assistant, edit)).single()
        assertEquals(edit.seq, projectedEvent.seq)
        assertEquals("message", projectedEvent.type)
        assertEquals("after", projectedEvent.payload["content"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }
}
