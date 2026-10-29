package dev.chiller.app.di

import dev.chiller.app.i18n.*
import dev.chiller.agent.ModelProfile
import dev.chiller.agent.ModelProfiles
import dev.chiller.contracts.ProviderModel
import dev.chiller.providers.RESERVED_REQUEST_FIELDS
import dev.chiller.providers.VisionModels
import kotlinx.serialization.json.*

/** The profile in effect for a model, after "auto" is resolved by its id and, failing that, its catalog name. */
fun effectiveProfile(options: AgentOptions, modelId: String, listed: ProviderModel? = null): ModelProfile =
    ModelProfiles.resolve(options.profile, modelId, listed?.displayName)

/** Whether attached images go to the model as pixels: forced by the setting, else what the catalog states, else guessed from the id. */
fun visionEnabled(options: AgentOptions, modelId: String, listed: ProviderModel? = null): Boolean = when (options.vision) {
    "on" -> true
    "off" -> false
    else -> listed?.vision ?: VisionModels.likely(modelId)
}

/**
 * The image model generate_image uses: off, an explicit id (listed or typed), or in auto the first model the catalog
 * says can generate images. Null keeps the tool out of the toolbox.
 */
fun imageModelFor(options: AgentOptions, catalog: ModelCatalogState): ProviderModel? = when (val choice = options.imageModel) {
    "off" -> null
    "auto" -> catalog.models.firstOrNull { it.imageGeneration == true }
    else -> catalog.entry(choice) ?: ProviderModel(choice)
}

/** Where generate_image goes: the endpoint chosen for images, else the chat endpoint. */
fun imageEndpoint(settings: DesktopSettings): String = settings.agent.imageBaseUrl.ifBlank { settings.baseUrl }

/** The models generate_image picks from: the live chat catalog on the chat endpoint, else the one cached for the image endpoint. */
fun imageCatalog(settings: DesktopSettings, catalog: ModelCatalogState): ModelCatalogState {
    val endpoint = normalizeEndpoint(imageEndpoint(settings))
    if (endpoint == normalizeEndpoint(settings.baseUrl)) return catalog
    return settings.modelCatalogs[endpoint]?.let { ModelCatalogState("ready", it.models, fetchedAt = it.fetchedAt) } ?: ModelCatalogState()
}

/** Other endpoints whose cached catalog lists an image model, as (url, name); the chosen one stays even if its cache is gone. */
fun imageProviderChoices(settings: DesktopSettings): List<Pair<String, String>> {
    val chat = normalizeEndpoint(settings.baseUrl)
    val listed = settings.modelCatalogs.filter { (endpoint, cached) -> endpoint != chat && cached.models.any { it.imageGeneration == true } }.keys
    val chosen = settings.agent.imageBaseUrl.takeIf { it.isNotBlank() && normalizeEndpoint(it) != chat }?.let(::normalizeEndpoint)
    return (listed + listOfNotNull(chosen)).distinct().map { url -> url to (ProviderPresets.byOrigin(url, settings.customProviders)?.name ?: url) }
}

/** The catalog entry for a model id, if the endpoint listed it. */
fun ModelCatalogState.entry(modelId: String): ProviderModel? = models.firstOrNull { it.id == modelId }

/**
 * Extra request fields for the provider: the profile's and model family's defaults (Gemma's recommended sampling), then
 * the user's temperature, then the user's JSON extras, where a null value removes a default the server does not accept.
 * Invalid user JSON is ignored here; [validateRequestExtras] reports it in Settings.
 */
fun requestExtrasFor(profile: ModelProfile, options: AgentOptions, modelId: String, listed: ProviderModel? = null): JsonObject {
    val fields = LinkedHashMap<String, JsonElement>(ModelProfiles.requestDefaults(profile, modelId, listed?.displayName))
    options.temperature?.let { fields["temperature"] = JsonPrimitive(it) }
    parseRequestExtras(options.requestExtras)?.forEach { (k, v) -> if (v is JsonNull) fields.remove(k) else fields[k] = v }
    return JsonObject(fields)
}

/** The temperature a request gets when the user has not chosen one: the model family's recommendation, else the profile's. */
fun defaultTemperature(profile: ModelProfile, modelId: String, listed: ProviderModel? = null): Double? =
    ModelProfiles.recommendedSampling(modelId, listed?.displayName)?.temperature ?: profile.temperature

fun parseRequestExtras(text: String): JsonObject? =
    text.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        ?.let { obj -> JsonObject(obj.filterKeys { it !in RESERVED_REQUEST_FIELDS }) }

/** Null when the text is empty or a usable JSON object; otherwise a short reason for Settings. */
fun validateRequestExtras(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.length > 4000) return I18n.current.di.extrasTooLong
    val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() ?: return I18n.current.di.extrasNotJson
    val obj = parsed as? JsonObject ?: return I18n.current.di.extrasNotObject
    val reserved = obj.keys.filter { it in RESERVED_REQUEST_FIELDS }
    return if (reserved.isNotEmpty()) I18n.current.di.extrasReservedPrefix + reserved.joinToString() else null
}
