package dev.chiller.app.di

import dev.chiller.contracts.AgentMessage

/** Half-open message range selected for an append-only history.remove_turn event. */
data class RemoveTurnPlan(val start: Int, val end: Int, val expectedCount: Int) {
    val eventType: String get() = "history.remove_turn"
}

/** Replacement projection and append-only history.replace_turn event for a user message. */
data class EditTurnPlan(
    val index: Int,
    val expectedCount: Int,
    val replacement: AgentMessage,
    val messages: List<AgentMessage>,
) {
    val eventType: String get() = "history.replace_turn"
    val text: String get() = replacement.content.orEmpty()
}

/** Pure planning helpers. They do not append events, call providers, or execute tools. */
object MessageHistoryActions {
    /** Finds the user turn containing [selectedIndex], including its assistant and tool messages. */
    fun planDeleteTurn(messages: List<AgentMessage>, selectedIndex: Int): RemoveTurnPlan? {
        if (selectedIndex !in messages.indices) return null
        val start = (selectedIndex downTo 0).firstOrNull { messages[it].role == "user" } ?: return null
        val end = ((start + 1) until messages.size).firstOrNull { messages[it].role == "user" } ?: messages.size
        if (!hasCompleteToolPairs(messages.take(start) + messages.drop(end))) return null
        return RemoveTurnPlan(start, end, messages.size)
    }

    /** Edits only a user message, keeps its attachments, and truncates every later response/turn. */
    fun planEditTurn(messages: List<AgentMessage>, selectedIndex: Int, text: String): EditTurnPlan? {
        if (selectedIndex !in messages.indices || messages[selectedIndex].role != "user") return null
        val original = messages[selectedIndex]
        if (text.isBlank() && original.attachments.isEmpty()) return null
        val retained = messages.take(selectedIndex) + original.copy(content = text, toolCalls = emptyList(), toolCallId = null)
        if (!hasCompleteToolPairs(retained)) return null
        return EditTurnPlan(selectedIndex, messages.size, retained.last(), retained)
    }

    /** Rejects projections that would leave an unmatched tool call or tool result. */
    private fun hasCompleteToolPairs(messages: List<AgentMessage>): Boolean {
        val pending = mutableSetOf<String>()
        for (message in messages) {
            if (message.role == "assistant") {
                for (call in message.toolCalls) if (!pending.add(call.id)) return false
            }
            if (message.role == "tool") {
                val id = message.toolCallId ?: return false
                if (!pending.remove(id)) return false
            }
        }
        return pending.isEmpty()
    }
}
