package dev.chiller.app.ui

import dev.chiller.contracts.*

fun modelLabel(id: String): String = when (id) {
    "deepseek-flash" -> "DeepSeek V4.1 Flash"
    "chiller-demo" -> "Демо-сценарий"
    else -> id.substringAfterLast('/').ifBlank { id }
}

/** Model names the provider gave in its listing (`name` is an extension of the OpenAI /models format), by id. */
val LocalModelNames = androidx.compose.runtime.staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/** The provider's name for a model when it gave one, else a known label or the id. */
@androidx.compose.runtime.Composable
@androidx.compose.runtime.ReadOnlyComposable
fun modelName(id: String): String = LocalModelNames.current[id] ?: modelLabel(id)

fun authorLabel(message: AgentMessage): String = when {
    message.role == "user" -> "Вы"
    message.role != "assistant" -> "Chiller"
    message.origin?.kind == MessageOriginKind.OFFLINE -> "Chiller · offline"
    message.origin?.kind == MessageOriginKind.APPLICATION -> "Chiller"
    message.origin?.kind == MessageOriginKind.MODEL -> message.origin?.modelId?.let(::modelLabel) ?: "Ассистент"
    else -> "Ассистент"
}
