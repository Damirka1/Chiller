package dev.chiller.app.di

import com.sun.net.httpserver.HttpServer
import dev.chiller.app.ui.isStepLimitError
import dev.chiller.contracts.ExecutionMode
import dev.chiller.sessions.JsonlEventStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LimitsApplyTest {
    @TempDir lateinit var temp: Path

    @Test fun `changed limits apply from the next message of the same dialog`() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.requestBody.readBytes()
                val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(sse.toByteArray()) }
            }
            start()
        }
        val data = temp.resolve("data")
        val controller = DesktopController(data, initialCredential = null)
        try {
            controller.initialize()
            controller.configure("http://127.0.0.1:${server.address.port}/v1", "fake-model", false, null)
            controller.chooseWorkspace(Files.createDirectories(temp.resolve("project")).toRealPath())
            suspend fun sendAndWait(text: String) {
                val before = controller.state.value.run.messages.size
                val receipt = controller.send(text, ExecutionMode.PLAN); assertTrue(receipt.accepted, receipt.reason)
                withTimeout(30_000) { while (controller.state.value.run.status != "completed" || controller.state.value.run.messages.size < before + 2) delay(20) }
            }
            sendAndWait("first")
            controller.updateAgentOptions(controller.state.value.settings.agent.copy(maxRounds = AgentOptions.UNLIMITED))
            sendAndWait("second")
            val session = controller.state.value.sessionId
            val rounds = JsonlEventStore(data.resolve("sessions")).read(session).filter { it.type == "run.started" }.map { it.payload["maxRounds"]!!.jsonPrimitive.content.toInt() }
            assertEquals(listOf(AgentOptions().maxRounds, AgentOptions.UNLIMITED_ROUNDS_BACKSTOP), rounds, "the second message runs with the new limit")
            assertEquals(4, controller.state.value.run.messages.size, "the dialog history survives the rebuild")
        } finally { controller.close(); server.stop(0) }
    }

    @Test fun `step limit stop is recognised for the continue button`() {
        assertTrue(isStepLimitError("Достигнут лимит 24 шагов; работа остановлена с сохранением истории."))
        assertFalse(isStepLimitError("INCOMPLETE_TOOL_CALL: Provider truncated a tool call"))
    }
}
