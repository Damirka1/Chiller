package dev.chiller.app.di

import com.sun.net.httpserver.HttpServer
import dev.chiller.contracts.ExecutionMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectPrefsTest {
    @TempDir lateinit var temp: Path

    @Test fun `a project brings back its model and process permission`() = runBlocking<Unit> {
        val model = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.requestBody.readBytes()
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray()) }
            }
            start()
        }
        val shop = Files.createDirectories(temp.resolve("shop")).toRealPath().toString()
        val api = Files.createDirectories(temp.resolve("api")).toRealPath().toString()
        val endpoint = "http://127.0.0.1:${model.address.port}/v1"
        val controller = DesktopController(temp.resolve("data"), initialCredential = null, catalogClient = OfflineCatalog)
        suspend fun turn() {
            val before = controller.state.value.run.messages.size
            assertTrue(controller.send("Привет", ExecutionMode.PLAN).accepted)
            withTimeout(30_000) { while (controller.state.value.run.status != "completed" || controller.state.value.run.messages.size < before + 2) delay(20) }
        }
        suspend fun processes(value: Boolean) {
            controller.allowProcesses(value)
            withTimeout(5_000) { while (controller.state.value.allowProcesses != value) delay(10) }
        }
        try {
            controller.initialize()
            controller.configure(endpoint, "small-model", false, null)
            controller.newSessionIn(shop)
            processes(true)
            turn()
            controller.configure(endpoint, "big-model", false, null)
            controller.newSessionIn(api)
            processes(false)
            turn()

            controller.newSessionIn(shop)
            assertEquals("small-model", controller.state.value.settings.model, "a new dialog in the project takes its model")
            assertTrue(controller.state.value.allowProcesses)
            controller.newSessionIn(api)
            assertEquals("big-model", controller.state.value.settings.model)
            assertFalse(controller.state.value.allowProcesses)

            // Another endpoint is never switched to silently.
            controller.configure("http://127.0.0.1:9/v1", "other", false, null)
            controller.newSessionIn(shop)
            assertEquals("other", controller.state.value.settings.model)
            assertTrue(controller.state.value.allowProcesses, "permission still follows the project")
        } finally { controller.close(); model.stop(0) }
    }
}
