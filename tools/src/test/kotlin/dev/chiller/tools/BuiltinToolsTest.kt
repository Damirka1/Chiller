package dev.chiller.tools

import dev.chiller.contracts.*
import dev.chiller.workspace.LocalWorkspace
import dev.chiller.runtime.LocalProcessService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class BuiltinToolsTest {
    @Test fun permissionsAndSchemaGuardEffects() = runBlocking {
        val root = Files.createTempDirectory("chiller-tools")
        val workspace = LocalWorkspace(root)
        val processes = LocalProcessService(root)
        val artifacts = object : ArtifactStore {
            override suspend fun put(sessionId: String, content: String) = ArtifactRef("unused", sessionId, content.length.toLong())
            override suspend fun read(sessionId: String, artifactId: String, offset: Int, limit: Int) = ""
        }
        val questions = object : InteractionBroker { override suspend fun ask(question: UserQuestion) = "answer" }
        val tools = BuiltinTools(workspace, processes, artifacts, questions)
        try {
            val plan = ToolContext("s", "r", ExecutionMode.PLAN)
            val network = tools.execute(ToolCall("net-1", "web_search", """{"query":"Kotlin"}"""), plan)
            assertEquals("PERMISSION_DENIED", network.error?.code)
            val explicitlyAllowedNetwork = tools.execute(ToolCall("net-2", "web_search", """{"query":"Kotlin"}"""), plan.copy(allowNetwork = true))
            assertEquals("NETWORK_DISABLED", explicitlyAllowedNetwork.error?.code)
            val create = tools.execute(ToolCall("1", "create_file", "{\"path\":\"new.txt\",\"content\":\"x\"}"), plan)
            assertEquals("PERMISSION_DENIED", create.error?.code)
            assertFalse(Files.exists(root.resolve("new.txt")))
            val editMode = plan.copy(mode = ExecutionMode.WORKSPACE_EDIT)
            val process = tools.execute(ToolCall("2", "run_process", "{\"argv\":[\"echo\",\"x\"]}"), editMode)
            assertEquals("PERMISSION_DENIED", process.error?.code)
            val unknown = tools.execute(ToolCall("3", "create_file", "{\"path\":\"new.txt\",\"content\":\"x\",\"overwrite\":true}"), editMode)
            assertEquals("INVALID_ARGUMENTS", unknown.error?.code)
            assertFalse(Files.exists(root.resolve("new.txt")))
            val invalid = tools.execute(ToolCall("4", "read_file", "{\"path\":42}"), plan)
            assertEquals("INVALID_ARGUMENTS", invalid.error?.code)
        } finally { processes.close(); root.toFile().deleteRecursively() }
    }

    @Test fun editResultsCarrySnapshotIdForChaining() = runBlocking {
        val root = Files.createTempDirectory("chiller-chain")
        val workspace = LocalWorkspace(root)
        val processes = LocalProcessService(root)
        val artifacts = object : ArtifactStore {
            override suspend fun put(sessionId: String, content: String) = ArtifactRef("unused", sessionId, content.length.toLong())
            override suspend fun read(sessionId: String, artifactId: String, offset: Int, limit: Int) = ""
        }
        val questions = object : InteractionBroker { override suspend fun ask(question: UserQuestion) = "answer" }
        val tools = BuiltinTools(workspace, processes, artifacts, questions)
        try {
            Files.writeString(root.resolve("a.txt"), "one\ntwo\n")
            val context = ToolContext("s", "r", ExecutionMode.WORKSPACE_EDIT)
            val read = tools.execute(ToolCall("1", "read_file", """{"path":"a.txt"}"""), context)
            val snapshot = read.data!!.jsonObject.getValue("snapshot_id").jsonPrimitive.content
            val first = tools.execute(ToolCall("2", "edit_file", """{"path":"a.txt","snapshot_id":"$snapshot","edits":[{"op":"replace","start_line":1,"content":"ONE"}]}"""), context)
            assertTrue(first.ok)
            val chained = first.data!!.jsonObject.getValue("snapshotId").jsonPrimitive.content
            assertEquals("ONE\ntwo\n", Files.readString(root.resolve("a.txt")))
            val second = tools.execute(ToolCall("3", "edit_file", """{"path":"a.txt","snapshot_id":"$chained","edits":[{"op":"replace","start_line":2,"content":"TWO"}]}"""), context)
            assertTrue(second.ok)
            assertEquals("ONE\nTWO\n", Files.readString(root.resolve("a.txt")))
            val rg = runCatching { ProcessBuilder("rg", "--version").start().waitFor() == 0 }.getOrDefault(false)
            if (rg) {
                val search = tools.execute(ToolCall("4", "search_text", """{"query":"ONE","context":1,"limit":5}"""), context)
                assertTrue(search.ok)
            }
        } finally { processes.close(); root.toFile().deleteRecursively() }
    }

    @Test fun replaceTextAndPlanAreModelFriendly() = runBlocking {
        val root = Files.createTempDirectory("chiller-replace-tool")
        val workspace = LocalWorkspace(root)
        val processes = LocalProcessService(root)
        val artifacts = object : ArtifactStore {
            override suspend fun put(sessionId: String, content: String) = ArtifactRef("unused", sessionId, content.length.toLong())
            override suspend fun read(sessionId: String, artifactId: String, offset: Int, limit: Int) = ""
        }
        val tools = BuiltinTools(workspace, processes, artifacts, object : InteractionBroker { override suspend fun ask(question: UserQuestion) = "a" })
        try {
            Files.writeString(root.resolve("a.py"), "x = 1\ny = 2\n")
            val plan = ToolContext("s", "r", ExecutionMode.PLAN)
            val edit = plan.copy(mode = ExecutionMode.WORKSPACE_EDIT)
            val snapshot = tools.execute(ToolCall("1", "read_file", """{"path":"a.py"}"""), plan).data!!.jsonObject.getValue("snapshot_id").jsonPrimitive.content
            assertEquals("PERMISSION_DENIED", tools.execute(ToolCall("2", "replace_text", """{"path":"a.py","snapshot_id":"$snapshot","old_text":"y = 2","new_text":"y = 3"}"""), plan).error?.code)
            val missing = tools.execute(ToolCall("3", "replace_text", """{"path":"a.py","snapshot_id":"$snapshot","old_text":"z = 2","new_text":"z = 3"}"""), edit)
            assertEquals("NO_MATCH", missing.error?.code)
            assertTrue(tools.execute(ToolCall("4", "replace_text", """{"path":"a.py","snapshot_id":"$snapshot","old_text":"y = 2","new_text":"y = 3"}"""), edit).ok)
            assertEquals("x = 1\ny = 3\n", Files.readString(root.resolve("a.py")))

            val steps = """{"steps":[{"step":"read","status":"done"},{"step":"fix","status":"in_progress"},{"step":"test","status":"pending"}]}"""
            val planned = tools.execute(ToolCall("5", "update_plan", steps), plan)
            assertTrue(planned.ok)
            assertEquals("fix", planned.data!!.jsonObject.getValue("next").jsonPrimitive.content)
            val twoActive = tools.execute(ToolCall("6", "update_plan", """{"steps":[{"step":"a","status":"in_progress"},{"step":"b","status":"in_progress"}]}"""), plan)
            assertEquals("INVALID_ARGUMENTS", twoActive.error?.code)
        } finally { processes.close(); root.toFile().deleteRecursively() }
    }
}
