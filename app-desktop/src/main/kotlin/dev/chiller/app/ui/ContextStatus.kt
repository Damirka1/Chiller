package dev.chiller.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chiller.contracts.RunState
import dev.chiller.design.*
import kotlinx.serialization.json.*

@Composable
internal fun ContextStatus(state: RunState) {
    val c = LocalChillerPalette.current
    var expanded by remember { mutableStateOf(false) }
    if (state.droppedContextMessages == 0 && state.prunedContextResults == 0) return
    Column {
        Text("Контекст сокращён: сообщений ${state.droppedContextMessages}, результатов ${state.prunedContextResults}.", color = c.muted, fontSize = 11.sp)
        state.contextMemory?.let { memory ->
            ChillerControl(if (expanded) "Скрыть память задачи" else "Что осталось в памяти", { expanded = !expanded })
            if (expanded) SelectionContainer {
                Text(memoryDescription(memory), color = c.muted, fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
            }
        }
    }
}

internal fun memoryDescription(raw: String): String = runCatching {
    val data = Json.parseToJsonElement(raw).jsonObject
    val labels = linkedMapOf("detailedRequests" to "Запросы и ограничения (выдержки)", "latestPlanExcerpt" to "Последний план (не подтверждение выполнения)",
        "recentUserRequests" to "Последние указания", "successfulFileTools" to "Файлы из журнала", "recentAssistantNotes" to "Заметки модели")
    buildString {
        appendLine("Это выдержки из истории, а не полная копия переписки. Исходные сообщения остаются в чате.")
        labels.forEach { (key, label) ->
            val value = data[key] ?: return@forEach
            val text = when (value) {
                is JsonPrimitive -> value.content
                is JsonArray -> value.joinToString("\n") { if (it is JsonPrimitive) it.content else it.toString() }
                else -> value.toString()
            }
            if (text.isNotBlank()) append("\n$label:\n$text\n")
        }
    }
}.getOrElse { "Память недоступна для просмотра. Исходная история сохранена в чате." }
