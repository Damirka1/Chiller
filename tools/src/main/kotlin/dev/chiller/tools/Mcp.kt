package dev.chiller.tools

import dev.chiller.contracts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A Model Context Protocol server Chiller starts as a local process and talks to over stdio, as configured by the
 * user. [env] values may name a variable as `${NAME}`; it is filled in from the environment or `.env` at start, so a
 * key never has to be written into the settings.
 */
@Serializable
data class McpServerConfig(
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
) {
    /** The name as it appears in tool names: letters, digits, `_` and `-`. */
    val id: String get() = name.lowercase().replace(Regex("[^a-z0-9_-]+"), "_").trim('_').take(24).ifEmpty { "server" }
}

/** A tool a server offers; [readOnly] is the server's own hint and decides whether Plan mode may use it. */
data class McpTool(val server: String, val name: String, val description: String, val inputSchema: JsonObject, val readOnly: Boolean)

data class McpServerState(val name: String, val status: String /* stopped, starting, ready, failed */, val tools: List<McpTool> = emptyList(), val message: String? = null)

class McpException(val code: String, message: String) : Exception(message)

/**
 * One server process speaking JSON-RPC 2.0 over newline-delimited stdio. Requests are bounded in time and output;
 * a cancelled call tells the server with `notifications/cancelled`. Requests from the server get "method not found",
 * except `ping`. Nothing here retries a call.
 */
