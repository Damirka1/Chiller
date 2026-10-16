package dev.chiller.tools

import dev.chiller.contracts.*
import dev.chiller.workspace.LocalWorkspace
import dev.chiller.runtime.LocalProcessService
import kotlinx.coroutines.runBlocking
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
}
