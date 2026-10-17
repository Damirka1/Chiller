package dev.chiller.app.ui

import dev.chiller.contracts.AgentMessage
import dev.chiller.contracts.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlanChecklistTest {
    private fun plan(vararg steps: Pair<String, String>) = AgentMessage("assistant", null, listOf(ToolCall("p", "update_plan",
        steps.joinToString(",", "{\"steps\":[", "]}") { (s, st) -> "{\"step\":\"$s\",\"status\":\"$st\"}" })))

    @Test fun `latest plan of the current turn only`() {
        val old = listOf(AgentMessage("user", "first"), plan("a" to "done"))
        assertEquals(listOf(PlanStep("a", "done")), currentTurnPlan(old))
        assertNull(currentTurnPlan(old + AgentMessage("user", "second")), "a new request does not show the previous plan")
        val updated = old + AgentMessage("user", "second") + plan("x" to "in_progress", "y" to "pending") + plan("x" to "done", "y" to "in_progress")
        assertEquals(listOf(PlanStep("x", "done"), PlanStep("y", "in_progress")), currentTurnPlan(updated))
    }
}
