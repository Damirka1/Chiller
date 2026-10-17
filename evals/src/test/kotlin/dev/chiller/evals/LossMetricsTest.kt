package dev.chiller.evals

import dev.chiller.contracts.Activity
import dev.chiller.contracts.AgentMessage
import dev.chiller.contracts.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LossMetricsTest {
    private fun call(id: String, name: String, args: String) = AgentMessage("assistant", toolCalls = listOf(ToolCall(id, name, args)))
    private fun result(id: String, text: String) = AgentMessage("tool", text, toolCallId = id)

    @Test fun `counts rereads until a write, failed edits and tool output`() {
        val messages = listOf(
            call("1", "read_file", """{"path":"a.py"}"""), result("1", "x".repeat(100)),
            call("2", "read_file", """{"path":"a.py"}"""), result("2", "x".repeat(100)),               // repeated
            call("3", "read_file", """{"path":"a.py","start_line":1,"end_line":5}"""), result("3", "y"), // other range
            call("4", "edit_file", """{"path":"a.py","snapshot_id":"s"}"""), result("4", "stale"),      // failed edit
            call("5", "read_file", """{"path":"a.py"}"""), result("5", "z"),                           // still repeated
            call("6", "replace_text", """{"path":"a.py"}"""), result("6", "ok"),                        // successful write
            call("7", "read_file", """{"path":"a.py"}"""), result("7", "w"),                           // legitimate
        )
        val activities = listOf(Activity(name = "edit_file", target = "a.py", status = "failed", toolCallId = "4"))
        val loss = lossMetrics(messages, activities)
        assertEquals(2, loss.repeatedReads)
        assertEquals(1, loss.failedEdits)
        assertEquals(100L + 100 + 1 + 5 + 1 + 2 + 1, loss.toolOutputChars)
        assertEquals(100, loss.largestToolOutput)
    }

    @Test fun `report shows losses and summaries add them up`() {
        val r = EvalResult("t", 1, true, "ok", "completed", null, 3, 4, 1, emptyMap(), listOf("read_file"), 1000, 10, 5, 0, "done",
            loss = LossMetrics(repeatedReads = 2, failedEdits = 1, toolOutputChars = 4000))
        val summary = summarize(listOf(r, r.copy(attempt = 2))).single()
        assertEquals(4, summary.repeatedReads); assertEquals(2, summary.failedEdits); assertEquals(8000, summary.toolOutputChars)
        val md = renderMarkdown(EvalReport("m", "e", "s", 14, 2048, 2, listOf(r), listOf(summary)))
        assertTrue("Losses: repeated reads 2 · failed edits 1 · tool output sent 4k chars" in md, md)
    }
}
