package dev.chiller.providers

import dev.chiller.contracts.AgentMessage
import dev.chiller.contracts.CapabilityReport
import dev.chiller.contracts.LlmProvider
import dev.chiller.contracts.ModelEvent
import dev.chiller.contracts.ModelRequest
import dev.chiller.contracts.ToolCall
import dev.chiller.contracts.ToolSpec
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.charsets.TooLongLineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

/** Configuration for an OpenAI Chat Completions compatible endpoint. */
data class ChatCompletionsProfile(
    val id: String,
    val baseUrl: String,
    val model: String,
    val chatPath: String = "/chat/completions",
    val reasoningField: String? = null,
    val thinkingOptions: (Boolean) -> JsonObject = { JsonObject(emptyMap()) },
    val streamOptions: JsonObject = buildJsonObject { put("include_usage", true) },
) {
    companion object {
        fun deepSeekFlash(
            baseUrl: String = "https://api.deepseek.com",
            model: String = "deepseek-flash",
            id: String = "deepseek-direct",
        ) = ChatCompletionsProfile(
            id = id,
            baseUrl = baseUrl,
            model = model,
            reasoningField = "reasoning_content",
            thinkingOptions = { enabled -> buildJsonObject {
                put("thinking", buildJsonObject { put("type", if (enabled) "enabled" else "disabled") })
            } },
        )
    }
}

/** Uses a lambda so credentials can be resolved at request time and never appear in object strings. */
class ChatCompletionsProvider(
    private val client: HttpClient,
    private val profile: ChatCompletionsProfile,
    private val apiKey: () -> String?,
) : LlmProvider {
    override suspend fun probe() = CapabilityReport(profile.id, tools = null, reasoning = profile.reasoningField?.let { true })

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        val key = apiKey()?.takeIf { it.isNotBlank() }
        if (key == null) {
            emit(ModelEvent.Failed("MISSING_CREDENTIAL", "Provider credentials are not configured"))
            return@flow
        }
        val payload = runCatching { requestJson(request) }.getOrElse {
            emit(ModelEvent.Failed("INVALID_REQUEST", "Could not encode provider request")); return@flow
        }
        try {
            val accumulator = StreamAccumulator(profile.reasoningField)
            var sawDone = false
            var protocolFailure: ModelEvent.Failed? = null
            client.preparePost(profile.baseUrl.trimEnd('/') + "/" + profile.chatPath.trimStart('/')) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $key")
                header(HttpHeaders.Accept, "text/event-stream")
                setBody(payload.toString())
            }.execute { response ->
                if (response.status.value !in 200..299) {
                    val status = response.status.value
                    protocolFailure = ModelEvent.Failed(
                        when (status) { 401, 403 -> "AUTHENTICATION_FAILED"; 429 -> "RATE_LIMITED"; else -> "PROVIDER_HTTP_ERROR" },
                        when (status) { 401, 403 -> "Provider rejected credentials (HTTP $status)"; 429 -> "Provider rate limit reached"; else -> "Provider request failed (HTTP $status)" },
                    )
                    return@execute
                }
                val channel = response.bodyAsChannel()
                val dataLines = mutableListOf<String>()
                var frameChars = 0
                var totalChars = 0
                while (true) {
                    val line = channel.readUTF8Line(MAX_SSE_FRAME_CHARS) ?: break
                    frameChars += line.length
                    totalChars += line.length
                    if (frameChars > MAX_SSE_FRAME_CHARS || totalChars > MAX_SSE_RESPONSE_CHARS) {
                        protocolFailure = ModelEvent.Failed("RESPONSE_TOO_LARGE", "Provider response exceeded the streaming size limit")
                        return@execute
                    }
                    if (line.isEmpty()) {
                        if (dataLines.isNotEmpty()) {
                            val data = dataLines.joinToString("\n")
                            dataLines.clear()
                            frameChars = 0
                            if (data == "[DONE]") { sawDone = true; break }
                            val parsed = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull()
                            if (parsed == null) {
                                protocolFailure = ModelEvent.Failed("MALFORMED_STREAM", "Provider sent malformed streaming data")
                                return@execute
                            }
                            val chunk = accumulator.consume(parsed)
                            if (accumulator.isTooLarge()) {
                                protocolFailure = ModelEvent.Failed("RESPONSE_TOO_LARGE", "Provider response exceeded the streaming size limit")
                                return@execute
                            }
                            chunk.text?.let { emit(ModelEvent.TextDelta(it)) }
                            chunk.reasoning?.let { emit(ModelEvent.ReasoningDelta(it)) }
                        }
                    } else if (!line.startsWith(':')) {
                        val colon = line.indexOf(':')
                        val field = if (colon < 0) line else line.substring(0, colon)
                        if (field == "data") dataLines.add(if (colon >= 0 && line.getOrNull(colon + 1) == ' ') line.substring(colon + 2) else line.substring(colon + 1))
                    }
                }
            }
            protocolFailure?.let { emit(it); return@flow }
            if (!sawDone || accumulator.finishReason == null) {
                emit(ModelEvent.Failed("INCOMPLETE_STREAM", "Provider stream ended before a complete response"))
                return@flow
            }
            if (accumulator.finishReason == "length" && accumulator.hasToolCalls()) {
                emit(ModelEvent.Failed("INCOMPLETE_TOOL_CALL", "Provider truncated a tool call; it was not executed"))
                return@flow
            }
            if (accumulator.hasMalformedToolCall()) {
                emit(ModelEvent.Failed("MALFORMED_TOOL_CALL", "Provider returned an incomplete tool call; it was not executed"))
                return@flow
            }
            emit(ModelEvent.Completed(accumulator.message(), accumulator.finishReason!!))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            emit(ModelEvent.Failed("PROVIDER_TIMEOUT", "Provider request timed out"))
        } catch (_: TooLongLineException) {
            emit(ModelEvent.Failed("RESPONSE_TOO_LARGE", "Provider response exceeded the streaming size limit"))
        } catch (_: Exception) {
            emit(ModelEvent.Failed("PROVIDER_TRANSPORT_ERROR", "Provider connection failed"))
        }
    }

    private fun requestJson(request: ModelRequest): JsonObject {
        val messages = JsonArray(request.messages.map { message ->
            buildJsonObject {
                put("role", message.role)
                message.content?.let { put("content", it) }
                if (message.toolCalls.isNotEmpty()) put("tool_calls", JsonArray(message.toolCalls.map { call ->
                    buildJsonObject {
                        put("id", call.id); put("type", "function")
                        put("function", buildJsonObject { put("name", call.name); put("arguments", call.arguments) })
                    }
                }))
                message.toolCallId?.let { put("tool_call_id", it) }
                profile.reasoningField?.let { field ->
                    val replayReasoning = message.replay[field]
                    if (message.role == "assistant" && replayReasoning != null) put(field, replayReasoning)
                }
            }
        })
        return buildJsonObject {
            put("model", request.model.ifBlank { profile.model })
            put("messages", messages); put("stream", true)
            put("max_tokens", request.maxOutputTokens)
            if (request.tools.isNotEmpty()) put("tools", JsonArray(request.tools.map(::toolJson)))
            profile.thinkingOptions(request.thinking).forEach { (key, value) -> put(key, value) }
            put("stream_options", profile.streamOptions)
        }
    }

    private fun toolJson(tool: ToolSpec) = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", tool.name); put("description", tool.description); put("parameters", tool.parameters)
        })
    }
}

