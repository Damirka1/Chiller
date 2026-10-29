package dev.chiller.app.ui

import dev.chiller.app.i18n.*
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
        val s = I18n.current.screens
        append(s.draftIntro(title.short(80)))
        folder?.let { append(s.draftFolder(folderName(it))) }
        append(s.draftWhatWas)
        requests.firstOrNull()?.let { append(s.draftRequest).append(it.short(300)).append('\n') }
        requests.drop(1).takeLast(3).forEach { append(s.draftThen).append(it.short(200)).append('\n') }
        answer?.let { append(s.draftResult).append(it.short(800)).append('\n') }
        append(s.draftWhatHere)
    }
}
