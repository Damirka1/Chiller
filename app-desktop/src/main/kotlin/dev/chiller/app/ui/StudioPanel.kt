package dev.chiller.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chiller.contracts.*
import dev.chiller.design.*

/** One file in the Changes tab: every change to it in this dialog, oldest first. */
data class FileChanges(val path: String, val changes: List<Activity>) {
    val kind: String get() = when {
        changes.any { it.name == "delete_file" && it.status == "completed" } -> "deleted"
        changes.first().name in setOf("create_file", "generate_image") -> "created"
        changes.any { it.name == "move_file" } -> "moved"
        else -> "edited"
    }
    val stats: Pair<Int, Int> get() = changes.mapNotNull { it.diff }.map(::diffStats).fold(0 to 0) { a, b -> a.first + b.first to a.second + b.second }
}

private val FILE_TOOLS = setOf("edit_file", "replace_text", "append_file", "create_file", "move_file", "delete_file", "generate_image")

/** Successful file changes grouped by path (a move is listed under its new name), in first-touched order. */
fun groupFileChanges(activities: List<Activity>): List<FileChanges> =
    activities.filter { it.name in FILE_TOOLS && it.status == "completed" && it.target.isNotBlank() }
        .groupBy { it.target.substringAfter(" → ") }
        .map { (path, list) -> FileChanges(path, list) }

/**
 * Studio's right panel: what changed (files and diffs with undo), helper agents with live progress,
 * and the run's metrics. Tabs carry counts; the agents tab pulses while a helper works.
 */
@Composable
internal fun StudioPanel(
    run: RunState, agents: List<SubagentSnapshot>, onUndo: (String) -> Unit, canUndo: Boolean, reduced: Boolean,
    onCancelAgent: (String) -> Unit, onOpenTrace: (() -> Unit)?, modifier: Modifier, initialTab: String = "changes",
) {
    val c = LocalChillerPalette.current
    val files = groupFileChanges(run.activities)
    var tab by remember { mutableStateOf(initialTab) }
    // A helper starting is worth seeing: switch to the agents tab once per new agent.
    val agentIds = agents.map { it.id }
    var seenAgents by remember { mutableStateOf(agentIds.toSet()) }
    LaunchedEffect(agentIds) { if ((agentIds - seenAgents).isNotEmpty()) tab = "agents"; seenAgents = agentIds.toSet() }
    val running = agents.count { !it.status.isTerminal }
    Row(modifier) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
        Column(Modifier.weight(1f).fillMaxHeight().background(c.canvas)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).clip(RoundedCornerShape(9.dp)).background(c.sidebar).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                PanelTab("Изменения", files.size.takeIf { it > 0 }?.toString(), tab == "changes", Modifier.weight(1f)) { tab = "changes" }
                PanelTab("Агенты", agents.size.takeIf { it > 0 }?.toString(), tab == "agents", Modifier.weight(1f), live = running > 0) { tab = "agents" }
                PanelTab("Прогон", null, tab == "run", Modifier.weight(1f), live = run.status in setOf("preparing", "streaming", "executing")) { tab = "run" }
            }
            Box(Modifier.height(1.dp).fillMaxWidth().background(c.border.copy(alpha = .7f)))
            Crossfade(tab, animationSpec = tween(if (reduced) 0 else 160), label = "studio-tab") { current ->
                when (current) {
                    "changes" -> ChangesTab(files, onUndo, canUndo, Modifier.fillMaxSize())
                    "agents" -> AgentsTab(agents, onCancelAgent, Modifier.fillMaxSize())
                    else -> RunPanel(run, Modifier.fillMaxSize(), onOpenTrace, agents)
                }
            }
        }
    }
}

@Composable
private fun PanelTab(label: String, badge: String?, selected: Boolean, modifier: Modifier, live: Boolean = false, onClick: () -> Unit) {
    val c = LocalChillerPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier.height(30.dp).clip(RoundedCornerShape(7.dp)).background(when { selected -> c.surface; hovered -> c.hover; else -> Color.Transparent })
            .then(if (selected) Modifier.border(1.dp, c.border, RoundedCornerShape(7.dp)) else Modifier)
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) { Pulse(c.accent); Spacer(Modifier.width(6.dp)) }
        Text(label, color = if (selected) c.text else c.muted, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
        badge?.let { Spacer(Modifier.width(5.dp)); Text(it, color = c.faint, fontSize = 11.sp) }
    }
}

