package dev.chiller.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.chiller.app.di.UiPreferences
import dev.chiller.app.di.SendReceipt
import dev.chiller.app.di.ConnectionState
import dev.chiller.app.di.ModelCatalogState
import dev.chiller.contracts.*
import dev.chiller.design.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*

enum class WorkspaceLayout { FOCUS, STUDIO }
enum class DesktopAction { NEW_CHAT, SEND, SETTINGS, STOP, FOCUS, STUDIO, SIDEBAR }

@Composable
fun ChillerApp(
    state: RunState, sessions: List<SessionInfo>, selectedSessionId: String?, workspacePath: String?, isDemo: Boolean,
    providerModel: String, providerBaseUrl: String, thinkingEnabled: Boolean, apiKeyConfigured: Boolean,
    onSend: suspend (String, ExecutionMode, String) -> SendReceipt, onStop: () -> Unit, onNewSession: () -> Unit,
    onSelectSession: (String) -> Unit, onChooseWorkspace: () -> Unit,
    onConfigureProvider: suspend (baseUrl: String, model: String, thinking: Boolean, apiKey: String?) -> String?,
    onAnswerQuestion: (String) -> Unit, onUndo: (String) -> Unit,
    onAllowProcessesChange: (Boolean) -> Unit, allowProcesses: Boolean,
    actions: Flow<DesktopAction> = emptyFlow(),
    exportPreview: String? = null, exportFormat: String = "markdown",
    onDismissExport: () -> Unit = {}, onSaveExport: () -> Unit = {},
    preferences: UiPreferences = UiPreferences(), onPreferences: (UiPreferences) -> Unit = {},
    models: List<String> = emptyList(), onSelectModel: suspend (String) -> String? = { null },
    onRenameSession: suspend (String, String) -> Boolean = { _, _ -> false },
    onExportSession: (String, String) -> Unit = { _, _ -> },
    connection: ConnectionState = ConnectionState(), onCheckConnection: () -> Unit = {}, onCancelConnection: () -> Unit = {},
    catalog: ModelCatalogState = ModelCatalogState(), onRefreshModels: () -> Unit = {}, onCancelCatalogRefresh: () -> Unit = {},
) {
    val systemDark = isSystemInDarkTheme()
    var view by remember { mutableStateOf(preferences) }
    LaunchedEffect(preferences) { view = preferences }
    val updateView: (UiPreferences) -> Unit = { view = it; onPreferences(it) }
    val dark = when (view.theme) { "dark" -> true; "light" -> false; else -> systemDark }
    val layout = if (view.layout == "studio") WorkspaceLayout.STUDIO else WorkspaceLayout.FOCUS
    val compactDensity = view.density == "compact"
    val scope = rememberCoroutineScope()
    var pendingSend by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val latestSession by rememberUpdatedState(selectedSessionId)
    LaunchedEffect(selectedSessionId) { sendError = null; composing = false }
    var renameTarget by remember { mutableStateOf<SessionInfo?>(null) }
    var selectedActivityId by remember { mutableStateOf<String?>(null) }
    var sidebar by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf(false) }
    val drafts = remember { mutableStateMapOf<String, String>() }
    val draftKey = selectedSessionId ?: "new"
    val draft = drafts[draftKey].orEmpty()
    val updateDraft: (String) -> Unit = { drafts[draftKey] = it }
    var mode by remember { mutableStateOf(ExecutionMode.PLAN) }
    val reducedMotion = view.reducedMotion
    var showCompactChanges by remember { mutableStateOf(false) }
    val inspectorWidth = view.inspectorWidth.dp
    val density = LocalDensity.current
    val inspectorResize = rememberDraggableState { delta -> view = view.copy(inspectorWidth = (inspectorWidth - with(density) { delta.toDp() }).value.coerceIn(280f, 480f)) }
    val active = pendingSend || state.status in setOf("preparing", "streaming", "executing")
    val empty = state.messages.none { it.role in setOf("user", "assistant") } && state.activities.isEmpty() && state.error == null
    val contextNote = state.contextEstimatedTokens?.let { tokens -> "≈ $tokens токенов${if (state.droppedContextMessages > 0) " · контекст сокращён" else ""}" }
    val send = {
        if (draft.isNotBlank() && !active && !pendingSend && !composing) {
            val sentKey = draftKey; val sentText = draft; val requestId = newId()
            pendingSend = true; sendError = null
            scope.launch {
                try {
                    val receipt = onSend(sentText.trim(), mode, requestId)
                    if (receipt.accepted) {
                        if (receipt.sessionId == sentKey && drafts[sentKey] == sentText) drafts[sentKey] = ""
                    } else if (latestSession == receipt.sessionId) sendError = receipt.reason ?: "Сообщение не принято"
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { sendError = "Не удалось отправить сообщение. Текст сохранён." }
                finally { pendingSend = false }
            }
        }
    }
    val handleAction by rememberUpdatedState<(DesktopAction) -> Unit> { action ->
        when (action) {
            DesktopAction.NEW_CHAT -> if (!active) onNewSession()
            DesktopAction.SEND -> if (!settings && renameTarget == null && exportPreview == null) send()
            DesktopAction.SETTINGS -> if (!active) settings = true
            DesktopAction.STOP -> if (active) onStop()
            DesktopAction.FOCUS -> updateView(view.copy(layout = "focus"))
            DesktopAction.STUDIO -> updateView(view.copy(layout = "studio"))
            DesktopAction.SIDEBAR -> sidebar = !sidebar
        }
    }
    LaunchedEffect(actions) { actions.collect { handleAction(it) } }
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { rootFocus.requestFocus() }
    ChillerTheme(darkTheme = dark, reducedMotion = reducedMotion, compact = compactDensity) {
        val c = LocalChillerPalette.current
        BoxWithConstraints(Modifier.fillMaxSize().background(c.canvas).focusRequester(rootFocus).onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && (it.isMetaPressed || it.isCtrlPressed)) {
                when (it.key) {
                    Key.Enter -> { handleAction(DesktopAction.SEND); true }
                    else -> false
                }
            } else false
        }.focusable()) {
            val showSidebar = sidebar && maxWidth >= 740.dp
            Row(Modifier.fillMaxSize()) {
                if (showSidebar) Sidebar(sessions, selectedSessionId, workspacePath, apiKeyConfigured, !active, onNewSession, onSelectSession, onChooseWorkspace,
                    onSettings = { settings = true }, onTheme = { updateView(view.copy(theme = if (dark) "light" else "dark")) }, onRename = { renameTarget = it }, onExport = onExportSession, connection = connection, width = 248f * density.fontScale.coerceIn(1f, 1.2f))
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.border.copy(alpha = .7f)))
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val compact = maxWidth < 860.dp || density.fontScale > 1.3f
                    Column(Modifier.fillMaxSize()) {
                        Toolbar(
                            sessions.firstOrNull { it.id == selectedSessionId }?.title, workspacePath, layout, active, state.status,
                            onLayout = { updateView(view.copy(layout = it.name.lowercase())) }, onSidebar = { sidebar = !sidebar }, onSettings = { settings = true },
                            canConfigure = !active, compact = compact, compactChanges = showCompactChanges,
                            onCompactChanges = { showCompactChanges = !showCompactChanges },
                        )
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            if (!(compact && layout == WorkspaceLayout.STUDIO && showCompactChanges)) {
                                Column(Modifier.weight(1f).fillMaxHeight()) {
                                    if (empty) {
                                        Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                ChillerMark(Modifier.size(34.dp), c.faint)
                                                Spacer(Modifier.height(22.dp))
                                                Text(if (workspacePath == null) "Что будем создавать?" else "Что сделаем в ${workspacePath.substringAfterLast('/')}?", fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = (-.6).sp, color = c.text, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                                Spacer(Modifier.height(10.dp))
                                                Text(if (workspacePath == null) "Выберите проект или начните с идеи" else "От идеи до работающего кода — вместе с Chiller", color = c.muted, fontSize = 13.sp, lineHeight = 20.sp)
                                                Spacer(Modifier.height(20.dp))
                                                if (workspacePath == null) ChillerControl("Открыть проект", onChooseWorkspace, icon = Icons.Outlined.FolderOpen, style = ControlStyle.OUTLINE)
                                                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    ChillerControl("Разобраться в проекте", { updateDraft("Помоги разобраться в структуре и назначении этого проекта.") }, icon = Icons.Outlined.Search)
                                                    ChillerControl("Найти ошибку", { updateDraft("Помоги найти и исправить ошибку в проекте.") }, icon = Icons.Outlined.BugReport)
                                                }
                                            }
                                        }
                                    } else {
                                        Conversation(state, reducedMotion, onAnswerQuestion, onUndo, !active, { text -> updateDraft(text); sendError = null }, Modifier.weight(1f).fillMaxWidth())
                                    }
                                    Box(Modifier.fillMaxWidth().padding(horizontal = if (compact) 20.dp else 32.dp).padding(top = 6.dp, bottom = 18.dp), contentAlignment = Alignment.Center) {
                                        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            sendError?.let { Text(it, color = c.negative, fontSize = 12.sp, lineHeight = 18.sp) }
                                            Composer(draft, updateDraft, mode, { mode = it }, providerModel, active, send, onStop, { settings = true }, Modifier.fillMaxWidth(), apiKeyConfigured, models, { selected ->
                                                val oldDraft = draft
                                                scope.launch { onSelectModel(selected)?.let { id -> drafts.putIfAbsent(id, oldDraft) } }
                                            }, { composing = it }, pendingSend, contextNote, catalog, onRefreshModels, onCancelCatalogRefresh, allowProcesses, onAllowProcessesChange)
                                        }
                                    }
                                }
                            }
                            AnimatedVisibility(
                                visible = layout == WorkspaceLayout.STUDIO && (!compact || showCompactChanges),
                                enter = if (reducedMotion) EnterTransition.None else expandHorizontally(tween(160)) + fadeIn(tween(160)),
                                exit = if (reducedMotion) ExitTransition.None else shrinkHorizontally(tween(180)) + fadeOut(tween(110)),
                            ) {
                                Row(if (compact) Modifier.fillMaxSize() else Modifier.width(inspectorWidth).fillMaxHeight()) {
                                    if (!compact) Box(Modifier.width(10.dp).fillMaxHeight().background(c.sidebar).pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.E_RESIZE_CURSOR))).semantics { contentDescription = "Ширина панели изменений" }.draggable(inspectorResize, Orientation.Horizontal, onDragStopped = { onPreferences(view) }))
                                    Inspector(state.activities, onUndo, !active, reducedMotion, Modifier.weight(1f).fillMaxHeight(), selectedActivityId, { selectedActivityId = it }, { delta -> updateView(view.copy(inspectorWidth = (view.inspectorWidth + delta).coerceIn(280f, 480f))) })
                                }
                            }
                        }
                    }
                }
            }
        }
        if (settings && !active) Settings(providerBaseUrl, providerModel, thinkingEnabled, apiKeyConfigured, allowProcesses,
            view, connection, onCheckConnection, onCancelConnection, { settings = false }) { url, model, thinking, key, allow, nextView ->
            onAllowProcessesChange(allow); updateView(nextView)
            if (url != providerBaseUrl || model != providerModel || thinking != thinkingEnabled || key != null) {
                val oldDraft = draft
                scope.launch { onConfigureProvider(url, model, thinking, key)?.let { id -> drafts.putIfAbsent(id, oldDraft) } }
            }
            settings = false
        }
        renameTarget?.let { target -> RenameDialog(target, { renameTarget = null }) { title ->
            scope.launch { if (onRenameSession(target.id, title)) renameTarget = null }
        } }
        if (exportPreview != null) ExportPreview(exportPreview, exportFormat, onDismissExport, onSaveExport)
    }
}

