package dev.chiller.app.ui

import dev.chiller.contracts.*

fun modelLabel(id: String): String = when (id) {
    "deepseek-flash" -> "DeepSeek V4.1 Flash"
    else -> id.substringAfterLast('/').ifBlank { id }
}

fun authorLabel(message: AgentMessage): String = when {
    message.role == "user" -> "Вы"
    message.role != "assistant" -> "Chiller"
    message.origin?.kind == MessageOriginKind.OFFLINE -> "Chiller · offline"
    message.origin?.kind == MessageOriginKind.APPLICATION -> "Chiller"
    message.origin?.kind == MessageOriginKind.MODEL -> message.origin?.modelId?.let(::modelLabel) ?: "Ассистент"
    else -> "Ассистент"
}
