package dev.chiller.agent

import dev.chiller.contracts.AgentMessage
import dev.chiller.contracts.ToolCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContextPlannerTest {
    @Test fun budgetDropsOldestWholeTurnAndKeepsSystemAndNewestTurn() {
        val system = AgentMessage("system", "instructions")
        val oldUser = AgentMessage("user", "old ".repeat(25))
        val oldAssistant = AgentMessage("assistant", "old answer ".repeat(10))
        val currentUser = AgentMessage("user", "current request")
        val currentAssistant = AgentMessage("assistant", "current answer")
        val all = listOf(system, oldUser, oldAssistant, currentUser, currentAssistant)

        val selected = ContextPlanner(maxCharacters = 500, outputReserveCharacters = 350).select(all)

        assertEquals(listOf(system, currentUser, currentAssistant), selected.messages)
        assertEquals(2, selected.droppedMessages)
        assertTrue(selected.estimatedTokens > 0)
        assertTrue(selected.estimateOnly)
    }

    @Test fun retainedToolGroupPreservesReplayPayloadExactly() {
        val replay = buildJsonObject { put("reasoning_content", "private replay stays attached") }
        val call = ToolCall("call-1", "read", "{\"path\":\"src/Main.kt\"}")
        val messages = listOf(
            AgentMessage("system", "system"),
            AgentMessage("user", "inspect"),
            AgentMessage("assistant", toolCalls = listOf(call), replay = replay),
            AgentMessage("tool", "{\"ok\":true}", toolCallId = "call-1"),
            AgentMessage("assistant", "done"),
        )

        val selected = ContextPlanner(maxCharacters = 1_000, outputReserveCharacters = 100).select(messages)

        assertEquals(messages, selected.messages)
        assertEquals(replay, selected.messages[2].replay)
    }

    @Test fun droppingAHistoryTurnNeverLeavesOrphanedToolMessages() {
        val oldCall = ToolCall("old-call", "read", "{}")
        val messages = listOf(
            AgentMessage("system", "system"),
            AgentMessage("user", "old context ".repeat(30)),
            AgentMessage("assistant", toolCalls = listOf(oldCall)),
            AgentMessage("tool", "old result ".repeat(20), toolCallId = "old-call"),
            AgentMessage("assistant", "old final"),
            AgentMessage("user", "new question"),
        )

        val selected = ContextPlanner(maxCharacters = 500, outputReserveCharacters = 350).select(messages)

        assertEquals(listOf(messages[0], messages.last()), selected.messages)
        assertTrue(selected.messages.none { it.role == "tool" || it.toolCalls.isNotEmpty() })
        assertEquals(4, selected.droppedMessages)
    }

    @Test fun oversizedSystemMessagesFailWithTypedLimitException() {
        val planner = ContextPlanner(maxCharacters = 100, outputReserveCharacters = 20)
        assertFailsWith<ContextLimitException> {
            planner.select(listOf(AgentMessage("system", "x".repeat(100))))
        }
    }

    @Test fun oversizedCurrentTurnWithPairedActiveToolCallIsRejectedWhole() {
        val call = ToolCall("active-call", "read", "{\"path\":\"${"x".repeat(200)}\"}")
        val messages = listOf(
            AgentMessage("system", "system"),
            AgentMessage("user", "current request".repeat(20)),
            AgentMessage("assistant", toolCalls = listOf(call)),
            AgentMessage("tool", "result", toolCallId = "active-call"),
        )
        val planner = ContextPlanner(maxCharacters = 250, outputReserveCharacters = 50)

        assertFailsWith<ContextLimitException> { planner.select(messages) }
    }
}
