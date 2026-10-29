package dev.chiller.tools

import dev.chiller.contracts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * A stand-in MCP server, started as its own JVM from the test classpath: `echo` (read-only), `fail` (reports an
 * error), `slow` (never answers) and `env` (returns a variable). It also sends a `ping` request and a notification
 * before answering, as real servers may.
 */
object FakeMcpServer {
    @JvmStatic fun main(args: Array<String>) {
        // MCP's stdio transport is UTF-8, whatever the platform's default charset is.
        val input = System.`in`.bufferedReader(Charsets.UTF_8)
        val out = java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, Charsets.UTF_8)
        fun send(message: JsonObject) { out.println(message.toString()); out.flush() }
        while (true) {
            val line = input.readLine() ?: return
            val message = Json.parseToJsonElement(line).jsonObject
            val id = message["id"] ?: continue
            if (message["method"] == null) continue // the answer to our ping
            val params = message["params"]?.jsonObject
            val result: JsonObject = when (message["method"]!!.jsonPrimitive.content) {
                "initialize" -> {
                    send(buildJsonObject { put("jsonrpc", "2.0"); put("id", "server-ping"); put("method", "ping") })
                    send(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/message"); put("params", buildJsonObject { put("level", "info"); put("data", "hello") }) })
                    buildJsonObject { put("protocolVersion", "2025-06-18"); put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) }); put("serverInfo", buildJsonObject { put("name", "fake") }) }
                }
                "tools/list" -> buildJsonObject {
                    put("tools", buildJsonArray {
                        add(buildJsonObject { put("name", "echo"); put("description", "Echo text"); put("annotations", buildJsonObject { put("readOnlyHint", true) })
                            put("inputSchema", Json.parseToJsonElement("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""")) })
                        add(buildJsonObject { put("name", "fail"); put("description", "Always fails"); put("inputSchema", buildJsonObject { put("type", "object") }) })
                        add(buildJsonObject { put("name", "slow"); put("description", "Never answers"); put("inputSchema", buildJsonObject { put("type", "object") }) })
                        add(buildJsonObject { put("name", "env"); put("description", "Reads TOKEN"); put("inputSchema", buildJsonObject { put("type", "object") }) })
                    })
                }
                "tools/call" -> when (params!!["name"]!!.jsonPrimitive.content) {
                    "echo" -> content("echo: " + params["arguments"]!!.jsonObject["text"]!!.jsonPrimitive.content)
                    "fail" -> buildJsonObject { put("isError", true); put("content", buildJsonArray { add(text("boom")) }) }
                    "env" -> content(System.getenv("TOKEN") ?: "unset")
                    else -> continue
                }
                else -> buildJsonObject {}
            }
            send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) })
        }
    }
    private fun text(value: String) = buildJsonObject { put("type", "text"); put("text", value) }
    private fun content(value: String) = buildJsonObject { put("content", buildJsonArray { add(text(value)) }) }
}

class McpTest {
    private val java = File(System.getProperty("java.home"), "bin/java").path
    private val config = McpServerConfig("Fake Server", java, listOf("-cp", System.getProperty("java.class.path"), "dev.chiller.tools.FakeMcpServer"),
        env = mapOf("TOKEN" to "\${FAKE_TOKEN}"))
    private val context = ToolContext("s", "r", ExecutionMode.WORKSPACE_EDIT)

