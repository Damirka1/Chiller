package dev.chiller.agent

import dev.chiller.contracts.AgentMessage

/** A character-based context estimate; it is not a model tokenizer result. */
data class ContextSelection(
    val messages: List<AgentMessage>,
    val droppedMessages: Int,
    val estimatedTokens: Int,
    val estimateOnly: Boolean = true,
)

class ContextLimitException(message: String) : IllegalArgumentException(message)

/**
 * Keeps system instructions and the newest contiguous history that fits the configured
 * character allowance. User turns and their assistant/tool exchanges are indivisible.
 */
class ContextPlanner(
    private val maxCharacters: Int = 96_000,
    private val outputReserveCharacters: Int = 16_000,
) {
    init {
        require(maxCharacters > 0) { "maxCharacters must be positive" }
        require(outputReserveCharacters >= 0) { "outputReserveCharacters cannot be negative" }
        require(outputReserveCharacters < maxCharacters) { "output reserve must be smaller than the total character budget" }
    }

    fun select(messages: List<AgentMessage>): ContextSelection {
        val budget = (maxCharacters - outputReserveCharacters).toLong()
        val fixedCost = messages.filter { it.role == "system" }.sumOf(::messageCharacters)
        if (fixedCost > budget) {
            throw ContextLimitException("System messages exceed the available context character budget")
        }

        val groups = splitIntoGroups(messages)
        val keep = BooleanArray(groups.size)
        var used = fixedCost
        if (groups.isNotEmpty()) {
            val current = groups.last()
            if (!current.toolPairsComplete) {
                throw ContextLimitException("The current turn contains an incomplete or orphaned tool call")
            }
            val currentCost = current.indices.sumOf { messageCharacters(messages[it]) }
            if (used + currentCost > budget) {
                throw ContextLimitException("The current turn exceeds the available context character budget")
            }
            keep[groups.lastIndex] = true
            used += currentCost

            for (index in groups.lastIndex - 1 downTo 0) {
                val group = groups[index]
                // Never cross a broken or unfinished historical turn: doing so could hide
                // an unknown operation or leave its call/result context inconsistent.
                if (!group.complete || !group.toolPairsComplete) break
                val cost = group.indices.sumOf { messageCharacters(messages[it]) }
                if (used + cost > budget) break
                keep[index] = true
                used += cost
            }
        }

        val keepIndices = HashSet<Int>()
        groups.forEachIndexed { index, group -> if (keep[index]) keepIndices.addAll(group.indices) }
        val selected = messages.filterIndexed { index, message -> message.role == "system" || keepIndices.contains(index) }
        val dropped = messages.size - selected.size
        val estimatedTokens = ((used + 3L) / 4L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return ContextSelection(selected, dropped, estimatedTokens)
    }

    private fun splitIntoGroups(messages: List<AgentMessage>): List<TurnGroup> {
        val groups = mutableListOf<TurnGroup>()
        var current = mutableListOf<Int>()
        var hasUser = false

        fun finishGroup() {
            if (current.isNotEmpty()) {
                val groupMessages = current.map(messages::get)
                val paired = hasCompleteToolPairs(groupMessages)
                val finalMessage = groupMessages.lastOrNull { it.role != "system" }
                groups += TurnGroup(
                    indices = current.toList(),
                    toolPairsComplete = paired,
                    complete = paired && hasUser && finalMessage?.role == "assistant" && finalMessage.toolCalls.isEmpty(),
                )
            }
            current = mutableListOf()
            hasUser = false
        }

        messages.forEachIndexed { index, message ->
            if (message.role == "system") return@forEachIndexed
            if (message.role == "user" && hasUser) finishGroup()
            if (message.role == "user") hasUser = true
            current += index
        }
        finishGroup()
        return groups
    }

    private fun hasCompleteToolPairs(messages: List<AgentMessage>): Boolean {
        val calls = HashSet<String>()
        val results = HashSet<String>()
        for (message in messages) {
            if (message.role == "assistant") {
                for (call in message.toolCalls) {
                    if (call.id.isBlank() || !calls.add(call.id)) return false
                }
            } else if (message.role == "tool") {
                val id = message.toolCallId?.takeIf { it.isNotBlank() } ?: return false
                if (!calls.contains(id) || !results.add(id)) return false
            }
        }
        return calls == results
    }

    private fun messageCharacters(message: AgentMessage): Long =
        8L + message.role.length + (message.content?.length ?: 0) + (message.toolCallId?.length ?: 0) +
            message.toolCalls.sumOf { it.id.length.toLong() + it.name.length + it.arguments.length } + message.replay.toString().length

    private data class TurnGroup(
        val indices: List<Int>,
        val complete: Boolean,
        val toolPairsComplete: Boolean,
    )
}
