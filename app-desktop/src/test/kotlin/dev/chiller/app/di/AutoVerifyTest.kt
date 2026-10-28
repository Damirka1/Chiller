package dev.chiller.app.di

import dev.chiller.contracts.ProcessOutput
import dev.chiller.contracts.ProcessService
import dev.chiller.contracts.StartProcessRequest
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
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

    @Test fun `verification completion is rejected after config, permission, run or engine changes`() {
        val processService = FakeProcessService()
        val settings = DesktopSettings(
            workspace = "/project/one",
            agent = AgentOptions(autoVerify = true),
            verifyCommands = mapOf("/project/one" to "./gradlew test"),
        )
        val context = AutoVerifyContext(
            sessionId = "session-one",
            root = "/project/one",
            allowProcesses = true,
            processService = processService,
            command = "./gradlew test",
            options = settings.agent.sanitized(),
            epoch = 7,
        )

        assertTrue(context.isCurrent(settings, true, processService, 7))
        assertTrue(context.isCurrent(settings.copy(modelCatalogs = mapOf(
            "https://api.deepseek.com" to CachedModelCatalog(listOf(dev.chiller.contracts.ProviderModel("discovered")), "now")
        )), true, processService, 7), "catalog cache updates do not invalidate the project's verification")
        assertTrue(context.isCurrent(settings.copy(workspace = "/project/two"), true, processService, 7), "another dialog on screen does not matter")
        assertFalse(context.isCurrent(settings.copy(verifyCommands = mapOf("/project/one" to "npm test")), true, processService, 7))
        assertFalse(context.isCurrent(settings.copy(agent = settings.agent.copy(autoVerify = false)), true, processService, 7))
        assertFalse(context.isCurrent(settings, false, processService, 7))
        assertFalse(context.isCurrent(settings, true, FakeProcessService(), 7))
        assertFalse(context.isCurrent(settings, true, processService, 8))
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
                root = projectRoot,
                allowProcesses = capturedState.allowProcesses,
                processService = processService,
                command = "./gradlew test",
                options = capturedState.settings.agent.sanitized(),
                epoch = 0,
            )

            assertTrue(controller.completeAutoVerify(context, ProcessOutput("verify", "all passed", 0, "exited", 0), 12))
            assertEquals("passed", controller.state.value.verify.status)
            val otherProject = Files.createDirectories(root.resolve("other-project"))
            controller.newSessionIn(otherProject.toString())
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

    @Test fun `a dialog left during its run is checked, repaired and reported in the background`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("verify-background")
        val project = Files.createDirectories(root.resolve("project")).toRealPath()
        var created = 0
        // A model that writes one file per turn and then answers; its turns come from a local server, nothing leaves the machine.
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val request = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val last = Json.parseToJsonElement(request).jsonObject["messages"]!!.jsonArray.last().jsonObject
                val chunk = if (last["role"]?.jsonPrimitive?.content == "tool") buildJsonObject {
                    put("choices", buildJsonArray { add(buildJsonObject { put("delta", buildJsonObject { put("content", "готово") }); put("finish_reason", "stop") }) })
                } else {
                    created++
                    val arguments = buildJsonObject { put("path", "file-$created.txt"); put("content", "ok\n") }.toString()
                    val call = buildJsonObject { put("index", 0); put("id", "call-$created"); put("type", "function"); put("function", buildJsonObject { put("name", "create_file"); put("arguments", arguments) }) }
                    buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject { put("delta", buildJsonObject { put("tool_calls", buildJsonArray { add(call) }) }); put("finish_reason", "tool_calls") }) }) }
                }
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write("data: $chunk\n\ndata: [DONE]\n\n".toByteArray()) }
            }
            start()
        }
        // The project's check fails once, then passes; it takes long enough for the dialog to be left first.
        val checks = java.util.concurrent.atomic.AtomicInteger()
        val processes = object : ProcessService by FakeProcessService() {
            override suspend fun start(request: StartProcessRequest, runId: String): ProcessOutput {
                if (!runId.startsWith("verify-")) return ProcessOutput("p", "", 0, "exited", 0)
                delay(300)
                return if (checks.incrementAndGet() == 1) ProcessOutput("v", "FAIL: file-1", 0, "exited", 1) else ProcessOutput("v", "ok", 0, "exited", 0)
            }
        }
        val controller = DesktopController(root.resolve("data"), initialCredential = null, processServiceFactory = { processes }, catalogClient = OfflineCatalog)
        try {
            controller.initialize()
            controller.configure("http://127.0.0.1:${server.address.port}/v1", "fake-model", false, null)
            controller.chooseWorkspace(project)
            controller.updateAgentOptions(AgentOptions(autoVerify = true, autoVerifyAttempts = 2))
            controller.setVerifyCommand(project.toString(), "check")
            controller.allowProcesses(true)
            withTimeout(5_000) { while (controller.state.value.settings.verifyCommands[project.toString()] != "check" || !controller.state.value.allowProcesses) delay(10) }
            assertTrue(controller.send("Создай файл", dev.chiller.contracts.ExecutionMode.WORKSPACE_EDIT).accepted)
            val worked = controller.state.value.sessionId
            controller.newSessionIn(null)
            val shown = controller.state.value.sessionId
            withTimeout(30_000) { while (controller.state.value.notices.none { it.sessionId == worked && it.tone == "positive" }) delay(20) }
            assertEquals("Задача завершена, проверка пройдена", controller.state.value.notices.single { it.sessionId == worked }.message)
            assertEquals(2, checks.get(), "the failed check was repaired and checked again")
            assertTrue(Files.exists(project.resolve("file-2.txt")), "the repair turn ran in the background dialog")
            assertEquals(shown, controller.state.value.sessionId)
            assertEquals("idle", controller.state.value.verify.status, "the shown dialog has no check of its own")
            withTimeout(10_000) { while (worked in controller.state.value.runningSessions) delay(20) }
            val journal = dev.chiller.sessions.JsonlEventStore(root.resolve("data").resolve("sessions")).read(worked)
            assertEquals(listOf(1, 0), journal.filter { it.type == "verify.result" }.map { it.payload["exitCode"]!!.jsonPrimitive.content.toInt() })
            // Opening the dialog shows its result and reads its notice.
            controller.selectSession(worked)
            assertEquals("passed", controller.state.value.verify.status)
            assertTrue(controller.state.value.notices.none { it.sessionId == worked })
        } finally { controller.close(); server.stop(0) }
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
