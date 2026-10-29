package dev.chiller.app.ui

import dev.chiller.contracts.Activity
import dev.chiller.contracts.AgentMessage
import dev.chiller.contracts.RunState
import dev.chiller.contracts.ToolCall
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryOfferTest {
    private val call = AgentMessage("assistant", toolCalls = listOf(ToolCall("c1", "run_process", "{}")))
    private val interrupted = Activity(name = "run_process", target = "python _preview.py", status = "interrupted", toolCallId = "c1")

    @Test fun theOfferStaysOnlyWhileAnInterruptedStepIsOpenAndNothingRuns() {
        val open = RunState(status = "cancelled", messages = listOf(call), activities = listOf(interrupted))
        assertTrue(needsRecovery(open))
        assertTrue(needsRecovery(open.copy(status = "interrupted")))
        assertFalse(needsRecovery(open.copy(status = "streaming")), "a running turn has nothing to recover")
        // A new message closed the call as unknown: the interrupted row stays in history, the offer goes away.
        val closed = open.copy(status = "completed", messages = listOf(call, AgentMessage("tool", "OUTCOME_UNKNOWN", toolCallId = "c1"), AgentMessage("user", "Continue")))
        assertFalse(needsRecovery(closed))
    }
}
