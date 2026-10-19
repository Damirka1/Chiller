package dev.chiller.app.di

import kotlinx.serialization.Serializable
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

/**
 * A provider the user defines on this machine (a company gateway, a home server). It lives in the local settings
 * file only, so a private address never reaches the repository or the installers. Its key comes from the named
 * environment/.env variable, bound to this provider's origin like every other key.
 */
@Serializable
data class CustomProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val keyVariable: String? = null,
    val defaultModel: String? = null,
    /** Thinking switch the provider is applied with; null keeps the current one. */
    val thinking: Boolean? = null,
    /** "auto", "full" or "compact" applied with the provider; null keeps the current profile. */
    val profile: String? = null,
) {
    fun preset() = ProviderPreset("custom:$id", name, baseUrl, listOfNotNull(keyVariable), defaultModel, note = "Свой провайдер · хранится только в настройках этого компьютера")

    /** Why this provider cannot be saved, in words for the settings page; null when it is valid. */
    fun problem(): String? = when {
        name.isBlank() || name.length > 60 -> "Название: от 1 до 60 символов"
        !endpointAllowed(baseUrl) -> "Адрес: HTTPS, или HTTP только для localhost; без логина, query и #"
        keyVariable != null && !isVariableName(keyVariable) -> "Переменная ключа: заглавные латинские буквы, цифры и _, например QARMET_API_KEY"
        defaultModel != null && defaultModel.length > 200 -> "Модель: не длиннее 200 символов"
        profile != null && profile !in setOf("auto", "full", "compact") -> "Профиль: авто, полный или компактный"
        else -> null
    }

    fun normalized() = copy(name = name.trim(), baseUrl = baseUrl.trim().trimEnd('/'), keyVariable = keyVariable?.trim()?.ifEmpty { null },
        defaultModel = defaultModel?.trim()?.ifEmpty { null })
}

/** An environment variable name as people write them (QARMET_API_KEY); a pasted key with lowercase letters is not one. */
fun isVariableName(value: String): Boolean = Regex("[A-Z_][A-Z0-9_]{0,63}").matches(value)

/** The same rule the connection settings apply: HTTPS, or plain HTTP to a loopback host; no credentials, query or fragment. */
fun endpointAllowed(url: String): Boolean = runCatching {
    val uri = URI(url.trim())
    val host = uri.host ?: return false
    uri.userInfo == null && uri.query == null && uri.fragment == null &&
        (uri.scheme == "https" || (uri.scheme == "http" && isLoopback(url)))
}.getOrDefault(false)

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

    fun byOrigin(url: String, custom: List<CustomProvider> = emptyList()): ProviderPreset? =
        originOf(url)?.let { origin -> (all + custom.map { it.preset() }).firstOrNull { originOf(it.baseUrl) == origin } }
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
