package dev.chiller.agent

import dev.chiller.contracts.AgentMessage
import kotlinx.serialization.json.*

/** A character-based context estimate; it is not a model tokenizer result. */
data class ContextSelection(
    val messages: List<AgentMessage>,
    val droppedMessages: Int,
    val estimatedTokens: Int,
    val estimateOnly: Boolean = true,
    val prunedResults: Int = 0,
)

class ContextLimitException(message: String) : IllegalArgumentException(message)

/**
 * Keeps system instructions and the newest contiguous history that fits the configured
 * character allowance. User turns and their assistant/tool exchanges are indivisible.
 * Old, large tool results are replaced with a short stub once the context is under
 * pressure, which is much cheaper than dropping a whole turn that still holds the goal.
 */
class ContextPlanner(
    private val maxCharacters: Int = 96_000,
    private val outputReserveCharacters: Int = 16_000,
    private val pruneThreshold: Double = 0.6,
    private val keepToolResults: Int = 6,
    private val pruneAboveCharacters: Int = 600,
) {
    init {
        require(maxCharacters > 0) { "maxCharacters must be positive" }
        require(outputReserveCharacters >= 0) { "outputReserveCharacters cannot be negative" }
        require(outputReserveCharacters < maxCharacters) { "output reserve must be smaller than the total character budget" }
        require(pruneThreshold in 0.0..1.0) { "pruneThreshold must be within 0..1" }
        require(keepToolResults >= 0) { "keepToolResults cannot be negative" }
    }

    fun select(history: List<AgentMessage>): ContextSelection {
        val budget = (maxCharacters - outputReserveCharacters).toLong()
        // Compact before fitting whole turns: a long active tool loop must have the same
        // opportunity to shed old results as completed history. Pairing/replay stay intact.
        val candidates = history.indices.filter {
            history[it].role == "tool" && (history[it].content?.length ?: 0) > pruneAboveCharacters
        }.dropLast(keepToolResults)
        val prune = if (history.sumOf(::messageCharacters) > budget * pruneThreshold) candidates.toSet() else emptySet()
        val messages = history.mapIndexed { index, message ->
            if (index in prune) message.copy(content = prunedResult(message.content)) else message
        }
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
        var selected = messages.filterIndexed { index, message -> message.role == "system" || keepIndices.contains(index) }
        val dropped = messages.size - selected.size

        val pruned = keepIndices.count { it in prune }

        val estimatedTokens = ((selected.sumOf(::messageCharacters) + 3L) / 4L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return ContextSelection(selected, dropped, estimatedTokens, prunedResults = pruned)
    }

    private fun splitIntoGroups(messages: List<AgentMessage>): List<TurnGroup> {
        val groups = mutableListOf<TurnGroup>()
        var current = mutableListOf<Int>()
        var hasUser = false

        fun finishGroup() {
            if (current.isNotEmpty()) {
                val groupMessages = current.map(messages::get)
                val paired = hasCompleteToolPairs(groupMessages)
                val finalMessage = groupMessages.lastOrNull()
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
            message.toolCalls.sumOf { it.id.length.toLong() + it.name.length + it.arguments.length } + message.replay.toString().length + message.attachments.sumOf { it.name.length.toLong() + it.artifactId.length + 200 }

    private data class TurnGroup(
        val indices: List<Int>,
        val complete: Boolean,
        val toolPairsComplete: Boolean,
    )

    private fun prunedResult(content: String?): String {
        val original = runCatching { Json.parseToJsonElement(content.orEmpty()) as? JsonObject }.getOrNull()
        return buildJsonObject {
            // Never turn an error or unknown outcome into a successful result during compaction.
            original?.get("ok")?.let { put("ok", it) }
            original?.get("error")?.let { error ->
                val code = (error as? JsonObject)?.get("code")
                if (code is JsonPrimitive) put("error", buildJsonObject { put("code", code.content.take(120)) })
            }
            original?.get("artifactId")?.let { if (it is JsonPrimitive) put("artifactId", it.content.take(100)) }
            put("pruned", true)
            put("note", "Older tool output omitted. Read the file or re-run a READ tool to recover data. Never repeat a write or process merely to recover output.")
        }.toString()
    }
}