@Composable
private fun Sidebar(
    sessions: List<SessionInfo>, selected: String?, workspace: String?, connected: Boolean, enabled: Boolean,
    onNew: () -> Unit, onSelect: (String) -> Unit, onChoose: () -> Unit, onSettings: () -> Unit, onTheme: () -> Unit, onRename: (SessionInfo) -> Unit, onExport: (String, String) -> Unit, connection: ConnectionState, width: Float,
) {
    val c = LocalChillerPalette.current
    Column(Modifier.width(width.dp).fillMaxHeight().background(c.sidebar).padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChillerMark(Modifier.size(23.dp)); Spacer(Modifier.width(9.dp))
            Text("Chiller", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.4f).sp)
            Spacer(Modifier.weight(1f))
        }
        ChillerControl("Новый диалог", onNew, Modifier.fillMaxWidth(), icon = Icons.Outlined.Add, enabled = enabled, trailing = "⌘ N")
        Spacer(Modifier.height(18.dp))
        Text("Проект", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        Hint(workspace ?: "Открыть папку проекта") {
            ChillerControl(workspace?.substringAfterLast('/') ?: "Открыть папку…", onChoose, Modifier.fillMaxWidth(), icon = Icons.Outlined.FolderOpen, style = if (workspace != null) ControlStyle.SELECTED else ControlStyle.QUIET, enabled = enabled)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Недавние диалоги", color = c.muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            if (sessions.isNotEmpty()) Text("${sessions.size}", color = c.faint, fontSize = 10.sp)
        }
        if (sessions.isEmpty()) Text("Здесь появятся ваши задачи", color = c.faint, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            historySections(sessions).forEach { section ->
                item(key = "section-${section.title}") { Text(section.title, color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 4.dp)) }
                items(section.sessions, key = { it.id }) { session ->
                    var menu by remember(session.id) { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Hint(session.title) {
                            ChillerControl(session.title.ifBlank { "Новый диалог" }, { onSelect(session.id) }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Outlined.Chat, style = if (session.id == selected) ControlStyle.SELECTED else ControlStyle.QUIET, enabled = enabled)
                        } }
                        Box {
                            ChillerIconButton(Icons.Outlined.MoreHoriz, "Действия диалога", { menu = !menu }, enabled = enabled)
                            if (menu) Popup(alignment = Alignment.TopEnd, onDismissRequest = { menu = false }, properties = PopupProperties(focusable = true)) {
                                Column(Modifier.width(210.dp).background(c.surface).border(1.dp, c.border).padding(5.dp)) {
                                    ChillerControl("Переименовать", { menu = false; onRename(session) }, Modifier.fillMaxWidth())
                                    ChillerControl("Экспорт Markdown", { menu = false; onExport(session.id, "markdown") }, Modifier.fillMaxWidth())
                                    ChillerControl("Экспорт JSON", { menu = false; onExport(session.id, "json") }, Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border.copy(alpha = .75f)))
        Row(Modifier.fillMaxWidth().padding(vertical = 11.dp, horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            ChillerIconButton(Icons.Outlined.Settings, "Настройки", onSettings, enabled = enabled)
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Text("Настройки", color = c.text, fontSize = 12.sp, lineHeight = 18.sp)
                Text(when (connection.status) { "verified" -> "Доступ к модели проверен"; "checking" -> "Проверка соединения…"; "failed" -> "Ошибка подключения"; else -> if (connected) "Ключ задан · не проверен" else "Подключить модель" }, color = c.muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ChillerIconButton(Icons.Outlined.Contrast, "Сменить тему", onTheme)
        }
    }
}

@Composable
private fun Toolbar(title: String?, workspace: String?, layout: WorkspaceLayout, active: Boolean, status: String, onLayout: (WorkspaceLayout) -> Unit, onSidebar: () -> Unit, onSettings: () -> Unit, canConfigure: Boolean, compact: Boolean, compactChanges: Boolean, onCompactChanges: () -> Unit) {
    val c = LocalChillerPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 15.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ChillerIconButton(Icons.Outlined.VerticalSplit, "Боковая панель", onSidebar)
        Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(workspace?.substringAfterLast('/') ?: "Chiller", color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
            Text(" / ", color = c.faint, fontSize = 12.sp)
            Text(title?.take(44) ?: "Новый диалог", color = c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (active) { Text(statusLabel(status), color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp).padding(end = 12.dp)) }
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
private fun Composer(
    draft: String, onDraft: (String) -> Unit, mode: ExecutionMode, onMode: (ExecutionMode) -> Unit,
    model: String, running: Boolean, onSend: () -> Unit, onStop: () -> Unit,
    onSettings: () -> Unit, modifier: Modifier, configured: Boolean, models: List<String>,
    onModel: (String) -> Unit, onComposition: (Boolean) -> Unit, pending: Boolean, contextNote: String?,
    catalog: ModelCatalogState, onRefreshModels: () -> Unit, onCancelCatalogRefresh: () -> Unit,
    allowProcesses: Boolean, onAllow: (Boolean) -> Unit,
) {
    val c = LocalChillerPalette.current
    var focused by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue(draft)) }
    LaunchedEffect(draft) { if (field.text != draft) { field = TextFieldValue(draft, TextRange(draft.length)); onComposition(false) } }
    // Empty block: 26 dp text + 20 dp vertical padding + 6 dp gap + 32 dp toolbar = 84 dp. The text area grows to 144 dp
    // (a ~200 dp block at normal type) and then scrolls; the block grows only with the toolbar at larger type.
    val fieldMaxHeight = 144.dp
    val shape = RoundedCornerShape(16.dp)
    val borderColor by animateColorAsState(if (focused) c.muted.copy(alpha = .55f) else c.border, tween(if (LocalChillerReducedMotion.current) 0 else 120))
    Column(
        modifier.shadow(3.dp, shape, ambientColor = c.text.copy(alpha = .04f), spotColor = c.text.copy(alpha = .04f)).clip(shape).background(c.surface)
            .border(1.dp, borderColor, shape).padding(horizontal = 12.dp, vertical = 10.dp)
            .onPreviewKeyEvent { event -> if (modelMenu && event.type == KeyEventType.KeyDown && event.key == Key.Escape) { modelMenu = false; true } else false }
    ) {
        BasicTextField(field, { field = it; onDraft(it.text); onComposition(it.composition != null) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 26.dp, max = fieldMaxHeight).onFocusChanged { focused = it.isFocused }.verticalScroll(rememberScrollState()),
            textStyle = TextStyle(color = c.text, fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.sp), cursorBrush = SolidColor(c.text),
            decorationBox = { inner -> Box { if (draft.isEmpty()) Text("Напишите задачу…", color = c.faint, fontSize = 14.sp, lineHeight = 22.sp); inner() } },
        )
        if (modelMenu) {
            Spacer(Modifier.height(6.dp))
            ModelPanel(
                model = model, models = models, configured = configured, running = running, catalog = catalog,
                onRefresh = onRefreshModels, onCancelRefresh = onCancelCatalogRefresh,
                onClose = { modelMenu = false }, onModel = onModel,
                onSettings = { modelMenu = false; onSettings() },
            )
        }
        Spacer(Modifier.height(6.dp))
        ComposerToolbar(mode, onMode, model, running, pending, draft.isNotBlank(), contextNote, modelMenu, { modelMenu = !modelMenu }, allowProcesses, onAllow, onSend, onStop)
    }
}

/**
 * The model picker expands inside the composer above the toolbar. An inline panel cannot be
 * positioned away from its anchor, which removes the floating-popup failure mode entirely.
 */
@Composable
private fun ModelPanel(
    model: String, models: List<String>, configured: Boolean, running: Boolean, catalog: ModelCatalogState,
    onRefresh: () -> Unit, onCancelRefresh: () -> Unit, onClose: () -> Unit, onModel: (String) -> Unit, onSettings: () -> Unit,
) {
    val c = LocalChillerPalette.current
    var filter by remember { mutableStateOf("") }
    val panelShape = RoundedCornerShape(10.dp)
    val discovered = catalog.models.map { it.id }
    val known = (listOf(model) + discovered + models).distinct().take(20)
    val query = filter.trim()
    val shown = if (query.isEmpty()) known else known.filter { it.contains(query, true) || modelLabel(it).contains(query, true) }
    Column(Modifier.fillMaxWidth().clip(panelShape).background(c.canvas).border(1.dp, c.border, panelShape).padding(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Модели API", color = c.text, fontSize = 12.sp)
                Text(catalogLine(catalog, configured), color = if (catalog.status == "failed") c.negative else c.muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
            if (catalog.status == "loading") ChillerIconButton(Icons.Outlined.Close, "Отменить загрузку списка", onCancelRefresh)
            else ChillerIconButton(Icons.Outlined.Refresh, "Обновить список моделей", onRefresh, enabled = !running)
            ChillerIconButton(Icons.Outlined.Close, "Закрыть список моделей", onClose)
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).height(1.dp).background(c.border))
        if (known.size > 6) {
            Box(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                ChillerField(filter, { filter = it }, "Поиск модели", placeholder = "ID или название")
            }
        }
        Column(Modifier.heightIn(max = 210.dp).verticalScroll(rememberScrollState())) {
            shown.forEach { id ->
                val source = when { id == model -> null; id in discovered -> "API"; else -> "вручную" }
                Hint(modelDetail(id, catalog)) {
                    ChillerControl(modelLabel(id), { onModel(id); onClose() }, Modifier.fillMaxWidth(), icon = if (id == model) Icons.Outlined.Check else null, trailing = source)
                }
            }
            if (shown.isEmpty() && known.isNotEmpty()) {
                Text("Ничего не найдено по запросу «$filter».", color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
            }
            if (catalog.status == "ready" && catalog.models.isEmpty()) {
                Text("Провайдер не вернул ни одной модели. Задайте ID вручную в настройках.", color = c.muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).height(1.dp).background(c.border))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Смена модели создаёт новый диалог", color = c.muted, fontSize = 11.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
            ChillerControl("Настройки", onSettings, icon = Icons.Outlined.Tune)
        }
    }
}

@Composable
private fun ComposerToolbar(
    mode: ExecutionMode, onMode: (ExecutionMode) -> Unit, model: String, running: Boolean,
    pending: Boolean, draftNotEmpty: Boolean, contextNote: String?, modelMenuOpen: Boolean, onToggleModel: () -> Unit,
    allowProcesses: Boolean, onAllow: (Boolean) -> Unit, onSend: () -> Unit, onStop: () -> Unit,
) {
    val c = LocalChillerPalette.current
    var modeMenu by remember { mutableStateOf(false) }
    // At larger type the model label needs proportionally more room before the toolbar is allowed to wrap.
    val fontScale = LocalDensity.current.fontScale
    val minModelWidth = (104f * fontScale.coerceIn(1f, 2f)).dp
    val modeMenuWidth = (224f * fontScale.coerceIn(1f, 1.4f)).dp
    val menuShape = RoundedCornerShape(10.dp)
    Box(Modifier.fillMaxWidth()) {
        Layout(
            content = {
                Box {
                    ChillerControl(if (mode == ExecutionMode.PLAN) "План" else "Изменения", { modeMenu = !modeMenu }, icon = if (mode == ExecutionMode.PLAN) Icons.Outlined.Lightbulb else Icons.Outlined.Edit, enabled = !running)
                }
                Box {
                    Hint(contextNote?.let { "$model\n$it" } ?: model) {
                        ChillerControl(friendlyModel(model), onToggleModel, enabled = !running, trailing = "⌄", inlineTrailing = true, style = if (modelMenuOpen) ControlStyle.SELECTED else ControlStyle.QUIET)
                    }
                }
                ChillerIconButton(if (running && !pending) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward, if (pending) "Сохранение сообщения" else if (running) "Остановить" else "Отправить", if (running && !pending) onStop else onSend, enabled = !pending && (running || draftNotEmpty), primary = true)
            },
            modifier = Modifier.fillMaxWidth()
        ) { measurables, constraints ->
            val gap = 8.dp.roundToPx()
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
            val leading = measurables[0].measure(Constraints())
            val trailing = measurables[2].measure(Constraints())
            val singleLine = (width - leading.width - trailing.width - gap * 2).coerceAtLeast(0)
            val wrapped = singleLine < minModelWidth.roundToPx()
            val modelControl = measurables[1].measure(Constraints(maxWidth = if (wrapped) (width - leading.width - gap).coerceAtLeast(0) else singleLine))
            if (wrapped) {
                val line = maxOf(leading.height, modelControl.height)
                val rowGap = 6.dp.roundToPx()
                layout(width, line + rowGap + trailing.height) {
                    leading.placeRelative(0, (line - leading.height) / 2)
                    modelControl.placeRelative(leading.width + gap, (line - modelControl.height) / 2)
                    trailing.placeRelative(width - trailing.width, line + rowGap)
                }
            } else {
                val height = maxOf(leading.height, modelControl.height, trailing.height)
                layout(width, height) {
                    leading.placeRelative(0, (height - leading.height) / 2)
                    modelControl.placeRelative(leading.width + gap, (height - modelControl.height) / 2)
                    trailing.placeRelative(width - trailing.width, (height - trailing.height) / 2)
                }
            }
        }
        val menuGap = with(LocalDensity.current) { 6.dp.roundToPx() }
        if (modeMenu) Popup(popupPositionProvider = AboveToolbarPositionProvider(alignRight = false, gapPx = menuGap), onDismissRequest = { modeMenu = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.width(modeMenuWidth).shadow(12.dp, menuShape).clip(menuShape).background(c.surface).border(1.dp, c.border, menuShape).padding(5.dp)) {
                ChillerControl("План · только чтение", { onMode(ExecutionMode.PLAN); modeMenu = false }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Lightbulb, style = if (mode == ExecutionMode.PLAN) ControlStyle.SELECTED else ControlStyle.QUIET)
                ChillerControl("Изменения в проекте", { onMode(ExecutionMode.WORKSPACE_EDIT); modeMenu = false }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Edit, style = if (mode == ExecutionMode.WORKSPACE_EDIT) ControlStyle.SELECTED else ControlStyle.QUIET)
                if (mode == ExecutionMode.WORKSPACE_EDIT) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp).height(1.dp).background(c.border))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Команды процессов", color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        ChillerToggle(allowProcesses, { onAllow(it) }, "Команды процессов", enabled = !running)
                    }
                }
            }
        }
    }
}

/**
 * Places a menu fully above the toolbar it belongs to, aligned to the left or right edge
 * of the toolbar and clamped to the window. Compose's built-in alignment aligns popup and
 * anchor bottoms, which made a menu cover the button that opened it.
 */
private class AboveToolbarPositionProvider(private val alignRight: Boolean, private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = if (alignRight) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val y = anchorBounds.top - popupContentSize.height - gapPx
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

private fun catalogLine(catalog: ModelCatalogState, configured: Boolean): String = when (catalog.status) {
    "loading" -> "Загружаю список…"
    "failed" -> catalog.message.ifBlank { "Список моделей не получен" }
    "ready" -> {
        val base = if (catalog.models.isEmpty()) "Список пуст — задайте ID вручную" else "Моделей: ${catalog.models.size}"
        val time = catalog.fetchedAt?.let(::formatCheckTime)
        val line = if (time.isNullOrBlank()) base else "$base · $time"
        if (catalog.stale) "устарел · $line" else line
    }
    else -> if (configured) "Список не загружен" else "Ключ не задан"
}

/** Tooltip text: exact model id plus server-declared metadata when the catalog returned it. */
private fun modelDetail(id: String, catalog: ModelCatalogState): String {
    val model = catalog.models.firstOrNull { it.id == id } ?: return id
    val parts = buildList {
        model.displayName?.takeIf { it != id }?.let(::add)
        model.contextWindow?.let { add("контекст: ${formatTokens(it)}") }
        model.maxOutputTokens?.let { add("вывод: ${formatTokens(it)}") }
    }
    return if (parts.isEmpty()) id else "$id\n${parts.joinToString(" · ")}"
}

private fun formatTokens(value: Int): String = if (value >= 1_000) "${(value + 500) / 1_000}k" else value.toString()

@Composable
private fun Conversation(state: RunState, reduced: Boolean, onAnswer: (String) -> Unit, onUndo: (String) -> Unit, canUndo: Boolean, onRestoreDraft: (String) -> Unit, modifier: Modifier) {
    val c = LocalChillerPalette.current
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(scroll) { snapshotFlow { !scroll.canScrollForward || scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index == scroll.layoutInfo.totalItemsCount - 1 }.collect { follow = it } }
    LaunchedEffect(state.messages.size, state.streamingText, state.activities, state.question) {
        if (follow && scroll.layoutInfo.totalItemsCount > 0) { if (reduced) scroll.scrollToItem(scroll.layoutInfo.totalItemsCount - 1) else scroll.animateScrollToItem(scroll.layoutInfo.totalItemsCount - 1) }
    }
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        LazyColumn(state = scroll, modifier = Modifier.widthIn(max = 816.dp).fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(if (LocalChillerCompact.current) 16.dp else 22.dp)) {
            val projection = projectConversation(state.messages, state.activities)
            projection.entries.forEach { entry ->
                item(key = "message-${entry.index}") {
                    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!entry.message.content.isNullOrBlank()) Message(entry.message)
                        ToolGroup(entry.activities, reduced, onUndo, canUndo)
                    }
                }
            }
            if (projection.unlinked.isNotEmpty()) item(key = "unlinked-activities") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Действия сессии", color = c.muted, fontSize = 12.sp)
                    projection.unlinked.forEach { ToolRow(it, reduced, onUndo, canUndo) }
                }
            }
            if (state.status in setOf("preparing", "streaming", "executing", "cancelled")) item(key = "run-status") {
                val running = state.activities.lastOrNull { it.status == "running" }
                val label = when (state.status) {
                    "streaming" -> "${state.activeModelId?.let(::modelLabel) ?: "Ассистент"} отвечает…"
                    "executing" -> running?.let { "${toolLabel(it.name)} · ${it.target}" } ?: "Выполняет действие…"
                    "cancelled" -> "Задача остановлена"
                    else -> "Подготовка…"
                }
                Text(label, color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
            }
            if (state.reasoning.isNotBlank()) item(key = "reasoning") {
                var open by remember { mutableStateOf(false) }
                Column {
                    ChillerControl(if (open) "Скрыть рассуждения" else "Рассуждения модели", { open = !open }, icon = Icons.Outlined.MoreHoriz)
                    if (open) Text(state.reasoning, color = c.muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                }
            }
            if (state.streamingText.isNotBlank()) item(key = "stream") { Message(AgentMessage("assistant", state.streamingText, origin = state.activeModelId?.let { MessageOrigin(MessageOriginKind.MODEL, it) })) }
            state.question?.let { q -> item(key = "question-${q.id}") { Question(q, onAnswer, state.status != "interrupted") } }
            state.error?.let { error -> item(key = "error") { Column { Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = c.negative)
                Text(error, color = c.negative, fontSize = 12.sp, lineHeight = 20.sp)
            }
                state.messages.lastOrNull { it.role == "user" }?.content?.let { text -> ChillerControl("Вернуть текст в поле", { onRestoreDraft(text) }) }
            } } }
            item(key = "tail") { Spacer(Modifier.height(2.dp)) }
        }
        if (!follow) ChillerControl("К последнему сообщению", { scope.launch { scroll.animateScrollToItem((scroll.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } }, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), icon = Icons.Outlined.ArrowDownward, style = ControlStyle.OUTLINE)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Hint(text: String, content: @Composable () -> Unit) {
    val c = LocalChillerPalette.current
    TooltipArea(tooltip = { Text(text, color = c.text, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.sidebar).border(1.dp, c.border, RoundedCornerShape(6.dp)).padding(8.dp)) }, content = content)
}

