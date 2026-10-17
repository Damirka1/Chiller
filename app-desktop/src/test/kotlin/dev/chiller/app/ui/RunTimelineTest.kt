package dev.chiller.app.ui

import dev.chiller.contracts.RunState
import dev.chiller.contracts.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunTimelineTest {
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
}
