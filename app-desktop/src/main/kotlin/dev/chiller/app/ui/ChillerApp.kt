package dev.chiller.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.chiller.contracts.*
import dev.chiller.design.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

enum class WorkspaceLayout { FOCUS, STUDIO }

@Composable
fun ChillerApp(
    state: RunState, sessions: List<SessionInfo>, selectedSessionId: String?, workspacePath: String?, isDemo: Boolean,
    providerModel: String, providerBaseUrl: String, thinkingEnabled: Boolean, apiKeyConfigured: Boolean,
    onSend: (String, ExecutionMode) -> Unit, onStop: () -> Unit, onNewSession: () -> Unit,
    onSelectSession: (String) -> Unit, onChooseWorkspace: () -> Unit,
    onConfigureProvider: (baseUrl: String, model: String, thinking: Boolean, apiKey: String?) -> Unit,
    onAnswerQuestion: (String) -> Unit, onUndo: (String) -> Unit,
    onAllowProcessesChange: (Boolean) -> Unit, allowProcesses: Boolean,
) {
    val systemDark = isSystemInDarkTheme()
    var dark by remember { mutableStateOf<Boolean?>(null) }
    var layout by remember { mutableStateOf(WorkspaceLayout.FOCUS) }
    var sidebar by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf(false) }
    var draft by remember(selectedSessionId) { mutableStateOf("") }
    var mode by remember { mutableStateOf(ExecutionMode.PLAN) }
    var reducedMotion by remember { mutableStateOf(false) }
    var showCompactChanges by remember { mutableStateOf(false) }
    val active = state.status in setOf("preparing", "streaming", "executing")
    val empty = state.messages.none { it.role in setOf("user", "assistant") } && state.activities.isEmpty() && state.error == null
    val send = { if (draft.isNotBlank() && !active) { onSend(draft.trim(), mode); draft = "" } }
    ChillerTheme(darkTheme = dark ?: systemDark) {
        val c = LocalChillerPalette.current
        BoxWithConstraints(Modifier.fillMaxSize().background(c.canvas).onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && (it.isMetaPressed || it.isCtrlPressed)) {
                when (it.key) {
                    Key.Enter -> { send(); true }
                    Key.N -> { if (!active) onNewSession(); true }
                    else -> false
                }
            } else false
        }) {
            val showSidebar = sidebar && maxWidth >= 740.dp
            Row(Modifier.fillMaxSize()) {
                if (showSidebar) Sidebar(sessions, selectedSessionId, workspacePath, apiKeyConfigured, !active, onNewSession, onSelectSession, onChooseWorkspace,
                    onSettings = { settings = true }, onTheme = { dark = !(dark ?: systemDark) })
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.border.copy(alpha = .7f)))
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val compact = maxWidth < 860.dp
                    Column(Modifier.fillMaxSize()) {
                        Toolbar(
                            sessions.firstOrNull { it.id == selectedSessionId }?.title, workspacePath, layout, active, state.status,
                            onLayout = { layout = it }, onSidebar = { sidebar = !sidebar }, onSettings = { settings = true },
                            canConfigure = !active, compact = compact, compactChanges = showCompactChanges,
                            onCompactChanges = { showCompactChanges = !showCompactChanges },
                        )
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            if (!(compact && layout == WorkspaceLayout.STUDIO && showCompactChanges)) {
                                Column(Modifier.weight(1f).fillMaxHeight()) {
                                    if (empty) {
                                        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 36.dp), contentAlignment = Alignment.Center) {
                                            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(bottom = 34.dp)) {
                                                ChillerMark(Modifier.size(31.dp), c.accent)
                                                Spacer(Modifier.height(21.dp))
                                                Text("С чего начнём?", fontSize = 29.sp, lineHeight = 37.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.Normal, color = c.text)
                                                Spacer(Modifier.height(8.dp))
                                                Text(if (workspacePath == null) "Откройте проект и расскажите, что хотите сделать." else "${workspacePath.substringAfterLast('/')} — готов к вашей следующей идее.", color = c.muted, fontSize = 13.sp, lineHeight = 21.sp)
                                                Spacer(Modifier.height(29.dp))
                                                Composer(draft, { draft = it }, mode, { mode = it }, providerModel, active, send, onStop, allowProcesses, onAllowProcessesChange, { settings = true }, Modifier.fillMaxWidth())
                                                Spacer(Modifier.height(17.dp))
                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    ChillerControl("Разобраться в проекте", { draft = "Помоги разобраться в структуре и назначении этого проекта." }, icon = Icons.Outlined.Search)
                                                    ChillerControl("Найти ошибку", { draft = "Помоги найти и исправить ошибку в проекте." }, icon = Icons.Outlined.BugReport)
                                                }
                                            }
                                        }
                                    } else {
                                        Conversation(state, reducedMotion, onAnswerQuestion, onUndo, !active, Modifier.weight(1f).fillMaxWidth())
                                        Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp), contentAlignment = Alignment.Center) {
                                            Composer(draft, { draft = it }, mode, { mode = it }, providerModel, active, send, onStop, allowProcesses, onAllowProcessesChange, { settings = true }, Modifier.widthIn(max = 740.dp).fillMaxWidth())
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth().height(27.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(5.dp).clip(CircleShape).background(if (isDemo) c.faint else c.accent))
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (isDemo) "Модель не подключена" else "${friendlyModel(providerModel)} · API", color = c.faint, fontSize = 10.sp, lineHeight = 14.sp)
                                        Spacer(Modifier.weight(1f))
                                        Text("⌘ ↵  Отправить", color = c.faint, fontSize = 10.sp, lineHeight = 14.sp)
                                    }
                                }
                            }
                            AnimatedVisibility(
                                visible = layout == WorkspaceLayout.STUDIO && (!compact || showCompactChanges),
                                enter = if (reducedMotion) EnterTransition.None else expandHorizontally(tween(220)) + fadeIn(tween(160)),
                                exit = if (reducedMotion) ExitTransition.None else shrinkHorizontally(tween(180)) + fadeOut(tween(110)),
                            ) {
                                Inspector(state.activities, onUndo, !active, reducedMotion, if (compact) Modifier.fillMaxSize() else Modifier.width(340.dp).fillMaxHeight())
                            }
                        }
                    }
                }
            }
        }
        if (settings && !active) Settings(providerBaseUrl, providerModel, thinkingEnabled, apiKeyConfigured, allowProcesses, onAllowProcessesChange, reducedMotion, { reducedMotion = it }, { settings = false }) { url, model, thinking, key ->
            onConfigureProvider(url, model, thinking, key); settings = false
        }
    }
}

