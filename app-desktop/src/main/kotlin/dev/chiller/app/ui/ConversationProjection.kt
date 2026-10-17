package dev.chiller.app.ui

import dev.chiller.contracts.*

data class ConversationEntry(val index: Int, val message: AgentMessage, val activities: List<Activity>)
data class ConversationProjection(val entries: List<ConversationEntry>, val unlinked: List<Activity>)

fun projectConversation(messages: List<AgentMessage>, activities: List<Activity>): ConversationProjection {
    val pending = activities.filter { it.toolCallId != null }.groupBy { it.toolCallId }.mapValues { ArrayDeque(it.value) }
    val used = mutableSetOf<String>()
    val entries = messages.mapIndexedNotNull { index, message ->
        if (message.role !in setOf("user", "assistant")) null else {
            val linked = message.toolCalls.mapNotNull { pending[it.id]?.removeFirstOrNull() }.onEach { used += it.id }
            ConversationEntry(index, message, linked)
        }
    }
    return ConversationProjection(entries, activities.filterNot { it.id in used })
}

fun activityGroupLabel(activities: List<Activity>): String {
    val complete = activities.all { it.status == "completed" }
    val paths = activities.map { it.target }.filter { it.isNotBlank() }.distinct()
    val count = paths.size
    return when {
        complete && paths.size == activities.map { it.target }.distinct().size && count > 0 && activities.all { it.name == "read_file" } -> "Прочитано файлов: $count"
        complete && activities.all { it.target.isNotBlank() && it.name in setOf("edit_file", "replace_text", "create_file", "move_file", "delete_file") } -> "Изменено файлов: $count"
        else -> "Действий: ${activities.size}"
    }
}
