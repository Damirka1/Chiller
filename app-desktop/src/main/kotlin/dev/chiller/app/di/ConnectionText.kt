package dev.chiller.app.di

import dev.chiller.app.i18n.*
import dev.chiller.contracts.ModelCatalogResult

// Stateless helpers of the controller: comparing endpoints and putting provider results into the user's words.

/** One spelling per endpoint: lower-case scheme and host, default port dropped, no trailing slash. */
internal fun normalizeEndpoint(endpoint: String): String = runCatching {
    val uri = java.net.URI(endpoint.trim()).normalize()
    val scheme = uri.scheme.lowercase(); val host = uri.host.lowercase()
    val port = if ((scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)) -1 else uri.port
    java.net.URI(scheme, null, host, port, uri.path.trimEnd('/'), null, null).toASCIIString().trimEnd('/')
}.getOrDefault(endpoint.trim().trimEnd('/').lowercase())

/** Same scheme, host and effective port: a key never leaves the origin it belongs to. */
internal fun sameOrigin(first: String, second: String): Boolean = runCatching {
    val a = java.net.URI(first); val b = java.net.URI(second)
    a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && effectivePort(a) == effectivePort(b)
}.getOrDefault(false)

internal fun effectivePort(uri: java.net.URI) = uri.port.takeIf { it >= 0 } ?: if (uri.scheme.equals("https", true)) 443 else 80

/** The model list's failure in the user's words; a key that slipped into the message is hidden. */
internal fun catalogMessage(result: ModelCatalogResult, key: String?) = when (result.errorCode) {
    "AUTHENTICATION_FAILED" -> I18n.current.di.catalogKeyRejected
    "UNSUPPORTED" -> I18n.current.di.catalogUnsupported
    "RATE_LIMITED" -> I18n.current.di.rateLimited
    "RESPONSE_TOO_LARGE" -> I18n.current.di.catalogTooLarge
    "MALFORMED_RESPONSE" -> I18n.current.di.catalogMalformed
    "PROVIDER_TIMEOUT", "NETWORK" -> I18n.current.di.catalogNetwork
    "PROVIDER_HTTP_ERROR" -> I18n.current.di.catalogHttpError
    else -> (result.message ?: I18n.current.di.catalogFailed).let { message -> if (key.isNullOrBlank()) message else message.replace(key, I18n.current.controller.hidden) }.take(200)
}

/** The connection check's result in the user's words. */
internal fun localizeConnectionMessage(message: String): String = when {
    message == "Connection and model access verified" || message == "verified" -> I18n.current.di.verified
    message.startsWith("Authentication failed") -> I18n.current.di.keyRejected
    message.startsWith("Rate limit reached") -> I18n.current.di.rateLimited
    message.startsWith("Model or endpoint not found") -> I18n.current.di.notFound
    message.startsWith("Provider server error") -> I18n.current.di.serverError
    message.startsWith("Provider rejected the request") -> I18n.current.di.requestRejected
    message.startsWith("Network error") -> I18n.current.di.networkError
    message.startsWith("Invalid or unsupported endpoint") -> I18n.current.di.badEndpoint
    message == "Model ID is required" -> I18n.current.di.modelIdRequired
    message == "API key is required" -> I18n.current.di.apiKeyRequired
    message.isBlank() -> I18n.current.di.checkNotRun
    else -> message
}