class McpConnection(
    private val config: McpServerConfig,
    private val resolveVariable: (String) -> String?,
    private val workingDirectory: Path,
    private val clientVersion: String,
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val writeLock = Mutex()
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private val stderrTail = StringBuilder()
    private var reader: Thread? = null
    @Volatile private var closed = false

    suspend fun start(): List<McpTool> = withContext(Dispatchers.IO) {
        val environment = config.env.mapValues { (_, value) -> expand(value) }
        val started = try {
            ProcessBuilder(listOf(resolveCommand(config.command)) + config.args).directory(workingDirectory.toFile()).apply {
                environment().putAll(environment)
                redirectErrorStream(false)
            }.start()
        } catch (e: Exception) { throw McpException("MCP_START_FAILED", tr("Could not start “${config.command}”: ${e.message?.take(200)}", "Не удалось запустить «${config.command}»: ${e.message?.take(200)}")) }
        process = started
        writer = OutputStreamWriter(started.outputStream, Charsets.UTF_8)
        reader = Thread({ readLoop(started) }, "mcp-${config.id}").apply { isDaemon = true; start() }
        Thread({ drainErrors(started) }, "mcp-${config.id}-stderr").apply { isDaemon = true; start() }
        request("initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject { put("name", "Chiller"); put("version", clientVersion) })
        }, START_TIMEOUT_MS)
        notify("notifications/initialized", null)
        listTools()
    }

    suspend fun listTools(): List<McpTool> {
        val tools = mutableListOf<McpTool>()
        var cursor: String? = null
        do {
            val result = request("tools/list", cursor?.let { buildJsonObject { put("cursor", it) } }, START_TIMEOUT_MS)
            result["tools"]?.jsonArray?.forEach { item ->
                val tool = item.jsonObject
                val name = tool["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                tools += McpTool(config.id, name, tool["description"]?.jsonPrimitive?.contentOrNull.orEmpty().take(1024),
                    tool["inputSchema"] as? JsonObject ?: buildJsonObject { put("type", "object") },
                    tool["annotations"]?.jsonObject?.get("readOnlyHint")?.jsonPrimitive?.booleanOrNull == true)
            }
            cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull
        } while (cursor != null && tools.size < MAX_TOOLS)
        return tools.take(MAX_TOOLS)
    }

    /** Calls a tool and returns its result object (`content`, `isError`, `structuredContent`). */
    suspend fun call(tool: String, arguments: JsonObject): JsonObject =
        request("tools/call", buildJsonObject { put("name", tool); put("arguments", arguments) }, CALL_TIMEOUT_MS)

    /** What the server last wrote to stderr, for the settings page when it fails. */
    fun errors(): String = synchronized(stderrTail) { stderrTail.toString().trim() }

    private suspend fun request(method: String, params: JsonObject?, timeoutMs: Long): JsonObject {
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<JsonObject>()
        pending[id] = answer
        try {
            send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); params?.let { put("params", it) } })
            val response = try { withTimeout(timeoutMs) { answer.await() } }
                catch (_: TimeoutCancellationException) {
                    runCatching { notify("notifications/cancelled", buildJsonObject { put("requestId", id); put("reason", "timeout") }) }
                    throw McpException("MCP_TIMEOUT", tr("The ${config.name} server did not answer within ${timeoutMs / 1000} s", "Сервер ${config.name} не ответил за ${timeoutMs / 1000} с"))
                }
            response["error"]?.jsonObject?.let { error ->
                throw McpException("MCP_ERROR", error["message"]?.jsonPrimitive?.contentOrNull?.take(500) ?: tr("The ${config.name} server returned an error", "Сервер ${config.name} вернул ошибку"))
            }
            return response["result"] as? JsonObject ?: buildJsonObject {}
        } catch (cancelled: CancellationException) {
            // The model's run was stopped: the server hears about it; the call's outcome stays unknown to Chiller.
            withContext(NonCancellable) { runCatching { notify("notifications/cancelled", buildJsonObject { put("requestId", id); put("reason", "cancelled") }) } }
            throw cancelled
        } finally { pending.remove(id) }
    }

    private suspend fun notify(method: String, params: JsonObject?) =
        send(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); params?.let { put("params", it) } })

    private suspend fun send(message: JsonObject) {
        val out = writer ?: throw McpException("MCP_UNAVAILABLE", tr("The ${config.name} server is not running", "Сервер ${config.name} не запущен"))
        if (closed || process?.isAlive != true) throw McpException("MCP_UNAVAILABLE", tr("The ${config.name} server stopped", "Сервер ${config.name} остановлен") + errors().takeIf { it.isNotEmpty() }?.let { ": ${it.takeLast(300)}" }.orEmpty())
        writeLock.withLock { withContext(Dispatchers.IO) { out.write(json.encodeToString(JsonObject.serializer(), message)); out.write("\n"); out.flush() } }
    }

    private fun readLoop(started: Process) {
        try {
            BufferedReader(InputStreamReader(started.inputStream, Charsets.UTF_8)).use { input ->
                while (true) {
                    val line = readBoundedLine(input) ?: break
                    if (line.isBlank()) continue
                    val message = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                    val id = message["id"]?.jsonPrimitive?.longOrNull
                    val method = message["method"]?.jsonPrimitive?.contentOrNull
                    when {
                        method != null && message["id"] != null -> answerServerRequest(message["id"]!!, method)
                        method != null -> Unit // Notifications (logging, list changes) need no answer.
                        id != null -> pending[id]?.complete(message)
                    }
                }
            }
        } catch (_: Exception) { }
        pending.values.forEach { it.complete(buildJsonObject { put("error", buildJsonObject { put("message", tr("The ${config.name} server exited", "Сервер ${config.name} завершился") + errors().takeIf { e -> e.isNotEmpty() }?.let { e -> ": ${e.takeLast(300)}" }.orEmpty()) }) }) }
    }

    /** One line of at most [MAX_LINE] characters; a longer one is dropped whole instead of filling memory. */
    private fun readBoundedLine(input: BufferedReader): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString()
            if (line.length < MAX_LINE) line.append(c.toChar()) else { while (true) { val d = input.read(); if (d < 0 || d == '\n'.code) break }; return "" }
        }
    }

    private fun answerServerRequest(id: JsonElement, method: String) {
        val reply = if (method == "ping") buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", buildJsonObject {}) }
        else buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject { put("code", -32601); put("message", "Chiller does not support $method") }) }
        CoroutineScope(Dispatchers.IO).launch { runCatching { send(reply) } }
    }

    private fun drainErrors(started: Process) = runCatching {
        BufferedReader(InputStreamReader(started.errorStream, Charsets.UTF_8)).use { input ->
            val buffer = CharArray(1024)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                synchronized(stderrTail) { stderrTail.append(buffer, 0, n); if (stderrTail.length > 4096) stderrTail.delete(0, stderrTail.length - 4096) }
            }
        }
    }

    private fun expand(value: String): String = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}""").replace(value) { match ->
        resolveVariable(match.groupValues[1]) ?: throw McpException("MCP_START_FAILED", tr("The variable ${match.groupValues[1]} is not set in the environment or .env", "Переменная ${match.groupValues[1]} не задана в окружении или .env"))
    }

    override fun close() {
        closed = true
        runCatching { writer?.close() }
        process?.let { running ->
            running.destroy()
            if (!running.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) running.destroyForcibly()
        }
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        const val START_TIMEOUT_MS = 30_000L
        const val CALL_TIMEOUT_MS = 120_000L
        const val MAX_LINE = 4 * 1024 * 1024
        const val MAX_TOOLS = 128

        /** `npx` is `npx.cmd` on Windows: a bare name is looked up on PATH with the system's executable extensions. */
        fun resolveCommand(command: String): String {
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows") || command.contains('/') || command.contains('\\') || command.contains('.')) return command
            val extensions = (System.getenv("PATHEXT") ?: ".COM;.EXE;.BAT;.CMD").split(';').filter { it.isNotBlank() }
            return System.getenv("PATH").orEmpty().split(File.pathSeparator).asSequence()
                .flatMap { dir -> extensions.asSequence().map { File(dir, command + it.lowercase()) } }
                .firstOrNull { it.isFile }?.path ?: command
        }
    }
}

/**
 * The servers the user enabled, started once for the app and shared by every dialog. A changed configuration
 * restarts only what changed; [states] feeds the settings page.
 */
class McpHub(
    private val resolveVariable: (String) -> String?,
    private val workingDirectory: Path,
    private val clientVersion: String,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val connections = ConcurrentHashMap<String, Pair<McpServerConfig, McpConnection>>()
    private val _states = MutableStateFlow<Map<String, McpServerState>>(emptyMap())
    val states: StateFlow<Map<String, McpServerState>> = _states.asStateFlow()
    private val lock = Mutex()

    suspend fun configure(configs: List<McpServerConfig>) = lock.withLock {
        val wanted = configs.filter { it.enabled }.associateBy { it.id }
        connections.keys.filter { id -> wanted[id] != connections[id]?.first }.forEach { id ->
            connections.remove(id)?.second?.let { connection -> withContext(Dispatchers.IO) { connection.close() } }
            _states.update { it - id }
        }
        _states.update { states -> states.filterKeys { it in wanted } + configs.filter { !it.enabled }.associate { it.id to McpServerState(it.name, "stopped") } }
        wanted.values.filter { !connections.containsKey(it.id) }.forEach { config ->
            val connection = McpConnection(config, resolveVariable, workingDirectory, clientVersion)
            connections[config.id] = config to connection
            _states.update { it + (config.id to McpServerState(config.name, "starting")) }
            scope.launch {
                val state = try { McpServerState(config.name, "ready", connection.start()) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: Exception) { McpServerState(config.name, "failed", message = (e.message ?: tr("The server did not start", "Сервер не запустился")) + connection.errors().takeIf { it.isNotEmpty() }?.let { "\n${it.takeLast(400)}" }.orEmpty()) }
                if (connections[config.id]?.second === connection) _states.update { it + (config.id to state) }
                else connection.close()
            }
        }
    }

    /** The tools of the servers ready now; a dialog keeps this set until its engine is rebuilt. */
    fun executor(): ToolExecutor? {
        val ready = _states.value.filterValues { it.status == "ready" }
        if (ready.isEmpty()) return null
        return McpTools(ready.mapNotNull { (id, state) -> connections[id]?.second?.let { id to (it to state.tools) } }.toMap())
    }

    override fun close() { connections.values.forEach { runCatching { it.second.close() } }; connections.clear() }
}

/** MCP tools as Chiller tools: `mcp__<server>__<tool>`, arguments checked, results bounded. */
class McpTools(private val servers: Map<String, Pair<McpConnection, List<McpTool>>>) : ToolExecutor {
    private val json = Json { encodeDefaults = true }
    private val byName: Map<String, Pair<McpConnection, McpTool>> = servers.values.flatMap { (connection, tools) ->
        tools.map { tool -> toolName(tool) to (connection to tool) }
    }.toMap()

    override val specs: List<ToolSpec> = byName.map { (name, entry) ->
        val tool = entry.second
        ToolSpec(name, "[MCP: ${tool.server}] ${tool.description}".take(1100), schemaFor(tool.inputSchema), if (tool.readOnly) ToolEffect.READ else ToolEffect.EXTERNAL)
    }

    override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
        val (connection, tool) = byName[call.name] ?: return ToolResult.failure("UNKNOWN_TOOL", "There is no MCP tool named ${call.name}")
        val arguments = runCatching { Json.parseToJsonElement(call.arguments.ifBlank { "{}" }) }.getOrNull() as? JsonObject
            ?: return ToolResult.failure("INVALID_ARGUMENTS", "Arguments must be a JSON object")
        checkArguments(arguments, tool.inputSchema)?.let { return ToolResult.failure("INVALID_ARGUMENTS", it) }
        return try {
            val result = connection.call(tool.name, arguments)
            val text = result["content"]?.jsonArray.orEmpty().joinToString("\n") { part ->
                val item = part.jsonObject
                when (item["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> item["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    "image", "audio" -> "[${item["type"]!!.jsonPrimitive.content} ${item["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()} is not passed to the model]"
                    "resource", "resource_link" -> item["resource"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                        ?: item["uri"]?.jsonPrimitive?.contentOrNull?.let { "[resource $it]" }.orEmpty()
                    else -> ""
                }
            }.ifEmpty { result["structuredContent"]?.let { json.encodeToString(JsonElement.serializer(), it) }.orEmpty() }
            val truncated = text.length > MAX_RESULT
            val payload = buildJsonObject { put("server", tool.server); put("tool", tool.name); put("content", text.take(MAX_RESULT)); if (truncated) put("truncated", true) }
            if (result["isError"]?.jsonPrimitive?.booleanOrNull == true) ToolResult(false, payload, ToolError("MCP_TOOL_ERROR", text.take(500).ifEmpty { "The tool reported an error" }), truncated)
            else ToolResult(true, payload, truncated = truncated)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: McpException) { ToolResult.failure(e.code, e.message ?: "MCP error", retryable = e.code == "MCP_TIMEOUT") }
    }

    companion object {
        const val MAX_RESULT = 32 * 1024

        fun toolName(tool: McpTool): String =
            "mcp__${tool.server}__${tool.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}".take(64)

        /** The provider needs an object schema; anything else becomes an open object. */
        fun schemaFor(schema: JsonObject): JsonObject =
            if (schema["type"]?.jsonPrimitive?.contentOrNull == "object") schema else buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}) }

        /**
         * The checks that matter before an outside call: required arguments present, simple types right. The server
         * validates its full schema itself; a call that fails here never leaves Chiller.
         */
        fun checkArguments(arguments: JsonObject, schema: JsonObject): String? {
            schema["required"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.firstOrNull { it !in arguments }?.let { return "Missing required argument $it" }
            val properties = schema["properties"] as? JsonObject ?: return null
            for ((key, value) in arguments) {
                val type = (properties[key] as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull ?: continue
                val fits = when (type) {
                    "string" -> value is JsonPrimitive && value.isString
                    "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
                    "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
                    "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
                    "array" -> value is JsonArray
                    "object" -> value is JsonObject
                    else -> true
                }
                if (!fits) return "$key must be of type $type"
            }
            return null
        }
    }
}

/** Several executors as one; names never collide because MCP tools carry their prefix. */
class CombinedTools(private val parts: List<ToolExecutor>) : ToolExecutor {
    override val specs: List<ToolSpec> = parts.flatMap { it.specs }
    private val owner: Map<String, ToolExecutor> = parts.flatMap { part -> part.specs.map { it.name to part } }.toMap()
    override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult =
        owner[call.name]?.execute(call, context) ?: ToolResult.failure("UNKNOWN_TOOL", "There is no tool named ${call.name}")
}
