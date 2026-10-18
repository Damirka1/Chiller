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

private val FILE_TOOLS = setOf("edit_file", "replace_text", "create_file", "move_file", "delete_file", "create_directory")

/** Paths the file tools touched in the turn after [userIndex] (both ends of a move). */
fun toolTouchedPaths(messages: List<AgentMessage>, userIndex: Int): Set<String> =
    messages.drop(userIndex + 1).flatMap { it.toolCalls }.filter { it.name in FILE_TOOLS }.flatMap { call ->
        val args = runCatching { Json.parseToJsonElement(call.arguments).jsonObject }.getOrNull() ?: return@flatMap emptyList()
        listOfNotNull(args["path"]?.jsonPrimitive?.contentOrNull, args["destination"]?.jsonPrimitive?.contentOrNull)
    }.map { it.removePrefix("./") }.toSet()

/** Compares two snapshots; paths the file tools touched are excluded, since the journal already covers them. */
fun outsideChanges(before: Map<String, FileStamp>, after: Map<String, FileStamp>, toolPaths: Set<String>, userIndex: Int, limit: Int = 200): OutsideChanges {
    val added = (after.keys - before.keys - toolPaths).sorted()
    val deleted = (before.keys - after.keys - toolPaths).sorted()
    val modified = after.keys.intersect(before.keys).filter { it !in toolPaths && after[it] != before[it] }.sorted()
    val total = added.size + deleted.size + modified.size
    return OutsideChanges(userIndex, added.take(limit), modified.take(limit), deleted.take(limit), truncated = total > added.take(limit).size + modified.take(limit).size + deleted.take(limit).size)
}