@Composable
private fun Message(message: AgentMessage) {
    val role = message.role
    val content = message.content.orEmpty()
    val clipboard = LocalClipboardManager.current
    var copied by remember(content) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1600); copied = false } }
    val c = LocalChillerPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        if (role == "assistant") ChillerMark(Modifier.padding(top = 1.dp).size(21.dp), c.accent)
        else Box(Modifier.size(21.dp).clip(CircleShape).background(c.selected), contentAlignment = Alignment.Center) { Text("В", color = c.muted, fontSize = 10.sp) }
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Hint(message.origin?.modelId ?: authorLabel(message)) {
                    Text(authorLabel(message), color = c.muted, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp))
                }
                Spacer(Modifier.weight(1f))
                if (role == "assistant") ChillerIconButton(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, if (copied) "Скопировано" else "Копировать ответ", { clipboard.setText(AnnotatedString(content)); copied = true })
            }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val segments = content.split("```")
                    segments.forEachIndexed { index, text ->
                        if (index % 2 == 1) CodeBlock(text.substringAfter('\n', text).trimEnd(), text.substringBefore('\n', "").take(24))
                        else if (text.isNotBlank()) Text(text.trim(), color = c.text, fontSize = 14.sp, lineHeight = 23.sp, letterSpacing = 0.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String, language: String) {
    val c = LocalChillerPalette.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, c.border, RoundedCornerShape(10.dp)).background(c.sidebar)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(language.ifBlank { "Код" }, color = c.muted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            ChillerControl(if (copied) "Скопировано" else "Копировать", { clipboard.setText(AnnotatedString(code)); copied = true }, icon = Icons.Outlined.ContentCopy)
        }
        Text(code, color = c.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp))
    }
}

