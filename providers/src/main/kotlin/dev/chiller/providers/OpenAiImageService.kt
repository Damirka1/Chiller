package dev.chiller.providers

import dev.chiller.contracts.ImageData
import dev.chiller.contracts.ImageService
import dev.chiller.contracts.RequestBudget
import dev.chiller.contracts.ToolException
import dev.chiller.contracts.imageMimeType
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * OpenAI Images API: `POST /images/generations` (JSON) and `POST /images/edits` (multipart with the reference image).
 * Asks for `b64_json` and never follows a returned URL, so the request stays on the configured endpoint. Generation
 * has no side effect on the server, so a quota or server error before an image arrives is retried a bounded number
 * of times; a timeout is not, because the server may still be rendering.
 */
class OpenAiImageService(
    private val client: HttpClient,
    private val baseUrl: String,
    override val model: String,
    override val supportsEditing: Boolean,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val requestBudget: RequestBudget? = null,
    private val apiKey: () -> String?,
) : ImageService {
    override suspend fun generate(prompt: String, size: String, reference: ImageData?): ImageData {
        if (reference != null && !supportsEditing) throw ToolException("UNSUPPORTED", "The image model cannot edit images", "Generate a new image from a text prompt instead")
        var attempt = 0
        while (true) {
            attempt++
            val reservation = requestBudget?.reserveImage()
            try {
                // The fixed per-image ceiling remains reserved when no token usage is reported, including retries.
                return try { request(prompt, size, reference) } finally { reservation?.finish() }
            } catch (retry: Retry) {
                if (attempt >= MAX_ATTEMPTS) throw retry.error
                sleep(retry.delayMs)
            }
        }
    }

    private suspend fun request(prompt: String, size: String, reference: ImageData?): ImageData = try {
        val key = apiKey()?.takeIf { it.isNotBlank() }
        client.preparePost(baseUrl.trimEnd('/') + if (reference == null) "/images/generations" else "/images/edits") {
            timeout { requestTimeoutMillis = TIMEOUT_MS; socketTimeoutMillis = TIMEOUT_MS }
            key?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            header(HttpHeaders.Accept, "application/json")
            if (reference == null) {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("model", model); put("prompt", prompt); put("size", size); put("n", 1); put("response_format", "b64_json")
                }.toString())
            } else {
                setBody(MultiPartFormDataContent(formData {
                    append("model", model); append("prompt", prompt); append("size", size); append("n", "1"); append("response_format", "b64_json")
                    append("image", reference.bytes, Headers.build {
                        append(HttpHeaders.ContentType, reference.mimeType)
                        append(HttpHeaders.ContentDisposition, "filename=\"reference.${extensionOf(reference.mimeType)}\"")
                    })
                }))
            }
        }.execute { response -> read(response) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: ToolException) {
        throw e
    } catch (e: Retry) {
        throw e
    } catch (_: HttpRequestTimeoutException) {
        throw ToolException("PROVIDER_TIMEOUT", "The image model did not answer within ${TIMEOUT_MS / 1000} s", "Try a simpler prompt or a smaller size")
    } catch (e: Exception) {
        if (isTlsFailure(e)) throw ToolException("PROVIDER_TLS_ERROR", TLS_FAILURE_MESSAGE)
        throw Retry(ToolException("PROVIDER_TRANSPORT_ERROR", "Image provider connection failed"), FALLBACK_DELAY_MS)
    }

    private suspend fun read(response: HttpResponse): ImageData {
        val status = response.status.value
        if (status in 200..299) {
            val body = readBounded(response, MAX_RESPONSE_BYTES) ?: throw ToolException("RESPONSE_TOO_LARGE", "Image response exceeded ${MAX_RESPONSE_BYTES / 1_048_576} MiB")
            return decode(body)
        }
        val text = readBounded(response, 16_384)?.decodeToString().orEmpty()
        val detail = providerErrorMessage(text)?.let { ": $it" }.orEmpty()
        val retryAfter = response.headers[HttpHeaders.RetryAfter]?.let(::retryAfterMs)
        when {
            status == 401 || status == 403 -> throw ToolException("AUTHENTICATION_FAILED", "Image provider rejected credentials (HTTP $status)")
            status == 429 && (retryAfter ?: 0) <= MAX_RETRY_AFTER_MS -> throw Retry(ToolException("RATE_LIMITED", "Image provider rate limit reached"), retryAfter ?: FALLBACK_DELAY_MS)
            status == 429 -> throw ToolException("RATE_LIMITED", "Image provider rate limit reached; retry in ${(retryAfter!! + 999) / 1000} s")
            status in 500..599 -> throw Retry(ToolException("PROVIDER_HTTP_ERROR", "Image provider failed (HTTP $status)$detail"), retryAfter?.coerceAtMost(MAX_RETRY_AFTER_MS) ?: FALLBACK_DELAY_MS)
            status in 400..499 -> throw ToolException("IMAGE_REQUEST_REJECTED", "Image provider rejected the request (HTTP $status)$detail", "Adjust the prompt, size or reference image")
            else -> throw ToolException("PROVIDER_HTTP_ERROR", "Image provider request failed (HTTP $status)")
        }
    }

    private fun decode(body: ByteArray): ImageData {
        val root = runCatching { Json.parseToJsonElement(body.decodeToString()).jsonObject }.getOrNull()
            ?: throw ToolException("MALFORMED_RESPONSE", "Image provider returned malformed JSON")
        val first = runCatching { root.getValue("data").jsonArray.first().jsonObject }.getOrNull()
            ?: throw ToolException("MALFORMED_RESPONSE", "Image provider returned no image")
        val b64 = (first["b64_json"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: if (first["url"] != null) throw ToolException("UNSUPPORTED_RESPONSE", "Image provider returned a URL instead of image data; only b64_json is accepted")
            else throw ToolException("MALFORMED_RESPONSE", "Image provider returned no image data")
        val bytes = runCatching { Base64.getMimeDecoder().decode(b64) }.getOrNull()
            ?: throw ToolException("MALFORMED_RESPONSE", "Image data is not valid base64")
        val mime = imageMimeType(bytes) ?: throw ToolException("MALFORMED_RESPONSE", "Image data is not PNG, JPEG or WebP")
        return ImageData(bytes, mime)
    }

    /** A failure worth one more attempt after [delayMs]: nothing was produced and the request is stateless. */
    private class Retry(val error: ToolException, val delayMs: Long) : Exception(error.message)
}

private fun extensionOf(mime: String) = when (mime) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "png" }

/** At most [limit] bytes of the body; null when it is longer. */
private suspend fun readBounded(response: HttpResponse, limit: Int): ByteArray? {
    val channel = response.bodyAsChannel()
    val buffer = ByteArray(65_536)
    val out = ByteArrayOutputStream()
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read == -1) return out.toByteArray()
        if (read == 0) continue
        out.write(buffer, 0, read)
        if (out.size() > limit) return null
    }
}

private const val MAX_ATTEMPTS = 3
private const val TIMEOUT_MS = 180_000L
private const val FALLBACK_DELAY_MS = 2_000L
private const val MAX_RETRY_AFTER_MS = 120_000L
/** A 2048×2048 PNG in base64 is well under this; a larger answer is not an image we asked for. */
private const val MAX_RESPONSE_BYTES = 64 * 1_048_576
