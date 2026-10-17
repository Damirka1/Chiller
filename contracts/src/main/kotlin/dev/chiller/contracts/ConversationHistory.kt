package dev.chiller.contracts

import kotlinx.serialization.json.*

/** Logical, append-only history edits never roll back or replay workspace/process effects. */
fun projectConversation(history: List<StoredEvent>): List<AgentMessage> {
    val json = Json { ignoreUnknownKeys = true }
    return projectConversationEvents(history).map { json.decodeFromJsonElement<AgentMessage>(it.payload) }
}

/** Event projection parallel to [projectConversation], retaining source identity for provenance. */
fun projectConversationEvents(history: List<StoredEvent>): List<StoredEvent> {
    val json = Json { ignoreUnknownKeys = true }
    val messages = mutableListOf<StoredEvent>()
    for (event in history) when (event.type) {
        "message" -> {
            json.decodeFromJsonElement<AgentMessage>(event.payload)
            messages += event
        }
        "history.remove_turn" -> {
            val start = event.payload.getValue("start").jsonPrimitive.int
            val end = event.payload.getValue("end").jsonPrimitive.int
            require(event.payload.getValue("expectedCount").jsonPrimitive.int == messages.size) { "History edit does not match its source" }
            require(start in messages.indices && end in (start + 1)..messages.size && role(messages[start]) == "user") { "Invalid history turn" }
            require(end == messages.size || role(messages[end]) == "user") { "History edit must remove a whole turn" }
            messages.subList(start, end).clear()
        }
        "history.truncate" -> {
            val count = event.payload.getValue("count").jsonPrimitive.int
            require(event.payload.getValue("expectedCount").jsonPrimitive.int == messages.size && count in 0..messages.size) { "History edit does not match its source" }
            require(count == messages.size || role(messages[count]) == "user") { "Truncate only at a user-turn boundary" }
            messages.subList(count, messages.size).clear()
        }
        "history.replace_turn" -> {
            val index = event.payload.getValue("index").jsonPrimitive.int
            require(event.payload.getValue("expectedCount").jsonPrimitive.int == messages.size) { "History edit does not match its source" }
            require(index in messages.indices && role(messages[index]) == "user") { "Only a user turn can be replaced" }
            val replacement = json.decodeFromJsonElement<AgentMessage>(event.payload.getValue("message"))
            require(replacement.role == "user") { "A replacement must be a user message" }
            messages.subList(index, messages.size).clear()
            messages += event.copy(type = "message", payload = json.encodeToJsonElement(replacement).jsonObject)
        }
    }
    return messages.toList()
}

private fun role(event: StoredEvent): String = event.payload["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