@Composable
private fun Sidebar(
    sessions: List<SessionInfo>, selected: String?, workspace: String?, connected: Boolean, enabled: Boolean,
    onNew: () -> Unit, onSelect: (String) -> Unit, onChoose: () -> Unit, onSettings: () -> Unit, onTheme: () -> Unit,
) {
    val c = LocalChillerPalette.current
    Column(Modifier.width(224.dp).fillMaxHeight().background(c.sidebar).padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChillerMark(Modifier.size(23.dp)); Spacer(Modifier.width(9.dp))
            Text("Chiller", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.4f).sp)
            Spacer(Modifier.weight(1f))
            ChillerIconButton(Icons.Outlined.EditNote, "Новый диалог", onNew, enabled = enabled)
        }
        ChillerControl("Новый диалог", onNew, Modifier.fillMaxWidth(), icon = Icons.Outlined.Add, enabled = enabled, trailing = "⌘ N")
        Spacer(Modifier.height(23.dp))
        Text("Проект", color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        ChillerControl(workspace?.substringAfterLast('/') ?: "Открыть папку…", onChoose, Modifier.fillMaxWidth(), icon = Icons.Outlined.FolderOpen, style = if (workspace != null) ControlStyle.SELECTED else ControlStyle.QUIET, enabled = enabled)
        Spacer(Modifier.height(23.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Диалоги", color = c.faint, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            if (sessions.isNotEmpty()) Text("${sessions.size}", color = c.faint, fontSize = 10.sp)
        }
        if (sessions.isEmpty()) Text("Здесь появятся ваши задачи", color = c.faint, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(sessions, key = { it.id }) { session ->
                ChillerControl(session.title.ifBlank { "Новый диалог" }, { onSelect(session.id) }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Outlined.Chat, style = if (session.id == selected) ControlStyle.SELECTED else ControlStyle.QUIET, enabled = enabled)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border.copy(alpha = .75f)))
        Row(Modifier.fillMaxWidth().padding(vertical = 11.dp, horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            ChillerIconButton(Icons.Outlined.Settings, "Настройки", onSettings, enabled = enabled)
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Text("Настройки", color = c.text, fontSize = 12.sp, lineHeight = 18.sp)
                Text(if (connected) "Подключено к API" else "Подключить модель", color = c.faint, fontSize = 10.sp, lineHeight = 16.sp)
            }
            ChillerIconButton(Icons.Outlined.Contrast, "Сменить тему", onTheme)
        }
    }
}

