package dev.chiller.app.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.URI
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

data class ConnectionCheckResult(val success: Boolean, val message: String)

interface ConnectionChecker {
    suspend fun check(baseUrl: String, model: String, key: String, thinking: Boolean): ConnectionCheckResult
}

/** One explicit, bounded authentication/model-access probe. It never follows redirects or retries. */
class KtorConnectionChecker(
    private val timeoutMillis: Long = 30_000L,
    private val deepSeekHosts: Set<String> = setOf("api.deepseek.com"),
) : ConnectionChecker, AutoCloseable {
    private val client = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = timeoutMillis
            socketTimeoutMillis = timeoutMillis
            requestTimeoutMillis = timeoutMillis
        }
    }

    init {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    }

    @Suppress("UNUSED_PARAMETER")
    override suspend fun check(baseUrl: String, model: String, key: String, thinking: Boolean): ConnectionCheckResult {
        val endpoint = safeEndpoint(baseUrl) ?: return ConnectionCheckResult(false, "Адрес провайдера недопустим или не поддерживается")
        if (model.isBlank()) return ConnectionCheckResult(false, "Укажите ID модели")
        if (key.isBlank() && !isLoopback(baseUrl)) return ConnectionCheckResult(false, "Укажите API-ключ")
        val host = runCatching { URI(baseUrl.trim()).host.lowercase() }.getOrDefault("")

        val request = buildJsonObject {
            put("model", model)
            put("messages", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Answer OK")
                })
            })
            put("max_tokens", 128)
            put("stream", false)
            // The probe checks access only; do not enable optional reasoning or tools.
            if (deepSeekHosts.any { host == it.lowercase() || host.endsWith(".${it.lowercase()}") }) {
                put("thinking", buildJsonObject { put("type", "disabled") })
            }
        }

        return try {
            client.preparePost(endpoint) {
                contentType(ContentType.Application.Json)
                if (key.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $key")
                setBody(request.toString())
            }.execute { response ->
                when (val status = response.status.value) {
                    in 200..299 -> {
                        val responseText = readBounded(response.bodyAsChannel())
                            ?: return@execute ConnectionCheckResult(false, "Ответ провайдера пустой, повреждён или превышает 64 КиБ")
                        if (isSuccessfulChatResponse(responseText)) ConnectionCheckResult(true, "Подключение и доступ к модели подтверждены")
                        else ConnectionCheckResult(false, "Ответ провайдера не соответствует формату Chat Completions")
                    }
                    401, 403 -> ConnectionCheckResult(false, "Провайдер отклонил API-ключ (HTTP $status)")
                    429 -> ConnectionCheckResult(false, "Провайдер ограничил частоту запросов (HTTP 429)")
                    404 -> ConnectionCheckResult(false, "Модель или адрес провайдера не найдены (HTTP 404)")
                    in 500..599 -> ConnectionCheckResult(false, "Ошибка сервера провайдера (HTTP $status)")
                    else -> ConnectionCheckResult(false, "Провайдер отклонил запрос (HTTP $status)")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ConnectionCheckResult(false, "Сетевая ошибка или истекло время ожидания")
        }
    }

    private suspend fun readBounded(channel: ByteReadChannel): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = channel.readAvailable(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES + 1 - total))
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > MAX_RESPONSE_BYTES) return null
            output.write(buffer, 0, count)
        }
        if (total == 0) return null
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private fun isSuccessfulChatResponse(text: String): Boolean = runCatching {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return false
        val choices = root["choices"] as? JsonArray ?: return false
        val message = choices.firstOrNull()?.jsonObject?.get("message") as? JsonObject ?: return false
        message["role"]?.jsonPrimitive?.content == "assistant" && message["content"]?.jsonPrimitive?.isString == true
    }.getOrDefault(false)

    override fun close() = client.close()

    private fun safeEndpoint(baseUrl: String): String? = runCatching {
        val uri = URI(baseUrl.trim())
        val host = uri.host?.lowercase() ?: return null
        val loopback = host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
        if (uri.userInfo != null || uri.query != null || uri.fragment != null ||
            !(uri.scheme.equals("https", true) || (uri.scheme.equals("http", true) && loopback))
        ) return null
        uri.toASCIIString().trimEnd('/') + "/chat/completions"
    }.getOrNull()

    private companion object { const val MAX_RESPONSE_BYTES = 64 * 1024 }
}
