package dev.chiller.app.di

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
    "AUTHENTICATION_FAILED" -> "Провайдер отклонил ключ"
    "UNSUPPORTED" -> "Этот адрес не отдаёт список моделей — укажите ID вручную"
    "RATE_LIMITED" -> "Провайдер ограничил частоту запросов"
    "RESPONSE_TOO_LARGE" -> "Ответ со списком моделей слишком большой"
    "MALFORMED_RESPONSE" -> "Провайдер вернул неожиданный формат списка"
    "PROVIDER_TIMEOUT", "NETWORK" -> "Список моделей не получен: таймаут или сеть"
    "PROVIDER_HTTP_ERROR" -> "Провайдер не отдал список моделей"
    else -> (result.message ?: "Не удалось получить список моделей").let { message -> if (key.isNullOrBlank()) message else message.replace(key, "[СКРЫТО]") }.take(200)
}

/** The connection check's result in the user's words. */
internal fun localizeConnectionMessage(message: String): String = when {
    message == "Connection and model access verified" || message == "verified" -> "Подключение и доступ к модели подтверждены"
    message.startsWith("Authentication failed") -> "Провайдер отклонил API-ключ"
    message.startsWith("Rate limit reached") -> "Провайдер ограничил частоту запросов"
    message.startsWith("Model or endpoint not found") -> "Модель или адрес провайдера не найдены"
    message.startsWith("Provider server error") -> "Ошибка сервера провайдера"
    message.startsWith("Provider rejected the request") -> "Провайдер отклонил запрос"
    message.startsWith("Network error") -> "Сетевая ошибка или истекло время ожидания"
    message.startsWith("Invalid or unsupported endpoint") -> "Адрес провайдера недопустим или не поддерживается"
    message == "Model ID is required" -> "Укажите ID модели"
    message == "API key is required" -> "Укажите API-ключ"
    message.isBlank() -> "Проверка подключения не выполнена"
    else -> message
}