@Composable
private fun Toolbar(title: String?, workspace: String?, layout: WorkspaceLayout, active: Boolean, status: String, onLayout: (WorkspaceLayout) -> Unit, onSidebar: () -> Unit, onSettings: () -> Unit, canConfigure: Boolean, compact: Boolean, compactChanges: Boolean, onCompactChanges: () -> Unit) {
    val c = LocalChillerPalette.current
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        ChillerIconButton(Icons.Outlined.VerticalSplit, "Боковая панель", onSidebar)
        Spacer(Modifier.width(10.dp))
        Text(workspace?.substringAfterLast('/') ?: "Chiller", color = c.muted, fontSize = 12.sp)
        Text(" / ", color = c.border, fontSize = 12.sp)
        Text(title?.take(44) ?: "Новый диалог", color = c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (active) { Text(statusLabel(status), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(end = 12.dp)) }
        Row(Modifier.clip(RoundedCornerShape(7.dp)).background(c.sidebar).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ChillerControl("Focus", { onLayout(WorkspaceLayout.FOCUS) }, style = if (layout == WorkspaceLayout.FOCUS) ControlStyle.SELECTED else ControlStyle.QUIET)
            ChillerControl("Studio", { onLayout(WorkspaceLayout.STUDIO) }, style = if (layout == WorkspaceLayout.STUDIO) ControlStyle.SELECTED else ControlStyle.QUIET)
        }
        if (compact && layout == WorkspaceLayout.STUDIO) {
            Spacer(Modifier.width(5.dp))
            ChillerIconButton(if (compactChanges) Icons.AutoMirrored.Outlined.Chat else Icons.Outlined.Difference, "Диалог / изменения", onCompactChanges)
        }
        Spacer(Modifier.width(7.dp))
        ChillerIconButton(Icons.Outlined.Tune, "Настройки подключения", onSettings, enabled = canConfigure)
    }
}

@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, mode: ExecutionMode, onMode: (ExecutionMode) -> Unit, model: String, running: Boolean, onSend: () -> Unit, onStop: () -> Unit, allowProcesses: Boolean, onAllow: (Boolean) -> Unit, onSettings: () -> Unit, modifier: Modifier) {
    val c = LocalChillerPalette.current
    var menu by remember { mutableStateOf(false) }
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(12.dp)).padding(13.dp)) {
        BasicTextField(draft, onDraft,
            modifier = Modifier.fillMaxWidth().heightIn(min = 47.dp, max = 180.dp).verticalScroll(rememberScrollState()).padding(horizontal = 2.dp, vertical = 2.dp),
            textStyle = TextStyle(color = c.text, fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.sp), cursorBrush = SolidColor(c.text),
            decorationBox = { field -> Box { if (draft.isEmpty()) Text("Напишите задачу…", color = c.faint, fontSize = 14.sp, lineHeight = 22.sp); field() } },
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box {
                ChillerControl(if (mode == ExecutionMode.PLAN) "План" else "Изменения", { menu = !menu }, icon = if (mode == ExecutionMode.PLAN) Icons.Outlined.Lightbulb else Icons.Outlined.Edit, enabled = !running)
                if (menu) Popup(alignment = Alignment.BottomStart, onDismissRequest = { menu = false }, properties = PopupProperties(focusable = true)) {
                    Column(Modifier.width(210.dp).clip(RoundedCornerShape(9.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(9.dp)).padding(5.dp)) {
                        ChillerControl("План · только чтение", { onMode(ExecutionMode.PLAN); menu = false }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Lightbulb)
                        ChillerControl("Изменения в проекте", { onMode(ExecutionMode.WORKSPACE_EDIT); menu = false }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Edit)
                    }
                }
            }
            Text("/", color = c.border, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 2.dp))
            ChillerControl(friendlyModel(model), onSettings, enabled = !running)
            Spacer(Modifier.weight(1f))
            if (mode == ExecutionMode.WORKSPACE_EDIT) {
                ChillerIconButton(Icons.Outlined.Terminal, if (allowProcesses) "Команды разрешены" else "Команды выключены", { onAllow(!allowProcesses) }, enabled = !running, primary = allowProcesses)
                Spacer(Modifier.width(8.dp))
            }
            ChillerIconButton(if (running) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward, if (running) "Остановить" else "Отправить", if (running) onStop else onSend, enabled = running || draft.isNotBlank(), primary = true)
        }
    }
}

