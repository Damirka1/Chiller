package dev.chiller.app.ui

import dev.chiller.contracts.Activity
import dev.chiller.contracts.AgentMessage

private val FILE_CHANGES = setOf("edit_file", "replace_text", "create_file", "move_file", "delete_file", "create_directory")

/**
 * Change ids made in answer to the user message at [userIndex], oldest first: every successful file change
 * whose tool call belongs to an assistant message after it and before the next user message.
 */
fun turnChangeIds(messages: List<AgentMessage>, activities: List<Activity>, userIndex: Int): List<String> {
    if (messages.getOrNull(userIndex)?.role != "user") return emptyList()
    val end = (userIndex + 1 until messages.size).firstOrNull { messages[it].role == "user" } ?: messages.size
    val callIds = (userIndex + 1 until end).flatMap { messages[it].toolCalls.map { call -> call.id } }.toSet()
    // Already undone changes are skipped: an undo activity carries the path, not the id, so match by output.
    return activities.filter { it.toolCallId in callIds && it.name in FILE_CHANGES && it.status == "completed" }
        .mapNotNull { changeId(it.output) }
}
