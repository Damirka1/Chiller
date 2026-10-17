package dev.chiller.app.di

import java.net.URI

/**
 * Ready-made OpenAI-compatible endpoints: pick one and paste a key. Keys are kept per origin, so a key is
 * only ever sent to the endpoint it was entered for. Only DeepSeek has been verified live in this project;
 * the others follow each provider's published OpenAI-compatible base URL and are marked as not verified.
 */
data class ProviderPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    /** Environment / .env variables that may hold this provider's key. */
    val keyVariables: List<String> = emptyList(),
    /** Model filled in when the preset is chosen; null means "pick from the provider's list". */
    val defaultModel: String? = null,
    val keysUrl: String? = null,
    val note: String = "",
    val verified: Boolean = false,
) {
    /** Loopback servers (Ollama, LM Studio, vLLM) take requests without a key. */
    val keyless: Boolean get() = isLoopback(baseUrl)
}

object ProviderPresets {
    val all: List<ProviderPreset> = listOf(
        ProviderPreset("deepseek", "DeepSeek", "https://api.deepseek.com", listOf("DEEPSEEK_API_KEY", "API_TOKEN"), "deepseek-flash",
            "https://platform.deepseek.com/api_keys", "Модель разработки Chiller, проверена вживую", verified = true),
        ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1", listOf("OPENAI_API_KEY"), keysUrl = "https://platform.openai.com/api-keys"),
        ProviderPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", listOf("OPENROUTER_API_KEY"), keysUrl = "https://openrouter.ai/keys", note = "Много моделей разных компаний через один ключ"),
        ProviderPreset("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", listOf("GEMINI_API_KEY"), keysUrl = "https://aistudio.google.com/apikey", note = "OpenAI-совместимый режим"),
        ProviderPreset("groq", "Groq", "https://api.groq.com/openai/v1", listOf("GROQ_API_KEY"), keysUrl = "https://console.groq.com/keys"),
        ProviderPreset("mistral", "Mistral", "https://api.mistral.ai/v1", listOf("MISTRAL_API_KEY"), keysUrl = "https://console.mistral.ai/api-keys"),
        ProviderPreset("xai", "xAI", "https://api.x.ai/v1", listOf("XAI_API_KEY"), keysUrl = "https://console.x.ai"),
        ProviderPreset("together", "Together AI", "https://api.together.xyz/v1", listOf("TOGETHER_API_KEY"), keysUrl = "https://api.together.ai/settings/api-keys"),
        ProviderPreset("fireworks", "Fireworks", "https://api.fireworks.ai/inference/v1", listOf("FIREWORKS_API_KEY"), keysUrl = "https://fireworks.ai/account/api-keys"),
        ProviderPreset("ollama", "Ollama", "http://localhost:11434/v1", note = "Локальный сервер, ключ не нужен"),
        ProviderPreset("lmstudio", "LM Studio", "http://localhost:1234/v1", note = "Локальный сервер, ключ не нужен"),
        ProviderPreset("vllm", "vLLM", "http://localhost:8000/v1", note = "Локальный сервер (например, Gemma), ключ не нужен"),
    )

    fun byOrigin(url: String): ProviderPreset? = originOf(url)?.let { origin -> all.firstOrNull { originOf(it.baseUrl) == origin } }
    val keyVariables: List<String> get() = all.flatMap { it.keyVariables }.distinct()
}

/** scheme://host:port with the default port made explicit; null for an invalid URL. */
fun originOf(url: String): String? = runCatching {
    val uri = URI(url.trim())
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val port = uri.port.takeIf { it >= 0 } ?: if (scheme == "https") 443 else 80
    "$scheme://$host:$port"
}.getOrNull()

fun isLoopback(url: String): Boolean = runCatching { URI(url.trim()).host?.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]") }.getOrDefault(false)
