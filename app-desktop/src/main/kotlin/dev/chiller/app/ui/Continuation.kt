package dev.chiller.app.ui

import dev.chiller.contracts.AgentMessage

/**
 * The opening draft of a dialog that continues another one in a different folder: the first request, the latest
 * ones and the last answer, shortened. It is put into the composer, not sent, so the user sees and edits what the
 * model will get; nothing is summarized by a model and no request is spent on it.
 */
fun continuationDraft(title: String, folder: String?, messages: List<AgentMessage>): String {
    val requests = messages.filter { it.role == "user" }.mapNotNull { it.content?.trim()?.takeIf(String::isNotEmpty) }
    val answer = messages.lastOrNull { it.role == "assistant" && !it.content.isNullOrBlank() }?.content?.trim()
    fun String.short(limit: Int) = replace(Regex("\\s+"), " ").let { if (it.length <= limit) it else it.take(limit - 1).trimEnd() + "…" }
    return buildString {
        append("Продолжаем задачу из диалога «").append(title.short(80)).append("»")
        folder?.let { append(" (папка ").append(folderName(it)).append(")") }
        append(". Что было:\n")
        requests.firstOrNull()?.let { append("- Запрос: ").append(it.short(300)).append('\n') }
        requests.drop(1).takeLast(3).forEach { append("- Затем: ").append(it.short(200)).append('\n') }
        answer?.let { append("- Итог: ").append(it.short(800)).append('\n') }
        append("\nЧто сделать здесь: ")
    }
}