@Composable
private fun Conversation(state: RunState, reduced: Boolean, onAnswer: (String) -> Unit, onUndo: (String) -> Unit, canUndo: Boolean, modifier: Modifier) {
    val c = LocalChillerPalette.current
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(scroll) { snapshotFlow { !scroll.canScrollForward || scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index == scroll.layoutInfo.totalItemsCount - 1 }.collect { follow = it } }
    LaunchedEffect(state.messages.size, state.streamingText, state.activities, state.question) {
        if (follow && scroll.layoutInfo.totalItemsCount > 0) { if (reduced) scroll.scrollToItem(scroll.layoutInfo.totalItemsCount - 1) else scroll.animateScrollToItem(scroll.layoutInfo.totalItemsCount - 1) }
    }
    Box(modifier) {
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            var activityOffset = 0
            state.messages.forEachIndexed { index, message ->
                if (message.role in setOf("user", "assistant")) {
                    val calls = message.toolCalls.size
                    val activities = state.activities.drop(activityOffset).take(calls)
                    activityOffset += calls
                    item(key = "message-$index") {
                        Column(Modifier.widthIn(max = 740.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!message.content.isNullOrBlank()) Message(message.role, message.content!!)
                            activities.forEach { ToolRow(it, reduced, onUndo, canUndo) }
                        }
                    }
                }
            }
            val consumed = state.messages.sumOf { it.toolCalls.size }
            if (state.activities.size > consumed) item(key = "extra-activities") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { state.activities.drop(consumed).forEach { ToolRow(it, reduced, onUndo, canUndo) } } }
            if (state.reasoning.isNotBlank()) item(key = "reasoning") {
                var open by remember { mutableStateOf(false) }
                Column {
                    ChillerControl(if (open) "Скрыть рассуждения" else "Рассуждения модели", { open = !open }, icon = Icons.Outlined.MoreHoriz)
                    if (open) Text(state.reasoning, color = c.muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                }
            }
            if (state.streamingText.isNotBlank()) item(key = "stream") { Message("assistant", state.streamingText) }
            state.question?.let { q -> item(key = "question-${q.id}") { Question(q, onAnswer) } }
            state.error?.let { error -> item(key = "error") { Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = c.negative)
                Text(error, color = c.negative, fontSize = 12.sp, lineHeight = 20.sp)
            } } }
            item(key = "tail") { Spacer(Modifier.height(2.dp)) }
        }
        if (!follow) ChillerControl("К последнему сообщению", { scope.launch { scroll.animateScrollToItem((scroll.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } }, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), icon = Icons.Outlined.ArrowDownward, style = ControlStyle.OUTLINE)
    }
}

