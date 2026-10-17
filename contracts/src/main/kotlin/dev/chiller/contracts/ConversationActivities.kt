package dev.chiller.contracts

import kotlinx.serialization.json.*

/** Provider call IDs can repeat across runs; bind each operation to its journaled message. */
fun projectConversationActivities(history: List<StoredEvent>, visibleMessageSequences: Set<Long>): List<StoredEvent> {
    if (history.none { it.type.startsWith("history.") }) return history.filter { it.type == "activity" }
    val callOwners = mutableMapOf<String, Long>()
    val operationOwners = mutableMapOf<String, Long>()
    val result = mutableListOf<StoredEvent>()
    for (event in history) when (event.type) {
        "message" -> if (event.payload["role"]?.jsonPrimitive?.contentOrNull == "assistant") {
            event.payload["toolCalls"]?.jsonArray?.forEach { call ->
                call.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.let { callOwners[it] = event.seq }
            }
        }
        "activity" -> {
            val operation = event.payload["id"]?.jsonPrimitive?.contentOrNull
            val call = event.payload["toolCallId"]?.jsonPrimitive?.contentOrNull
            val owner = operation?.let { operationOwners[it] } ?: call?.let { callOwners[it] }
            if (operation != null && owner != null) operationOwners.putIfAbsent(operation, owner)
            if (owner in visibleMessageSequences) result += event
        }
    }
    return result
}
