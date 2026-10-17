package dev.chiller.app.di

import dev.chiller.agent.AgentCoordinator
import dev.chiller.agent.SubagentSupervisor
import dev.chiller.tools.SubagentTools
import dev.chiller.contracts.*
import dev.chiller.runtime.LocalProcessService
import dev.chiller.sessions.JsonlEventStore
import dev.chiller.sessions.LocalArtifactStore
import dev.chiller.tools.BuiltinTools
import dev.chiller.workspace.LocalWorkspace
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DemoScenarioTest {
    @Test fun `demo drives every tool through the real loop`() = runBlocking {
        val root = Files.createTempDirectory("chiller-demo-test")
        val project = root.resolve("project").also { DemoScenario.createProject(it) }.toRealPath()
        val store = JsonlEventStore(root.resolve("sessions"))
        val artifacts = LocalArtifactStore(root.resolve("artifacts"))
        val processes = LocalProcessService(project)
        val asked = mutableListOf<String>()
        val builtin = BuiltinTools(LocalWorkspace(project, store, artifacts, "demo"), processes, artifacts, object : InteractionBroker {
            override suspend fun ask(question: UserQuestion): String { asked += question.question; return question.options.first() }
        })
        try {
            val provider = DemoScenario.Provider(pacing = false)
            val supervisor = SubagentSupervisor(provider, builtin, store, "demo", DemoScenario.MODEL_ID, this)
            val engine = AgentCoordinator(provider, SubagentTools(builtin, supervisor), store, processes, "demo", DemoScenario.MODEL_ID, subagents = supervisor, scope = this)
            withTimeout(60_000) { engine.send(DemoScenario.PROMPT, ExecutionMode.WORKSPACE_EDIT, allowProcesses = true).join() }
            val state = engine.state.value
            assertEquals("completed", state.status, state.error)
            val used = state.activities.map { it.name }.toSet()
            val expected = setOf("update_plan", "list_files", "search_text", "read_file", "replace_text", "edit_file", "create_file",
                "run_process", "read_process", "move_file", "delete_file", "ask_user", "spawn_agent", "wait_agents", "agent_result")
            assertEquals(expected - used, emptySet(), "unused tools")
            // Exactly one deliberate failure: the inexact replace_text, which changed nothing. Without python3
            // (e.g. a Windows runner) the process step may fail too; that is the environment, not the loop.
            assertEquals(listOf("replace_text"), state.activities.filter { it.status == "failed" && it.name !in setOf("run_process", "read_process") }.map { it.name })
            assertEquals(1, asked.size)
            assertEquals(listOf(SubagentStatus.COMPLETED, SubagentStatus.COMPLETED), supervisor.state.value.map { it.status })
            assertTrue(supervisor.state.value.all { it.readPaths.isNotEmpty() || it.toolCalls == 1 })
            assertTrue("return 'Hello'\n" in Files.readString(project.resolve("greeting.py")))
            assertTrue("TIMEOUT_SECONDS = 45" in Files.readString(project.resolve("config.py")))
            assertTrue(Files.exists(project.resolve("stats.py")) && Files.exists(project.resolve("helpers.py")))
            assertFalse(Files.exists(project.resolve("helpers_old.py")) || Files.exists(project.resolve("scratch.txt")))
            assertTrue("## Checks" in Files.readString(project.resolve("README.md")))
        } finally { processes.close(); root.toFile().deleteRecursively() }
    }
}
