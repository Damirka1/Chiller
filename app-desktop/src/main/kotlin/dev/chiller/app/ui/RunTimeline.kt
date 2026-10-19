package dev.chiller.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chiller.contracts.Activity
import dev.chiller.contracts.FilePresentation
import dev.chiller.contracts.RunState
import dev.chiller.contracts.plus
import dev.chiller.design.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

private val FAILED = setOf("failed", "error", "conflict")
private val UNCERTAIN = setOf("interrupted", "cancelled")
private val RUNNING = setOf("running", "executing", "preparing")
private val SUCCESS = setOf("completed", "applied", "exited")
private val FILE_EFFECTS = setOf("edit_file", "replace_text", "create_file", "move_file", "delete_file", "generate_image")

/** Short user-facing state for the current run, derived only from engine state. */
internal fun runProgressLabel(state: RunState): String? = when {
    state.recoveryNotice != null && state.status == "streaming" -> state.recoveryNotice
    state.question != null -> "Ожидает вашего ответа"
    state.status == "preparing" -> "Подготавливает запрос…"
    state.status == "streaming" && state.streamingText.isBlank() -> "Ждёт ответа модели…"
    state.status == "streaming" -> "Модель пишет…"
    state.status == "executing" -> state.activities.lastOrNull { it.status == "running" }?.let {
        "${toolLabel(it.name)}${if (it.target.isNotBlank()) " · ${it.target}" else ""}"
    } ?: "Выполняет действие…"
    state.status == "completed" -> "Ответ готов"
    state.status == "cancelled" -> "Задача остановлена"
    state.status == "interrupted" -> "Прогон прерван"
    else -> null
}

internal fun presentedFileFromOutput(output: String): FilePresentation? = runCatching {
    val file = Json.parseToJsonElement(output).jsonObject["data"]?.jsonObject?.get("file")?.jsonObject ?: return null
    FilePresentation(
        path = file["path"]?.jsonPrimitive?.contentOrNull ?: return null,
        name = file["name"]?.jsonPrimitive?.contentOrNull ?: return null,
        mimeType = file["mimeType"]?.jsonPrimitive?.contentOrNull ?: return null,
        sizeBytes = file["sizeBytes"]?.jsonPrimitive?.longOrNull ?: return null,
    )
}.getOrNull()

internal fun compactToolActivities(activities: List<Activity>, expanded: Boolean): List<Activity> {
    if (expanded || activities.size <= 1) return activities
    return activities.filter { it.status in FAILED || it.status in UNCERTAIN || it.name in setOf("ask_user", "request_approval") }.distinctBy { it.id }
}

