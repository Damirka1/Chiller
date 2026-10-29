package dev.chiller.app.di

import com.sun.net.httpserver.HttpServer
import dev.chiller.contracts.ExecutionMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A real server process and a local model endpoint: the dialog is offered the server's tools by mode. Nothing leaves the machine. */
class McpEngineTest {
    @TempDir lateinit var temp: Path

    /** A one-file Java MCP server (run with the JDK's source launcher): one read-only tool and one that changes data. */
    private fun fakeServer(): Path = temp.resolve("FakeServer.java").also {
        Files.writeString(it, """
            import java.io.*; import java.nio.charset.StandardCharsets; import java.util.regex.*;
            public class FakeServer {
              public static void main(String[] a) throws Exception {
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
                Pattern id = Pattern.compile("\"id\":(\\d+)");
                for (String line; (line = in.readLine()) != null; ) {
                  Matcher m = id.matcher(line); if (!m.find()) continue;
                  String result = line.contains("\"initialize\"") ? "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}"
                    : line.contains("\"tools/list\"") ? "{\"tools\":[{\"name\":\"search\",\"description\":\"Search issues\",\"annotations\":{\"readOnlyHint\":true},\"inputSchema\":{\"type\":\"object\"}},{\"name\":\"create_issue\",\"description\":\"Create an issue\",\"inputSchema\":{\"type\":\"object\"}}]}"
                    : "{}";
                  out.println("{\"jsonrpc\":\"2.0\",\"id\":" + m.group(1) + ",\"result\":" + result + "}");
                }
              }
            }
        """.trimIndent())
    }

    @Test fun `a connected server's tools reach the dialog, only read ones in plan`() = runBlocking<Unit> {
        val offered = mutableListOf<List<String>>()
        val model = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val request = Json.parseToJsonElement(exchange.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
                synchronized(offered) { offered += request["tools"]?.jsonArray.orEmpty().map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content } }
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray()) }
            }
            start()
        }
        val controller = DesktopController(temp.resolve("data"), initialCredential = null, catalogClient = OfflineCatalog)
        try {
            controller.initialize()
            controller.configure("http://127.0.0.1:${model.address.port}/v1", "fake-model", false, null)
            // A dialog without a folder always runs as a conversation; Plan applies inside a project.
            controller.chooseWorkspace(Files.createDirectories(temp.resolve("project")))
            val java = File(System.getProperty("java.home"), "bin/java").path
            assertEquals(null, controller.saveMcpServers(listOf(dev.chiller.tools.McpServerConfig("Tracker", java, listOf(fakeServer().toString())))))
            withTimeout(60_000) { while (controller.state.value.mcp["tracker"]?.status != "ready") { assertTrue(controller.state.value.mcp["tracker"]?.status != "failed", controller.state.value.mcp["tracker"]?.message); delay(50) } }
            assertEquals(listOf("search", "create_issue"), controller.state.value.mcp.getValue("tracker").tools.map { it.name })

            suspend fun turn(mode: ExecutionMode) {
                val before = controller.state.value.run.messages.size
                assertTrue(controller.send("Что в трекере?", mode).accepted)
                withTimeout(30_000) { while (controller.state.value.run.status != "completed" || controller.state.value.run.messages.size < before + 2) delay(20) }
            }
            turn(ExecutionMode.PLAN)
            turn(ExecutionMode.WORKSPACE_EDIT)
            val (plan, edit) = synchronized(offered) { offered.toList() }
            assertTrue("mcp__tracker__search" in plan && "mcp__tracker__create_issue" !in plan, plan.toString())
            assertTrue("mcp__tracker__search" in edit && "mcp__tracker__create_issue" in edit, edit.toString())
        } finally { controller.close(); model.stop(0) }
    }
}