@Composable
private fun ChangesTab(files: List<FileChanges>, onUndo: (String) -> Unit, canUndo: Boolean, modifier: Modifier) {
    val c = LocalChillerPalette.current
    if (files.isEmpty()) { EmptyState(Icons.Outlined.Difference, "Пока без изменений", "Здесь появятся файлы, которые агент создал, изменил или удалил, с диффом и отменой.", modifier); return }
    var selected by remember { mutableStateOf<String?>(null) }
    var expandedDiff by remember { mutableStateOf<Pair<String, String>?>(null) }
    val current = files.firstOrNull { it.path == selected } ?: files.last()
    val (added, removed) = files.map { it.stats }.fold(0 to 0) { a, b -> a.first + b.first to a.second + b.second }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${files.size} ${plural(files.size, "файл", "файла", "файлов")}", color = c.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            DiffStat(added, removed)
        }
        Column(Modifier.heightIn(max = 236.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            files.forEach { file -> FileRow(file, file.path == current.path) { selected = file.path } }
        }
        Box(Modifier.padding(top = 6.dp).height(1.dp).fillMaxWidth().background(c.border.copy(alpha = .7f)))
        // The diff viewer for the selected file: each change with its own undo.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current.path.substringAfterLast('/'), color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if ('/' in current.path) Text("  " + current.path.substringBeforeLast('/'), color = c.faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            current.changes.forEachIndexed { index, change ->
                val shape = RoundedCornerShape(9.dp)
                Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, c.border, shape)) {
                    Row(Modifier.fillMaxWidth().background(c.sidebar.copy(alpha = .6f)).padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${toolLabel(change.name)}", color = c.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        change.diff?.let { val (a, r) = diffStats(it); DiffStat(a, r); Spacer(Modifier.width(6.dp)) }
                        change.diff?.let { ChillerIconButton(Icons.Outlined.OpenInFull, "Развернуть дифф", { expandedDiff = current.path to it }, Modifier.size(26.dp)) }
                        val id = if (canUndo) changeId(change.output) else null
                        if (id != null) ChillerControl("Отменить", { onUndo(id) }, icon = Icons.AutoMirrored.Outlined.Undo)
                        else Spacer(Modifier.height(28.dp))
                    }
                    // A new or changed picture shows itself rather than "Binary files differ".
                    val picture = change.diff?.takeIf { "Binary files differ" in it && current.path.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg") }
                    if (picture != null && current.kind != "deleted") ChangedImage(current.path) { Diff(picture) }
                    else change.diff?.let { Diff(it) } ?: Text("Без диффа", color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(10.dp))
                }
            }
        }
    }
    expandedDiff?.let { (path, diff) ->
        Dialog(onDismissRequest = { expandedDiff = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxWidth(.88f).fillMaxHeight(.86f).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(14.dp))) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(path, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    ChillerIconButton(Icons.Outlined.Close, "Закрыть дифф", { expandedDiff = null })
                }
                Box(Modifier.height(1.dp).fillMaxWidth().background(c.border))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) { Diff(diff) }
            }
        }
    }
}