@Composable
private fun ToolGroup(activities: List<Activity>, reduced: Boolean, onUndo: (String) -> Unit, canUndo: Boolean) {
    if (activities.isEmpty()) return
    if (activities.size == 1) { ToolRow(activities.first(), reduced, onUndo, canUndo); return }
    var open by remember(activities.first().id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChillerControl(activityGroupLabel(activities), { open = !open }, icon = if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown)
        AnimatedVisibility(open, enter = if (reduced) EnterTransition.None else expandVertically(tween(180)), exit = if (reduced) ExitTransition.None else shrinkVertically(tween(160))) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { activities.filter { it.status !in setOf("failed", "error", "conflict") }.forEach { ToolRow(it, reduced, onUndo, canUndo) } }
        }
        activities.filter { it.status in setOf("failed", "error", "conflict") }.forEach { ToolRow(it, reduced, onUndo, canUndo) }
    }
}

@Composable
private fun ToolRow(activity: Activity, reduced: Boolean, onUndo: (String) -> Unit, canUndo: Boolean) {
    val c = LocalChillerPalette.current
    var open by remember(activity.id) { mutableStateOf(activity.status in setOf("failed", "error", "conflict")) }
    LaunchedEffect(activity.status) { if (activity.status in setOf("failed", "error", "conflict")) open = true }
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

@Composable
private fun Inspector(activities: List<Activity>, onUndo: (String) -> Unit, canUndo: Boolean, reduced: Boolean, modifier: Modifier, selectedId: String?, onSelected: (String) -> Unit, onWidth: (Float) -> Unit) {
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
                ChillerIconButton(Icons.Outlined.KeyboardArrowLeft, "Расширить панель", { onWidth(24f) })
                ChillerIconButton(Icons.Outlined.KeyboardArrowRight, "Сузить панель", { onWidth(-24f) })
            }
            if (shown.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Difference, null, Modifier.size(23.dp), tint = c.faint)
                    Spacer(Modifier.height(13.dp))
                    Text(if (all) "Пока без действий" else "Пока без изменений", color = c.muted, fontSize = 12.sp)
                    Spacer(Modifier.height(5.dp))
                    Text("Результаты работы появятся здесь", color = c.faint, fontSize = 11.sp)
                }
            } else LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(shown, key = { it.id }) { activity ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Hint(activity.target.ifBlank { toolLabel(activity.name) }) {
                            ChillerControl(activity.target.ifBlank { toolLabel(activity.name) }, { onSelected(activity.id) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Description, style = if (selectedId == activity.id) ControlStyle.SELECTED else ControlStyle.QUIET)
                        }
                        activity.diff?.let { diff ->
                            val lines = diff.lines()
                            Text("+${lines.count { it.startsWith('+') && !it.startsWith("+++ ") }}  −${lines.count { it.startsWith('-') && !it.startsWith("--- ") }}", color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp))
                        }
                        if (selectedId == activity.id) ToolRow(activity, reduced, onUndo, canUndo)
                    }
                }
            }
        }
    }
}