@Composable
private fun Message(role: String, content: String) {
    val c = LocalChillerPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        if (role == "assistant") ChillerMark(Modifier.padding(top = 1.dp).size(21.dp), c.accent)
        else Box(Modifier.size(21.dp).clip(CircleShape).background(c.selected), contentAlignment = Alignment.Center) { Text("В", color = c.muted, fontSize = 10.sp) }
        Column(Modifier.weight(1f)) {
            Text(if (role == "assistant") "Chiller" else "Вы", color = c.muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 5.dp))
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val segments = content.split("```")
                    segments.forEachIndexed { index, text ->
                        if (index % 2 == 1) Text(text.substringAfter('\n', text).trimEnd(), color = c.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(c.sidebar).padding(12.dp))
                        else if (text.isNotBlank()) Text(text.trim(), color = c.text, fontSize = 14.sp, lineHeight = 23.sp, letterSpacing = 0.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolRow(activity: Activity, reduced: Boolean, onUndo: (String) -> Unit, canUndo: Boolean) {
    val c = LocalChillerPalette.current
    var open by remember(activity.id) { mutableStateOf(false) }
    val failed = activity.status in setOf("failed", "error", "conflict")
    val running = activity.status in setOf("running", "executing", "preparing")
    val success = activity.status in setOf("completed", "applied", "exited")
    val color = when { failed -> c.negative; success -> c.positive; else -> c.muted }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, c.border.copy(alpha = .85f), RoundedCornerShape(8.dp))) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (failed) Icons.Outlined.Close else if (running) Icons.Outlined.MoreHoriz else if (success) Icons.Outlined.Check else Icons.Outlined.HelpOutline, null, Modifier.size(14.dp), tint = color)
            Spacer(Modifier.width(9.dp))
            Text(toolLabel(activity.name), color = c.muted, fontSize = 12.sp)
            if (activity.target.isNotBlank()) Text("  ${activity.target}", color = c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, Modifier.size(15.dp), tint = c.faint)
        }
        AnimatedVisibility(open, enter = if (reduced) EnterTransition.None else expandVertically(tween(180)) + fadeIn(tween(120)), exit = if (reduced) ExitTransition.None else shrinkVertically(tween(160)) + fadeOut(tween(100))) {
            Column {
                if (activity.diff != null) Diff(activity.diff!!)
                else if (activity.output.isNotBlank()) SelectionContainer { Text(prettyOutput(activity.output), color = c.muted, fontSize = 11.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().background(c.sidebar).padding(12.dp), maxLines = 24) }
                val id = if (canUndo && success && activity.name in setOf("edit_file", "create_file", "move_file", "delete_file")) changeId(activity.output) else null
                if (id != null) Box(Modifier.fillMaxWidth().padding(5.dp), contentAlignment = Alignment.CenterEnd) {
                    ChillerControl("Отменить правку", { onUndo(id) }, icon = Icons.AutoMirrored.Outlined.Undo)
                }
            }
        }
    }
}

@Composable
private fun Diff(diff: String) {
    val c = LocalChillerPalette.current
    SelectionContainer {
        Column(Modifier.fillMaxWidth().background(c.canvas).padding(vertical = 7.dp)) {
            diff.lineSequence().take(500).forEach { line ->
                val added = line.startsWith('+') && !line.startsWith("+++")
                val removed = line.startsWith('-') && !line.startsWith("---")
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 19.sp, letterSpacing = 0.sp,
                    color = when { added -> c.positive; removed -> c.negative; else -> c.muted },
                    modifier = Modifier.fillMaxWidth().background(when { added -> c.added; removed -> c.removed; else -> c.canvas }).padding(horizontal = 12.dp))
            }
        }
    }
}

@Composable
private fun Inspector(activities: List<Activity>, onUndo: (String) -> Unit, canUndo: Boolean, reduced: Boolean, modifier: Modifier) {
    val c = LocalChillerPalette.current
    var all by remember { mutableStateOf(false) }
    val changes = activities.filter { it.diff != null }
    val shown = if (all) activities else changes
    Row(modifier) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
        Column(Modifier.weight(1f).fillMaxHeight().background(c.canvas)) {
            Row(Modifier.fillMaxWidth().height(49.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ChillerControl("Изменения", { all = false }, style = if (!all) ControlStyle.SELECTED else ControlStyle.QUIET)
                ChillerControl("Активность", { all = true }, style = if (all) ControlStyle.SELECTED else ControlStyle.QUIET)
                Spacer(Modifier.weight(1f))
                Text("${shown.size}", color = c.faint, fontSize = 11.sp)
            }
            if (shown.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Difference, null, Modifier.size(23.dp), tint = c.faint)
                    Spacer(Modifier.height(13.dp))
                    Text(if (all) "Пока без действий" else "Пока без изменений", color = c.muted, fontSize = 12.sp)
                    Spacer(Modifier.height(5.dp))
                    Text("Результаты работы появятся здесь", color = c.faint, fontSize = 11.sp)
                }
            } else LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { items(shown, key = { it.id }) { ToolRow(it, reduced, onUndo, canUndo) } }
        }
    }
}