/** One card per assistant turn: a summary header, then compact rows. Failures stay visible when collapsed. */
@Composable
internal fun ToolGroup(activities: List<Activity>, reduced: Boolean, onUndo: (String) -> Unit, canUndo: Boolean, onPreviewFile: suspend (String) -> ByteArray? = { null }, onRevealFile: (String) -> Unit = {}) {
    if (activities.isEmpty()) return
    val c = LocalChillerPalette.current
    val shape = RoundedCornerShape(10.dp)
    val failed = activities.filter { it.status in FAILED }
    val running = activities.any { it.status in RUNNING }
    val uncertain = activities.any { it.status in UNCERTAIN }
    val questions = activities.filter { it.name in setOf("ask_user", "request_approval") }
    var open by remember(activities.first().id) { mutableStateOf(running || questions.isNotEmpty()) }
    LaunchedEffect(activities.map { it.status }, questions.map { it.id }) {
        open = running || questions.isNotEmpty()
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, c.border, shape)) {
        if (activities.size > 1) {
            HeaderRow(open, { open = !open }) {
                if (running) Pulse(c.accent) else Icon(if (uncertain) Icons.Outlined.Info else if (failed.isEmpty()) Icons.Outlined.Check else Icons.Outlined.ErrorOutline, null, Modifier.size(14.dp), tint = if (uncertain) c.muted else if (failed.isEmpty()) c.positive else c.negative)
                Spacer(Modifier.width(9.dp))
                Text(activityGroupLabel(activities), color = c.text, fontSize = 12.sp, lineHeight = 18.sp)
                val (added, removed) = activities.mapNotNull { it.diff }.map(::diffStats).fold(0 to 0) { a, b -> a.first + b.first to a.second + b.second }
                if (added + removed > 0) { Spacer(Modifier.width(8.dp)); DiffStat(added, removed) }
                if (failed.isNotEmpty()) Text("  ·  ошибок: ${failed.size}", color = c.negative, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        val visible = compactToolActivities(activities, open)
        visible.forEachIndexed { index, activity ->
            if (index > 0 || activities.size > 1) Box(Modifier.fillMaxWidth().height(1.dp).background(c.border.copy(alpha = .6f)))
            ToolRow(activity, reduced, onUndo, canUndo, framed = false, onPreviewFile = onPreviewFile, onRevealFile = onRevealFile, showPresentation = false)
        }
      }
      presentedFiles(activities).forEach { FilePresentationCard(it, onPreviewFile, onRevealFile) }
    }
}

/** Results stay visible independently of disclosure state. Latest presentation wins per path. */
internal fun presentedFiles(activities: List<Activity>): List<FilePresentation> = activities
    .filter { it.status in SUCCESS && it.name in setOf("show_file", "capture_window", "generate_image") }
    .mapNotNull { presentedFileFromOutput(it.output) }
    .asReversed().distinctBy { it.path }.asReversed()

@Composable
private fun HeaderRow(open: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val c = LocalChillerPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().background(if (hovered) c.hover.copy(alpha = .6f) else c.sidebar.copy(alpha = .55f)).hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        Spacer(Modifier.weight(1f))
        Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, if (open) "Свернуть" else "Развернуть", Modifier.size(15.dp), tint = c.faint)
    }
}

@Composable
internal fun ToolRow(activity: Activity, reduced: Boolean, onUndo: (String) -> Unit, canUndo: Boolean, framed: Boolean = true, onPreviewFile: suspend (String) -> ByteArray? = { null }, onRevealFile: (String) -> Unit = {}, showPresentation: Boolean = true) {
    val c = LocalChillerPalette.current
    val failed = activity.status in FAILED
    val running = activity.status in RUNNING
    val success = activity.status in SUCCESS
    val presentedFile = if (success && activity.name in setOf("show_file", "capture_window", "generate_image")) presentedFileFromOutput(activity.output) else null
    var open by remember(activity.id) { mutableStateOf(failed) }
    LaunchedEffect(activity.status) { if (failed) open = true }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().then(if (framed) Modifier.clip(shape).border(1.dp, c.border, shape) else Modifier)) {
        Row(
            Modifier.fillMaxWidth().background(if (hovered) c.hover.copy(alpha = .5f) else Color.Transparent).hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button) { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                when {
                    running -> Pulse(c.accent)
                    else -> Icon(toolIcon(activity.name), null, Modifier.size(15.dp), tint = if (failed) c.negative else c.muted)
                }
            }
            Spacer(Modifier.width(9.dp))
            Text(toolLabel(activity.name), color = if (failed) c.negative else c.muted, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.width(8.dp))
            Text(activity.target, color = c.text, fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            activity.diff?.let { val (a, r) = diffStats(it); if (a + r > 0) { Spacer(Modifier.width(8.dp)); DiffStat(a, r) } }
            Spacer(Modifier.width(8.dp))
            when {
                failed -> StatusTag("ошибка", c.negative)
                running -> StatusTag("выполняется", c.muted)
                activity.status in UNCERTAIN -> StatusTag("прервано", c.muted)
                !success -> StatusTag(activity.status, c.muted)
            }
            Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, Modifier.size(15.dp), tint = c.faint)
        }
        if (showPresentation) presentedFile?.let { FilePresentationCard(it, onPreviewFile, onRevealFile) }
        AnimatedVisibility(open, enter = if (reduced) EnterTransition.None else expandVertically(tween(180)) + fadeIn(tween(120)), exit = if (reduced) ExitTransition.None else shrinkVertically(tween(160)) + fadeOut(tween(100))) {
            Column(Modifier.fillMaxWidth().background(c.sidebar.copy(alpha = .5f))) {
                if (activity.diff != null) Diff(activity.diff!!)
                else if (activity.output.isNotBlank() && presentedFile == null) SelectionContainer {
                    Text(prettyOutput(activity.output), color = c.muted, fontSize = 11.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().padding(12.dp), maxLines = 24, overflow = TextOverflow.Ellipsis)
                }
                else Text("Без вывода", color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(12.dp))
                val id = if (canUndo && success && activity.name in FILE_EFFECTS) changeId(activity.output) else null
                if (id != null) Box(Modifier.fillMaxWidth().padding(5.dp), contentAlignment = Alignment.CenterEnd) {
                    ChillerControl("Отменить правку", { onUndo(id) }, icon = Icons.AutoMirrored.Outlined.Undo)
                }
            }
        }
    }
}

