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
        assertTrue(prompt.contains("содержимое изображения не передано"))
    }
}
