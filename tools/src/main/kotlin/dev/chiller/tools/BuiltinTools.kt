package dev.chiller.tools

import dev.chiller.contracts.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class BuiltinTools(
    private val workspace: WorkspaceService,
    private val processes: ProcessService,
    private val artifacts: ArtifactStore,
    private val questions: InteractionBroker,
) : ToolExecutor {
    private val json = Json { encodeDefaults = true }
    override val specs: List<ToolSpec> = listOf(
        spec("list_files", "List project files respecting ignore rules. Results are bounded.", ToolEffect.READ, mapOf("path" to string(), "glob" to string(), "limit" to integer())),
        spec("search_text", "Search text in project; literal by default. Optional context adds surrounding lines.", ToolEffect.READ, mapOf("query" to string(), "path" to string(), "glob" to string(), "regex" to boolean(), "limit" to integer(), "context" to integer()), "query"),
        spec("read_file", "Read numbered lines and obtain snapshot_id. Copy snapshot_id exactly for edits.", ToolEffect.READ, mapOf("path" to string(), "start_line" to integer(), "end_line" to integer()), "path"),
        spec("edit_file", "Edit read lines of the exact snapshot. 1-based inclusive ranges. All edits use the original snapshot. The returned snapshotId can be used for further edits without re-reading.", ToolEffect.FILE_WRITE,
            mapOf("path" to string(), "snapshot_id" to string(), "edits" to buildJsonObject { put("type", "array"); put("maxItems", 32); put("items", buildJsonObject {
                put("type", "object"); put("additionalProperties", false)
                put("properties", buildJsonObject { put("op", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("replace", "insert", "delete").map(::JsonPrimitive))) }); put("start_line", integer()); put("end_line", integer()); put("before_line", integer()); put("content", string()) })
                put("required", JsonArray(listOf(JsonPrimitive("op"))))
            }) }), "path", "snapshot_id", "edits"),
        spec("create_file", "Create a new UTF-8 file. Never overwrites existing files.", ToolEffect.FILE_WRITE, mapOf("path" to string(), "content" to string()), "path", "content"),
        spec("move_file", "Move one file without overwriting destination; snapshot required.", ToolEffect.FILE_WRITE, mapOf("path" to string(), "snapshot_id" to string(), "destination" to string()), "path", "snapshot_id", "destination"),
        spec("delete_file", "Delete one read file with a recoverable checkpoint; snapshot required.", ToolEffect.FILE_WRITE, mapOf("path" to string(), "snapshot_id" to string()), "path", "snapshot_id"),
        spec("run_process", "Run an executable with argument array. Explicit process permission required. cwd is relative. Shell commands require explicit shell argv.", ToolEffect.PROCESS, mapOf("argv" to array(string()), "cwd" to string(), "timeout_ms" to integer(), "background" to boolean()), "argv"),
        spec("read_process", "Read new process output using cursor; inspect exit code before claiming checks passed.", ToolEffect.READ, mapOf("process_id" to string(), "cursor" to integer()), "process_id"),
        spec("stop_process", "Stop a process by its opaque id.", ToolEffect.PROCESS, mapOf("process_id" to string()), "process_id"),
        spec("read_artifact", "Read part of a large saved result from this session.", ToolEffect.READ, mapOf("artifact_id" to string(), "offset" to integer(), "limit" to integer()), "artifact_id"),
        spec("ask_user", "Ask the user a necessary question, optionally with choices.", ToolEffect.USER_INTERACTION, mapOf("question" to string(), "options" to array(string())), "question"),
    )

    override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
        return try {
            val spec = specs.find { it.name == call.name } ?: return ToolResult.failure("UNKNOWN_TOOL", "Unknown tool")
            if (context.mode == ExecutionMode.PLAN && spec.effect in setOf(ToolEffect.FILE_WRITE, ToolEffect.PROCESS))
                return ToolResult.failure("PERMISSION_DENIED", "Plan mode permits reads and questions only")
            if (spec.effect == ToolEffect.PROCESS && !context.allowProcesses)
                return ToolResult.failure("PERMISSION_DENIED", "Process execution was not enabled by the user")
            if (call.arguments.length > 262_144) return ToolResult.failure("INVALID_ARGUMENTS", "Arguments exceed limit")
            val args = json.parseToJsonElement(call.arguments).jsonObject
            validateObject(args, spec.parameters)
            val result = when (call.name) {
                "list_files" -> workspace.list(args.str("path", "."), args.optionalString("glob"), args.int("limit", 200))
                "search_text" -> workspace.search(args.str("query"), args.str("path", "."), args.optionalString("glob"), args.bool("regex"), args.int("limit", 100), args.int("context", 0))
                "read_file" -> {
                    val r = workspace.read(ReadFileRequest(args.str("path"), args.int("start_line", 1), args.optionalInt("end_line")))
                    ToolResult(true, buildJsonObject {
                        put("path", r.path); put("snapshot_id", r.snapshotId); put("total_lines", r.totalLines); put("eol", r.eol)
                        put("content", r.content.lineSequence().mapIndexed { i, line -> "${args.int("start_line", 1) + i} | $line" }.joinToString("\n"))
                    }, truncated = r.truncated)
                }
                "edit_file" -> {
                    val edits = args.getValue("edits").jsonArray.map { e -> e.jsonObject.let {
                        FileEdit(it.str("op"), it.optionalInt("start_line"), it.optionalInt("end_line"), it.optionalInt("before_line"), it.str("content", ""))
                    } }
                    if (edits.isEmpty() || edits.size > 32) throw ToolException("INVALID_ARGUMENTS", "Provide 1–32 edits")
                    success(workspace.edit(EditFileRequest(args.str("path"), args.str("snapshot_id"), edits)))
                }
                "create_file" -> success(workspace.create(args.str("path"), args.str("content")))
                "move_file" -> success(workspace.move(args.str("path"), args.str("snapshot_id"), args.str("destination")))
                "delete_file" -> success(workspace.delete(args.str("path"), args.str("snapshot_id")))
                "run_process" -> success(processes.start(StartProcessRequest(args.getValue("argv").jsonArray.map { it.jsonPrimitive.content }, args.str("cwd", "."), args.long("timeout_ms", 60_000), args.bool("background")), context.runId))
                "read_process" -> success(processes.read(args.str("process_id"), args.long("cursor", 0)))
                "stop_process" -> success(processes.stop(args.str("process_id")))
                "read_artifact" -> ToolResult(true, buildJsonObject { put("content", artifacts.read(context.sessionId, args.str("artifact_id"), args.int("offset", 0), args.int("limit", 16_384))) })
                "ask_user" -> ToolResult(true, buildJsonObject { put("answer", questions.ask(UserQuestion(question = args.str("question"), options = args["options"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()))) })
                else -> error("Unregistered tool")
            }
            val text = json.encodeToString(result)
            if (text.length > 24_000) {
                val artifact = artifacts.put(context.sessionId, text)
                ToolResult(result.ok, buildJsonObject { put("summary", "Result saved as artifact; read it in pages"); put("preview", text.take(6000)) }, result.error, truncated = true, artifactId = artifact.id)
            } else result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: ToolException) { ToolResult.failure(e.code, e.message, e.code in setOf("STALE_SNAPSHOT", "UNKNOWN_SNAPSHOT"), e.recovery ?: if (e.code.contains("SNAPSHOT")) "Read the file again" else null) }
        catch (_: kotlinx.serialization.SerializationException) { ToolResult.failure("INVALID_ARGUMENTS", "Arguments must match the tool JSON schema") }
        catch (_: IllegalArgumentException) { ToolResult.failure("INVALID_ARGUMENTS", "Arguments must match the tool JSON schema") }
        catch (_: Exception) { ToolResult.failure("TOOL_FAILED", "Tool failed; inspect path, permissions and arguments") }
    }

    private inline fun <reified T> success(value: T) = ToolResult(true, json.encodeToJsonElement(value))
    private fun validateObject(value: JsonObject, schema: JsonObject) {
        val properties = schema.getValue("properties").jsonObject
        require(value.keys.all { it in properties })
        schema["required"]?.jsonArray?.forEach { require(it.jsonPrimitive.content in value) }
        value.forEach { (key, item) -> validateValue(item, properties.getValue(key).jsonObject) }
    }
    private fun validateValue(item: JsonElement, schema: JsonObject) {
        when (schema["type"]?.jsonPrimitive?.content) {
            "string" -> require(item is JsonPrimitive && item.isString)
            "integer" -> require(item is JsonPrimitive && !item.isString && item.longOrNull != null)
            "boolean" -> require(item is JsonPrimitive && !item.isString && item.booleanOrNull != null)
            "array" -> { require(item is JsonArray); require(item.size <= (schema["maxItems"]?.jsonPrimitive?.int ?: 64)); item.forEach { validateValue(it, schema.getValue("items").jsonObject) } }
            "object" -> { require(item is JsonObject); validateObject(item, schema) }
        }
        schema["enum"]?.jsonArray?.let { require(item in it) }
    }
    private fun JsonObject.str(key: String, default: String? = null): String = this[key]?.jsonPrimitive?.content ?: default ?: throw ToolException("INVALID_ARGUMENTS", "Missing $key")
    private fun JsonObject.optionalString(key: String) = this[key]?.jsonPrimitive?.content
    private fun JsonObject.optionalInt(key: String) = this[key]?.jsonPrimitive?.int
    private fun JsonObject.int(key: String, default: Int) = optionalInt(key) ?: default
    private fun JsonObject.long(key: String, default: Long) = this[key]?.jsonPrimitive?.long ?: default
    private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.boolean ?: false

    companion object {
        private fun string() = buildJsonObject { put("type", "string") }
        private fun integer() = buildJsonObject { put("type", "integer") }
        private fun boolean() = buildJsonObject { put("type", "boolean") }
        private fun array(item: JsonObject) = buildJsonObject { put("type", "array"); put("items", item); put("maxItems", 64) }
        private fun spec(name: String, description: String, effect: ToolEffect, props: Map<String, JsonObject>, vararg required: String) = ToolSpec(name, description, buildJsonObject {
            put("type", "object"); put("additionalProperties", false); put("properties", JsonObject(props)); put("required", JsonArray(required.map(::JsonPrimitive)))
        }, effect)
    }
}