@Composable
private fun Question(question: UserQuestion, onAnswer: (String) -> Unit, canAnswer: Boolean) {
    val c = LocalChillerPalette.current
    var answer by remember(question.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).border(1.dp, c.border, RoundedCornerShape(9.dp)).padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(question.question, color = c.text, fontSize = 13.sp, lineHeight = 21.sp)
        if (canAnswer) {
        question.options.forEach { ChillerControl(it, { onAnswer(it) }, icon = Icons.AutoMirrored.Outlined.ArrowForward, style = ControlStyle.OUTLINE) }
        ChillerField(answer, { answer = it }, "Ваш ответ")
        ChillerControl("Продолжить", { if (answer.isNotBlank()) onAnswer(answer) }, style = ControlStyle.PRIMARY, enabled = answer.isNotBlank())
        } else {
            question.options.forEach { Text("• $it", color = c.muted, fontSize = 12.sp) }
            Text("Вопрос сохранён после прерывания. Проверьте историю и начните новый диалог; прежняя операция не будет запущена повторно.", color = c.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Settings(url: String, model: String, thinking: Boolean, configured: Boolean, allow: Boolean,
    preferences: UiPreferences, connection: ConnectionState, onCheck: () -> Unit, onCancelCheck: () -> Unit,
    onDismiss: () -> Unit, onSave: (String, String, Boolean, String?, Boolean, UiPreferences) -> Unit) {
    val c = LocalChillerPalette.current
    var page by remember { mutableStateOf("Модели") }
    var nextUrl by remember { mutableStateOf(url) }
    var nextModel by remember { mutableStateOf(model) }
    var nextThinking by remember { mutableStateOf(thinking) }
    var key by remember { mutableStateOf("") }
    var clear by remember { mutableStateOf(false) }
    var nextAllow by remember { mutableStateOf(allow) }
    var nextView by remember { mutableStateOf(preferences) }
    val changed = nextUrl.trim() != url || nextModel.trim() != model || nextThinking != thinking || key.isNotEmpty() || clear
    val dialogFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { dialogFocus.requestFocus() }
        Column(Modifier.widthIn(max = 540.dp).fillMaxWidth().heightIn(max = 700.dp).focusRequester(dialogFocus).onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onDismiss(); true } else false
        }.focusable().clip(RoundedCornerShape(13.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(13.dp)).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Настройки", color = c.text, fontSize = 20.sp)
                Spacer(Modifier.weight(1f)); ChillerIconButton(Icons.Outlined.Close, "Закрыть настройки", onDismiss)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("Модели", "Внешний вид", "Разрешения").forEach { name -> ChillerControl(name, { page = name }, style = if (page == name) ControlStyle.SELECTED else ControlStyle.QUIET) }
            }
            when (page) {
                "Модели" -> {
                    ChillerField(nextUrl, { nextUrl = it }, "Адрес API", "https://api.deepseek.com")
                    ChillerField(nextModel, { nextModel = it }, "Модель", "deepseek-flash")
                    ChillerField(key, { key = it }, if (configured) "API-ключ · задан" else "API-ключ", if (configured) "Пустое поле сохраняет текущий ключ" else "Введите ключ", secret = true)
                    Text("UI-ключ хранится до выхода. .env / API_TOKEN читается при запуске из папки проекта.", color = c.muted, fontSize = 12.sp, lineHeight = 18.sp)
                    if (configured) ChillerControl(if (clear) "Ключ будет удалён" else "Удалить ключ из памяти", { clear = !clear })
                    SettingRow("Рассуждения модели", "Поддержка зависит от провайдера", nextThinking) { nextThinking = it }
                    val connectionText = when (connection.status) {
                        "missing" -> "Ключ не задан"
                        "checking" -> "Проверка соединения…"
                        "verified" -> "Доступ к модели проверен · ${formatCheckTime(connection.checkedAt)}"
                        "failed" -> connection.message
                        else -> "Ключ задан · соединение не проверено"
                    }
                    Text(if (changed) "Примените изменения перед проверкой" else connectionText, color = if (connection.status == "failed" && !changed) c.negative else c.muted, fontSize = 12.sp, lineHeight = 18.sp)
                    Text("Проверка отправляет один запрос до 128 выходных токенов, без файлов и инструментов. Проверяет доступ, а не все возможности модели.", color = c.muted, fontSize = 11.sp, lineHeight = 17.sp)
                    if (connection.status == "checking") ChillerControl("Отменить проверку", onCancelCheck, style = ControlStyle.OUTLINE)
                    else ChillerControl("Проверить подключение", onCheck, style = ControlStyle.OUTLINE, enabled = configured && !changed)
                }
                "Внешний вид" -> {
                    Text("Тема", color = c.muted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("system" to "Системная", "light" to "Светлая", "dark" to "Тёмная").forEach { (id, label) -> ChillerControl(label, { nextView = nextView.copy(theme = id) }, style = if (nextView.theme == id) ControlStyle.SELECTED else ControlStyle.QUIET) }
                    }
                    Text("Плотность", color = c.muted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("normal" to "Обычная", "compact" to "Компактная").forEach { (id, label) -> ChillerControl(label, { nextView = nextView.copy(density = id) }, style = if (nextView.density == id) ControlStyle.SELECTED else ControlStyle.QUIET) }
                    }
                    SettingRow("Уменьшить движение", "Без пространственных переходов", nextView.reducedMotion) { nextView = nextView.copy(reducedMotion = it) }
                }
                else -> SettingRow("Разрешить команды", "Процессы доступны в режиме изменений. Это не OS sandbox.", nextAllow) { nextAllow = it }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                ChillerControl("Отмена", onDismiss)
                Spacer(Modifier.width(8.dp))
                ChillerControl("Применить", { onSave(nextUrl.trim(), nextModel.trim(), nextThinking, if (clear) "" else key.takeIf { it.isNotBlank() }, nextAllow, nextView); key = "" }, style = ControlStyle.PRIMARY, enabled = nextUrl.isNotBlank() && nextModel.isNotBlank())
            }
        }
    }
}

