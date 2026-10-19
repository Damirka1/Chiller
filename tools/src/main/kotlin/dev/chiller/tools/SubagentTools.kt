package dev.chiller.tools

import dev.chiller.contracts.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * Adds the subagent tools to the main agent's executor and delegates everything else to [base].
 * Children are run by the [service] with the plain base executor, so they never see these tools (depth one).
 */
class SubagentTools(private val base: ToolExecutor, private val service: SubagentService) : ToolExecutor {
    private val json = Json { encodeDefaults = true }

    override val specs: List<ToolSpec> = base.specs + listOf(
        spec("spawn_agent", "Start a read-only helper for an independent investigation. It can list, search and read project files, and use web_search/web_fetch when web permission is on. " +
            "It cannot create or edit files, run commands or ask the user: do those yourself. Start one helper per independent target (e.g. one per city or per module); they run in parallel, at most 3 per task. " +
            "Give a self-contained goal and the folders it may read. Then call wait_agents, which returns when a helper finishes.",
            mapOf("task_id" to str("Your short unique id for this job, e.g. 'auth-review'"), "goal" to str("What to find out and what to report"),
                "context" to str("Facts the helper needs; it cannot see this conversation"), "paths" to arr(str("Workspace-relative folder or file")),
                "max_rounds" to int("1–6, default 4")), "task_id", "goal"),
        spec("list_agents", "List helper agents of this task with status and progress.", emptyMap()),
        spec("wait_agents", "Wait until a helper finishes after cursor (or all are finished), up to timeout_ms. Returns their status; pass the returned cursor next time. Read reports with agent_result.",
            mapOf("after_cursor" to int("cursor from the previous call, 0 at first"), "timeout_ms" to int("up to 60000, default 30000"))),
        spec("agent_result", "Read a helper's full report and status.", mapOf("agent_id" to str("id from spawn_agent")), "agent_id"),
        spec("message_agent", "Send a running helper an extra instruction; it reads it before its next round.",
            mapOf("agent_id" to str("id"), "message_id" to str("your unique id for this message"), "text" to str("instruction")), "agent_id", "message_id", "text"),
        spec("cancel_agent", "Stop a helper that is no longer needed.", mapOf("agent_id" to str("id")), "agent_id"),
    )

    private val own = setOf("spawn_agent", "list_agents", "wait_agents", "agent_result", "message_agent", "cancel_agent")

    override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
        if (call.name !in own) return base.execute(call, context)
        return try {
            val args = (json.parseToJsonElement(call.arguments.ifBlank { "{}" }) as? JsonObject) ?: throw ToolException("INVALID_ARGUMENTS", "Arguments must be an object")
            val allowed = specs.first { it.name == call.name }.parameters["properties"]!!.jsonObject.keys
            if (!allowed.containsAll(args.keys)) throw ToolException("INVALID_ARGUMENTS", "Unknown argument: ${(args.keys - allowed).joinToString()}")
            val data: JsonElement = when (call.name) {
                "spawn_agent" -> json.encodeToJsonElement(SubagentSnapshot.serializer(), service.spawn(context, SubagentRequest(
                    taskId = args.text("task_id"), goal = args.text("goal"), context = args.optText("context").orEmpty(),
                    allowedPaths = args["paths"]?.let { p -> (p as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { e -> e.isString }?.content ?: throw ToolException("INVALID_ARGUMENTS", "paths must be strings") } ?: throw ToolException("INVALID_ARGUMENTS", "paths must be an array") } ?: listOf("."),
                    maxRounds = args.number("max_rounds") ?: 4,
                ))).compact()
                "list_agents" -> json.encodeToJsonElement(SubagentPage.serializer(), service.list(context)).compact()
                "wait_agents" -> json.encodeToJsonElement(SubagentPage.serializer(), service.wait(context, (args.number("after_cursor") ?: 0).toLong(), (args.number("timeout_ms") ?: 30_000).toLong())).compact()
                "agent_result" -> json.encodeToJsonElement(SubagentSnapshot.serializer(), service.result(context, args.text("agent_id")))
                "message_agent" -> json.encodeToJsonElement(SubagentMessageReceipt.serializer(), service.send(context, args.text("agent_id"), args.text("message_id"), args.text("text")))
                "cancel_agent" -> json.encodeToJsonElement(SubagentSnapshot.serializer(), service.cancel(context, args.text("agent_id"))).compact()
                else -> error("unreachable")
            }
            ToolResult(true, data)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: ToolException) { ToolResult.failure(e.code, e.message, recovery = e.recovery) }
        catch (_: Exception) { ToolResult.failure("INVALID_ARGUMENTS", "Arguments must match the tool JSON schema") }
    }

    /** Status views drop bulky fields the model does not need every time. */
    private fun JsonElement.compact(): JsonElement = when (this) {
        is JsonObject -> JsonObject(filterKeys { it !in setOf("readPaths", "allowedPaths", "startedAt", "finishedAt") }.mapValues { (_, v) -> v.compact() })
        is JsonArray -> JsonArray(map { it.compact() })
        else -> this
    }

    private fun JsonObject.text(key: String) = optText(key) ?: throw ToolException("INVALID_ARGUMENTS", "Missing $key")
    private fun JsonObject.optText(key: String) = (this[key] as? JsonPrimitive)?.let { if (!it.isString) throw ToolException("INVALID_ARGUMENTS", "$key must be a string"); it.content }
    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.let { if (it.isString) throw ToolException("INVALID_ARGUMENTS", "$key must be a number"); it.intOrNull ?: throw ToolException("INVALID_ARGUMENTS", "$key must be an integer") }

    private companion object {
        fun str(description: String) = buildJsonObject { put("type", "string"); put("description", description) }
        fun int(description: String) = buildJsonObject { put("type", "integer"); put("description", description) }
        fun arr(item: JsonObject) = buildJsonObject { put("type", "array"); put("items", item); put("maxItems", 8) }
        fun spec(name: String, description: String, props: Map<String, JsonObject>, vararg required: String) = ToolSpec(name, description, buildJsonObject {
            put("type", "object"); put("additionalProperties", false); put("properties", JsonObject(props)); put("required", JsonArray(required.map(::JsonPrimitive)))
        }, ToolEffect.READ)
    }
}
