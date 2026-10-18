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

    @Test fun `only a matching final hash is covered by a successful tool change`() {
        val before = mapOf("a.py" to FileStamp(1, 1), "b.py" to FileStamp(1, 1), "gone.txt" to FileStamp(1, 1), "tool.py" to FileStamp(1, 1))
        val after = mapOf("a.py" to FileStamp(1, 1), "b.py" to FileStamp(2, 5), "new.txt" to FileStamp(1, 9), "tool.py" to FileStamp(9, 9, "expected"))
        val expected = mapOf("tool.py" to "expected")
        val changes = outsideChanges(before, after, expected, 0)
        assertEquals(listOf("new.txt"), changes.added)
        assertEquals(listOf("b.py"), changes.modified)
        assertEquals(listOf("gone.txt"), changes.deleted)
        // Same size and timestamp do not hide a command overwrite of a tool's file.
        val overwritten = outsideChanges(before, after + ("tool.py" to FileStamp(9, 9, "different")), expected, 0)
        assertEquals(listOf("b.py", "tool.py"), overwritten.modified)
        val many = outsideChanges(emptyMap(), (1..5).associate { "f$it" to FileStamp(1, 1) }, emptyMap(), 0, limit = 2)
        assertTrue(many.truncated && many.count == 2)
    }

    @Test fun `failed changes never cover a path and moves cover both ends only on success`() {
        val messages = listOf(AgentMessage("user", "go"), AgentMessage("assistant", toolCalls = listOf(
            ToolCall("failed", "replace_text", "{}"), ToolCall("move", "move_file", "{}"))),
            AgentMessage("user", "next"), AgentMessage("assistant", toolCalls = listOf(ToolCall("later", "create_file", "{}"))))
        fun activity(id: String, change: String, status: String) = dev.chiller.contracts.Activity(name = "replace_text", target = "file", status = status, toolCallId = id,
            output = """{"ok":true,"data":{"id":"$change"}}""")
        fun commit(id: String, path: String, operation: String, hash: String, destination: String? = null) = dev.chiller.contracts.StoredEvent(
            sessionId = "s", seq = 1, type = "workspace.commit", payload = buildJsonObject {
                put("changeId", id); put("path", path); put("operation", operation); put("afterHash", hash); destination?.let { put("destination", it) }
            })
        val expected = toolExpectedStates(messages, 0,
            listOf(activity("failed", "c1", "failed"), activity("move", "c2", "completed"), activity("later", "c3", "completed")),
            listOf(commit("c1", "failed.txt", "write", "h1"), commit("c2", "old.txt", "move", "h2", "new.txt"), commit("c3", "later.txt", "create", "h3")))
        assertEquals(mapOf("old.txt" to null, "new.txt" to "h2"), expected)
        val outside = outsideChanges(mapOf("failed.txt" to FileStamp(1, 1)), mapOf("failed.txt" to FileStamp(2, 2), "new.txt" to FileStamp(1, 1, "h2")), expected, 0)
        assertEquals(listOf("failed.txt"), outside.modified)
        assertTrue(outside.added.isEmpty())
    }

    @Test fun `a command's file is reported after the turn and survives reopening the session`() = checkTurn(false)

    @Test fun `command overwrites after successful and failed file calls remain visible`() = checkTurn(true)

    private fun checkTurn(overwrite: Boolean) = runBlocking<Unit> {
        val project = Files.createDirectories(temp.resolve("project")).toRealPath()
        Files.writeString(project.resolve("keep.txt"), "unchanged")
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val argv = if (overwrite) {
            if (windows) listOf("cmd", "/c", "echo x> made.txt & echo overwritten> tool.txt & echo overwritten> keep.txt")
            else listOf("sh", "-c", "echo x > made.txt; echo overwritten > tool.txt; echo overwritten > keep.txt")
        } else if (windows) listOf("cmd", "/c", "echo x> made.txt") else listOf("sh", "-c", "echo x > made.txt")
        val firstCalls = buildJsonArray {
            add(buildJsonObject { put("index", 0); put("id", "c1"); put("type", "function"); put("function", buildJsonObject { put("name", "create_file"); put("arguments", """{"path":"tool.txt","content":"by tool\n"}""") }) })
            if (overwrite) add(buildJsonObject { put("index", 1); put("id", "failed"); put("type", "function"); put("function", buildJsonObject { put("name", "create_file"); put("arguments", """{"path":"keep.txt","content":"cannot replace existing"}""") }) })
            add(buildJsonObject { put("index", 2); put("id", "c2"); put("type", "function"); put("function", buildJsonObject {
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
        val controller = DesktopController(data, initialCredential = null, catalogClient = OfflineCatalog)
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
            assertEquals(if (overwrite) listOf("made.txt", "tool.txt") else listOf("made.txt"), outside.added)
            assertEquals(if (overwrite) listOf("keep.txt") else emptyList(), outside.modified)
            assertTrue(outside.deleted.isEmpty())
            val session = controller.state.value.sessionId

            val reopened = DesktopController(data, initialCredential = null, catalogClient = OfflineCatalog)
            try {
                reopened.initialize()
                reopened.selectSession(session)
                assertEquals(outside, reopened.state.value.outside)
            } finally { reopened.close() }
        } finally { controller.close(); server.stop(0) }
    }
}
