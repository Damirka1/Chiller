package dev.chiller.app.di

import dev.chiller.contracts.AgentMessage
import dev.chiller.tools.FileStamp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Project files that changed during a turn without going through the agent's file tools: written by a
 * command or edited by hand. Turn rewind does not cover them. [userIndex] ties the record to its turn.
 */
@Serializable
data class OutsideChanges(
    val userIndex: Int,
    val added: List<String> = emptyList(),
    val modified: List<String> = emptyList(),
    val deleted: List<String> = emptyList(),
    /** More paths changed than are listed. */
    val truncated: Boolean = false,
) {
    val count: Int get() = added.size + modified.size + deleted.size
}

/** Final expected states from successful, journaled changes in this turn (null means absent).
 * Failed calls never exclude a path. A move covers both its original and destination path.
 */
fun toolExpectedStates(messages: List<AgentMessage>, userIndex: Int, activities: List<dev.chiller.contracts.Activity>, journal: List<dev.chiller.contracts.StoredEvent>): Map<String, String?> {
    val end = (userIndex + 1 until messages.size).firstOrNull { messages[it].role == "user" } ?: messages.size
    val calls = messages.subList((userIndex + 1).coerceAtMost(messages.size), end).flatMap { it.toolCalls }.map { it.id }.toSet()
    val ids = activities.filter { it.toolCallId in calls && it.status == "completed" }.mapNotNull { activity ->
        runCatching { Json.parseToJsonElement(activity.output).jsonObject["data"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull }.getOrNull()
    }.toSet()
    return buildMap {
        journal.filter { it.type == "workspace.commit" && it.payload["changeId"]?.jsonPrimitive?.contentOrNull in ids }.forEach { event ->
            val path = event.payload["path"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val operation = event.payload["operation"]?.jsonPrimitive?.contentOrNull
            if (operation == "mkdir") return@forEach
            val hash = event.payload["afterHash"]?.jsonPrimitive?.contentOrNull
            if (operation == "move") {
                put(path, null)
                event.payload["destination"]?.jsonPrimitive?.contentOrNull?.let { put(it, hash) }
            } else put(path, hash)
        }
    }
}

/** Compare the final files against the journal, not merely the names passed to tools.
 * An unknown hash is reported conservatively; metadata alone cannot prove that undo covers a file.
 */
fun outsideChanges(before: Map<String, FileStamp>, after: Map<String, FileStamp>, expected: Map<String, String?>, userIndex: Int, limit: Int = 200): OutsideChanges {
    val candidates = (before.keys + after.keys + expected.keys).filter { path ->
        if (path in expected) {
            val hash = expected[path]
            if (hash == null) path in after else after[path]?.contentHash != hash
        } else before[path] != after[path]
    }.sorted()
    val added = candidates.filter { it !in before && it in after }
    val deleted = candidates.filter { it in before && it !in after || it in expected && expected[it] != null && it !in after }
    val modified = candidates.filter { it in before && it in after }
    var remaining = limit.coerceAtLeast(0)
    fun bounded(paths: List<String>): List<String> = paths.take(remaining).also { remaining -= it.size }
    return OutsideChanges(userIndex, bounded(added), bounded(modified), bounded(deleted), truncated = candidates.size > limit)
}
