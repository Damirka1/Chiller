package dev.chiller.evals

import dev.chiller.contracts.AgentMessage
import dev.chiller.contracts.ComposerAttachment
import dev.chiller.contracts.ModelEvent
import dev.chiller.contracts.ModelRequest
import dev.chiller.providers.ChatCompletionsProfile
import dev.chiller.providers.ChatCompletionsProvider
import dev.chiller.providers.ImageInput
import dev.chiller.providers.ModelImage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Opt-in live check that the endpoint actually sees attached images: one request, an image showing a random
 * number that appears nowhere in the text, and a small output cap. Never part of `check`.
 *
 * Environment: CHILLER_VISION_BASE_URL (default https://api.deepseek.com), CHILLER_VISION_MODEL (default
 * deepseek-flash); key from CHILLER_EVAL_API_KEY, DEEPSEEK_API_KEY or API_TOKEN, environment or nearest .env.
 */
fun main() = runBlocking {
    val env = System.getenv()
    val baseUrl = env["CHILLER_VISION_BASE_URL"]?.takeIf { it.isNotBlank() } ?: "https://api.deepseek.com"
    val model = env["CHILLER_VISION_MODEL"]?.takeIf { it.isNotBlank() } ?: "deepseek-flash"
    val keyNames = listOf("CHILLER_EVAL_API_KEY", "DEEPSEEK_API_KEY", "API_TOKEN")
    val key = keyNames.firstNotNullOfOrNull { env[it]?.takeIf(String::isNotBlank) } ?: dotenvKey(keyNames)
    val host = runCatching { URI(baseUrl).host }.getOrNull().orEmpty()
    val loopback = host in setOf("localhost", "127.0.0.1", "::1")
    if (key == null && !loopback) { System.err.println("LIVE VISION SKIPPED: no key configured."); exitProcess(2) }

    val secret = (1000..9999).random().toString()
    val image = BufferedImage(480, 200, BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        color = Color.WHITE; fillRect(0, 0, 480, 200)
        color = Color.BLACK; font = Font(Font.SANS_SERIF, Font.BOLD, 120); drawString(secret, 70, 150)
        dispose()
    }
    val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    val base = if (host == "api.deepseek.com") ChatCompletionsProfile.deepSeekFlash(baseUrl, model)
        else ChatCompletionsProfile("generic", baseUrl, model, requiresApiKey = !loopback)
    val profile = base.copy(imageInput = ImageInput { ModelImage("image/png", Base64.getEncoder().encodeToString(png)) })
    val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 60_000 } }
    val started = System.nanoTime()
    try {
        val message = AgentMessage("user", "What number is written in the attached image? Reply with the digits only.",
            attachments = listOf(ComposerAttachment("vision-smoke", "number.png", "image/png", png.size.toLong())))
        val events = ChatCompletionsProvider(http, profile, emptyList()) { key }.stream(ModelRequest(model, listOf(message), maxOutputTokens = 64)).toList()
        val ms = (System.nanoTime() - started) / 1_000_000
        val failure = events.filterIsInstance<ModelEvent.Failed>().firstOrNull()
        if (failure != null) { println("LIVE VISION FAILED · $model · ${ms} ms · provider error: ${failure.toString().take(300)}"); exitProcess(1) }
        val answer = events.filterIsInstance<ModelEvent.Completed>().lastOrNull()?.message?.content.orEmpty().trim()
        val usage = events.filterIsInstance<ModelEvent.Usage>().lastOrNull()
        val seen = secret in answer.filter(Char::isDigit)
        println("LIVE VISION ${if (seen) "PASSED" else "FAILED"} · $model · ${ms} ms · answer '${answer.take(40)}' (expected $secret) · usage ${usage ?: "n/a"}")
        if (!seen) exitProcess(1)
    } finally { http.close() }
}
