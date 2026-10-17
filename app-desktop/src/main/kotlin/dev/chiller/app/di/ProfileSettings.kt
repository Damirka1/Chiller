package dev.chiller.app.di

import dev.chiller.agent.ModelProfile
import dev.chiller.agent.ModelProfiles
import dev.chiller.providers.RESERVED_REQUEST_FIELDS
import dev.chiller.providers.VisionModels
import kotlinx.serialization.json.*

/** The profile in effect for a model, after "auto" is resolved. */
fun effectiveProfile(options: AgentOptions, modelId: String): ModelProfile = ModelProfiles.resolve(options.profile, modelId)

/** Whether attached images go to the model as pixels: forced by the setting, otherwise guessed from the model id. */
fun visionEnabled(options: AgentOptions, modelId: String): Boolean = when (options.vision) {
    "on" -> true
    "off" -> false
    else -> VisionModels.likely(modelId)
}

/**
 * Extra request fields for the provider: the profile's defaults, then the user's temperature, then the
 * user's JSON extras. Invalid user JSON is ignored here; [validateRequestExtras] reports it in Settings.
 */
fun requestExtrasFor(profile: ModelProfile, options: AgentOptions): JsonObject = buildJsonObject {
    profile.temperature?.let { put("temperature", it) }
    profile.parallelToolCalls?.let { put("parallel_tool_calls", it) }
    options.temperature?.let { put("temperature", it) }
    parseRequestExtras(options.requestExtras)?.forEach { (k, v) -> put(k, v) }
}

fun parseRequestExtras(text: String): JsonObject? =
    text.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        ?.let { obj -> JsonObject(obj.filterKeys { it !in RESERVED_REQUEST_FIELDS }) }

/** Null when the text is empty or a usable JSON object; otherwise a short reason for Settings. */
fun validateRequestExtras(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.length > 4000) return "Не больше 4000 символов"
    val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() ?: return "Это не JSON"
    val obj = parsed as? JsonObject ?: return "Нужен JSON-объект {…}"
    val reserved = obj.keys.filter { it in RESERVED_REQUEST_FIELDS }
    return if (reserved.isNotEmpty()) "Служебные поля игнорируются: ${reserved.joinToString()}" else null
}