internal fun decodePreviewImage(bytes: ByteArray): ImageBitmap? = runCatching {
    require(bytes.size <= 4 * 1024 * 1024) { "Preview exceeds 4 MiB" }
    val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
    input.use {
        val reader = ImageIO.getImageReaders(it).asSequence().firstOrNull() ?: return null
        try {
            reader.input = it
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            require(width > 0 && height > 0 && width.toLong() * height <= 16_000_000L) { "Preview dimensions exceed limit" }
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        } finally { reader.dispose() }
    }
}.getOrNull()

internal fun formatPresentedSize(sizeBytes: Long): String = when {
    sizeBytes < 1024 -> "$sizeBytes Б"
    sizeBytes < 1024 * 1024 -> "%.1f КБ".format(java.util.Locale.ROOT, sizeBytes / 1024.0).replace('.', ',')
    else -> "%.1f МБ".format(java.util.Locale.ROOT, sizeBytes / (1024.0 * 1024.0)).replace('.', ',')
}

@Composable
private fun StatusTag(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(5.dp)).background(color.copy(alpha = .1f)).padding(horizontal = 6.dp, vertical = 1.dp))
}

@Composable
internal fun DiffStat(added: Int, removed: Int) {
    val c = LocalChillerPalette.current
    Row {
        if (added > 0) Text("+$added", color = c.positive, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
        if (added > 0 && removed > 0) Spacer(Modifier.width(5.dp))
        if (removed > 0) Text("−$removed", color = c.negative, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
    }
}

/** A small breathing dot for work in progress; static when reduced motion is on. */
@Composable
internal fun Pulse(color: Color) {
    val reduced = LocalChillerReducedMotion.current
    val alpha = if (reduced) 1f else rememberInfiniteTransition(label = "pulse").animateFloat(.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha").value
    Box(Modifier.size(8.dp).alpha(alpha).clip(CircleShape).background(color))
}

@Composable
internal fun Diff(diff: String) {
    val c = LocalChillerPalette.current
    SelectionContainer {
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(c.canvas).padding(vertical = 7.dp)) {
            diff.lineSequence().take(500).forEach { line ->
                val added = line.startsWith('+') && !line.startsWith("+++ ")
                val removed = line.startsWith('-') && !line.startsWith("--- ")
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 19.sp, letterSpacing = 0.sp,
                    color = when { added -> c.positive; removed -> c.negative; else -> c.muted },
                    modifier = Modifier.fillMaxWidth().background(when { added -> c.added; removed -> c.removed; else -> c.canvas }).padding(horizontal = 12.dp))
            }
        }
    }
}

/** Quiet footer under a finished run, built from the run's own counters. */
@Composable
internal fun RunSummaryLine(state: RunState) {
    val c = LocalChillerPalette.current
    val parts = runSummaryParts(state)
    if (parts.isEmpty()) return
    Row(Modifier.padding(start = 34.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Insights, null, Modifier.size(13.dp), tint = c.faint)
        Spacer(Modifier.width(7.dp))
        Text(parts.joinToString("  ·  "), color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

/** Thin indeterminate bar for an active run, drawn under the toolbar. */
@Composable
internal fun RunProgressBar(active: Boolean) {
    val c = LocalChillerPalette.current
    val reduced = LocalChillerReducedMotion.current
    // The segment starts left of the bar; without clipping it is drawn over the sidebar and the window title.
    Box(Modifier.fillMaxWidth().height(2.dp).clipToBounds()) {
        if (!active) return@Box
        if (reduced) { Box(Modifier.fillMaxSize().background(c.accent.copy(alpha = .5f))); return@Box }
        val shift by rememberInfiniteTransition(label = "progress").animateFloat(-.35f, 1f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)), label = "shift")
        Layout({ Box(Modifier.background(c.accent.copy(alpha = .75f))) }, Modifier.fillMaxSize()) { measurables, constraints ->
            val width = (constraints.maxWidth * .35f).toInt()
            val bar = measurables[0].measure(constraints.copy(minWidth = width, maxWidth = width))
            layout(constraints.maxWidth, constraints.maxHeight) { bar.place((constraints.maxWidth * shift).toInt(), 0) }
        }
    }
}

/** The model's latest update_plan call in this session, read from its own tool-call arguments. */
internal fun latestPlan(state: RunState): List<Pair<String, String>>? {
    val call = state.messages.asReversed().asSequence().flatMap { it.toolCalls.asReversed().asSequence() }.firstOrNull { it.name == "update_plan" } ?: return null
    return runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(call.arguments).let { it as kotlinx.serialization.json.JsonObject }["steps"]!!
            .let { it as kotlinx.serialization.json.JsonArray }.map { item ->
                val o = item as kotlinx.serialization.json.JsonObject
                (o["step"] as kotlinx.serialization.json.JsonPrimitive).content to (o["status"] as kotlinx.serialization.json.JsonPrimitive).content
            }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

internal fun runSummaryParts(state: RunState): List<String> {
    if (state.modelRounds == 0) return emptyList()
    return buildList {
        add("${state.modelRounds} ${plural(state.modelRounds, "раунд", "раунда", "раундов")}")
        add("${state.toolCalls} ${plural(state.toolCalls, "действие", "действия", "действий")}" + if (state.failedToolCalls > 0) " (ошибок: ${state.failedToolCalls})" else "")
        state.runDurationMs?.let { add(formatDuration(it)) }
        val usage = state.usage
        if (usage.promptTokens != null || usage.completionTokens != null) add("${compactTokens(usage.promptTokens)} → ${compactTokens(usage.completionTokens)} токенов")
        if (state.usageIncomplete) add("расход известен частично")
        val prompt = usage.promptTokens; val cached = usage.cachedPromptTokens
        if (prompt != null && cached != null && prompt > 0) add("кэш ${(cached * 100 / prompt)}%")
    }
}


/** Count each process once, using its latest observed terminal result in the current user turn. */
internal fun latestFailedCommands(state: RunState): Int {
    val messages = state.messages.drop((state.messages.indexOfLast { it.role == "user" } + 1).coerceAtLeast(0))
    val calls = messages.flatMap { it.toolCalls }.associate { it.id to it.name }
    val outcomes = linkedMapOf<String, Boolean>()
    for (message in messages) {
        if (message.role != "tool" || calls[message.toolCallId] !in setOf("run_process", "read_process", "stop_process")) continue
        runCatching {
            val result = Json.parseToJsonElement(message.content.orEmpty()).jsonObject
            val data = result["data"]?.jsonObject ?: return@runCatching
            val id = data["processId"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
            val status = data["state"]?.jsonPrimitive?.contentOrNull
            val exit = data["exitCode"]?.jsonPrimitive?.longOrNull
            if (status == "timed_out") outcomes[id] = true
            else if (status == "exited" && exit != null) outcomes[id] = exit != 0L
        }
    }
    return outcomes.values.count { it }
}

internal fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> "$ms мс"
    ms < 60_000 -> "%.1f с".format(java.util.Locale.ROOT, ms / 1000.0).replace('.', ',')
    else -> "${ms / 60_000} мин ${ms % 60_000 / 1000} с"
}

internal fun compactTokens(value: Long?): String = when {
    value == null -> "?"
    value >= 10_000 -> "${(value + 500) / 1000}k"
    value >= 1_000 -> "%.1fk".format(java.util.Locale.ROOT, value / 1000.0).replace('.', ',')
    else -> value.toString()
}

internal fun diffStats(diff: String): Pair<Int, Int> {
    var added = 0; var removed = 0
    diff.lineSequence().forEach { line ->
        if (line.startsWith('+') && !line.startsWith("+++ ")) added++
        else if (line.startsWith('-') && !line.startsWith("--- ")) removed++
    }
    return added to removed
}

private fun toolIcon(name: String): ImageVector = when (name) {
    "web_search", "web_fetch" -> Icons.Outlined.Language
    "capture_window", "list_capture_targets" -> Icons.Outlined.PhotoCamera
    "create_directory" -> Icons.Outlined.CreateNewFolder
    "show_file" -> Icons.Outlined.InsertDriveFile
    "generate_image" -> Icons.Outlined.Image
    "git_status", "git_diff", "git_log", "git_show", "find_symbols", "read_symbol" -> Icons.Outlined.Code
    "read_diagnostics" -> Icons.Outlined.Terminal
    "read_file", "read_artifact" -> Icons.Outlined.Description
    "list_files" -> Icons.Outlined.FolderOpen
    "search_text" -> Icons.Outlined.Search
    "edit_file", "replace_text" -> Icons.Outlined.Edit
    "update_plan" -> Icons.Outlined.Checklist
    "spawn_agent", "list_agents", "agent_result" -> Icons.Outlined.SmartToy
    "wait_agents" -> Icons.Outlined.HourglassEmpty
    "message_agent" -> Icons.Outlined.Send
    "cancel_agent" -> Icons.Outlined.Stop
    "create_file" -> Icons.Outlined.NoteAdd
    "move_file" -> Icons.Outlined.DriveFileMove
    "delete_file" -> Icons.Outlined.DeleteOutline
    "run_process", "read_process", "stop_process" -> Icons.Outlined.Terminal
    "ask_user" -> Icons.Outlined.HelpOutline
    "undo" -> Icons.Outlined.History
    else -> Icons.Outlined.Build
}

/** Studio's run tab: the counters the engine reports for the latest run, and its last visible reasoning. */
@Composable
internal fun RunPanel(state: RunState, modifier: Modifier, onOpenTrace: (() -> Unit)? = null, agents: List<dev.chiller.contracts.SubagentSnapshot> = emptyList()) {
    val c = LocalChillerPalette.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.modelRounds == 0 && state.status == "idle") {
            Text("Метрики появятся после первого запуска в этом диалоге.", color = c.muted, fontSize = 12.sp, lineHeight = 18.sp)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.status in RUNNING || state.status == "streaming") { Pulse(c.accent); Spacer(Modifier.width(8.dp)) }
            Text(runStatusLabel(state.status), color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            (state.activeModelId)?.let { Text(modelLabel(it), color = c.faint, fontSize = 11.sp) }
        }
        val usage = state.usage
        if (state.usageIncomplete) Text("Провайдер сообщил расход не для всех запросов. Значения ниже неполные.", color = c.muted, fontSize = 12.sp)
        val cacheShare = usage.promptTokens?.takeIf { it > 0 }?.let { p -> usage.cachedPromptTokens?.let { "${it * 100 / p}%" } }
        MetricGrid(listOf(
            "Раунды" to state.modelRounds.toString(),
            "Действия" to state.toolCalls.toString(),
            "Ошибки инструментов" to state.failedToolCalls.toString(),
            "Неуспешные команды" to latestFailedCommands(state).toString(),
            "Время" to (state.runDurationMs?.let(::formatDuration) ?: "—"),
            "Вход, всего ток." to compactTokens(usage.promptTokens).replace("?", "—"),
            "Выход, всего ток." to compactTokens(usage.completionTokens).replace("?", "—"),
            "Из кэша" to (cacheShare ?: "—"),
            "Контекст ≈" to (state.contextEstimatedTokens?.let { compactTokens(it.toLong()) } ?: "—"),
        ))
        if (agents.isNotEmpty()) {
            val total = agents.fold(dev.chiller.contracts.TokenUsage()) { acc, a -> acc + a.usage }
            MetricGrid(listOf(
                "Помощники" to "${agents.count { it.status == dev.chiller.contracts.SubagentStatus.COMPLETED }}/${agents.size}",
                "Их токены" to "${compactTokens(total.promptTokens).replace("?", "—")} → ${compactTokens(total.completionTokens).replace("?", "—")}",
            ))
        }
        Text("Токены суммируются за все раунды последнего прогона; «—» — значение не сообщено. Контекст — текущая оценка по символам / 4. Неуспешные команды — процессы с ненулевым кодом выхода или тайм-аутом; ошибка запуска учитывается среди ошибок инструментов.", color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        onOpenTrace?.let { ChillerControl("Трассировка запросов", it, icon = Icons.Outlined.ReceiptLong, style = ControlStyle.OUTLINE) }
        latestPlan(state)?.let { steps ->
            Text("План модели · ${steps.count { it.second == "done" }}/${steps.size}", color = c.muted, fontSize = 12.sp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                steps.forEach { (step, status) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                            when (status) {
                                "done" -> Icon(Icons.Outlined.CheckCircle, null, Modifier.size(15.dp), tint = c.positive)
                                "in_progress" -> Pulse(c.accent)
                                else -> Icon(Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(15.dp), tint = c.faint)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(step, color = if (status == "done") c.muted else c.text, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
        if (state.droppedContextMessages > 0) Text("Из контекста убрано старых сообщений: ${state.droppedContextMessages}", color = c.muted, fontSize = 12.sp, lineHeight = 18.sp)
        if (state.reasoning.isNotBlank()) {
            Text("Рассуждения последнего раунда", color = c.muted, fontSize = 12.sp)
            SelectionContainer {
                Text(state.reasoning, color = c.muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.sidebar).padding(12.dp))
            }
        }
    }
}

@Composable
private fun MetricGrid(items: List<Pair<String, String>>) {
    val c = LocalChillerPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).border(1.dp, c.border, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text(label, color = c.faint, fontSize = 11.sp, lineHeight = 15.sp)
                        Text(value, color = c.text, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

private fun runStatusLabel(status: String) = when (status) {
    "preparing" -> "Подготовка"; "streaming" -> "Модель отвечает"; "executing" -> "Выполняются действия"
    "completed" -> "Завершён"; "failed" -> "Ошибка"; "cancelled" -> "Остановлен"; "interrupted" -> "Прерван"; else -> "Последний прогон"
}

internal fun runErrorDescription(error: String): String = when {
    error.contains("INCOMPLETE_TOOL_CALL") -> "Ответ достиг лимита, и попытки восстановления исчерпаны. Незавершённый вызов не выполнен. Продолжите задачу меньшими частями или увеличьте лимит ответа."
    error.contains("INCOMPLETE_STREAM") -> "Соединение оборвалось до завершения ответа. Незавершённые вызовы не выполнялись. Проверьте подключение и продолжите задачу."
    error.contains("current request or newest exchange exceeds") -> "Запрос или последний обмен не помещается в контекст. Сократите запрос либо начните новый диалог с сохранённой памятью задачи."
    error.contains("Общий бюджет") -> "Бюджет задачи исчерпан. Увеличьте его в настройках агента и продолжите задачу."
    else -> error
}