@Composable
private fun Question(question: UserQuestion, onAnswer: (String) -> Unit) {
    val c = LocalChillerPalette.current
    var answer by remember(question.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).border(1.dp, c.border, RoundedCornerShape(9.dp)).padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(question.question, color = c.text, fontSize = 13.sp, lineHeight = 21.sp)
        question.options.forEach { ChillerControl(it, { onAnswer(it) }, icon = Icons.AutoMirrored.Outlined.ArrowForward, style = ControlStyle.OUTLINE) }
        ChillerField(answer, { answer = it }, "Ваш ответ")
        ChillerControl("Продолжить", { if (answer.isNotBlank()) onAnswer(answer) }, style = ControlStyle.PRIMARY, enabled = answer.isNotBlank())
    }
}

@Composable
private fun Settings(url: String, model: String, thinking: Boolean, configured: Boolean, allow: Boolean, onAllow: (Boolean) -> Unit, reduced: Boolean, onReduced: (Boolean) -> Unit, onDismiss: () -> Unit, onSave: (String, String, Boolean, String?) -> Unit) {
    val c = LocalChillerPalette.current
    var nextUrl by remember { mutableStateOf(url) }
    var nextModel by remember { mutableStateOf(model) }
    var nextThinking by remember { mutableStateOf(thinking) }
    var key by remember { mutableStateOf("") }
    var clear by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(440.dp).clip(RoundedCornerShape(13.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(13.dp)).padding(25.dp), verticalArrangement = Arrangement.spacedBy(19.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Подключение", color = c.text, fontSize = 19.sp, letterSpacing = (-.5).sp)
                Spacer(Modifier.weight(1f)); ChillerIconButton(Icons.Outlined.Close, "Закрыть настройки", onDismiss)
            }
            ChillerField(nextUrl, { nextUrl = it }, "Адрес API", "https://api.deepseek.com")
            ChillerField(nextModel, { nextModel = it }, "Модель", "deepseek-flash")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChillerField(key, { key = it }, if (configured) "API-ключ · настроен" else "API-ключ", if (configured) "Оставьте пустым, чтобы сохранить текущий" else "Введите ключ", secret = true)
                Text("Хранится в памяти до закрытия приложения.", color = c.faint, fontSize = 11.sp)
                if (configured) ChillerControl(if (clear) "Ключ будет удалён" else "Удалить сохранённый ключ", { clear = !clear })
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
            SettingRow("Рассуждения модели", "Если провайдер поддерживает этот режим", nextThinking) { nextThinking = it }
            SettingRow("Разрешить команды", "Запуск процессов в режиме изменений", allow, onAllow)
            SettingRow("Уменьшить движение", "Без раскрывающих переходов", reduced, onReduced)
            Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.End) {
                ChillerControl("Отмена", onDismiss)
                Spacer(Modifier.width(8.dp))
                ChillerControl("Сохранить", { onSave(nextUrl.trim(), nextModel.trim(), nextThinking, if (clear) "" else key.takeIf { it.isNotBlank() }); key = "" }, style = ControlStyle.PRIMARY, enabled = nextUrl.isNotBlank() && nextModel.isNotBlank())
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalChillerPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = c.text, fontSize = 12.sp); Text(detail, color = c.faint, fontSize = 11.sp, lineHeight = 17.sp) }
        ChillerToggle(value, onChange, title)
    }
}

private fun friendlyModel(model: String) = when (model) { "deepseek-flash" -> "DeepSeek Flash"; else -> model.substringAfterLast('/').take(30).ifBlank { "Выбрать модель" } }
private fun statusLabel(status: String) = when (status) { "preparing" -> "Подготовка…"; "streaming" -> "Работает…"; "executing" -> "Выполняет действие…"; else -> "" }
private fun changeId(output: String): String? = runCatching { Json.parseToJsonElement(output).jsonObject["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content }.getOrNull()
private fun prettyOutput(output: String): String = runCatching {
    val data = Json.parseToJsonElement(output).jsonObject["data"]
    (data as? JsonObject)?.get("output")?.jsonPrimitive?.content ?: Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), data ?: JsonPrimitive(output))
}.getOrDefault(output)
private fun toolLabel(name: String) = when (name) {
    "read_file" -> "Чтение"; "list_files" -> "Файлы"; "search_text" -> "Поиск"; "edit_file" -> "Правка"; "create_file" -> "Создание"
    "move_file" -> "Перемещение"; "delete_file" -> "Удаление"; "run_process" -> "Команда"; "read_process" -> "Вывод"; "stop_process" -> "Остановка"
    "read_artifact" -> "Результат"; "ask_user" -> "Вопрос"; "undo" -> "Откат"; else -> name
}
