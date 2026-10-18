package dev.chiller.app.di

import dev.chiller.contracts.ProcessOutput
import dev.chiller.contracts.ProcessService
import dev.chiller.contracts.StartProcessRequest
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoVerifyTest {
    @Test fun `commands split like argv without a shell`() {
        assertEquals(listOf("./gradlew", "test", "--tests", "*Foo*"), splitCommand("./gradlew test --tests '*Foo*'"))
        assertEquals(listOf("python3", "-c", "print(1 + 1)"), splitCommand("python3 -c \"print(1 + 1)\""))
        assertEquals(listOf("echo", ""), splitCommand("echo ''"))
        assertFailsWith<IllegalArgumentException> { splitCommand("echo 'open") }
    }

    @Test fun `suggestions follow the build files`() {
        val root = Files.createTempDirectory("verify")
        assertNull(suggestVerifyCommand(root))
        Files.writeString(root.resolve("pyproject.toml"), "")
        assertEquals("python3 -m pytest -q", suggestVerifyCommand(root))
        Files.writeString(root.resolve("gradlew"), ""); Files.writeString(root.resolve("build.gradle.kts"), "")
        assertEquals("./gradlew test", suggestVerifyCommand(root))
    }

    @Test fun `verification completion is rejected after session workspace config run or engine changes`() {
        val processService = FakeProcessService()
        val settings = DesktopSettings(
            workspace = "/project/one",
            agent = AgentOptions(autoVerify = true),
            verifyCommands = mapOf("/project/one" to "./gradlew test"),
        )
        val completedRun = dev.chiller.contracts.RunState(status = "completed")
        val context = AutoVerifyContext(
            sessionId = "session-one",
            settings = settings,
            allowProcesses = true,
            processService = processService,
            command = "./gradlew test",
            options = settings.agent.sanitized(),
            epoch = 7,
        )
        val current = DesktopState(sessionId = "session-one", settings = settings, allowProcesses = true, run = completedRun)

        assertTrue(context.isCurrent(current, processService, 7))
        assertTrue(context.isCurrent(current.copy(settings = settings.copy(modelCatalogs = mapOf(
            "https://api.deepseek.com" to CachedModelCatalog(listOf(dev.chiller.contracts.ProviderModel("discovered")), "now")
        ))), processService, 7), "catalog cache updates do not invalidate the project's verification")
        assertFalse(context.isCurrent(current.copy(sessionId = "session-two"), processService, 7))
        assertFalse(context.isCurrent(current.copy(settings = settings.copy(workspace = "/project/two")), processService, 7))
        assertFalse(context.isCurrent(current.copy(settings = settings.copy(agent = settings.agent.copy(autoVerify = false))), processService, 7))
        assertFalse(context.isCurrent(current, FakeProcessService(), 7))
        assertFalse(context.isCurrent(current, processService, 8))
    }

    @Test fun `late failed check after switching sessions cannot update state or send repair`() = runBlocking {
        val root = Files.createTempDirectory("verify-session-switch")
        val project = Files.createDirectories(root.resolve("project"))
        val processService = FakeProcessService()
        val controller = DesktopController(root.resolve("data"), initialCredential = null, processServiceFactory = { processService }, catalogClient = OfflineCatalog)
        try {
            controller.initialize()
            controller.chooseWorkspace(project)
            controller.updateAgentOptions(AgentOptions(autoVerify = true, autoVerifyAttempts = 2))
            val projectRoot = project.toRealPath().toString()
            controller.setVerifyCommand(projectRoot, "./gradlew test")
            controller.allowProcesses(true)
            withTimeout(5_000) {
                while (controller.state.value.settings.verifyCommands[projectRoot] != "./gradlew test" || !controller.state.value.allowProcesses) delay(10)
            }
            // Build the real controller engine and install the injected process service without making a model request.
            controller.undo("not-a-real-change")
            val capturedState = controller.state.value
            val context = AutoVerifyContext(
                sessionId = capturedState.sessionId,
                settings = capturedState.settings,
                allowProcesses = capturedState.allowProcesses,
                processService = processService,
                command = "./gradlew test",
                options = capturedState.settings.agent.sanitized(),
                epoch = 1,
            )

            assertTrue(controller.completeAutoVerify(context, ProcessOutput("verify", "all passed", 0, "exited", 0), 12))
            assertEquals("passed", controller.state.value.verify.status)
            val otherProject = Files.createDirectories(root.resolve("other-project"))
            controller.chooseWorkspace(otherProject)
            val switchedSession = controller.state.value.sessionId
            assertFalse(context.sessionId == switchedSession)
            assertEquals(otherProject.toRealPath().toString(), controller.state.value.settings.workspace)

            assertFalse(controller.completeAutoVerify(context, ProcessOutput("verify", "FAIL", 0, "exited", 1), 20))
            assertEquals("idle", controller.state.value.verify.status)
            assertTrue(controller.state.value.run.messages.none { it.role == "user" })
            assertEquals(switchedSession, controller.state.value.sessionId)
        } finally {
            controller.close()
        }
    }

    @Test fun `follow up carries command, exit code and output tail`() {
        val text = verificationFollowUp("npm test", 1, "exited", "x".repeat(5000) + "FAIL src/a.test.js", 1, 2)
        assertTrue("`npm test`" in text && "Код завершения: 1" in text && text.contains("FAIL src/a.test.js") && "попытка 1 из 2" in text)
    }
}

private class FakeProcessService : ProcessService {
    override suspend fun start(request: StartProcessRequest, runId: String) = ProcessOutput("fake", "", 0, "exited", 0)
    override suspend fun read(processId: String, cursor: Long) = ProcessOutput(processId, "", cursor, "exited", 0)
    override suspend fun stop(processId: String) = ProcessOutput(processId, "", 0, "stopped")
    override suspend fun stopRun(runId: String) = Unit
    override suspend fun close() = Unit
}
