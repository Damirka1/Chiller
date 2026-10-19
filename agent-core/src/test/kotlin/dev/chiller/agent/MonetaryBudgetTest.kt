package dev.chiller.agent

import dev.chiller.contracts.CostBudgetSettings
import dev.chiller.contracts.TokenUsage
import dev.chiller.contracts.ToolException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MonetaryBudgetTest {
    // $1 per 1M input and $2 per 1M output tokens; the worst case of one request is 100k in + 50k out = $0.2.
    private val settings = CostBudgetSettings(enabled = true, limitUsd = "0.5", inputUsdPerMillion = "1", outputUsdPerMillion = "2",
        imageUsdPerRequest = "0.1", inputTokenCeiling = 100_000)

    @Test fun reservationsAreReplacedByMeasuredCost() {
        val budget = MonetaryBudget(settings)
        budget.reserveChat(50_000).finish(TokenUsage(promptTokens = 10_000, completionTokens = 1_000))
        assertEquals("0.012", budget.snapshot().accountedUsd)
        assertEquals("0", budget.snapshot().reservedUsd)
    }

    @Test fun unknownUsageKeepsTheWorstCaseAndTheLimitStops() {
        val budget = MonetaryBudget(settings)
        budget.reserveChat(50_000).finish(null)
        budget.reserveChat(50_000).finish(null)
        assertEquals("0.4", budget.snapshot().accountedUsd)
        assertEquals(2, budget.snapshot().uncertainAttempts)
        assertEquals("COST_BUDGET_EXHAUSTED", assertFailsWith<ToolException> { budget.reserveChat(50_000) }.code)
        budget.beginTask()
        budget.reserveImage().finish()
        assertEquals("0.1", budget.snapshot().accountedUsd, "a new task starts from zero; images keep their fixed price")
    }

    @Test fun aPromptOverTheCeilingStopsFurtherSpending() {
        val budget = MonetaryBudget(settings)
        budget.reserveChat(1_000).finish(TokenUsage(promptTokens = 150_000, completionTokens = 10))
        assertTrue(budget.snapshot().exceededEstimate)
        assertEquals("COST_BUDGET_EXHAUSTED", assertFailsWith<ToolException> { budget.reserveChat(1_000) }.code)
    }

    @Test fun missingPricesFailClosed() {
        assertEquals("INVALID_COST_BUDGET", assertFailsWith<ToolException> { MonetaryBudget(settings.copy(outputUsdPerMillion = "")) }.code)
        assertEquals("INVALID_COST_BUDGET", assertFailsWith<ToolException> { MonetaryBudget(settings.copy(imageUsdPerRequest = "")).reserveImage() }.code)
        assertEquals("INVALID_COST_BUDGET", assertFailsWith<ToolException> { MonetaryBudget(settings.copy(limitUsd = "0")) }.code)
    }

    @Test fun aBudgetForAnotherModelRefuses() {
        val bound = BoundBudget(MonetaryBudget(settings), "prices are for model-a", null)
        assertEquals("COST_BUDGET_MISMATCH", assertFailsWith<ToolException> { bound.reserveChat(10) }.code)
        bound.reserveImage().finish()
        assertEquals("0.1", bound.snapshot().accountedUsd)
    }
}
