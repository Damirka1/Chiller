package dev.chiller.app.di

import com.sun.net.httpserver.HttpServer
import dev.chiller.contracts.AgentMessage
import dev.chiller.contracts.ExecutionMode
import dev.chiller.contracts.ToolCall
import dev.chiller.tools.FileStamp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutsideChangesTest {
    @TempDir lateinit var temp: Path

    @Test fun `snapshots report added, modified and deleted paths except those the tools touched`() {
        val before = mapOf("a.py" to FileStamp(1, 1), "b.py" to FileStamp(1, 1), "gone.txt" to FileStamp(1, 1), "tool.py" to FileStamp(1, 1))
        val after = mapOf("a.py" to FileStamp(1, 1), "b.py" to FileStamp(2, 5), "new.txt" to FileStamp(1, 9), "tool.py" to FileStamp(9, 9), "moved.py" to FileStamp(1, 1))
        val tools = toolTouchedPaths(listOf(AgentMessage("user", "go"),
            AgentMessage("assistant", toolCalls = listOf(ToolCall("1", "replace_text", """{"path":"tool.py"}"""), ToolCall("2", "move_file", """{"path":"x.py","destination":"moved.py"}""")))), 0)
        val changes = outsideChanges(before, after, tools, 0)
        assertEquals(listOf("new.txt"), changes.added)
        assertEquals(listOf("b.py"), changes.modified)
        assertEquals(listOf("gone.txt"), changes.deleted)
        assertEquals(3, changes.count)
        val many = outsideChanges(emptyMap(), (1..5).associate { "f$it" to FileStamp(1, 1) }, emptySet(), 0, limit = 2)
        assertTrue(many.truncated && many.added.size == 2)
    }

    @Test fun `a command's file is reported after the turn and survives reopening the session`() = runBlocking<Unit> {
        val project = Files.createDirectories(temp.resolve("project")).toRealPath()
        Files.writeString(project.resolve("keep.txt"), "unchanged")
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val argv = if (windows) listOf("cmd", "/c", "echo x> made.txt") else listOf("sh", "-c", "echo x > made.txt")
        val firstCalls = buildJsonArray {
            add(buildJsonObject { put("index", 0); put("id", "c1"); put("type", "function"); put("function", buildJsonObject { put("name", "create_file"); put("arguments", """{"path":"tool.txt","content":"by tool\n"}""") }) })
            add(buildJsonObject { put("index", 1); put("id", "c2"); put("type", "function"); put("function", buildJsonObject {
                put("name", "run_process"); put("arguments", buildJsonObject { put("argv", buildJsonArray { argv.forEach { add(JsonPrimitive(it)) } }) }.toString())
            }) })
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val delta = if ("\"role\":\"tool\"" in body) """{"content":"Готово."}""" else """{"tool_calls":$firstCalls}"""
                val finish = if ("\"role\":\"tool\"" in body) "stop" else "tool_calls"
                val sse = "data: {\"choices\":[{\"delta\":$delta,\"finish_reason\":\"$finish\"}]}\n\ndata: [DONE]\n\n"
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
            controller.chooseWorkspace(project)
            controller.allowProcesses(true)
            withTimeout(10_000) { while (!controller.state.value.allowProcesses) delay(10) }
            val receipt = controller.send("сделай файл", ExecutionMode.WORKSPACE_EDIT)
            assertTrue(receipt.accepted, receipt.reason)
            withTimeout(30_000) { while (controller.state.value.outside == null) delay(20) }
            val outside = controller.state.value.outside!!
            assertEquals(listOf("made.txt"), outside.added, "the tool's own file is covered by the journal")
            assertTrue(outside.modified.isEmpty() && outside.deleted.isEmpty())
            val session = controller.state.value.sessionId

            val reopened = DesktopController(data, initialCredential = null)
            try {
                reopened.initialize()
                reopened.selectSession(session)
                assertEquals(outside, reopened.state.value.outside)
            } finally { reopened.close() }
        } finally { controller.close(); server.stop(0) }
    }
}