private const val MAX_SSE_FRAME_CHARS = 1_048_576
private const val MAX_SSE_RESPONSE_CHARS = 8_388_608

private data class Delta(val text: String? = null, val reasoning: String? = null)
private data class PartialTool(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

private class StreamAccumulator(private val reasoningField: String?) {
    private val tools = sortedMapOf<Int, PartialTool>()
    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    var finishReason: String? = null
        private set
    private var malformed = false

    fun consume(root: JsonObject): Delta {
        val choices = root["choices"]?.jsonArray ?: return Delta()
        val choice = choices.firstOrNull()?.jsonObject ?: return Delta()
        choice["finish_reason"]?.takeUnless { it is JsonNull }?.let { finishReason = it.jsonPrimitive.content }
        val delta = choice["delta"]?.jsonObject ?: return Delta()
        val text = delta["content"]?.let { if (it is JsonPrimitive && it.isString) it.content else null }
        val why = reasoningField?.let { delta[it]?.let { item -> if (item is JsonPrimitive && item.isString) item.content else null } }
        if (text != null) content.append(text)
        if (why != null) reasoning.append(why)
        delta["tool_calls"]?.jsonArray?.forEach { item ->
            val obj = item.jsonObject
            val index = obj["index"]?.jsonPrimitive?.intOrNull
            if (index == null || index < 0) { malformed = true; return@forEach }
            val partial = tools.getOrPut(index) { PartialTool() }
            obj["id"]?.takeUnless { it is JsonNull }?.let { partial.id += it.jsonPrimitive.content }
            val fn = obj["function"]?.jsonObject
            fn?.get("name")?.takeUnless { it is JsonNull }?.let { partial.name += it.jsonPrimitive.content }
            fn?.get("arguments")?.takeUnless { it is JsonNull }?.let { partial.args.append(it.jsonPrimitive.content) }
        }
        return Delta(text, why)
    }

    fun hasToolCalls() = tools.isNotEmpty()
    fun isTooLarge() = content.length + reasoning.length + tools.values.sumOf { it.args.length + it.name.length + it.id.length } > MAX_SSE_RESPONSE_CHARS
    fun hasMalformedToolCall() = malformed || tools.values.any { it.id.isBlank() || it.name.isBlank() || !validObject(it.args.toString()) }
    fun message(): AgentMessage {
        val replay = if (reasoning.isNotEmpty() && reasoningField != null) buildJsonObject { put(reasoningField, reasoning.toString()) } else JsonObject(emptyMap())
        return AgentMessage("assistant", content.toString().ifEmpty { null }, tools.values.map { ToolCall(it.id, it.name, it.args.toString()) }, replay = replay)
    }
    private fun validObject(raw: String): Boolean = runCatching { Json.parseToJsonElement(raw) is JsonObject }.getOrDefault(false)
}
