package dev.chiller.evals

import dev.chiller.contracts.ToolException
import dev.chiller.contracts.pngSize
import dev.chiller.providers.OpenAiImageService
import dev.chiller.providers.useSystemTrust
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import java.net.URI
import kotlin.system.exitProcess

/**
 * Opt-in live check of the image model: one generation at 1024x1024, then one edit of that picture. Passes only when
 * both return a PNG of the requested size. Never part of `check`.
 *
 * Environment: CHILLER_EVAL_BASE_URL; CHILLER_IMAGE_MODEL (default: the first model the catalog lists with image
 * generation); key from CHILLER_EVAL_API_KEY, DEEPSEEK_API_KEY or API_TOKEN, environment or nearest .env.
 */
fun main() = runBlocking {
    val env = System.getenv()
    val baseUrl = env["CHILLER_EVAL_BASE_URL"]?.takeIf { it.isNotBlank() } ?: run { System.err.println("LIVE IMAGE SKIPPED: set CHILLER_EVAL_BASE_URL."); exitProcess(2) }
    val keyNames = listOf("CHILLER_EVAL_API_KEY", "DEEPSEEK_API_KEY", "API_TOKEN")
    val key = keyNames.firstNotNullOfOrNull { env[it]?.takeIf(String::isNotBlank) } ?: dotenvKey(keyNames)
    val loopback = runCatching { URI(baseUrl).host }.getOrNull() in setOf("localhost", "127.0.0.1", "::1")
    if (key == null && !loopback) { System.err.println("LIVE IMAGE SKIPPED: no key configured."); exitProcess(2) }
    val listed = listedModels(baseUrl, key).filter { it.imageGeneration == true }
    val model = env["CHILLER_IMAGE_MODEL"]?.takeIf { it.isNotBlank() }?.let { id -> listed.firstOrNull { it.id == id } ?: dev.chiller.contracts.ProviderModel(id) }
        ?: listed.firstOrNull() ?: run { println("LIVE IMAGE SKIPPED: the endpoint lists no image model"); exitProcess(2) }
    val http = HttpClient(CIO) { engine { useSystemTrust() }; install(HttpTimeout) }
    try {
        val service = OpenAiImageService(http, baseUrl, model.id, model.imageEditing != false) { key }
        var started = System.nanoTime()
        val made = service.generate("a small red cube on a white table, studio light", "1024x1024")
        val madeSize = pngSize(made.bytes)
        println("generate: ${made.mimeType} ${madeSize?.let { "${it.first}x${it.second}" } ?: "?"} ${made.bytes.size / 1024} KiB in ${(System.nanoTime() - started) / 1_000_000} ms")
        if (madeSize != 1024 to 1024) { println("LIVE IMAGE FAILED · ${model.id} · expected a 1024x1024 PNG"); exitProcess(1) }
        if (!service.supportsEditing) { println("LIVE IMAGE PASSED · ${model.id} · generation only (the catalog lists no editing)"); return@runBlocking }
        started = System.nanoTime()
        val edited = service.generate("make the cube blue, keep everything else", "1024x1024", made)
        val editedSize = pngSize(edited.bytes)
        println("edit: ${edited.mimeType} ${editedSize?.let { "${it.first}x${it.second}" } ?: "?"} ${edited.bytes.size / 1024} KiB in ${(System.nanoTime() - started) / 1_000_000} ms")
        val passed = editedSize == 1024 to 1024 && !edited.bytes.contentEquals(made.bytes)
        println("LIVE IMAGE ${if (passed) "PASSED" else "FAILED"} · ${model.id} · generate and edit")
        if (!passed) exitProcess(1)
    } catch (e: ToolException) {
        println("LIVE IMAGE FAILED · ${model.id} · ${e.code}: ${e.message}"); exitProcess(1)
    } finally { http.close() }
}
