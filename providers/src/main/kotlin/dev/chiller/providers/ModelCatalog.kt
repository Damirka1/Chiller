package dev.chiller.providers

import dev.chiller.contracts.ModelCatalogClient
import dev.chiller.contracts.ModelCatalogResult
import dev.chiller.contracts.ProviderModel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.URI

/**
 * Lists models from a single OpenAI-compatible `GET /models` endpoint.
 *
 * One request, no fallback probing and no retries: the caller decides when discovery is needed.
 * The HTTP client is supplied by the caller so redirect policy, proxy and TLS stay centralized.
 */
class KtorModelCatalogClient(private val client: HttpClient) : ModelCatalogClient {
    override suspend fun discover(baseUrl: String, apiKey: String?): ModelCatalogResult {
        val endpoint = modelsEndpoint(baseUrl)
            ?: return failure("INVALID_BASE_URL", "Provider address is not a supported HTTP(S) URL")
        val key = apiKey?.takeIf { it.isNotBlank() }
        return withTimeoutOrNull(TIMEOUT_MS) { request(endpoint, key) }
            ?: failure("PROVIDER_TIMEOUT", "Provider request timed out")
    }

    private suspend fun request(url: String, key: String?): ModelCatalogResult = try {
        client.prepareGet(url) {
            expectSuccess = false
            header(HttpHeaders.Accept, "application/json")
            if (key != null) header(HttpHeaders.Authorization, "Bearer $key")
        }.execute { response -> read(response) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: HttpRequestTimeoutException) {
        failure("PROVIDER_TIMEOUT", "Provider request timed out")
    } catch (_: Exception) {
        failure("PROVIDER_TRANSPORT_ERROR", "Provider connection failed")
    }

    private suspend fun read(response: HttpResponse): ModelCatalogResult {
        val status = response.status.value
        if (status in 300..399) return failure("PROVIDER_HTTP_ERROR", "Provider returned a redirect (HTTP $status)")
        if (status !in 200..299) return httpFailure(status)
        val body = readBounded(response.bodyAsChannel())
            ?: return failure("RESPONSE_TOO_LARGE", "Model list response exceeded the size limit")
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return failure("MALFORMED_RESPONSE", "Provider returned a malformed model list")
        return parseModels(root)
    }

    private fun httpFailure(status: Int): ModelCatalogResult = when (status) {
        401, 403 -> failure("AUTHENTICATION_FAILED", "Provider rejected credentials (HTTP $status)")
        404, 405 -> failure("UNSUPPORTED", "This endpoint does not list models")
        429 -> failure("RATE_LIMITED", "Provider rate limit reached (HTTP $status)")
        else -> failure("PROVIDER_HTTP_ERROR", "Provider request failed (HTTP $status)")
    }

    private fun parseModels(root: JsonObject): ModelCatalogResult {
        val data = root["data"] as? JsonArray
            ?: return failure("MALFORMED_RESPONSE", "Provider returned a malformed model list")
        val models = LinkedHashMap<String, ProviderModel>()
        for (element in data) {
            val entry = element as? JsonObject
                ?: return failure("MALFORMED_RESPONSE", "Provider returned a malformed model list")
            val id = (entry["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            if (id.isNullOrEmpty() || id.length > MAX_ID_CHARS) {
                return failure("MALFORMED_RESPONSE", "Provider returned a malformed model list")
            }
            if (models.size >= MAX_MODELS || id in models) continue
            models[id] = ProviderModel(
                id = id,
                displayName = displayName(entry["name"]),
                contextWindow = positiveInt(entry["context_window"]) ?: positiveInt(entry["context_length"]),
                maxOutputTokens = positiveInt(entry["max_output_tokens"]),
            )
        }
        return ModelCatalogResult(models = models.values.toList())
    }
}

/** Reads at most [MAX_RESPONSE_BYTES] + 1 bytes; null means the response exceeded the limit. */
private suspend fun readBounded(channel: ByteReadChannel): String? {
    val buffer = ByteArray(8_192)
    val output = ByteArrayOutputStream()
    while (true) {
        val remaining = MAX_RESPONSE_BYTES + 1 - output.size()
        if (remaining <= 0) return null
        val read = channel.readAvailable(buffer, 0, minOf(buffer.size, remaining))
        if (read == -1) return output.toByteArray().decodeToString()
        if (read == 0) continue
        output.write(buffer, 0, read)
        if (output.size() > MAX_RESPONSE_BYTES) return null
    }
}

/** Optional metadata is cosmetic: unusable values stay null instead of being guessed. */
private fun displayName(node: JsonElement?): String? =
    (node as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.take(MAX_NAME_CHARS)?.ifBlank { null }

private fun positiveInt(node: JsonElement?): Int? {
    val primitive = node as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.trim().toIntOrNull()?.takeIf { it > 0 }
}

/**
 * Builds the single models URL from a base.
 *
 * Trims one trailing slash and appends `/models`: `https://api.deepseek.com` -> `/models`,
 * `https://api.deepseek.com/v1` -> `/v1/models`, `https://gateway/v1/` -> `/v1/models`.
 * Rejects credentials, query, fragment and plain HTTP on non-loopback hosts before any request.
 */
private fun modelsEndpoint(baseUrl: String): String? {
    val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
    val loopback = host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
    if (scheme != "https" && !(scheme == "http" && loopback)) return null
    val base = uri.toASCIIString().trimEnd('/')
    return if (base.isBlank()) null else "$base/models"
}

private fun failure(code: String, message: String) = ModelCatalogResult(errorCode = code, message = message)

private const val TIMEOUT_MS = 20_000L
private const val MAX_RESPONSE_BYTES = 1_048_576
private const val MAX_MODELS = 500
private const val MAX_ID_CHARS = 200
private const val MAX_NAME_CHARS = 200
