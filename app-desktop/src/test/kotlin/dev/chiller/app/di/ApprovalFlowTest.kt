package dev.chiller.app.di

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.sun.net.httpserver.HttpServer
import dev.chiller.app.ui.ApprovalCard
import dev.chiller.app.ui.approvalPreview
import dev.chiller.contracts.ApprovalRequest
import dev.chiller.contracts.ExecutionMode
import dev.chiller.design.ChillerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ApprovalFlowTest {
    // The default language; previews are built outside composition and read the global one.
    @kotlin.test.BeforeTest fun english() { dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.EnStrings }

    @TempDir lateinit var temp: Path

    @Test fun `previews come from the call's own arguments`() {
        fun request(tool: String, args: String) = ApprovalRequest(runId = "r", callId = "c", tool = tool, arguments = Json.parseToJsonElement(args).jsonObject)
        val replace = approvalPreview(request("replace_text", """{"path":"src/a.kt","snapshot_id":"s","old_text":"val x = 1\n","new_text":"val x = 2\nval y = 3"}"""))
        assertEquals("Replace text", replace.title)
        assertEquals("--- a/src/a.kt\n+++ b/src/a.kt\n@@\n-val x = 1\n+val x = 2\n+val y = 3", replace.diff)
        val created = approvalPreview(request("create_file", """{"path":"n.txt","content":"${"line\\n".repeat(205)}"}"""))
        assertTrue(created.diff!!.startsWith("--- /dev/null\n+++ b/n.txt") && created.diff!!.endsWith("… 5 more lines"))
        val edits = approvalPreview(request("edit_file", """{"path":"b.kt","snapshot_id":"s","edits":[{"op":"delete","start_line":3,"end_line":4},{"op":"insert","before_line":9,"content":"x"}]}"""))
        assertEquals("2 edits by line number", edits.note)
        assertTrue("@@ delete lines 3–4" in edits.diff!! && "@@ insert before line 9\n+x" in edits.diff!!)
        assertEquals("→ c.txt", approvalPreview(request("move_file", """{"path":"a.txt","snapshot_id":"s","destination":"c.txt"}""")).note)
    }

    @Test fun `a change waits for the user, a declined one is never written`() = runBlocking<Unit> {
        val project = Files.createDirectories(temp.resolve("project")).toRealPath()
        var created = 0
        // A model that creates one file per turn, then answers; served locally.
        val model = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val last = Json.parseToJsonElement(exchange.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject["messages"]!!.jsonArray.last().jsonObject
                val chunk = if (last["role"]?.jsonPrimitive?.content == "tool") buildJsonObject {
                    put("choices", buildJsonArray { add(buildJsonObject { put("delta", buildJsonObject { put("content", "готово") }); put("finish_reason", "stop") }) })
                } else {
                    created++
                    val call = buildJsonObject { put("index", 0); put("id", "call-$created"); put("type", "function")
                        put("function", buildJsonObject { put("name", "create_file"); put("arguments", buildJsonObject { put("path", "file-$created.txt"); put("content", "ok\n") }.toString()) }) }
                    buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject { put("delta", buildJsonObject { put("tool_calls", buildJsonArray { add(call) }) }); put("finish_reason", "tool_calls") }) }) }
                }
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write("data: $chunk\n\ndata: [DONE]\n\n".toByteArray()) }
            }
            start()
        }
        val controller = DesktopController(temp.resolve("data"), initialCredential = null, catalogClient = OfflineCatalog)
        try {
            controller.initialize()
            controller.configure("http://127.0.0.1:${model.address.port}/v1", "fake-model", false, null)
            controller.chooseWorkspace(project)
            controller.setConfirmEdits(true)
            suspend fun waitFor(condition: () -> Boolean) = withTimeout(30_000) { while (!condition()) delay(20) }

            assertTrue(controller.send("Создай файл", ExecutionMode.WORKSPACE_EDIT).accepted)
            waitFor { controller.state.value.run.approval != null }
            assertEquals("file-1.txt", controller.state.value.run.approval!!.arguments["path"]!!.jsonPrimitive.content)
            assertFalse(Files.exists(project.resolve("file-1.txt")), "nothing is written before the decision")
            controller.decideApproval(false)
            waitFor { controller.state.value.run.status == "completed" }
            assertFalse(Files.exists(project.resolve("file-1.txt")))

            assertTrue(controller.send("Ещё раз", ExecutionMode.WORKSPACE_EDIT).accepted)
            waitFor { controller.state.value.run.approval != null }
            controller.decideApproval(true)
            waitFor { controller.state.value.run.status == "completed" && controller.state.value.run.approval == null }
            assertEquals("ok\n", Files.readString(project.resolve("file-2.txt")))
        } finally { controller.close(); model.stop(0) }
    }

    @Test fun `the card's buttons decide`() = runComposeUiTest {
        val decisions = mutableListOf<Pair<Boolean, Boolean>>()
        val request = ApprovalRequest(runId = "r", callId = "c", tool = "create_file", arguments = buildJsonObject { put("path", "a.txt"); put("content", "x") })
        setContent { ChillerTheme(darkTheme = false, reducedMotion = true) { ApprovalCard(request) { approved, rest -> decisions += approved to rest } } }
        onNodeWithText("Confirm the edit · Create file").assertExists()
        onNodeWithText("Decline").performClick()
        onNodeWithText("Apply").performClick()
        onNodeWithText("Apply the rest in this turn").performClick()
        assertEquals(listOf(false to false, true to false, true to true), decisions)
    }
}
