package dev.chiller.app.ui

import dev.chiller.contracts.Activity
import dev.chiller.contracts.AgentMessage
import dev.chiller.contracts.StoredEvent
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val FILE_CHANGES = setOf("edit_file", "replace_text", "create_file", "move_file", "delete_file", "create_directory", "generate_image")

/**
 * Change ids made in answer to the user message at [userIndex], oldest first: every successful file change
 * whose tool call belongs to an assistant message after it and before the next user message.
 */
fun turnChangeIds(messages: List<AgentMessage>, activities: List<Activity>, userIndex: Int, undone: Set<String> = emptySet()): List<String> {
    if (messages.getOrNull(userIndex)?.role != "user") return emptyList()
    val end = (userIndex + 1 until messages.size).firstOrNull { messages[it].role == "user" } ?: messages.size
    val callIds = (userIndex + 1 until end).flatMap { messages[it].toolCalls.map { call -> call.id } }.toSet()
    // Already undone changes are skipped: an undo activity carries the path, not the id, so match by output.
    return activities.filter { it.toolCallId in callIds && it.name in FILE_CHANGES && it.status == "completed" }
        .mapNotNull { changeId(it.output) }.filterNot { it in undone }
}

/** Change ids already undone, from the workspace's durable journal (every undo commit names the change it reverts). */
fun undoneChangeIds(journal: List<StoredEvent>): Set<String> =
    journal.filter { it.type == "workspace.commit" }.mapNotNull { it.payload["undoOf"]?.jsonPrimitive?.contentOrNull }.toSet()
