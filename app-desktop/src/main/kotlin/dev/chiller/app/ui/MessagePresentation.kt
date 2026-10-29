package dev.chiller.app.ui

import dev.chiller.app.i18n.*
import dev.chiller.contracts.*

fun modelLabel(id: String): String = when (id) {
    "deepseek-flash" -> "DeepSeek V4.1 Flash"
    "chiller-demo" -> I18n.current.screens.demoScenario
    else -> id.substringAfterLast('/').ifBlank { id }
}

/** Model names the provider gave in its listing (`name` is an extension of the OpenAI /models format), by id. */
val LocalModelNames = androidx.compose.runtime.staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/** The provider's name for a model when it gave one, else a known label or the id. */
@androidx.compose.runtime.Composable
@androidx.compose.runtime.ReadOnlyComposable
fun modelName(id: String): String = LocalModelNames.current[id] ?: modelLabel(id)

fun authorLabel(message: AgentMessage): String = when {
    message.role == "user" -> I18n.current.screens.you
    message.role != "assistant" -> "Chiller"
    message.origin?.kind == MessageOriginKind.OFFLINE -> "Chiller · offline"
    message.origin?.kind == MessageOriginKind.APPLICATION -> "Chiller"
    message.origin?.kind == MessageOriginKind.MODEL -> message.origin?.modelId?.let(::modelLabel) ?: I18n.current.screens.assistant
    else -> I18n.current.screens.assistant
}