@Composable
private fun RenameDialog(session: SessionInfo, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val c = LocalChillerPalette.current
    var title by remember(session.id) { mutableStateOf(session.title) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(360.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Название диалога", color = c.text, fontSize = 18.sp)
            ChillerField(title, { title = it.take(120) }, "Название")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ChillerControl("Отмена", onDismiss)
                ChillerControl("Сохранить", { onSave(title.trim()) }, style = ControlStyle.PRIMARY, enabled = title.trim().length in 1..120)
            }
        }
    }
}

@Composable
private fun ExportPreview(content: String, format: String, onDismiss: () -> Unit, onSave: () -> Unit) {
    val c = LocalChillerPalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(680.dp).heightIn(max = 650.dp).clip(RoundedCornerShape(13.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(13.dp)).padding(23.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Экспорт ${if (format == "json") "JSON" else "Markdown"}", color = c.text, fontSize = 18.sp, letterSpacing = (-.4).sp)
                Spacer(Modifier.weight(1f)); ChillerIconButton(Icons.Outlined.Close, "Закрыть экспорт", onDismiss)
            }
            Text("Проверьте содержимое перед сохранением. Известные поля ключей скрыты; произвольный текст может содержать личные данные.", color = c.muted, fontSize = 12.sp, lineHeight = 19.sp)
            SelectionContainer {
                Text(content, color = c.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 150.dp, max = 430.dp).verticalScroll(rememberScrollState()).background(c.canvas).padding(13.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ChillerControl("Отмена", onDismiss); Spacer(Modifier.width(8.dp)); ChillerControl("Сохранить файл…", onSave, style = ControlStyle.PRIMARY)
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

private fun formatCheckTime(value: String?): String = runCatching {
    java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))
}.getOrDefault(value.orEmpty())
private fun friendlyModel(model: String) = modelLabel(model).ifBlank { "Выбрать модель" }

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
