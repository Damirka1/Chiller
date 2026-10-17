package dev.chiller.evals

import dev.chiller.contracts.Activity
import dev.chiller.contracts.AgentMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Where a run wasted effort, independent of whether it passed: work the model repeated, edits that did not
 * apply and how much tool output it had to carry in context.
 */
@Serializable
data class LossMetrics(
    /** read_file calls for a path and range already read, with no write to that path in between. */
    val repeatedReads: Int = 0,
    /** Failed calls of file-changing tools (stale snapshot, no match, bad range…). */
    val failedEdits: Int = 0,
    /** Characters of tool results returned to the model; prompt tokens include them on every later round. */
    val toolOutputChars: Long = 0,
    /** Largest single tool result, in characters. */
    val largestToolOutput: Int = 0,
)

internal val FILE_WRITE_TOOLS = setOf("edit_file", "replace_text", "create_file", "create_directory", "move_file", "delete_file")

fun lossMetrics(messages: List<AgentMessage>, activities: List<Activity>): LossMetrics {
    val failedIds = activities.filter { it.status == "failed" }.mapNotNull { it.toolCallId }.toSet()
    val seen = mutableMapOf<String, MutableSet<String>>()
    var repeated = 0
    var failedEdits = 0
    messages.flatMap { it.toolCalls }.forEach { call ->
        val args = runCatching { Json.parseToJsonElement(call.arguments).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val path = args["path"]?.jsonPrimitive?.contentOrNull
        when {
            call.name == "read_file" && path != null && call.id !in failedIds -> {
                val range = "${args["start_line"]?.jsonPrimitive?.contentOrNull}-${args["end_line"]?.jsonPrimitive?.contentOrNull}"
                if (!seen.getOrPut(path) { mutableSetOf() }.add(range)) repeated++
            }
            call.name in FILE_WRITE_TOOLS -> {
                if (call.id in failedIds) failedEdits++
                // A successful change makes re-reading the touched paths legitimate.
                else listOfNotNull(path, args["destination"]?.jsonPrimitive?.contentOrNull).forEach { seen.remove(it) }
            }
        }
    }
    val outputs = messages.filter { it.role == "tool" }.map { it.content.orEmpty().length }
    return LossMetrics(repeated, failedEdits, outputs.sumOf { it.toLong() }, outputs.maxOrNull() ?: 0)
}
