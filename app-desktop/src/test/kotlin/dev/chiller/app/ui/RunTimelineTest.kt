package dev.chiller.app.ui

import dev.chiller.contracts.RunState
import dev.chiller.contracts.TokenUsage
import dev.chiller.contracts.Activity
import dev.chiller.contracts.ComposerAttachment
import dev.chiller.contracts.UserQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunTimelineTest {
    // Helpers read the global language; these checks are written against the Russian texts.
    @kotlin.test.BeforeTest fun russian() { dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.RuStrings }
    @kotlin.test.AfterTest fun english() { dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.EnStrings }

    @Test fun incompleteUsageAndRecoveryAreVisible() {
        assertTrue(runSummaryParts(RunState(modelRounds = 2, usageIncomplete = true)).contains("расход известен частично"))
        assertEquals("retrying", runProgressLabel(RunState(status = "streaming", recoveryNotice = "retrying")))
        assertTrue(runErrorDescription("INCOMPLETE_TOOL_CALL").contains("Незавершённый вызов не выполнен"))
    }

    @Test fun `run progress distinguishes model tool user and terminal states`() {
        assertEquals("Ждёт ответа модели…", runProgressLabel(RunState(status = "streaming")))
        assertEquals("Модель пишет…", runProgressLabel(RunState(status = "streaming", streamingText = "Привет")))
        assertEquals("Чтение · README.md", runProgressLabel(RunState(status = "executing", activities = listOf(Activity(name = "read_file", target = "README.md", status = "running")))))
        assertEquals("Ожидает вашего ответа", runProgressLabel(RunState(status = "executing", question = UserQuestion(question = "Как поступить?"))))
        assertEquals("Ответ готов", runProgressLabel(RunState(status = "completed")))
        assertEquals("Задача остановлена", runProgressLabel(RunState(status = "cancelled")))
    }

    @Test fun `summary uses only reported counters`() {
        val state = RunState(status = "completed", modelRounds = 5, toolCalls = 6, failedToolCalls = 1, runDurationMs = 9_400,
            usage = TokenUsage(promptTokens = 10_800, completionTokens = 634, cachedPromptTokens = 9_400))
        assertEquals(listOf("5 раундов", "6 действий (ошибок: 1)", "9,4 с", "11k → 634 токенов", "кэш 87%"), runSummaryParts(state))
    }

    @Test fun `summary omits usage the provider did not report`() {
        val parts = runSummaryParts(RunState(status = "completed", modelRounds = 1, toolCalls = 1))
        assertEquals(listOf("1 раунд", "1 действие"), parts)
        assertTrue(runSummaryParts(RunState()).isEmpty())
    }

    @Test fun `diff stats ignore file headers`() {
        assertEquals(2 to 1, diffStats("--- a/x\n+++ b/x\n@@\n-old\n+new\n+more\n ctx"))
        assertEquals("1,5k", compactTokens(1_500))
    }

    @Test fun `compact tool groups retain errors and questions`() {
        val read = Activity(id = "read", name = "read_file", target = "README.md", status = "completed")
        val error = Activity(id = "error", name = "edit_file", target = "src/App.kt", status = "failed")
        val question = Activity(id = "question", name = "ask_user", target = "", status = "completed")
        assertEquals(listOf(error, question), compactToolActivities(listOf(read, error, question), expanded = false))
        assertEquals(listOf(read, error, question), compactToolActivities(listOf(read, error, question), expanded = true))
    }

    @Test fun `file presentation output parses safely and attachment prompt stays explicit`() {
        val file = presentedFileFromOutput("""{"data":{"file":{"path":"/tmp/a.png","name":"a.png","mimeType":"image/png","sizeBytes":42}}}""")
        assertEquals("a.png", file?.name)
        assertEquals(null, presentedFileFromOutput("not json"))
        val prompt = promptWithAttachments(" inspect ", listOf(ComposerAttachment("att-1", "a.png", "image/png", 42)))
        assertTrue(prompt.startsWith("inspect"))
        assertTrue(prompt.contains("artifact_id=att-1"))
        assertTrue(prompt.contains("the image content was not sent"))
    }

    @Test fun `presented files are independent of group expansion and exclude failed operations`() {
        fun file(id: String, path: String, status: String = "completed") = Activity(id = id, name = "show_file", target = path, status = status,
            output = """{"data":{"file":{"path":"$path","name":"plot.png","mimeType":"image/png","sizeBytes":42}}}""")
        val activities = listOf(file("1", "a.png"), file("2", "b.png", "failed"), file("3", "a.png"), file("4", "c.png"))
        assertEquals(listOf("a.png", "c.png"), presentedFiles(activities).map { it.path })
        assertEquals(listOf("2"), compactToolActivities(activities, false).map { it.id })
    }

    @Test fun `command failures count terminal processes once separately from tool errors`() {
        val state = RunState(messages = listOf(
            dev.chiller.contracts.AgentMessage("user", "old"),
            dev.chiller.contracts.AgentMessage("assistant", toolCalls = listOf(dev.chiller.contracts.ToolCall("old", "run_process", "{}"))),
            dev.chiller.contracts.AgentMessage("tool", """{"ok":true,"data":{"processId":"old","state":"exited","exitCode":1}}""", toolCallId = "old"),
            dev.chiller.contracts.AgentMessage("user", "new"),
            dev.chiller.contracts.AgentMessage("assistant", toolCalls = listOf(
                dev.chiller.contracts.ToolCall("start", "run_process", "{}"), dev.chiller.contracts.ToolCall("read", "read_process", "{}"),
                dev.chiller.contracts.ToolCall("again", "read_process", "{}"), dev.chiller.contracts.ToolCall("ok", "run_process", "{}"))),
            dev.chiller.contracts.AgentMessage("tool", """{"ok":true,"data":{"processId":"p","state":"running","exitCode":null}}""", toolCallId = "start"),
            dev.chiller.contracts.AgentMessage("tool", """{"ok":true,"data":{"processId":"p","state":"exited","exitCode":1}}""", toolCallId = "read"),
            dev.chiller.contracts.AgentMessage("tool", """{"ok":true,"data":{"processId":"p","state":"exited","exitCode":1}}""", toolCallId = "again"),
            dev.chiller.contracts.AgentMessage("tool", """{"ok":true,"data":{"processId":"q","state":"exited","exitCode":0}}""", toolCallId = "ok"),
        ))
        assertEquals(1, latestFailedCommands(state))
    }
}