@Composable
private fun FileRow(file: FileChanges, selected: Boolean, onClick: () -> Unit) {
    val c = LocalChillerPalette.current
    val (icon, tint, tag) = when (file.kind) {
        "created" -> Triple(Icons.Outlined.NoteAdd, c.positive, "новый")
        "deleted" -> Triple(Icons.Outlined.DeleteOutline, c.negative, "удалён")
        "moved" -> Triple(Icons.Outlined.DriveFileMove, c.muted, "перемещён")
        else -> Triple(Icons.Outlined.Edit, c.muted, null)
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(7.dp)).background(when { selected -> c.selected; hovered -> c.hover; else -> Color.Transparent })
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand).clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = tint)
        Spacer(Modifier.width(9.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(file.path.substringAfterLast('/'), color = if (file.kind == "deleted") c.muted else c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if ('/' in file.path) Text(" " + file.path.substringBeforeLast('/'), color = c.faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        tag?.let { Text(it, color = tint, fontSize = 10.sp, modifier = Modifier.padding(end = 6.dp)) }
        if (file.changes.size > 1) Text("×${file.changes.size}", color = c.faint, fontSize = 10.sp, modifier = Modifier.padding(end = 6.dp))
        val (a, r) = file.stats
        DiffStat(a, r)
    }
}

@Composable
private fun AgentsTab(agents: List<SubagentSnapshot>, onCancel: (String) -> Unit, modifier: Modifier) {
    if (agents.isEmpty()) {
        EmptyState(Icons.Outlined.Hub, "Помощников пока нет",
            "Основной агент может запустить до трёх помощников только на чтение: они параллельно ищут и читают код и присылают отчёт. Прогресс и итоги появятся здесь.", modifier)
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(agents.asReversed(), key = { it.id }) { AgentCard(it, onCancel) }
    }
}

@Composable
private fun AgentCard(agent: SubagentSnapshot, onCancel: (String) -> Unit) {
    val c = LocalChillerPalette.current
    var open by remember(agent.id) { mutableStateOf(false) }
    val (label, color) = when (agent.status) {
        SubagentStatus.QUEUED -> "в очереди" to c.muted
        SubagentStatus.RUNNING -> "работает" to c.accent
        SubagentStatus.COMPLETED -> "готово" to c.positive
        SubagentStatus.FAILED -> "ошибка" to c.negative
        SubagentStatus.CANCELLED -> "остановлен" to c.muted
        SubagentStatus.INTERRUPTED -> "прерван" to c.muted
    }
    val shape = RoundedCornerShape(11.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, if (agent.status == SubagentStatus.RUNNING) c.accent.copy(alpha = .45f) else c.border, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                if (agent.status == SubagentStatus.RUNNING) Pulse(color) else Icon(Icons.Outlined.SmartToy, null, Modifier.size(14.dp), tint = color)
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(agent.taskId, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = ChillerFonts.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(label, color = color, fontSize = 11.sp)
            }
            if (!agent.status.isTerminal) ChillerControl("Остановить", { onCancel(agent.id) }, icon = Icons.Outlined.Stop)
        }
        Text(agent.goal, color = c.text, fontSize = 12.sp, lineHeight = 18.sp, maxLines = if (open) 12 else 3, overflow = TextOverflow.Ellipsis)
        // Round progress: filled segments for finished rounds, a live one for the current round.
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(agent.maxRounds) { i ->
                val done = i < agent.rounds - (if (agent.status == SubagentStatus.RUNNING) 1 else 0)
                val now = agent.status == SubagentStatus.RUNNING && i == agent.rounds - 1
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(when { done -> color.copy(alpha = .75f); now -> color.copy(alpha = .4f); else -> c.border }))
            }
        }
        Text(listOfNotNull("раунд ${agent.rounds}/${agent.maxRounds}", "${agent.toolCalls} ${plural(agent.toolCalls, "действие", "действия", "действий")}",
            agent.failedToolCalls.takeIf { it > 0 }?.let { "ошибок $it" }, agent.readPaths.size.takeIf { it > 0 }?.let { "прочитано файлов $it" },
            agent.acceptedMessages.takeIf { it > 0 }?.let { "сообщений ${agent.processedMessages}/$it" },
            agent.usage.promptTokens?.let { "${compactTokens(it)} → ${compactTokens(agent.usage.completionTokens)} ток." }).joinToString(" · "), color = c.faint, fontSize = 11.sp)
        if (agent.status == SubagentStatus.RUNNING && agent.activity.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Bolt, null, Modifier.size(12.dp), tint = c.muted)
            Spacer(Modifier.width(5.dp))
            Text(agent.activity, color = c.muted, fontSize = 11.sp, fontFamily = ChillerFonts.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        agent.error?.takeIf { agent.status != SubagentStatus.COMPLETED }?.let { Text(it, color = if (agent.status == SubagentStatus.FAILED) c.negative else c.muted, fontSize = 11.sp, lineHeight = 16.sp) }
        agent.result?.let { result ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.sidebar.copy(alpha = .7f)).padding(10.dp)) {
                Text("Отчёт", color = c.faint, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
                AnimatedContent(open, label = "report") { expanded ->
                    if (expanded) MarkdownText(result) else Text(inlineMarkdownPlain(result.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()), color = c.text, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (agent.result != null || agent.goal.length > 160) ChillerControl(if (open) "Свернуть" else "Подробнее", { open = !open }, icon = if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown)
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier) {
    val c = LocalChillerPalette.current
    Box(modifier.padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(c.sidebar), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(19.dp), tint = c.muted) }
            Spacer(Modifier.height(12.dp))
            Text(title, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(text, color = c.faint, fontSize = 12.sp, lineHeight = 18.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** The picture a change produced, read like any presented file; [fallback] when it cannot be shown. */
@Composable
private fun ChangedImage(path: String, fallback: @Composable () -> Unit) {
    val c = LocalChillerPalette.current
    val preview = LocalFilePreview.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path, preview) {
        value = preview?.let { read -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { read(path)?.let(::decodePreviewImage) }.getOrNull() } }
    }
    val image = bitmap ?: return fallback()
    Box(Modifier.fillMaxWidth().background(c.sidebar.copy(alpha = .4f)).padding(10.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Image(image, path.substringAfterLast('/'), contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.heightIn(max = 220.dp).clip(RoundedCornerShape(6.dp)))
    }
    Text("${image.width} × ${image.height}", color = c.faint, fontSize = 10.sp, modifier = Modifier.padding(start = 10.dp, bottom = 8.dp))
}
