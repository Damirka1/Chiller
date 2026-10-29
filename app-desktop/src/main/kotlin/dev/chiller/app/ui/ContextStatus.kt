package dev.chiller.app.ui

import dev.chiller.app.i18n.*
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
        Text(strings.run.contextShortened(state.droppedContextMessages, state.prunedContextResults), color = c.muted, fontSize = 11.sp)
        state.contextMemory?.let { memory ->
            ChillerControl(if (expanded) strings.run.hideMemory else strings.run.showMemory, { expanded = !expanded })
            if (expanded) SelectionContainer {
                Text(memoryDescription(memory), color = c.muted, fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
            }
        }
    }
}

internal fun memoryDescription(raw: String): String = runCatching {
    val data = Json.parseToJsonElement(raw).jsonObject
    val labels = linkedMapOf("detailedRequests" to I18n.current.run.memDetailedRequests, "latestPlanExcerpt" to I18n.current.run.memLatestPlan,
        "recentUserRequests" to I18n.current.run.memRecentRequests, "successfulFileTools" to I18n.current.run.memFiles, "recentAssistantNotes" to I18n.current.run.memNotes)
    buildString {
        appendLine(I18n.current.run.memIntro)
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
}.getOrElse { I18n.current.run.memUnavailable }
