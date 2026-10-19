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
    private val code: ReadOnlyCodeService = UnsupportedReadOnlyCodeService,
    private val web: WebService = DisabledWebService,
    private val screens: ScreenCaptureService? = null,
    /** The endpoint's image model; null keeps generate_image out of the toolbox. */
    private val images: ImageService? = null,
    private val attachmentImages: AttachmentImages? = null,
) : ToolExecutor {
    private val json = Json { encodeDefaults = true }
    private val imagesPerRun = java.util.concurrent.ConcurrentHashMap<String, Int>()
    override val specs: List<ToolSpec> = listOf(
        spec("list_files", "List project files respecting ignore rules. Results are bounded; copy returned nextCursor to cursor to continue.", ToolEffect.READ, mapOf("path" to string(), "glob" to string(), "limit" to integer(), "cursor" to string())),
        spec("search_text", "Search text in project; literal by default. Optional context adds surrounding lines. Copy returned nextCursor to cursor, keeping the same query options.", ToolEffect.READ, mapOf("query" to string(), "path" to string(), "glob" to string(), "regex" to boolean(), "limit" to integer(), "context" to integer(), "cursor" to string()), "query"),
        spec("git_status", "Show bounded read-only Git status, including untracked and tracked changes.", ToolEffect.READ, emptyMap()),
        spec("git_diff", "Show a bounded read-only Git diff. No external diff or text conversion is used.", ToolEffect.READ, mapOf("staged" to boolean(), "path" to string())),
        spec("git_log", "Show up to 30 recent commits, optionally from a simple revision name.", ToolEffect.READ, mapOf("limit" to integer(), "revision" to string())),
        spec("git_show", "Read one file from a commit or branch. This does not access the working-tree file.", ToolEffect.READ, mapOf("revision" to string(), "path" to string()), "revision", "path"),
        spec("web_search", "Search the public web for up to five results. Sends the query to DuckDuckGo when network permission is enabled.", ToolEffect.NETWORK, mapOf("query" to boundedString(300), "limit" to boundedInteger(1, 5)), "query"),
        spec("web_fetch", "Fetch bounded HTML, plain text, or JSON from a public URL when network permission is enabled. Redirect destinations are revalidated.", ToolEffect.NETWORK, mapOf("url" to boundedString(2048)), "url"),
        spec("find_symbols", "Find lexical class, function, and type declarations in supported source files; this is not semantic/LSP navigation.", ToolEffect.READ, mapOf("query" to string(), "path" to string(), "limit" to integer()), "query"),
        spec("read_symbol", "Read one complete block-bodied Kotlin class or function. For large files, pass start_line from find_symbols. Refuses ambiguity and expression-bodied declarations; lexical only, not a parser.", ToolEffect.READ, mapOf("path" to string(), "symbol" to string(), "start_line" to integer()), "path", "symbol"),
        spec("show_file", "Present an existing workspace file to the user, with an image preview when supported. This does not send image pixels to the model.", ToolEffect.READ, mapOf("path" to string()), "path"),
        spec("read_file", "Read numbered lines and obtain snapshot_id. Copy snapshot_id exactly for edits.", ToolEffect.READ, mapOf("path" to string(), "start_line" to integer(), "end_line" to integer()), "path"),
        spec("edit_file", "Edit read lines of the exact snapshot by line number. 1-based inclusive ranges. For small changes replace_text is simpler. All edits use the original snapshot. The returned snapshotId can be used for further edits without re-reading.", ToolEffect.FILE_WRITE,
            mapOf("path" to string(), "snapshot_id" to string(), "edits" to buildJsonObject { put("type", "array"); put("maxItems", 32); put("items", buildJsonObject {
                put("type", "object"); put("additionalProperties", false)
                put("properties", buildJsonObject { put("op", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("replace", "insert", "delete").map(::JsonPrimitive))) }); put("start_line", integer()); put("end_line", integer()); put("before_line", integer()); put("content", string()) })
                put("required", JsonArray(listOf(JsonPrimitive("op"))))
            }) }), "path", "snapshot_id", "edits"),
        spec("replace_text", "Replace exact text in a file you have read. Copy old_text from read_file output without the line-number prefix, including indentation. Must match exactly expected_count times (default 1); otherwise nothing changes. Prefer this for small changes. Returns snapshotId for further edits.", ToolEffect.FILE_WRITE,
            mapOf("path" to string(), "snapshot_id" to string(), "old_text" to string(), "new_text" to string(), "expected_count" to integer()), "path", "snapshot_id", "old_text", "new_text"),
        spec("create_file", "Create a new UTF-8 file, safely creating missing parent directories. Never overwrites existing files.", ToolEffect.FILE_WRITE, mapOf("path" to string(), "content" to string()), "path", "content"),
        spec("create_directory", "Create a directory and missing parents inside the workspace; succeeds if the directory already exists, refuses files and symlinks.", ToolEffect.FILE_WRITE, mapOf("path" to string()), "path"),
        spec("move_file", "Move one file without overwriting destination; snapshot required.", ToolEffect.FILE_WRITE, mapOf("path" to string(), "snapshot_id" to string(), "destination" to string()), "path", "snapshot_id", "destination"),
        spec("delete_file", "Delete one read file with a recoverable checkpoint; snapshot required.", ToolEffect.FILE_WRITE, mapOf("path" to string(), "snapshot_id" to string()), "path", "snapshot_id"),
        spec("run_process", "Run an executable with argument array. Explicit process permission required. cwd is relative. Shell commands require explicit shell argv.", ToolEffect.PROCESS, mapOf("argv" to array(string()), "cwd" to string(), "timeout_ms" to integer(), "background" to boolean()), "argv"),
        spec("read_process", "Read new process output using cursor; inspect exit code before claiming checks passed.", ToolEffect.READ, mapOf("process_id" to string(), "cursor" to integer()), "process_id"),
        spec("read_diagnostics", "Parse a bounded set of Kotlin, Java, and Python file/line diagnostics from new process output. Partial parser; inspect process state and exit_code separately.", ToolEffect.READ, mapOf("process_id" to string(), "cursor" to integer()), "process_id"),
        spec("stop_process", "Stop a process by its opaque id.", ToolEffect.PROCESS, mapOf("process_id" to string()), "process_id"),
        spec("read_artifact", "Read a large saved result or attachment: offset/limit for a part (limit up to 65536; next_offset says there is more). With query, find matching lines instead (literal, case-insensitive) with their offsets, then read around a match.",
            ToolEffect.READ, mapOf("artifact_id" to string(), "offset" to integer(), "limit" to integer(), "query" to string()), "artifact_id"),
        spec("update_plan", "Keep a short checklist for a multi-step task. Send the whole list each time; at most one step in_progress. Does not change the project.", ToolEffect.READ,
            mapOf("steps" to buildJsonObject { put("type", "array"); put("maxItems", 20); put("items", buildJsonObject {
                put("type", "object"); put("additionalProperties", false)
                put("properties", buildJsonObject { put("step", string()); put("status", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("pending", "in_progress", "done").map(::JsonPrimitive))) }) })
                put("required", JsonArray(listOf(JsonPrimitive("step"), JsonPrimitive("status"))))
            }) }), "steps"),
        spec("ask_user", "Ask the user a necessary question, optionally with choices.", ToolEffect.USER_INTERACTION, mapOf("question" to string(), "options" to array(string())), "question"),
    ) + listOfNotNull(images?.let { service ->
        spec("generate_image", "Generate an image with the image model (${service.model}) and save it as a NEW file in the project; never overwrites, so pick a new path ending in .png. " +
            (if (service.supportsEditing) "To change an existing picture pass reference_image: a project image path or an attachment artifact_id; the model edits it and the result goes to path. " else "") +
            "Takes 15-60 s; at most $MAX_IMAGES_PER_RUN per turn. Describe subject, style and composition in the prompt. Then show_file is not needed: the result is shown to the user.",
            ToolEffect.FILE_WRITE,
            mapOf("prompt" to boundedString(4000), "path" to string(), "size" to boundedString(16)) + (if (service.supportsEditing) mapOf("reference_image" to string()) else emptyMap()),
            "prompt", "path")
    }) + if (screens?.supported == true) listOf(
        spec("list_capture_targets", "List only the window explicitly selected by the user for capture. Ask the user to choose in Settings if empty.", ToolEffect.SCREEN_CAPTURE, emptyMap()),
        spec("capture_window", "Capture the user's selected window and present its PNG. Requires app and OS permission. Never captures the entire display; pixels are not sent as model vision input.", ToolEffect.SCREEN_CAPTURE, emptyMap()),
    ) else emptyList()

    override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
        return try {
            val spec = specs.find { it.name == call.name } ?: return ToolResult.failure("UNKNOWN_TOOL", "Unknown tool")
            if (context.mode == ExecutionMode.PLAN && spec.effect in setOf(ToolEffect.FILE_WRITE, ToolEffect.PROCESS))
                return ToolResult.failure("PERMISSION_DENIED", "Plan mode permits reads and questions only")
            if (spec.effect == ToolEffect.SCREEN_CAPTURE && !context.allowScreenshots)
                return ToolResult.failure("PERMISSION_DENIED", "Window capture was not enabled by the user")
            if (spec.effect == ToolEffect.PROCESS && !context.allowProcesses)
                return ToolResult.failure("PERMISSION_DENIED", "Process execution was not enabled by the user")
            if (spec.effect == ToolEffect.NETWORK && !context.allowNetwork)
                return ToolResult.failure("PERMISSION_DENIED", "Network access was not enabled by the user")
            if (call.arguments.length > 262_144) return ToolResult.failure("INVALID_ARGUMENTS", "Arguments exceed limit")
            val args = json.parseToJsonElement(call.arguments).jsonObject
            validateObject(args, spec.parameters)
            val result = when (call.name) {
                "list_files" -> workspace.listPage(args.str("path", "."), args.optionalString("glob"), args.int("limit", 200), args.optionalString("cursor"))
                "search_text" -> workspace.searchPage(args.str("query"), args.str("path", "."), args.optionalString("glob"), args.bool("regex"), args.int("limit", 100), args.int("context", 0), args.optionalString("cursor"))
                "git_status" -> code.gitStatus()
                "git_diff" -> code.gitDiff(args.bool("staged"), args.optionalString("path"))
                "git_log" -> code.gitLog(args.int("limit", 10), args.optionalString("revision"))
                "git_show" -> code.gitShow(args.str("revision"), args.str("path"))
                "web_search" -> web.search(args.str("query"), args.int("limit", 5))
                "web_fetch" -> web.fetch(args.str("url"))
                "find_symbols" -> code.findSymbols(args.str("query"), args.optionalString("path"), args.int("limit", 30))
                "read_symbol" -> {
                    val path = args.str("path")
                    if (path.substringAfterLast('.', "").lowercase() !in setOf("kt", "kts"))
                        throw ToolException("UNSUPPORTED_LANGUAGE", "read_symbol currently supports Kotlin .kt and .kts files")
                    val anchored = args.optionalInt("start_line") != null
                    val startLine = args.int("start_line", 1)
                    if (startLine < 1) throw ToolException("INVALID_ARGUMENTS", "start_line must be positive")
                    val file = workspace.read(ReadFileRequest(path, startLine))
                    if (!anchored && file.truncated) throw ToolException("FILE_TOO_LARGE", "Use find_symbols and retry with its start_line")
                    val symbol = args.str("symbol")
                    val block = LexicalSymbolReader.read(file.content, symbol, startLine, anchored)
                    ToolResult(true, buildJsonObject {
                        put("path", file.path); put("snapshot_id", file.snapshotId); put("symbol", symbol)
                        put("start_line", block.startLine); put("end_line", block.endLine); put("content", block.content)
                        put("lexical", true)
                    })
                }
                "show_file" -> ToolResult(true, buildJsonObject { put("file", json.encodeToJsonElement(workspace.describeFile(args.str("path")))) })
                "list_capture_targets" -> ToolResult(true, buildJsonObject { put("targets", json.encodeToJsonElement(screens!!.targets())) })
                "capture_window" -> ToolResult(true, buildJsonObject { put("file", json.encodeToJsonElement(screens!!.capture())); put("model_can_view", false) })
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
                "replace_text" -> success(workspace.replaceText(args.str("path"), args.str("snapshot_id"), args.str("old_text"), args.str("new_text"), args.int("expected_count", 1)))
                "update_plan" -> {
                    val steps = args.getValue("steps").jsonArray.map { it.jsonObject.str("step").trim().take(200) to it.jsonObject.str("status") }
                    if (steps.isEmpty() || steps.any { it.first.isEmpty() }) throw ToolException("INVALID_ARGUMENTS", "Provide 1–20 non-empty steps")
                    if (steps.count { it.second == "in_progress" } > 1) throw ToolException("INVALID_ARGUMENTS", "At most one step can be in_progress", "Mark finished steps done and keep one in_progress")
                    ToolResult(true, buildJsonObject {
                        put("done", steps.count { it.second == "done" }); put("total", steps.size)
                        put("next", steps.firstOrNull { it.second != "done" }?.first ?: "all steps done")
                    })
                }
                "create_file" -> success(workspace.create(args.str("path"), args.str("content")))
                "generate_image" -> generateImage(args, context)
                "create_directory" -> workspace.createDirectory(args.str("path"))
                "move_file" -> success(workspace.move(args.str("path"), args.str("snapshot_id"), args.str("destination")))
                "delete_file" -> success(workspace.delete(args.str("path"), args.str("snapshot_id")))
                "run_process" -> success(processes.start(StartProcessRequest(args.getValue("argv").jsonArray.map { it.jsonPrimitive.content }, args.str("cwd", "."), args.long("timeout_ms", 60_000), args.bool("background")), context.runId))
                "read_process" -> success(processes.read(args.str("process_id"), args.long("cursor", 0)))
                "read_diagnostics" -> {
                    val page = processes.read(args.str("process_id"), args.long("cursor", 0))
                    val raw = page.output.take(20_000)
                    val diagnostics = DiagnosticsParser.parse(raw)
                    val diagnosticsLimited = diagnostics.size == 100
                    ToolResult(true, buildJsonObject {
                        put("process_id", page.processId); put("cursor", page.cursor); put("state", page.state)
                        page.exitCode?.let { put("exit_code", it) }
                        put("diagnostics", JsonArray(diagnostics.map { d -> buildJsonObject {
                            put("file", d.file); put("line", d.line); d.column?.let { put("column", it) }
                            d.severity?.let { put("severity", it) }; put("message", d.message)
                        } }))
                        put("parser_partial", true); put("diagnostics_limited", diagnosticsLimited); put("raw_output", raw)
                    }, truncated = page.truncated || page.output.length > raw.length || diagnosticsLimited, nextCursor = page.cursor.toString())
                }
                "stop_process" -> success(processes.stop(args.str("process_id")))
                "read_artifact" -> {
                    val query = args.optionalString("query")?.takeIf { it.isNotBlank() }
                    if (query != null) searchArtifact(context.sessionId, args.str("artifact_id"), query)
                    else {
                        val offset = args.int("offset", 0); val limit = args.int("limit", 16_384)
                        val content = artifacts.read(context.sessionId, args.str("artifact_id"), offset, limit)
                        ToolResult(true, buildJsonObject {
                            put("content", content); put("offset", offset)
                            if (content.length == limit) put("next_offset", offset + content.length) else put("end", true)
                        })
                    }
                }
                "ask_user" -> ToolResult(true, buildJsonObject { put("answer", questions.ask(UserQuestion(question = args.str("question"), options = args["options"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()))) })
                else -> error("Unregistered tool")
            }
            val text = json.encodeToString(result)
            if (text.length > 24_000) {
                val artifact = artifacts.put(context.sessionId, text)
                ToolResult(result.ok, buildJsonObject { put("summary", "Result saved as artifact; read it in pages"); put("preview", text.take(6000)) }, result.error, truncated = true, nextCursor = result.nextCursor, artifactId = artifact.id)
            } else result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: ToolException) { ToolResult.failure(e.code, e.message, e.code in setOf("STALE_SNAPSHOT", "UNKNOWN_SNAPSHOT"), e.recovery ?: if (e.code.contains("SNAPSHOT")) "Read the file again" else null) }
        catch (_: kotlinx.serialization.SerializationException) { ToolResult.failure("INVALID_ARGUMENTS", "Arguments must match the tool JSON schema") }
        catch (_: IllegalArgumentException) { ToolResult.failure("INVALID_ARGUMENTS", "Arguments must match the tool JSON schema") }
        catch (_: Exception) { ToolResult.failure("TOOL_FAILED", "Tool failed; inspect path, permissions and arguments") }
    }

    private inline fun <reified T> success(value: T) = ToolResult(true, json.encodeToJsonElement(value))

    /**
     * Generates (or edits) one image and stores it as a new workspace file through the undoable binary create.
     * Everything that can be checked locally is checked before the slow request: path, size, reference and quota.
     */
    private suspend fun generateImage(args: JsonObject, context: ToolContext): ToolResult {
        val service = images ?: throw ToolException("UNSUPPORTED", "No image model is configured")
        val prompt = args.str("prompt").trim().ifEmpty { throw ToolException("INVALID_ARGUMENTS", "prompt must not be empty") }
        val path = args.str("path")
        val extension = path.substringAfterLast('.', "").lowercase()
        if (extension !in IMAGE_EXTENSIONS.keys) throw ToolException("INVALID_ARGUMENTS", "path must end in .png, .jpg, .jpeg or .webp", "Use a path like images/logo.png")
        if (runCatching { workspace.describeFile(path) }.isSuccess) throw ToolException("ALREADY_EXISTS", "Destination already exists", "Choose a new file name, e.g. add -2 before the extension")
        val size = args.str("size", DEFAULT_IMAGE_SIZE)
        if (!IMAGE_SIZE.matches(size)) throw ToolException("INVALID_ARGUMENTS", "size must look like 1024x1024")
        val reference = args.optionalString("reference_image")?.takeIf { it.isNotBlank() }?.let { loadReference(it, context.sessionId) }
        if ((imagesPerRun[context.runId] ?: 0) >= MAX_IMAGES_PER_RUN)
            throw ToolException("LIMIT_REACHED", "At most $MAX_IMAGES_PER_RUN images per turn", "Show the user what you have and ask before making more")
        imagesPerRun.merge(context.runId, 1, Int::plus)
        val started = System.nanoTime()
        val image = service.generate(prompt, size, reference)
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        // The file type follows the bytes the model returned; a mismatching extension is corrected and reported.
        val target = if (IMAGE_EXTENSIONS[extension] == image.mimeType) path
            else path.substringBeforeLast('.') + "." + IMAGE_EXTENSIONS.entries.first { it.value == image.mimeType }.key
        val change = workspace.createBinary(target, image.bytes)
        val presentation = workspace.describeFile(change.path)
        val dimensions = pngSize(image.bytes)
        return ToolResult(true, JsonObject(json.encodeToJsonElement(change).jsonObject + buildJsonObject {
            put("file", json.encodeToJsonElement(presentation))
            put("model", service.model); put("size", size); put("edited", reference != null)
            dimensions?.let { put("width", it.first); put("height", it.second) }
            put("bytes", image.bytes.size); put("seconds", "%.1f".format(java.util.Locale.ROOT, seconds).toDouble())
            if (target != path) put("note", "Saved as ${change.path}: the model returned ${image.mimeType}")
            put("model_can_view", false)
        }))
    }

    /** A reference is an attachment the user gave (by artifact_id) or an image file in the project; nothing else is sent. */
    private suspend fun loadReference(value: String, sessionId: String): ImageData {
        if (images?.supportsEditing != true) throw ToolException("UNSUPPORTED", "The image model cannot edit images", "Generate a new image from a text prompt instead")
        attachmentImages?.takeIf { ARTIFACT_ID.matches(value) }?.load(sessionId, value)?.let { return it }
        val bytes = workspace.readBinary(value, MAX_REFERENCE_BYTES)
        val mime = imageMimeType(bytes) ?: throw ToolException("NOT_AN_IMAGE", "reference_image is not a PNG, JPEG or WebP image", "Pass an image file path or an image attachment's artifact_id")
        return ImageData(bytes, mime)
    }
    /**
     * Literal, case-insensitive line search through an artifact, read in 64 KiB chunks so a line split across chunks is
     * still found. Returns at most [MAX_ARTIFACT_MATCHES] lines with their 1-based number and character offset.
     */
    private suspend fun searchArtifact(sessionId: String, artifactId: String, query: String): ToolResult {
        if (query.length > 200) throw ToolException("INVALID_ARGUMENTS", "query must be 1-200 characters")
        val matches = buildJsonArray {
            var offset = 0; var carry = ""; var carryStart = 0; var line = 1; var found = 0
            while (found < MAX_ARTIFACT_MATCHES) {
                val chunk = artifacts.read(sessionId, artifactId, offset, ARTIFACT_CHUNK)
                val last = chunk.length < ARTIFACT_CHUNK
                val text = carry + chunk
                val complete = if (last) text.length else text.lastIndexOf('\n') + 1
                var start = 0
                while (start < complete && found < MAX_ARTIFACT_MATCHES) {
                    val end = text.indexOf('\n', start).let { if (it < 0 || it >= complete) complete else it }
                    val value = text.substring(start, end)
                    if (value.contains(query, ignoreCase = true)) {
                        add(buildJsonObject { put("line", line); put("offset", carryStart + start); put("text", value.take(400)) }); found++
                    }
                    line++; start = end + 1
                }
                carry = text.substring(complete.coerceAtMost(text.length)); carryStart += complete
                offset += chunk.length
                if (last) break
            }
        }
        return ToolResult(true, buildJsonObject {
            put("query", query); put("matches", matches)
            if (matches.size >= MAX_ARTIFACT_MATCHES) put("more_matches_possible", true)
        }, truncated = matches.size >= MAX_ARTIFACT_MATCHES)
    }

    private fun validateObject(value: JsonObject, schema: JsonObject) {
        val properties = schema.getValue("properties").jsonObject
        require(value.keys.all { it in properties })
        schema["required"]?.jsonArray?.forEach { require(it.jsonPrimitive.content in value) }
        value.forEach { (key, item) -> validateValue(item, properties.getValue(key).jsonObject) }
    }
    private fun validateValue(item: JsonElement, schema: JsonObject) {
        when (schema["type"]?.jsonPrimitive?.content) {
            "string" -> { require(item is JsonPrimitive && item.isString); schema["maxLength"]?.jsonPrimitive?.int?.let { require(item.content.length <= it) } }
            "integer" -> {
                require(item is JsonPrimitive && !item.isString && item.longOrNull != null)
                schema["minimum"]?.jsonPrimitive?.long?.let { require(item.long >= it) }
                schema["maximum"]?.jsonPrimitive?.long?.let { require(item.long <= it) }
            }
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
        private fun boundedString(maxLength: Int) = buildJsonObject { put("type", "string"); put("maxLength", maxLength) }
        private fun integer() = buildJsonObject { put("type", "integer") }
        private fun boundedInteger(minimum: Int, maximum: Int) = buildJsonObject { put("type", "integer"); put("minimum", minimum); put("maximum", maximum) }
        private fun boolean() = buildJsonObject { put("type", "boolean") }
        private fun array(item: JsonObject) = buildJsonObject { put("type", "array"); put("items", item); put("maxItems", 64) }
        private fun spec(name: String, description: String, effect: ToolEffect, props: Map<String, JsonObject>, vararg required: String) = ToolSpec(name, description, buildJsonObject {
            put("type", "object"); put("additionalProperties", false); put("properties", JsonObject(props)); put("required", JsonArray(required.map(::JsonPrimitive)))
        }, effect)
    }
}

private const val ARTIFACT_CHUNK = 65_536
private const val MAX_IMAGES_PER_RUN = 4
private const val DEFAULT_IMAGE_SIZE = "1024x1024"
private const val MAX_REFERENCE_BYTES = 20 * 1_048_576
private val IMAGE_EXTENSIONS = linkedMapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp")
private val ARTIFACT_ID = Regex("[0-9a-fA-F-]{16,64}")
private val IMAGE_SIZE = Regex("[0-9]{2,4}x[0-9]{2,4}")
private const val MAX_ARTIFACT_MATCHES = 40
