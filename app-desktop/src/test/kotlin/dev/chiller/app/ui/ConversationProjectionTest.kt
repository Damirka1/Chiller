package dev.chiller.app.ui

import dev.chiller.contracts.AgentMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationProjectionTest {
    @Test fun `visible message entries keep original history indices`() {
        val history = listOf(
            AgentMessage("system", "instructions"),
            AgentMessage("user", "question"),
            AgentMessage("tool", "result"),
            AgentMessage("assistant", "answer"),
        )

        assertEquals(listOf(1, 3), projectConversation(history, emptyList()).entries.map { it.index })
    }
}
