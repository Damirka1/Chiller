package dev.chiller.app.di

import dev.chiller.contracts.CostBudgetSettings
import dev.chiller.contracts.ToolException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CostBudgetBindingTest {
    private val prices = CostBudgetSettings(enabled = true, limitUsd = "1", inputUsdPerMillion = "1", outputUsdPerMillion = "2", imageUsdPerRequest = "0.1",
        baseUrl = "https://gateway.example.com/v1/", model = "quasar", imageModel = "flux-2-klein")
    private val settings = DesktopSettings(baseUrl = "https://gateway.example.com/v1", model = "quasar", costBudget = prices)

    @Test fun `prices apply only to the models they were saved for`() {
        assertNull(costBudgetFor(settings.copy(costBudget = prices.copy(enabled = false)), "quasar", null))
        costBudgetFor(settings, "quasar", "flux-2-klein")!!.reserveChat(100).finish(null)
        assertEquals("COST_BUDGET_MISMATCH", assertFailsWith<ToolException> { costBudgetFor(settings, "nebula", null)!!.reserveChat(100) }.code)
        assertEquals("COST_BUDGET_MISMATCH", assertFailsWith<ToolException> { costBudgetFor(settings, "quasar", "other-image")!!.reserveImage() }.code)
        assertEquals("COST_BUDGET_MISMATCH", assertFailsWith<ToolException> { costBudgetFor(settings.copy(baseUrl = "https://elsewhere.example.com/v1"), "quasar", null)!!.reserveChat(100) }.code)
    }

    @Test fun `an unusable budget refuses with its reason instead of being skipped`() {
        val broken = costBudgetFor(settings.copy(costBudget = prices.copy(outputUsdPerMillion = "")), "quasar", null)!!
        val error = assertFailsWith<ToolException> { broken.reserveChat(100) }
        assertEquals("COST_BUDGET_MISMATCH", error.code)
    }
}