    @Test fun `a server's tools run as chiller tools with checked arguments and bounded results`() = runBlocking {
        val connection = McpConnection(config, { if (it == "FAKE_TOKEN") "from-env" else null }, Files.createTempDirectory("mcp"), "test")
        try {
            val tools = withTimeout(60_000) { connection.start() }
            assertEquals(listOf("echo", "fail", "slow", "env"), tools.map { it.name })
            val executor = McpTools(mapOf("fake_server" to (connection to tools)))
            val echo = executor.specs.single { it.name == "mcp__fake_server__echo" }
            assertEquals(ToolEffect.READ, echo.effect, "a read-only hint makes it a read")
            assertEquals(ToolEffect.EXTERNAL, executor.specs.single { it.name == "mcp__fake_server__fail" }.effect)

            val ok = executor.execute(ToolCall("1", echo.name, """{"text":"привет"}"""), context)
            assertTrue(ok.ok)
            assertEquals("echo: привет", (ok.data as JsonObject)["content"]!!.jsonPrimitive.content)
            assertEquals("from-env", (executor.execute(ToolCall("2", "mcp__fake_server__env", "{}"), context).data as JsonObject)["content"]!!.jsonPrimitive.content,
                "\${NAME} is filled in at start, the key is not in the settings")

            val missing = executor.execute(ToolCall("3", echo.name, "{}"), context)
            assertEquals("INVALID_ARGUMENTS", missing.error?.code, "a call missing a required argument never leaves Chiller")
            assertEquals("INVALID_ARGUMENTS", executor.execute(ToolCall("4", echo.name, """{"text":5}"""), context).error?.code)
            val failed = executor.execute(ToolCall("5", "mcp__fake_server__fail", "{}"), context)
            assertFalse(failed.ok); assertEquals("MCP_TOOL_ERROR", failed.error?.code); assertEquals("boom", failed.error?.message)

            // Stopping the run cancels the call instead of waiting for the server.
            val slow = async { executor.execute(ToolCall("6", "mcp__fake_server__slow", "{}"), context) }
            delay(300); slow.cancel()
            assertTrue(runCatching { slow.await() }.exceptionOrNull() is CancellationException)
            assertTrue(executor.execute(ToolCall("7", echo.name, """{"text":"after"}"""), context).ok, "the server still answers")
        } finally { connection.close() }
    }

    @Test fun `the hub starts enabled servers, reports failures and restarts on change`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hub = McpHub({ if (it == "FAKE_TOKEN") "x" else null }, Files.createTempDirectory("hub"), "test", scope)
        try {
            hub.configure(listOf(config, McpServerConfig("Broken", "chiller-no-such-command-xyz"), McpServerConfig("Off", "whatever", enabled = false)))
            withTimeout(60_000) { while (hub.states.value.values.any { it.status == "starting" }) delay(50) }
            val states = hub.states.value
            assertEquals("ready", states.getValue("fake_server").status)
            assertEquals(4, states.getValue("fake_server").tools.size)
            assertEquals("failed", states.getValue("broken").status)
            assertEquals("stopped", states.getValue("off").status)
            assertEquals(4, hub.executor()!!.specs.size, "only ready servers give tools")
            hub.configure(listOf(config.copy(enabled = false)))
            assertEquals(mapOf("fake_server" to "stopped"), hub.states.value.mapValues { it.value.status })
            assertNull(hub.executor())
        } finally { hub.close(); scope.cancel() }
    }

    @Test fun `names stay within provider limits and combined executors route by name`() = runTest {
        assertEquals("mcp__gh__create_issue", McpTools.toolName(McpTool("gh", "create.issue", "", buildJsonObject {}, false)).replace("create_issue", "create_issue"))
        assertTrue(McpTools.toolName(McpTool("server", "x".repeat(100), "", buildJsonObject {}, false)).length <= 64)
        assertEquals("github_enterprise", McpServerConfig("GitHub Enterprise!", "x").id)
        val a = object : ToolExecutor {
            override val specs = listOf(ToolSpec("a", "", buildJsonObject {}, ToolEffect.READ))
            override suspend fun execute(call: ToolCall, context: ToolContext) = ToolResult(true, JsonPrimitive("a"))
        }
        val combined = CombinedTools(listOf(a))
        assertEquals(JsonPrimitive("a"), combined.execute(ToolCall("1", "a", "{}"), context).data)
        assertEquals("UNKNOWN_TOOL", combined.execute(ToolCall("2", "b", "{}"), context).error?.code)
    }
}
