package dev.chiller.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.draganddrop.dragAndDropTarget
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
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.chiller.app.di.UiPreferences
import dev.chiller.app.di.AgentOptions
import dev.chiller.app.di.visionEnabled
import dev.chiller.app.di.entry
import dev.chiller.app.di.SendReceipt
import dev.chiller.app.di.ConnectionState
import dev.chiller.app.di.ModelCatalogState
import dev.chiller.contracts.*
import dev.chiller.design.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*
import java.awt.Image as AwtImage
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.ImageReader

enum class WorkspaceLayout { FOCUS, STUDIO }
private data class MessageActionTarget(val index: Int, val content: String, val role: String, val sessionId: String?, val historySize: Int)

/**
 * Geometry and behaviour of a title bar the app draws itself: macOS full-window content with floating traffic lights,
 * or a Windows custom title bar under the native caption buttons.
 */
class TitleBarSpec(
    val height: androidx.compose.ui.unit.Dp = 30.dp,
    /** Room for the native buttons at the leading edge (macOS traffic lights); 0 when they are elsewhere. */
    val trafficLightsWidth: androidx.compose.ui.unit.Dp = 78.dp,
    /** Room for the native caption buttons at the trailing edge (Windows minimize, maximize, close). */
    val captionButtonsWidth: androidx.compose.ui.unit.Dp = 0.dp,
    /** Renders an area that moves the window when dragged, e.g. Compose's WindowDraggableArea. */
    val dragArea: @Composable (Modifier) -> Unit,
    /** Applied to the whole window content; tells a native title bar which pointer events belong to controls. */
    val hitTest: Modifier = Modifier,
    /** The application menu, drawn in the toolbar with its accelerators when the window has no system menu bar. */
    val menu: List<AppMenuSection>? = null,
    /** Called with the effective app theme so native window buttons can match it. */
    val onDark: (Boolean) -> Unit = {},
)
/** Everything the comparison page needs from the app. */
class ComparisonModel(
    val state: dev.chiller.app.di.ComparisonState, val endpoint: String, val models: List<String>,
    val tasks: List<Pair<String, String>>, val actions: ComparisonActions,
)

enum class DesktopAction { NEW_CHAT, SEND, SETTINGS, STOP, FOCUS, STUDIO, SIDEBAR, COMPARE }

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
    recentWorkspaces: List<String> = emptyList(), onOpenWorkspace: (String) -> Unit = {},
    sessionWorkspaces: Map<String, String> = emptyMap(),
    onStartDemo: (() -> Unit)? = null,
    onCloseWorkspace: () -> Unit = {},
    subagents: List<SubagentSnapshot> = emptyList(), onCancelSubagent: (String) -> Unit = {},
    /** Full settings page content and actions; null builds a basic page from the parameters above. */
    settingsData: SettingsData? = null, settingsActions: SettingsActions? = null,
    openSettingsOnStart: String? = null,
    /** Model comparison page content; null hides the feature. */
    comparison: ComparisonModel? = null,
    /** Undo all file changes of one turn (change ids oldest first). */
    onRewindTurn: (List<String>) -> Unit = {},
    /** Changes already undone (from the session journal); rewind offers only what remains. */
    undoneChanges: Set<String> = emptySet(),
    /** Files changed outside the agent's file tools during the latest turn. */
    outsideChanges: dev.chiller.app.di.OutsideChanges? = null,
    /** Result of the project's check command after the last editing run. */
    verify: dev.chiller.app.di.VerifyState = dev.chiller.app.di.VerifyState(),
    onDismissVerify: () -> Unit = {},
    openComparisonOnStart: Boolean = false,
    onOpenTrace: (() -> Unit)? = null,
    /** macOS full-window content: the native title bar is transparent and the traffic lights float over the app. */
    titleBar: TitleBarSpec? = null,
    buildStamp: String? = null,
    onPreviewDeleteSession: suspend (String) -> String? = { null }, onDeleteSession: suspend (String) -> Boolean = { false },
    onPreviewFile: suspend (String) -> ByteArray? = { null }, onRevealFile: (String) -> Unit = {},
    onImportTextAttachment: suspend (String, String) -> ComposerAttachment? = { _, _ -> null },
    onImportFiles: suspend (String, List<String>) -> List<ComposerAttachment> = { _, _ -> emptyList() },
    onImportImageAttachment: suspend (String, ByteArray) -> ComposerAttachment? = { _, _ -> null },
    onSendWithAttachments: (suspend (String, ExecutionMode, String, List<ComposerAttachment>) -> SendReceipt)? = null,
    onDeleteMessage: suspend (Int) -> Boolean = { false },
    onEditMessage: suspend (Int, String, ExecutionMode) -> SendReceipt = { _, _, _ -> SendReceipt(false, "", "", "Редактирование недоступно") },
    onRecoverInterrupted: () -> Unit = {},
    /** Pinned and hidden projects, the dialog's folder state and project actions. */
    projects: ProjectsModel = ProjectsModel(),
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
    val latestMessages by rememberUpdatedState(state.messages)
    LaunchedEffect(selectedSessionId) { sendError = null; composing = false }
    var renameTarget by remember { mutableStateOf<SessionInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionInfo?>(null) }
    var deletePreview by remember { mutableStateOf<String?>(null) }
    var deletePreviewLoading by remember { mutableStateOf(false) }
    var deletePreviewError by remember { mutableStateOf<String?>(null) }
    var deleteInFlight by remember { mutableStateOf(false) }
    var deleteRequestVersion by remember { mutableIntStateOf(0) }
    val latestSelectedSession by rememberUpdatedState(selectedSessionId)
    val latestActive by rememberUpdatedState(pendingSend || state.status in setOf("preparing", "streaming", "executing"))
    var selectedActivityId by remember { mutableStateOf<String?>(null) }
    var messageEditTarget by remember { mutableStateOf<MessageActionTarget?>(null) }
    var messageDeleteTarget by remember { mutableStateOf<MessageActionTarget?>(null) }
    var messageEditText by remember { mutableStateOf("") }
    var messageActionBusy by remember { mutableStateOf(false) }
    var messageActionError by remember { mutableStateOf<String?>(null) }
    fun targetMessage(index: Int, content: String): MessageActionTarget? {
        val message = state.messages.getOrNull(index) ?: return null
        if (message.content.orEmpty() != content) return null
        return MessageActionTarget(index, content, message.role, selectedSessionId, state.messages.size)
    }
    fun targetStillCurrent(target: MessageActionTarget): Boolean {
        val message = latestMessages.getOrNull(target.index)
        return latestSession == target.sessionId && latestMessages.size == target.historySize && message != null && message.role == target.role && message.content.orEmpty() == target.content
    }
    var sidebar by remember { mutableStateOf(true) }
    var switcher by remember { mutableStateOf(false) }
    val tree = remember(sessions, sessionWorkspaces, recentWorkspaces, projects.pinned, projects.hidden, workspacePath) {
        projectTree(sessions, sessionWorkspaces, recentWorkspaces, projects.pinned, projects.hidden, workspacePath, projects.excluded)
    }
    var settings by remember { mutableStateOf(openSettingsOnStart != null) }
    var settingsSection by remember { mutableStateOf(SettingsSection.entries.firstOrNull { it.name == openSettingsOnStart } ?: SettingsSection.CONNECTION) }
    // Every way into settings starts at the connection, as before; the section is shared by the sidebar and the page.
    val openSettings = { settingsSection = SettingsSection.CONNECTION; settings = true }
    var comparing by remember { mutableStateOf(openComparisonOnStart) }
    val drafts = remember { mutableStateMapOf<String, String>() }
    val attachmentsBySession = remember { mutableStateMapOf<String, List<ComposerAttachment>>() }
    val pendingImportsBySession = remember { mutableStateMapOf<String, Int>() }
    val draftKey = selectedSessionId ?: "new"
    val draft = drafts[draftKey].orEmpty()
    val draftAttachments = attachmentsBySession[draftKey].orEmpty()
    val updateDraft: (String) -> Unit = { drafts[draftKey] = it }
    val updateAttachments: (String, List<ComposerAttachment>) -> Unit = { key, attachments ->
        if (attachments.isEmpty()) attachmentsBySession.remove(key)
        else attachmentsBySession[key] = attachments.distinctBy { it.artifactId }
    }
    val appendAttachments: (String, List<ComposerAttachment>) -> Boolean = { key, additions ->
        val merged = mergeComposerAttachments(attachmentsBySession[key].orEmpty(), additions)
        if (merged == null) false else { attachmentsBySession[key] = merged; true }
    }
    val removeAttachment: (String, String) -> Unit = { key, artifactId ->
        updateAttachments(key, attachmentsBySession[key].orEmpty().filterNot { it.artifactId == artifactId })
    }
    val updateImportCount: (String, Boolean) -> Boolean = { key, starting ->
        if (starting && pendingSend) false
        else {
            val next = nextPendingImportCount(pendingImportsBySession[key] ?: 0, if (starting) 1 else -1)
            if (next == null) pendingImportsBySession.remove(key) else pendingImportsBySession[key] = next
            true
        }
    }
    var mode by remember { mutableStateOf(ExecutionMode.PLAN) }
    val reducedMotion = view.reducedMotion
    var showCompactChanges by remember { mutableStateOf(false) }
    val inspectorWidth = view.inspectorWidth.dp
    val density = LocalDensity.current
    val inspectorResize = rememberDraggableState { delta -> view = view.copy(inspectorWidth = (inspectorWidth - with(density) { delta.toDp() }).value.coerceIn(280f, 480f)) }
    val active = pendingSend || state.status in setOf("preparing", "streaming", "executing")
    val demoCompleted = isCompletedDemo(isDemo, state.status)
    val empty = state.messages.none { it.role in setOf("user", "assistant") } && state.activities.isEmpty() && state.error == null
    val contextNote = state.contextEstimatedTokens?.let { tokens -> "Оценка входа ≈ $tokens токенов${state.contextOutputReserve?.let { "; резерв ответа: $it" }.orEmpty()}${state.contextReportedTokens?.let { "; провайдер: $it" }.orEmpty()}${if (state.droppedContextMessages > 0 || state.prunedContextResults > 0) " · контекст сокращён" else ""}" }
    // "Продолжить" after the step limit: the same task goes on in the current mode, with its history.
    val continueTask: () -> Unit = {
        if (!active && !pendingSend) {
            pendingSend = true; sendError = null
            scope.launch {
                try {
                    val receipt = onSend(CONTINUE_PROMPT, mode, newId())
                    if (!receipt.accepted && latestSession == receipt.sessionId) sendError = receipt.reason ?: "Сообщение не принято"
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { sendError = "Не удалось продолжить." }
                finally { pendingSend = false }
            }
        }
    }
    val send = {
        if ((draft.isNotBlank() || draftAttachments.isNotEmpty()) && !active && !pendingSend && !composing && (pendingImportsBySession[draftKey] ?: 0) == 0) {
            val sentKey = draftKey; val sentText = draft; val requestId = newId()
            val sentAttachments = draftAttachments.toList()
            val sentPrompt = promptWithAttachments(sentText, sentAttachments)
            pendingSend = true; sendError = null
            scope.launch {
                try {
                    val receipt = onSendWithAttachments?.invoke(sentText.trim(), mode, requestId, sentAttachments)
                        ?: onSend(sentPrompt, mode, requestId)
                    if (receipt.accepted) {
                        if (receipt.clientRequestId == requestId && receipt.sessionId == sentKey && latestSession == sentKey) {
                            if (drafts[sentKey] == sentText) drafts[sentKey] = ""
                            if (attachmentsBySession[sentKey] == sentAttachments) attachmentsBySession.remove(sentKey)
                        }
                    } else if (latestSession == receipt.sessionId) sendError = receipt.reason ?: "Сообщение не принято"
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { sendError = "Не удалось отправить сообщение. Текст сохранён." }
                finally { pendingSend = false }
            }
        }
    }
    val handleAction by rememberUpdatedState<(DesktopAction) -> Unit> { action ->
        when (action) {
            DesktopAction.NEW_CHAT -> onNewSession()
            DesktopAction.SEND -> if (!settings && renameTarget == null && exportPreview == null) send()
            DesktopAction.SETTINGS -> if (!active) { comparing = false; openSettings() }
            DesktopAction.COMPARE -> if (!active && comparison != null) { settings = false; comparing = true }
            DesktopAction.STOP -> if (active) onStop()
            DesktopAction.FOCUS -> updateView(view.copy(layout = "focus"))
            DesktopAction.STUDIO -> updateView(view.copy(layout = "studio"))
            DesktopAction.SIDEBAR -> sidebar = !sidebar
        }
    }
    LaunchedEffect(actions) { actions.collect { handleAction(it) } }
    val rootFocus = remember { FocusRequester() }
    val sidebarSearch = remember { FocusRequester() }
    LaunchedEffect(Unit) { rootFocus.requestFocus() }
    LaunchedEffect(dark, titleBar) { titleBar?.onDark?.invoke(dark) }
    // Provider-given model names (an optional extension of /models), for the composer's model button.
    val modelNames = remember(catalog.models) { catalog.models.mapNotNull { m -> m.displayName?.takeIf { it.isNotBlank() }?.let { m.id to it } }.toMap() }
    ChillerTheme(darkTheme = dark, reducedMotion = reducedMotion, compact = compactDensity) { CompositionLocalProvider(LocalModelNames provides modelNames) {
        val c = LocalChillerPalette.current
        val menu = titleBar?.menu
        BoxWithConstraints(Modifier.fillMaxSize().then(titleBar?.hitTest ?: Modifier).background(c.canvas).focusRequester(rootFocus).chillerKeyboardFocusTracking().onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && (it.isMetaPressed || it.isCtrlPressed)) {
                when (it.key) {
                    Key.Enter -> { handleAction(DesktopAction.SEND); true }
                    Key.K -> { sidebar = true; runCatching { sidebarSearch.requestFocus() }; true }
                    Key.P -> { if (!settings) switcher = true; true }
                    Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine -> {
                        val index = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine).indexOf(it.key)
                        tree.projects.getOrNull(index)?.let { group -> if (!settings) projects.onNewIn(group.path) }
                        true
                    }
                    // Without a system menu bar its accelerators live here.
                    else -> menu?.commandFor(it.key)?.let { command -> command.onClick(); true } ?: false
                }
            } else false
        }.focusable()) {
            val showSidebar = sidebar && maxWidth >= 740.dp
            Row(Modifier.fillMaxSize()) {
                if (showSidebar) Sidebar(sessions, tree, projects, selectedSessionId, workspacePath, apiKeyConfigured, !active, onNewSession, onSelectSession, onChooseWorkspace,
                    onSettings = { openSettings() }, onTheme = { updateView(view.copy(theme = if (dark) "light" else "dark")) }, onRename = { renameTarget = it }, onDelete = { session ->
                        if (!active && selectedSessionId != session.id && deleteTarget == null && !deletePreviewLoading && !deleteInFlight) {
                            deleteTarget = session
                            deletePreview = null
                            deletePreviewError = null
                            deletePreviewLoading = true
                            val requestVersion = ++deleteRequestVersion
                            scope.launch {
                                try {
                                    val preview = onPreviewDeleteSession(session.id)
                                    if (deleteRequestVersion == requestVersion && deleteTarget?.id == session.id) {
                                        if (preview.isNullOrBlank()) deletePreviewError = "Не удалось подготовить предварительный просмотр удаления."
                                        else deletePreview = preview
                                    }
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) {
                                    if (deleteRequestVersion == requestVersion && deleteTarget?.id == session.id) deletePreviewError = "Не удалось загрузить сведения для удаления."
                                } finally {
                                    if (deleteRequestVersion == requestVersion && deleteTarget?.id == session.id) deletePreviewLoading = false
                                }
                            }
                        }
                    }, onExport = onExportSession, connection = connection, width = 248f * density.fontScale.coerceIn(1f, 1.2f),
                    running = active, searchFocus = sidebarSearch, titleBar = titleBar,
                    settingsOpen = settings && !active, settingsSection = settingsSection, onSettingsSection = { settingsSection = it }, onCloseSettings = { settings = false }, version = settingsData?.version)
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.border.copy(alpha = .7f)))
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val compact = maxWidth < 860.dp || density.fontScale > 1.3f
                    Column(Modifier.fillMaxSize()) {
                        Toolbar(
                            titleBar, leadingInset = titleBar != null && titleBar.trafficLightsWidth > 0.dp && !showSidebar,
                            sessions.firstOrNull { it.id == selectedSessionId }?.title, workspacePath, layout, active, state.status, state.question != null,
                            onLayout = { updateView(view.copy(layout = it.name.lowercase())) }, onSidebar = { sidebar = !sidebar }, onSettings = { openSettings() },
                            canConfigure = !active, compact = compact, compactChanges = showCompactChanges,
                            onCompactChanges = { showCompactChanges = !showCompactChanges },
                        )
                        RunProgressBar(active)
                        // The page under the toolbar moves with the sidebar: settings come in from the right, the chat from the left.
                        val page = when { settings && !active -> "settings"; comparing && comparison != null && !active -> "compare"; else -> "chat" }
                        AnimatedContent(page, Modifier.weight(1f).fillMaxWidth(), label = "page", transitionSpec = {
                            if (reducedMotion) EnterTransition.None togetherWith ExitTransition.None
                            else {
                                val direction = if (targetState == "chat") -1 else 1
                                (fadeIn(tween(200, 60)) + slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { direction * it / 24 }) togetherWith fadeOut(tween(100))
                            }
                        }) { current ->
                        if (current == "settings") {
                            val data = (settingsData ?: SettingsData(providerBaseUrl, providerModel, thinkingEnabled, apiKeyConfigured, connection = connection, catalog = catalog, allowProcesses = allowProcesses))
                                .copy(preferences = view)
                            val base = settingsActions ?: SettingsActions()
                            SettingsPage(data, SettingsActions(
                                applyConnection = { url, model, thinking, key ->
                                    val oldDraft = draft
                                    scope.launch { onConfigureProvider(url, model, thinking, key)?.let { id -> drafts.putIfAbsent(id, oldDraft) } }
                                },
                                checkConnection = onCheckConnection, cancelCheck = onCancelConnection, refreshModels = onRefreshModels,
                                preferences = updateView, allowProcesses = onAllowProcessesChange, agent = base.agent,
                                removeRecent = base.removeRecent, openPath = base.openPath, openUrl = base.openUrl, deleteDemos = base.deleteDemos,
                                saveCredential = base.saveCredential, deleteCredential = base.deleteCredential,
                                saveCustomProvider = base.saveCustomProvider, deleteCustomProvider = base.deleteCustomProvider, costBudget = base.costBudget,
                                refreshCaptureTargets = base.refreshCaptureTargets, selectCaptureTarget = base.selectCaptureTarget,
                                openComparison = comparison?.let { { settings = false; comparing = true } },
                                setVerifyCommand = base.setVerifyCommand,
                                close = { settings = false },
                            ), settingsSection, { settingsSection = it }, Modifier.fillMaxSize(), showNav = !showSidebar)
                        } else if (current == "compare" && comparison != null) {
                            ComparisonPage(comparison.state, comparison.endpoint, comparison.models, comparison.tasks,
                                ComparisonActions(comparison.actions.start, comparison.actions.cancel, comparison.actions.show, comparison.actions.reset, close = { comparing = false }),
                                Modifier.fillMaxSize())
                        } else
                        Row(Modifier.fillMaxSize()) {
                            if (!(compact && layout == WorkspaceLayout.STUDIO && showCompactChanges)) {
                                Column(Modifier.weight(1f).fillMaxHeight()) {
                                    if (empty) {
                                        val (line, color) = connectionSummary(connection, apiKeyConfigured)
                                        val resume = sessions.firstOrNull { it.id != selectedSessionId }?.let { ResumeItem(it.id, it.title, it.updatedAt, sessionWorkspaces[it.id]) }
                                        StartScreen(
                                            workspacePath, recentProjects(recentWorkspaces, sessions, sessionWorkspaces).filter { it.path != workspacePath }, friendlyModel(providerModel), line, color,
                                            onOpenFolder = onChooseWorkspace, onOpenPath = { if (!active) onOpenWorkspace(it) },
                                            onStarter = { starter -> updateDraft(starter.prompt); if (starter.needsEdits) mode = ExecutionMode.WORKSPACE_EDIT },
                                            modifier = Modifier.weight(1f).fillMaxWidth(),
                                            resume = resume, onResume = { if (!active) onSelectSession(it) },
                                            onDemo = onStartDemo?.let { start -> { if (!active) start() } },
                                            onCloseProject = { if (!active) onCloseWorkspace() },
                                            connection = when {
                                                connection.status == "verified" -> null
                                                !apiKeyConfigured -> ConnectionPrompt("Добавить ключ") { openSettings() }
                                                connection.status == "checking" -> null
                                                else -> ConnectionPrompt("Проверить") { onCheckConnection() }
                                            },
                                        )
                                    } else {
                                        key(selectedSessionId) {
                                            Conversation(state, reducedMotion, onAnswerQuestion, onUndo, !active,
                                                { text -> updateDraft(text); sendError = null }, onPreviewFile, onRevealFile,
                                                onEdit = { index, text -> if (!active) targetMessage(index, text)?.let { messageEditTarget = it; messageEditText = text; messageActionError = null } },
                                                onDelete = { index, text -> if (!active) targetMessage(index, text)?.let { messageDeleteTarget = it; messageActionError = null } },
                                                onRecoverInterrupted = onRecoverInterrupted,
                                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                                onRewind = { ids -> if (!active) onRewindTurn(ids) }, onContinue = continueTask, undoneChanges = undoneChanges, outsideChanges = outsideChanges)
                                        }
                                    }
                                    Box(Modifier.fillMaxWidth().padding(horizontal = if (compact) 20.dp else 32.dp).padding(top = 6.dp, bottom = 18.dp), contentAlignment = Alignment.Center) {
                                        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            sendError?.let { Text(it, color = c.negative, fontSize = 12.sp, lineHeight = 18.sp) }
                                            VerifyBanner(verify, onDismissVerify)
                                            if (projects.workspaceMissing && workspacePath != null)
                                                MissingFolderBanner(workspacePath, !active, { projects.onRelocate(workspacePath) }, { projects.onNewIn(null) })
                                            ContextStatus(state)

                                            if (demoCompleted) {
                                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.sidebar).border(1.dp, c.border, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Outlined.CheckCircleOutline, null, Modifier.size(17.dp), tint = c.positive)
                                                    Spacer(Modifier.width(9.dp))
                                                    Column(Modifier.weight(1f)) {
                                                        Text(if (state.status == "completed") "Демо завершено" else "Демо остановлено", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                                        Text("История учебная и не отправляет новые запросы", color = c.muted, fontSize = 11.sp, lineHeight = 16.sp)
                                                    }
                                                    ChillerControl("Начать обычный диалог", { if (!active) onNewSession() }, icon = Icons.AutoMirrored.Outlined.ArrowForward, style = ControlStyle.PRIMARY, enabled = !active)
                                                }
                                            } else Composer(draft, updateDraft, mode, { mode = it }, providerModel, active, send, onStop, { openSettings() }, Modifier.fillMaxWidth(), apiKeyConfigured, models, { selected ->
                                                val oldDraft = draft
                                                scope.launch { onSelectModel(selected)?.let { id -> drafts.putIfAbsent(id, oldDraft) } }
                                            }, { composing = it }, pendingSend, contextNote, catalog, onRefreshModels, onCancelCatalogRefresh, allowProcesses, onAllowProcessesChange,
                                                workspacePath, onChooseWorkspace, state.contextReportedTokens?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: state.contextEstimatedTokens, state.contextReportedTokens == null, state.contextWindowTokens, connectionSummary(connection, apiKeyConfigured).second,
                                                attachments = draftAttachments, attachmentOwnerKey = draftKey, vision = visionEnabled(settingsData?.agent ?: AgentOptions(), providerModel, catalog.entry(providerModel)),
                                                importing = (pendingImportsBySession[draftKey] ?: 0) > 0,
                                                onAppendAttachments = appendAttachments,
                                                onRemoveAttachment = removeAttachment,
                                                onImportState = updateImportCount,
                                                onImportTextAttachment = onImportTextAttachment, onImportFiles = onImportFiles, onImportImageAttachment = onImportImageAttachment,
                                                folderMenu = FolderMenu(workspacePath, tree.projects.map { it.path }, projects.folderLocked, projects.workspaceMissing,
                                                    onOpen = { if (!active) onOpenWorkspace(it) }, onChoose = onChooseWorkspace,
                                                    onDetach = { if (!active) { if (projects.folderLocked) projects.onNewIn(null) else onCloseWorkspace() } },
                                                    onRelocate = projects.onRelocate))
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
                                    StudioPanel(state, subagents, onUndo, !active, reducedMotion, onCancelSubagent, onOpenTrace, Modifier.weight(1f).fillMaxHeight())
                                }
                            }
                        }
                        }
                    }
                }
            }
        }
        renameTarget?.let { target -> RenameDialog(target, { renameTarget = null }) { title ->
            scope.launch { if (onRenameSession(target.id, title)) renameTarget = null }
        } }
        deleteTarget?.let { target -> SessionDeleteDialog(
            session = target,
            preview = deletePreview,
            loading = deletePreviewLoading,
            previewError = deletePreviewError.takeIf { deletePreview == null && !deletePreviewLoading },
            deleting = deleteInFlight,
            deleteError = deletePreviewError.takeIf { deletePreview != null },
            onDismiss = {
                if (!deleteInFlight) {
                    deleteRequestVersion++
                    deleteTarget = null
                    deletePreview = null
                    deletePreviewLoading = false
                    deletePreviewError = null
                }
            },
            onDelete = {
                if (!deleteInFlight && deletePreview != null && !deletePreviewLoading) {
                    if (latestActive || latestSelectedSession == target.id) {
                        deletePreviewError = "Текущий или выполняющийся диалог нельзя удалить."
                    } else {
                        deleteInFlight = true
                        deletePreviewError = null
                        scope.launch {
                            try {
                                if (onDeleteSession(target.id)) {
                                    deleteTarget = null
                                    deletePreview = null
                                    deletePreviewError = null
                                } else deletePreviewError = "Удаление не завершено. Обновите предварительный просмотр и попробуйте снова."
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { deletePreviewError = "Не удалось завершить удаление. Обновите предварительный просмотр и попробуйте снова." }
                            finally { deleteInFlight = false }
                        }
                    }
                }
            },
        ) }
        messageEditTarget?.let { target -> MessageEditDialog(
            text = messageEditText, error = messageActionError, busy = messageActionBusy,
            onText = { messageEditText = it }, onDismiss = { if (!messageActionBusy) { messageEditTarget = null; messageActionError = null } },
            onSubmit = {
                if (!targetStillCurrent(target)) messageActionError = "История диалога изменилась. Закройте это окно и выберите сообщение снова."
                if (!messageActionBusy && !latestActive && targetStillCurrent(target)) {
                    messageActionBusy = true; messageActionError = null
                    scope.launch {
                        try {
                            if (!targetStillCurrent(target)) { messageActionError = "История диалога изменилась. Закройте это окно и выберите сообщение снова."; return@launch }
                            val receipt = onEditMessage(target.index, messageEditText, mode)
                            if (receipt.accepted && receipt.sessionId == target.sessionId) { messageEditTarget = null; messageActionError = null }
                            else messageActionError = receipt.reason ?: "Не удалось изменить сообщение в исходном диалоге. Текст сохранён."
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { messageActionError = "Не удалось изменить сообщение. Текст сохранён." }
                        finally { messageActionBusy = false }
                    }
                }
            },
        ) }
        messageDeleteTarget?.let { target -> MessageDeleteDialog(
            text = target.content, error = messageActionError, busy = messageActionBusy,
            onDismiss = { if (!messageActionBusy) { messageDeleteTarget = null; messageActionError = null } },
            onDelete = {
                if (!targetStillCurrent(target)) messageActionError = "История диалога изменилась. Закройте это окно и выберите сообщение снова."
                if (!messageActionBusy && !latestActive && targetStillCurrent(target)) {
                    messageActionBusy = true; messageActionError = null
                    scope.launch {
                        try {
                            if (!targetStillCurrent(target)) { messageActionError = "История диалога изменилась. Закройте это окно и выберите сообщение снова."; return@launch }
                            if (onDeleteMessage(target.index)) messageDeleteTarget = null else messageActionError = "Не удалось удалить ход диалога."
                        }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { messageActionError = "Не удалось удалить ход диалога." }
                        finally { messageActionBusy = false }
                    }
                }
            },
        ) }
        if (exportPreview != null) ExportPreview(exportPreview, exportFormat, onDismissExport, onSaveExport)
        if (switcher) ProjectSwitcher(tree.projects, workspacePath, onPick = { path -> switcher = false; projects.onNewIn(path) }, onChoose = onChooseWorkspace, onDismiss = { switcher = false; runCatching { rootFocus.requestFocus() } })
    } }
}

@Composable
private fun Sidebar(
    sessions: List<SessionInfo>, tree: ProjectTree, projects: ProjectsModel, selected: String?, workspace: String?, connected: Boolean, enabled: Boolean,
    onNew: () -> Unit, onSelect: (String) -> Unit, onChoose: () -> Unit, onSettings: () -> Unit, onTheme: () -> Unit, onRename: (SessionInfo) -> Unit, onDelete: (SessionInfo) -> Unit, onExport: (String, String) -> Unit, connection: ConnectionState, width: Float,
    running: Boolean, searchFocus: FocusRequester, titleBar: TitleBarSpec?,
    settingsOpen: Boolean = false, settingsSection: SettingsSection = SettingsSection.CONNECTION,
    onSettingsSection: (SettingsSection) -> Unit = {}, onCloseSettings: () -> Unit = {}, version: String? = null,
) {
    val c = LocalChillerPalette.current
    var query by remember { mutableStateOf("") }
    val shown = filterSessions(sessions, query)
    // Kept outside the animated content so the dialog list keeps its scroll position across a settings visit.
    val listState = rememberLazyListState()
    val reduced = LocalChillerReducedMotion.current
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val showAll = remember { mutableStateMapOf<String, Boolean>() }
    Column(Modifier.width(width.dp).fillMaxHeight().background(c.sidebar).padding(horizontal = 12.dp)) {
        // Room for the floating traffic lights; the strip moves the window like a title bar.
        val trafficLights = titleBar != null && titleBar.trafficLightsWidth > 0.dp
        if (trafficLights) titleBar!!.dragArea(Modifier.fillMaxWidth().height(titleBar.height))
        Row(Modifier.fillMaxWidth().height(if (trafficLights) 44.dp else if (titleBar != null) titleBar.height else 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChillerMark(Modifier.size(23.dp), c.accent); Spacer(Modifier.width(9.dp))
            Text("Chiller", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.4f).sp)
            Spacer(Modifier.weight(1f))
        }
        // While settings are open the chats cannot be used, so the sidebar turns into the settings navigation.
        // Opening slides it in from the right, closing slides the dialogs back from the left.
        AnimatedContent(
            settingsOpen, Modifier.weight(1f).fillMaxWidth(), label = "sidebar",
            transitionSpec = {
                if (reduced) EnterTransition.None togetherWith ExitTransition.None
                else {
                    val direction = if (targetState) 1 else -1
                    (slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { direction * it / 3 } + fadeIn(tween(200, 40))) togetherWith
                        (slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { -direction * it / 3 } + fadeOut(tween(120)))
                }
            },
        ) { inSettings ->
        if (inSettings) Column(Modifier.fillMaxSize()) {
            SettingsNav(settingsSection, onSettingsSection, onCloseSettings, Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp), connectionDot = connectionSummary(connection, connected).second)
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border.copy(alpha = .75f)))
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 3.dp).heightIn(min = 37.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull("Chiller", version).joinToString(" "), color = c.faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp))
                ChillerIconButton(Icons.Outlined.Contrast, "Сменить тему", onTheme)
            }
        }
        else Column(Modifier.fillMaxSize()) {
        // Dialogs stay reachable while one runs: it goes on in the background.
        ChillerControl("Новый диалог", onNew, Modifier.fillMaxWidth(), icon = Icons.Outlined.Add, trailing = shortcut("N"))
        SidebarSearch(query, { query = it }, searchFocus)
        Spacer(Modifier.height(6.dp))
        val searching = query.isNotBlank()
        if (searching && shown.isEmpty()) Text("Нет диалогов с «${query.trim()}»", color = c.faint, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
        val roots = remember(tree) { tree.projects.flatMap { group -> group.sessions.map { it.id to group.path } }.toMap() }
        val row: @Composable (SessionInfo, Boolean, String?) -> Unit = { session, nested, project ->
            val runs = session.id in projects.running || (running && session.id == selected)
            SessionRow(session, session.id == selected, runs, true, { onSelect(session.id) }, { onRename(session) }, { onDelete(session) }, !running && !runs && session.id != selected, { onExport(session.id, it) },
                nested = nested, project = project, waiting = session.id in projects.waiting && session.id != selected,
                onStop = if (runs && session.id != selected) ({ projects.onStop(session.id) }) else null)
        }
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (searching) {
                if (shown.isNotEmpty()) item(key = "search-header") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text("Найдено", color = c.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("${shown.size} из ${sessions.size}", color = c.faint, fontSize = 10.sp)
                    }
                }
                items(shown, key = { it.id }) { session -> row(session, false, roots[session.id]?.let(::folderName) ?: "без папки") }
            } else projectTreeItems(tree, workspace, selected, reduced, expanded, showAll, projects, onChoose) { session, nested -> row(session, nested, null) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border.copy(alpha = .75f)))
        val (line, dot) = connectionSummary(connection, connected)
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            HoverRow(onSettings, Modifier.weight(1f), enabled = enabled) {
                Icon(Icons.Outlined.Settings, null, Modifier.size(16.dp), tint = c.muted)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("Настройки", color = c.text, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
                        Spacer(Modifier.width(6.dp))
                        Text(line, color = c.muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            ChillerIconButton(Icons.Outlined.Contrast, "Сменить тему", onTheme)
        }
        }
        }
    }
}

@Composable
private fun SidebarSearch(query: String, onQuery: (String) -> Unit, focus: FocusRequester) {
    val c = LocalChillerPalette.current
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.padding(top = 6.dp).fillMaxWidth().height(32.dp).clip(RoundedCornerShape(7.dp))
            .background(if (focused) c.surface else c.hover.copy(alpha = .55f))
            .border(1.dp, if (focused) c.border else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(7.dp)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, Modifier.size(14.dp), tint = c.faint)
        Spacer(Modifier.width(8.dp))
        BasicTextField(query, onQuery, singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp), cursorBrush = SolidColor(c.text),
            modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape && query.isNotEmpty()) { onQuery(""); true } else false }
                .semantics { contentDescription = "Поиск диалогов" },
            decorationBox = { inner -> Box { if (query.isEmpty()) Text("Поиск", color = c.faint, fontSize = 12.sp, lineHeight = 18.sp); inner() } },
        )
        if (query.isNotEmpty()) Icon(Icons.Outlined.Close, "Очистить поиск", Modifier.size(14.dp).clip(CircleShape).clickable { onQuery("") }, tint = c.faint)
        else Text(shortcut("K"), color = c.faint, fontSize = 11.sp)
    }
}

@Composable
private fun SessionRow(session: SessionInfo, selected: Boolean, running: Boolean, enabled: Boolean, onSelect: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, canDelete: Boolean, onExport: (String) -> Unit,
    nested: Boolean = false, project: String? = null, waiting: Boolean = false, onStop: (() -> Unit)? = null) {
    val c = LocalChillerPalette.current
    var menu by remember(session.id) { mutableStateOf(false) }
    var closedAt by remember { mutableStateOf(0L) }
    var rowFocused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(7.dp))
            .background(when { selected -> c.selected; hovered && enabled -> c.hover; else -> androidx.compose.ui.graphics.Color.Transparent })
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
            .onFocusChanged { rowFocused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onSelect)
            .padding(start = if (nested) 24.dp else 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Inside a project the title lines up with the project name; the icon is left for the running pulse.
        Box(Modifier.size(15.dp), contentAlignment = Alignment.Center) {
            if (waiting) Box(Modifier.size(8.dp).clip(CircleShape).background(c.focus)) else if (running) Pulse(c.accent) else if (!nested) Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(14.dp), tint = if (selected) c.muted else c.faint)
        }
        Spacer(Modifier.width(9.dp))
        Text(session.title.ifBlank { "Новый диалог" }, color = if (selected || !nested) c.text else c.text.copy(alpha = .86f), fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (project != null && !hovered && !menu) Text(project, color = c.faint, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 70.dp).padding(start = 6.dp))
        Box(Modifier.width(44.dp), contentAlignment = Alignment.CenterEnd) {
            if (hovered || menu || selected || rowFocused) {
                Box {
                    ChillerIconButton(Icons.Outlined.MoreHoriz, "Действия диалога", { if (System.currentTimeMillis() - closedAt > 200) menu = !menu }, Modifier.size(26.dp), enabled = enabled)
                    if (menu) ChillerPopover({ menu = false; closedAt = System.currentTimeMillis() }, 200.dp, below = true, alignEnd = true) {
                        Spacer(Modifier.height(5.dp))
                        onStop?.let { stop -> MenuRow("Остановить", { menu = false; stop() }, subtitle = "Задача идёт в фоне", icon = Icons.Outlined.Stop); MenuDivider() }
                        MenuRow("Переименовать", { menu = false; onRename() }, icon = Icons.Outlined.Edit)
                        MenuRow("Экспорт Markdown", { menu = false; onExport("markdown") }, icon = Icons.Outlined.Description)
                        MenuRow("Экспорт JSON", { menu = false; onExport("json") }, icon = Icons.Outlined.DataObject)
                        if (canDelete) {
                            MenuDivider()
                            MenuRow("Удалить диалог…", { menu = false; onDelete() }, icon = Icons.Outlined.DeleteOutline)
                        }
                        Spacer(Modifier.height(5.dp))
                    }
                }
            } else Text(if (waiting) "ждёт ответа" else if (running) "идёт" else relativeAge(session.updatedAt), color = if (waiting) c.focus else if (running) c.accent else c.faint, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

@Composable
private fun Toolbar(titleBar: TitleBarSpec?, leadingInset: Boolean, title: String?, workspace: String?, layout: WorkspaceLayout, active: Boolean, status: String, waitingForUser: Boolean, onLayout: (WorkspaceLayout) -> Unit, onSidebar: () -> Unit, onSettings: () -> Unit, canConfigure: Boolean, compact: Boolean, compactChanges: Boolean, onCompactChanges: () -> Unit) {
    val c = LocalChillerPalette.current
    // Empty toolbar space drags the window; controls on top keep their clicks.
    Box(Modifier.fillMaxWidth()) {
    titleBar?.dragArea?.invoke(Modifier.matchParentSize())
    // Windows caption buttons sit flush with the window's top-right corner; the toolbar ends a little before them.
    val end = titleBar?.captionButtonsWidth?.takeIf { it > 0.dp }?.plus(6.dp) ?: 15.dp
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = if (leadingInset) titleBar!!.trafficLightsWidth else 15.dp, end = end, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        titleBar?.menu?.let { AppMenuButton(it); Spacer(Modifier.width(2.dp)) }
        ChillerIconButton(Icons.Outlined.VerticalSplit, "Боковая панель", onSidebar)
        Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(workspace?.let(::folderName) ?: "Chiller", color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
            Text(" / ", color = c.faint, fontSize = 12.sp)
            Text(title?.take(44) ?: "Новый диалог", color = c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (active) { Text(if (waitingForUser) "Ждёт ответа" else statusLabel(status), color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp).padding(end = 12.dp)) }
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
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Composer(
    draft: String, onDraft: (String) -> Unit, mode: ExecutionMode, onMode: (ExecutionMode) -> Unit,
    model: String, running: Boolean, onSend: () -> Unit, onStop: () -> Unit,
    onSettings: () -> Unit, modifier: Modifier, configured: Boolean, models: List<String>,
    onModel: (String) -> Unit, onComposition: (Boolean) -> Unit, pending: Boolean, contextNote: String?,
    catalog: ModelCatalogState, onRefreshModels: () -> Unit, onCancelCatalogRefresh: () -> Unit,
    allowProcesses: Boolean, onAllow: (Boolean) -> Unit,
    workspace: String?, onChooseWorkspace: () -> Unit, contextTokens: Int?, contextIsEstimate: Boolean, effectiveWindow: Int?, connectionDot: androidx.compose.ui.graphics.Color,
    attachments: List<ComposerAttachment>, attachmentOwnerKey: String,
    importing: Boolean,
    onAppendAttachments: (String, List<ComposerAttachment>) -> Boolean,
    onRemoveAttachment: (String, String) -> Unit,
    onImportState: (String, Boolean) -> Boolean,
    onImportTextAttachment: suspend (String, String) -> ComposerAttachment?, onImportFiles: suspend (String, List<String>) -> List<ComposerAttachment>,
    onImportImageAttachment: suspend (String, ByteArray) -> ComposerAttachment?,
    vision: Boolean = false,
    folderMenu: FolderMenu? = null,
) {
    val c = LocalChillerPalette.current
    var focused by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var modelClosedAt by remember { mutableStateOf(0L) }
    var field by remember { mutableStateOf(TextFieldValue(draft)) }
    var importError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(draft) { if (field.text != draft) { field = TextFieldValue(draft, TextRange(draft.length)); onComposition(false) } }
    val density = LocalDensity.current
    var measuredFieldHeight by remember { mutableStateOf(26.dp) }
    val fieldMaxHeight = 144.dp
    val shape = RoundedCornerShape(16.dp)
    val scope = rememberCoroutineScope()
    // Cards for pastes still being prepared: they appear on the key press, before any encoding or I/O.
    var preparing by remember(attachmentOwnerKey) { mutableStateOf(listOf<PendingPaste>()) }
    val launchImport: (suspend () -> Unit) -> Unit = { operation ->
        if (pending || !onImportState(attachmentOwnerKey, true)) importError = "Сообщение уже отправляется. Добавьте вложение после завершения отправки."
        else {
            scope.launch {
                importError = null
                try { operation() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { importError = "Не удалось добавить вложение. Проверьте файл и повторите попытку." }
                finally { onImportState(attachmentOwnerKey, false) }
            }
        }
    }
    suspend fun appendImported(items: List<ComposerAttachment>) {
        if (items.isEmpty()) {
            importError = "Не удалось добавить вложение. Проверьте файл и повторите попытку."
            return
        }
        if (!onAppendAttachments(attachmentOwnerKey, items)) importError = "Можно прикрепить не больше восьми файлов к одному диалогу."
    }
    val addFiles: () -> Unit = {
        launchImport {
            val paths = withContext(Dispatchers.IO) {
                val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Добавить файлы", java.awt.FileDialog.LOAD)
                dialog.isMultipleMode = true
                dialog.isVisible = true
                dialog.files.map { it.absolutePath }
            }
            if (paths.isNotEmpty()) appendImported(onImportFiles(attachmentOwnerKey, paths))
        }
    }
    val importTransferable: (Transferable) -> Unit = { transferable ->
        if (pending) importError = "Сообщение уже отправляется. Добавьте вложение после завершения отправки."
        else {
            var snapshotFailed = false
            val payload = try { snapshotComposerTransferPayload(transferable) }
            catch (_: Exception) { snapshotFailed = true; null }
            if (payload == null) {
                importError = if (snapshotFailed) "Не удалось получить данные из буфера обмена. Повторите попытку." else "Этот формат буфера обмена не поддерживается."
            } else if (payload is ComposerTransferPayload.Text && textPasteTarget(payload.value) == TextPasteTarget.FIELD) {
                // Short text goes straight into the field, synchronously, like any editor.
                val pasted = payload.value
                val start = field.selection.min.coerceIn(0, field.text.length)
                val end = field.selection.max.coerceIn(start, field.text.length)
                val text = field.text.replaceRange(start, end, pasted)
                field = TextFieldValue(text, TextRange(start + pasted.length)); onDraft(text); onComposition(false)
            } else {
                val card = PendingPaste(newId(), when (payload) {
                    is ComposerTransferPayload.Files -> if (payload.paths.size == 1) java.io.File(payload.paths.single()).name else "${payload.paths.size} ${plural(payload.paths.size, "файл", "файла", "файлов")}"
                    is ComposerTransferPayload.Image -> pasteLabelForImage(payload.value)
                    is ComposerTransferPayload.Text -> pasteLabelForText(payload.value)
                })
                preparing = preparing + card
                launchImport {
                    try {
                        when (payload) {
                            is ComposerTransferPayload.Files -> appendImported(onImportFiles(attachmentOwnerKey, payload.paths))
                            is ComposerTransferPayload.Image -> {
                                // PNG encoding (and scaling an oversized image) never runs on the UI thread.
                                val encoded = pastePreparation.withPermit { withContext(Dispatchers.Default) { encodePastedImage(payload.value) } } ?: error("Clipboard image cannot be encoded within the size limit")
                                appendImported(listOf(onImportImageAttachment(attachmentOwnerKey, encoded.png) ?: error("Image import failed")))
                                if (encoded.scaled) importError = "Изображение уменьшено до ${encoded.width}×${encoded.height}, чтобы уложиться в 4 МБ."
                            }
                            is ComposerTransferPayload.Text -> appendImported(listOf(pastePreparation.withPermit { onImportTextAttachment(attachmentOwnerKey, payload.value) } ?: error("Text import failed")))
                        }
                    } finally { preparing = preparing - card }
                }
            }
        }
    }
    val dropTarget = remember(attachmentOwnerKey, attachments) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val transferable = event.awtTransferable
                if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) && !transferable.isDataFlavorSupported(DataFlavor.imageFlavor) && !transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return false
                importTransferable(transferable)
                return true
            }
        }
    }
    val borderColor by animateColorAsState(if (focused) c.muted.copy(alpha = .55f) else c.border, tween(if (LocalChillerReducedMotion.current) 0 else 120))
    Column(
        modifier.dragAndDropTarget(shouldStartDragAndDrop = { event -> event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || event.awtTransferable.isDataFlavorSupported(DataFlavor.imageFlavor) || event.awtTransferable.isDataFlavorSupported(DataFlavor.stringFlavor) }, target = dropTarget)
            .shadow(3.dp, shape, ambientColor = c.text.copy(alpha = .04f), spotColor = c.text.copy(alpha = .04f)).clip(shape).background(c.surface)
            .border(1.dp, borderColor, shape).padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // @-mentions: project files listed once per folder, matched against the token under the cursor.
        val files by produceState(emptyList<String>(), workspace) { value = workspace?.let { root -> withContext(Dispatchers.IO) { runCatching { listProjectFiles(java.nio.file.Path.of(root)) }.getOrDefault(emptyList()) } }.orEmpty() }
        val mention = if (field.selection.collapsed && files.isNotEmpty()) mentionAt(field.text, field.selection.start) else null
        var dismissedAt by remember { mutableStateOf(-1) }
        val matches = mention?.takeIf { it.first != dismissedAt }?.let { rankFiles(files, it.second) }.orEmpty()
        var highlight by remember(mention?.second) { mutableStateOf(0) }
        fun insert(path: String) {
            val (start, _) = mention ?: return
            val end = field.selection.start
            val text = field.text.replaceRange(start, end, "@$path ")
            field = TextFieldValue(text, TextRange(start + path.length + 2)); onDraft(text)
        }
        val mentioned = mentionedFiles(field.text, files)
        BasicTextField(field, { field = it; onDraft(it.text); onComposition(it.composition != null); if (mentionAt(it.text, it.selection.start)?.first != dismissedAt) dismissedAt = -1 },
                onTextLayout = { layout -> measuredFieldHeight = with(density) { layout.size.height.toDp() }.coerceIn(26.dp, fieldMaxHeight) },
                modifier = Modifier.fillMaxWidth().height(measuredFieldHeight.coerceIn(26.dp, fieldMaxHeight)).onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.V && (event.isCtrlPressed || event.isMetaPressed)) {
                            val clipboard = runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }.getOrNull()
                            if (clipboard != null) importTransferable(clipboard)
                            return@onPreviewKeyEvent clipboard != null
                        }
                        // Shift+Tab switches Plan / Edit. Plain Tab inserts the highlighted file in the @ menu and is ignored
                        // otherwise: an invisible tab character in a chat message is never what was meant.
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Tab && !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed) {
                            if (event.isShiftPressed) { if (!running) onMode(toggledMode(mode)); return@onPreviewKeyEvent true }
                            if (matches.isEmpty()) return@onPreviewKeyEvent true
                        }
                        if (matches.isEmpty() || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> { highlight = (highlight + 1) % matches.size; true }
                            Key.DirectionUp -> { highlight = (highlight - 1 + matches.size) % matches.size; true }
                            Key.Enter, Key.NumPadEnter, Key.Tab -> { insert(matches[highlight.coerceIn(0, matches.lastIndex)]); true }
                            Key.Escape -> { dismissedAt = mention?.first ?: -1; true }
                            else -> false
                        }
                    }.verticalScroll(rememberScrollState()),
                textStyle = TextStyle(color = c.text, fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.sp), cursorBrush = SolidColor(c.text),
                decorationBox = { inner -> Box { if (draft.isEmpty()) Text(if (workspace != null) "Напишите задачу… @ — упомянуть файл" else "Напишите задачу…", color = c.faint, fontSize = 14.sp, lineHeight = 22.sp); inner() } },
            )
        if (mentioned.isNotEmpty()) MentionChips(mentioned) { path ->
            val text = field.text.replace(Regex("(?<=^|\\s)@" + Regex.escape(path) + "\\s?"), "")
            field = TextFieldValue(text, TextRange(text.length)); onDraft(text)
        }
        if (attachments.isNotEmpty() || preparing.isNotEmpty()) AttachmentChips(attachments, onRemove = { item -> onRemoveAttachment(attachmentOwnerKey, item.artifactId) }, preparing = preparing)
        if (importing && preparing.isEmpty()) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) { Pulse(c.accent); Spacer(Modifier.width(6.dp)); Text("Добавляю вложение…", color = c.faint, fontSize = 10.sp) }
        importError?.let { Text(it, color = c.negative, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp)) }
        if (attachments.any { it.mimeType.startsWith("image/") }) Text(if (vision) "Модель увидит изображения: они уменьшаются до 1568 px и отправляются с этим сообщением." else "Эта модель не принимает изображения: она получит только имя и размер файла. Включить можно в настройках профиля.", color = c.faint, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Box {
            if (matches.isNotEmpty() && focused) MentionPopover(matches, highlight.coerceIn(0, matches.lastIndex), ::insert) { dismissedAt = mention?.first ?: -1 }
        }
        Spacer(Modifier.height(4.dp))
        ComposerToolbar(mode, onMode, model, running, pending, importing, draft.isNotBlank() || attachments.isNotEmpty(), modelMenu, { if (System.currentTimeMillis() - modelClosedAt > 200) modelMenu = !modelMenu }, allowProcesses, onAllow, onSend, onStop, addFiles,
            connectionDot, contextTokens, effectiveWindow, contextIsEstimate, workspace, onChooseWorkspace, folderMenu = folderMenu) {
            ModelPickerPopover(
                model = model, models = models, configured = configured, running = running, catalog = catalog, contextNote = contextNote,
                onRefresh = onRefreshModels, onCancelRefresh = onCancelCatalogRefresh,
                onClose = { modelMenu = false; modelClosedAt = System.currentTimeMillis() }, onModel = onModel,
                onSettings = { modelMenu = false; onSettings() },
            )
        }
    }
}

@Composable
private fun AttachmentChips(items: List<ComposerAttachment>, onRemove: (ComposerAttachment) -> Unit, preparing: List<PendingPaste> = emptyList()) {
    val c = LocalChillerPalette.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        preparing.forEach { card ->
            Row(Modifier.height(30.dp).clip(RoundedCornerShape(7.dp)).background(c.accent.copy(alpha = .08f)).border(1.dp, c.accent.copy(alpha = .3f), RoundedCornerShape(7.dp)).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Pulse(c.accent); Spacer(Modifier.width(7.dp))
                Text("${card.label} · подготовка…", color = c.text, fontSize = 10.sp, maxLines = 1)
            }
        }
        items.forEach { item ->
            Row(Modifier.clip(RoundedCornerShape(7.dp)).background(c.sidebar).border(1.dp, c.border, RoundedCornerShape(7.dp)).padding(start = 8.dp, end = 3.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (item.mimeType.startsWith("image/")) Icons.Outlined.Image else Icons.Outlined.InsertDriveFile, null, Modifier.size(13.dp), tint = c.accent)
                Spacer(Modifier.width(5.dp))
                Text("${item.name} · ${formatPresentedSize(item.sizeBytes)}", color = c.text, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ChillerIconButton(Icons.Outlined.Close, "Убрать ${item.name}", { onRemove(item) }, Modifier.size(22.dp))
            }
        }
    }
}

internal fun promptWithAttachments(text: String, attachments: List<ComposerAttachment>): String = buildString {
    append(text.trim())
    attachments.forEach { attachment ->
        if (isNotEmpty()) append("\n\n")
        append("Прикреплённый файл: ").append(attachment.name)
            .append(" (только метаданные; содержимое изображения не передано модели).\n")
            .append("Для чтения текстового файла используй read_artifact с artifact_id=").append(attachment.artifactId)
    }
}

internal fun mergeComposerAttachments(current: List<ComposerAttachment>, additions: List<ComposerAttachment>, limit: Int = 8): List<ComposerAttachment>? {
    val merged = (current + additions).distinctBy { it.artifactId }
    return merged.takeIf { limit >= 0 && it.size <= limit }
}

internal fun nextPendingImportCount(current: Int, delta: Int): Int? {
    val next = (current + delta).coerceAtLeast(0)
    return next.takeIf { it > 0 }
}

internal sealed interface ComposerTransferPayload {
    data class Files(val paths: List<String>) : ComposerTransferPayload
    data class Text(val value: String) : ComposerTransferPayload
    data class Image(val value: AwtImage) : ComposerTransferPayload
}

/** Must be called while the clipboard or DnD callback still owns the Transferable. */
internal fun snapshotComposerTransferPayload(transferable: Transferable): ComposerTransferPayload? = when {
    transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
        @Suppress("UNCHECKED_CAST")
        val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
            ?: error("Clipboard file list has an unexpected type")
        ComposerTransferPayload.Files(files.map { it.absolutePath }.toList())
    }
    transferable.isDataFlavorSupported(DataFlavor.imageFlavor) -> {
        val image = transferable.getTransferData(DataFlavor.imageFlavor) as? AwtImage
            ?: error("Clipboard image has an unexpected type")
        ComposerTransferPayload.Image(image)
    }
    transferable.isDataFlavorSupported(DataFlavor.stringFlavor) -> {
        val text = transferable.getTransferData(DataFlavor.stringFlavor) as? String
            ?: error("Clipboard text has an unexpected type")
        ComposerTransferPayload.Text(text)
    }
    else -> null
}

@Composable
private fun MentionChips(paths: List<String>, onRemove: (String) -> Unit) {
    val c = LocalChillerPalette.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        paths.take(6).forEach { path ->
            Row(Modifier.clip(RoundedCornerShape(6.dp)).background(c.accent.copy(alpha = .10f)).padding(start = 7.dp, end = 3.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Description, null, Modifier.size(12.dp), tint = c.accent)
                Spacer(Modifier.width(4.dp))
                Text(path.substringAfterLast('/'), color = c.text, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.padding(start = 2.dp).size(18.dp).clip(CircleShape).pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button) { onRemove(path) }.semantics { contentDescription = "Убрать $path" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Close, null, Modifier.size(11.dp), tint = c.muted)
                }
            }
        }
        if (paths.size > 6) Text("+${paths.size - 6}", color = c.faint, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun ComposerToolbar(
    mode: ExecutionMode, onMode: (ExecutionMode) -> Unit, model: String, running: Boolean,
    pending: Boolean, importing: Boolean, draftNotEmpty: Boolean, modelMenuOpen: Boolean, onToggleModel: () -> Unit,
    allowProcesses: Boolean, onAllow: (Boolean) -> Unit, onSend: () -> Unit, onStop: () -> Unit,
    onAttach: () -> Unit,
    connectionDot: androidx.compose.ui.graphics.Color, contextTokens: Int?, contextWindow: Int?, contextIsEstimate: Boolean,
    workspace: String?, onChooseWorkspace: () -> Unit,
    folderMenu: FolderMenu? = null,
    modelPopover: @Composable () -> Unit,
) {
    val c = LocalChillerPalette.current
    var modeMenu by remember { mutableStateOf(false) }
    // A click on the anchor while its popover is open first dismisses it (outside click), then would reopen it.
    var modeClosedAt by remember { mutableStateOf(0L) }
    val toggleMode = { if (System.currentTimeMillis() - modeClosedAt > 200) modeMenu = !modeMenu }
    // At larger type the model label needs proportionally more room before the toolbar is allowed to wrap.
    val fontScale = LocalDensity.current.fontScale
    val minModelWidth = (104f * fontScale.coerceIn(1f, 2f)).dp
    val modeMenuWidth = (264f * fontScale.coerceIn(1f, 1.4f)).dp
    Box(Modifier.fillMaxWidth()) {
        Layout(
            content = {
                if (workspace == null) Box {
                    ChillerControl("Разговор", toggleMode, icon = Icons.AutoMirrored.Outlined.Chat, enabled = !running, style = if (modeMenu) ControlStyle.SELECTED else ControlStyle.QUIET)
                    if (modeMenu) ChillerPopover(onDismiss = { modeMenu = false; modeClosedAt = System.currentTimeMillis() }, width = modeMenuWidth) {
                        Text("Диалог без папки: модель отвечает, ищет в вебе и рисует, но не видит ваших файлов и не запускает команды.",
                            color = c.muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 6.dp))
                        MenuDivider()
                        MenuRow("Выбрать папку…", { modeMenu = false; onChooseWorkspace() }, subtitle = "Диалог продолжится в проекте", icon = Icons.Outlined.CreateNewFolder)
                        Spacer(Modifier.height(6.dp))
                    }
                } else Box {
                    ChillerControl(if (mode == ExecutionMode.PLAN) "План" else "Изменения", toggleMode, icon = if (mode == ExecutionMode.PLAN) Icons.Outlined.Lightbulb else Icons.Outlined.Edit, enabled = !running, style = if (modeMenu) ControlStyle.SELECTED else ControlStyle.QUIET)
                    if (modeMenu) ChillerPopover(onDismiss = { modeMenu = false; modeClosedAt = System.currentTimeMillis() }, width = modeMenuWidth) {
                        Spacer(Modifier.height(6.dp))
                        MenuRow("План", { onMode(ExecutionMode.PLAN); modeMenu = false }, subtitle = "Только чтение, без изменений", icon = Icons.Outlined.Lightbulb, selected = mode == ExecutionMode.PLAN)
                        MenuRow("Изменения", { onMode(ExecutionMode.WORKSPACE_EDIT); modeMenu = false }, subtitle = "Правит файлы проекта", icon = Icons.Outlined.Edit, selected = mode == ExecutionMode.WORKSPACE_EDIT)
                        if (mode == ExecutionMode.WORKSPACE_EDIT) {
                            MenuDivider()
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Команды процессов", color = c.text, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Запуск тестов и сборки", color = c.faint, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                ChillerToggle(allowProcesses, { onAllow(it) }, "Команды процессов", enabled = !running)
                            }
                        }
                        MenuDivider()
                        Text("Shift+Tab в поле ввода — переключить режим", color = c.faint, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(start = 16.dp, end = 14.dp, top = 2.dp))
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Box {
                    // The provider's name when its listing gives one; the menu keeps showing name and id.
                    ChillerControl(modelName(model).ifBlank { "Выбрать модель" }, onToggleModel, enabled = !running, trailing = "⌄", inlineTrailing = true, style = if (modelMenuOpen) ControlStyle.SELECTED else ControlStyle.QUIET, dot = connectionDot)
                    if (modelMenuOpen) modelPopover()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (folderMenu != null) FolderChip(folderMenu, enabled = !running) else FolderToolbarChip(workspace, onChooseWorkspace, enabled = !running)
                    Spacer(Modifier.width(6.dp))
                    ChillerIconButton(Icons.Outlined.AttachFile, "Добавить файлы", onAttach, enabled = !running)
                    Spacer(Modifier.width(8.dp))
                    contextTokens?.let { ContextMeter(it, contextWindow, contextIsEstimate); Spacer(Modifier.width(10.dp)) }
                    ChillerIconButton(if (running && !pending) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward, if (pending) "Сохранение сообщения" else if (running) "Остановить" else if (importing) "Добавление вложения" else "Отправить", if (running && !pending) onStop else onSend, enabled = !pending && (running || (!importing && draftNotEmpty)), primary = true)
                }
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
    }
}

@Composable
private fun FolderToolbarChip(workspace: String?, onChoose: () -> Unit, enabled: Boolean) {
    val c = LocalChillerPalette.current
    Row(
        Modifier.widthIn(max = 150.dp).height(28.dp).clip(RoundedCornerShape(7.dp)).background(c.sidebar)
            .pointerHoverIcon(PointerIcon.Hand).clickable(enabled = enabled, role = Role.Button, onClick = onChoose)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (workspace == null) Icons.Outlined.CreateNewFolder else Icons.Outlined.Folder, null, Modifier.size(13.dp), tint = if (workspace == null) c.faint else c.muted)
        Spacer(Modifier.width(5.dp))
        Text(workspace?.let(::folderName) ?: "Без папки", color = if (workspace == null) c.faint else c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Conversation(state: RunState, reduced: Boolean, onAnswer: (String) -> Unit, onUndo: (String) -> Unit, canUndo: Boolean, onRestoreDraft: (String) -> Unit, onPreviewFile: suspend (String) -> ByteArray?, onRevealFile: (String) -> Unit, onEdit: (Int, String) -> Unit, onDelete: (Int, String) -> Unit, onRecoverInterrupted: () -> Unit, modifier: Modifier, onRewind: (List<String>) -> Unit = {}, onContinue: () -> Unit = {}, undoneChanges: Set<String> = emptySet(), outsideChanges: dev.chiller.app.di.OutsideChanges? = null) {
    val c = LocalChillerPalette.current
    // Begin at the tail even for restored long conversations; LazyColumn clamps to the final item.
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = Int.MAX_VALUE)
    val scope = rememberCoroutineScope()
    // Following is a user decision: only the user's own scroll turns it off (scrolling up) or on (reaching the end).
    var follow by remember { mutableStateOf(true) }
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.collect { inProgress ->
            if (autoScrolling) return@collect
            if (inProgress) follow = false else follow = !scroll.canScrollForward
        }
    }
    // Any growth below the viewport (new item, streamed text, expanded card) pulls the view to the end while following.
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.canScrollForward to scroll.layoutInfo.totalItemsCount }.collect { (canScroll, count) ->
            if (follow && canScroll && count > 0 && !scroll.isScrollInProgress) {
                autoScrolling = true
                try { scroll.scrollToItem(count - 1) } finally { autoScrolling = false }
            }
        }
    }
    // Sending a message always returns to the end.
    LaunchedEffect(state.messages.size) { if (state.messages.lastOrNull()?.role == "user") follow = true }
    val projection = projectConversation(state.messages, state.activities)
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        LazyColumn(state = scroll, modifier = Modifier.widthIn(max = 816.dp).fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(if (LocalChillerCompact.current) 14.dp else 18.dp)) {
            // LazyColumn fades items in only when they are added after the first layout, and slides neighbours into place.
            // No fade-out: a removed status line ("Модель пишет…") fading while the list auto-scrolls to the new
            // summary could stay drawn over it.
            @Suppress("UNUSED_PARAMETER")
            fun LazyItemScope.entryModifier(key: String): Modifier =
                if (reduced) Modifier else Modifier.animateItem(fadeInSpec = tween(220), placementSpec = spring(stiffness = 500f, dampingRatio = .9f), fadeOutSpec = null)
            projection.entries.forEachIndexed { position, entry ->
                val key = "message-${entry.index}"
                item(key = key) {
                    val previous = projection.entries.getOrNull(position - 1)?.message?.role
                    Column(entryModifier(key).widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!entry.message.content.isNullOrBlank() || entry.message.attachments.isNotEmpty()) Message(entry.message, showHeader = !(entry.message.role == "assistant" && previous == "assistant"),
                            onPreviewFile = onPreviewFile,
                            onEdit = if (canUndo) ({ _ -> onEdit(entry.index, entry.message.content.orEmpty()) }) else null,
                            onDelete = if (canUndo) ({ _ -> onDelete(entry.index, entry.message.content.orEmpty()) }) else null,
                            rewindCount = if (entry.message.role == "user" && canUndo) turnChangeIds(state.messages, state.activities, entry.index, undoneChanges).size else 0,
                            onRewind = if (canUndo) ({ onRewind(turnChangeIds(state.messages, state.activities, entry.index, undoneChanges)) }) else null)
                        ToolGroup(entry.activities, reduced, onUndo, canUndo, onPreviewFile, onRevealFile)
                    }
                }
            }
            // The streamed reply takes the key of the message it becomes, so completion does not flash.
            if (state.streamingText.isNotBlank()) item(key = "message-${state.messages.size}") {
                Box(entryModifier("message-${state.messages.size}")) {
                    Message(AgentMessage("assistant", state.streamingText, origin = state.activeModelId?.let { MessageOrigin(MessageOriginKind.MODEL, it) }),
                        showHeader = state.messages.lastOrNull()?.role != "assistant", streaming = true)
                }
            }
            if (projection.unlinked.isNotEmpty()) item(key = "unlinked-activities") {
                Column(entryModifier("unlinked-activities"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Действия сессии", color = c.muted, fontSize = 12.sp)
                    projection.unlinked.forEach { ToolRow(it, reduced, onUndo, canUndo, onPreviewFile = onPreviewFile, onRevealFile = onRevealFile) }
                }
            }
            if (state.reasoning.isNotBlank()) item(key = "reasoning") {
                var open by remember { mutableStateOf(false) }
                Column(entryModifier("reasoning")) {
                    ChillerControl(if (open) "Скрыть рассуждения" else "Рассуждения модели", { open = !open }, icon = Icons.Outlined.Psychology)
                    AnimatedVisibility(open, enter = if (reduced) EnterTransition.None else expandVertically(tween(180)) + fadeIn(tween(160)), exit = if (reduced) ExitTransition.None else shrinkVertically(tween(150)) + fadeOut(tween(100))) {
                        Text(state.reasoning, color = c.muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(start = 34.dp, top = 6.dp, end = 10.dp))
                    }
                }
            }
            // The model's checklist for this turn, kept under the latest messages and updated live.
            currentTurnPlan(state.messages)?.takeUnless {
                state.status in setOf("completed", "failed", "cancelled", "interrupted") && !turnResult(state, undoneChanges).isEmpty
            }?.let { steps ->
                item(key = "turn-plan") { PlanChecklist(steps, running = state.status in setOf("preparing", "streaming", "executing"), modifier = entryModifier("turn-plan")) }
            }
            if (state.status in setOf("preparing", "streaming", "executing")) item(key = "run-status") {
                val label = runProgressLabel(state).orEmpty()
                Row(entryModifier("run-status").padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.question == null) Pulse(c.accent) else Icon(Icons.Outlined.Chat, null, Modifier.size(15.dp), tint = c.accent)
                    Spacer(Modifier.width(10.dp))
                    if (state.question == null) ShimmerText(label, reduced) else Text(label, color = c.accent, fontSize = 12.sp)
                }
            }
            // A turn that did something ends with a card of facts; a plain answer keeps the short status line.
            val finished = state.status in setOf("completed", "failed", "cancelled", "interrupted")
            val result = if (finished) turnResult(state, undoneChanges) else null
            val outside = outsideChanges?.takeIf { it.count > 0 && it.userIndex == state.messages.indexOfLast { m -> m.role == "user" } }
            if (result != null && (!result.isEmpty || outside != null)) item(key = "turn-result") { TurnResultCard(state, result.withUnshownOutputs(outside), entryModifier("turn-result"), outside = outside) }
            else {
                if (state.status in setOf("completed", "cancelled", "interrupted")) item(key = "run-outcome") {
                    Text(runProgressLabel(state).orEmpty(), color = c.muted, fontSize = 12.sp, modifier = entryModifier("run-outcome").padding(start = 6.dp))
                }
                if (state.status in setOf("completed", "failed", "cancelled") && state.modelRounds > 0) item(key = "run-summary") { Box(entryModifier("run-summary")) { RunSummaryLine(state) } }
            }
            state.question?.let { q -> item(key = "question-${q.id}") { Box(entryModifier("question-${q.id}")) { Question(q, onAnswer, state.status != "interrupted") } } }
            state.error?.let { error -> item(key = "error") { Column(entryModifier("error")) { Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = c.negative)
                Text(runErrorDescription(error), color = c.negative, fontSize = 12.sp, lineHeight = 20.sp)
            }
                if (isStepLimitError(error)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChillerControl("Продолжить", onContinue, icon = Icons.Outlined.PlayArrow, style = ControlStyle.OUTLINE)
                    Text("Шагов не хватило: агент продолжит ту же задачу. Лимит меняется в Настройки → Агент.", color = c.faint, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.align(Alignment.CenterVertically))
                }
                else state.messages.lastOrNull { it.role == "user" }?.content?.let { text -> ChillerControl("Вернуть текст в поле", { onRestoreDraft(text) }) }
            } } }
            if (!canUndo && (state.status == "interrupted" || state.activities.any { it.status == "interrupted" })) item(key = "recover-interrupted") {
                Column(entryModifier("recover-interrupted").padding(start = 34.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Неизвестные действия не повторяются; агент сначала проверит состояние.", color = c.muted, fontSize = 11.sp, lineHeight = 16.sp)
                    ChillerControl("Проверить и продолжить", onRecoverInterrupted, icon = Icons.Outlined.Refresh, style = ControlStyle.OUTLINE)
                }
            }
            item(key = "tail") { Spacer(Modifier.height(2.dp)) }
        }
        AnimatedVisibility(!follow && scroll.canScrollForward, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            enter = if (reduced) EnterTransition.None else fadeIn(tween(150)) + slideInVertically(tween(180)) { it / 2 },
            exit = if (reduced) ExitTransition.None else fadeOut(tween(120)) + slideOutVertically(tween(150)) { it / 2 }) {
            Box(Modifier.shadow(8.dp, CircleShape).clip(CircleShape).background(c.surface).border(1.dp, c.border, CircleShape)) {
                ChillerIconButton(Icons.Outlined.ArrowDownward, "К последнему сообщению", {
                    follow = true
                    scope.launch { autoScrolling = true; try { scroll.animateScrollToItem((scroll.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } finally { autoScrolling = false } }
                })
            }
        }
    }
}

/** Status text with a soft light sweep, so a waiting run reads as alive. Static with reduced motion. */
@Composable
private fun ShimmerText(text: String, reduced: Boolean) {
    val c = LocalChillerPalette.current
    if (reduced) { Text(text, color = c.muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); return }
    val x by rememberInfiniteTransition(label = "shimmer").animateFloat(-200f, 600f, infiniteRepeatable(tween(1600, easing = androidx.compose.animation.core.LinearEasing)), label = "x")
    val brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(c.muted, c.text, c.muted), start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x + 160f, 0f))
    Text(text, style = TextStyle(brush = brush, fontSize = 12.sp, lineHeight = 18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Hint(text: String, content: @Composable () -> Unit) {
    val c = LocalChillerPalette.current
    TooltipArea(tooltip = { Text(text, color = c.text, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.sidebar).border(1.dp, c.border, RoundedCornerShape(6.dp)).padding(8.dp)) }, content = content)
}

@Composable
private fun Message(message: AgentMessage, showHeader: Boolean = true, streaming: Boolean = false, onEdit: ((String) -> Unit)? = null, onDelete: ((String) -> Unit)? = null, onPreviewFile: suspend (String) -> ByteArray? = { null },
    rewindCount: Int = 0, onRewind: (() -> Unit)? = null) {
    val content = message.content.orEmpty()
    val c = LocalChillerPalette.current
    if (message.role == "user") {
        // The user's own messages sit on the right as quiet bubbles; the assistant writes on the page.
        val clipboard = LocalClipboardManager.current
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        var hasFocus by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(start = 64.dp).hoverable(interaction).onFocusChanged { hasFocus = it.hasFocus }.focusable(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
            androidx.compose.animation.AnimatedVisibility(hovered || hasFocus, enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
                Row(Modifier.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Rewind undoes every file change of this turn; the first click asks, the second does it.
                    if (onRewind != null && rewindCount > 0) {
                        var confirm by remember(rewindCount) { mutableStateOf(false) }
                        LaunchedEffect(confirm) { if (confirm) { kotlinx.coroutines.delay(4000); confirm = false } }
                        if (confirm) ChillerControl("Откатить $rewindCount ${plural(rewindCount, "правку", "правки", "правок")}?", { confirm = false; onRewind() }, icon = Icons.Outlined.History, style = ControlStyle.OUTLINE)
                        else ChillerIconButton(Icons.Outlined.History, "Откатить изменения файлов этого хода", { confirm = true }, Modifier.size(26.dp))
                    }
                    onEdit?.let { ChillerIconButton(Icons.Outlined.Edit, "Изменить сообщение", { it(content) }, Modifier.size(26.dp)) }
                    onDelete?.let { ChillerIconButton(Icons.Outlined.DeleteOutline, "Удалить ход диалога", { it(content) }, Modifier.size(26.dp)) }
                    ChillerIconButton(Icons.Outlined.ContentCopy, "Копировать", { clipboard.setText(AnnotatedString(content)) }, Modifier.size(26.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
              if (content.isNotBlank()) SelectionContainer {
                Text(content.trim(), color = c.text, fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.sp,
                    modifier = Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp))
                        .background(c.selected.copy(alpha = .75f)).padding(horizontal = 15.dp, vertical = 10.dp))
              }
              message.attachments.forEach { item -> FilePresentationCard(FilePresentation("attachment:${item.artifactId}", item.name, item.mimeType, item.sizeBytes), onPreviewFile, {}, showReveal = false) }
            }
        }
        return
    }
    val clipboard = LocalClipboardManager.current
    var copied by remember(content) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1600); copied = false } }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var hasFocus by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().hoverable(interaction).onFocusChanged { hasFocus = it.hasFocus }.focusable(), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        Box(Modifier.width(21.dp)) { if (showHeader) ChillerMark(Modifier.padding(top = 1.dp).size(21.dp), c.accent) }
        Column(Modifier.weight(1f)) {
            if (showHeader) Row(Modifier.fillMaxWidth().height(24.dp).padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Hint(message.origin?.modelId ?: authorLabel(message)) {
                    Text(authorLabel(message), color = c.muted, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp))
                }
            }
            Row {
                SelectionContainer(Modifier.weight(1f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val segments = content.split("```")
                        segments.forEachIndexed { index, text ->
                            val last = index == segments.lastIndex
                            if (index % 2 == 1) CodeBlock(text.substringAfter('\n', text).trimEnd(), text.substringBefore('\n', "").take(24))
                            else if (text.isNotBlank() || (streaming && last)) {
                                if (streaming && last) StreamingText(text.trim()) else MarkdownText(text.trim())
                            }
                        }
                    }
                }
                // Copy sits beside the text and shows on hover, so it adds no height to the message.
                Box(Modifier.width(32.dp).padding(start = 8.dp), contentAlignment = Alignment.TopEnd) {
                    if (!streaming) androidx.compose.animation.AnimatedVisibility(hovered || hasFocus || copied, enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
                        Row {
                            ChillerIconButton(Icons.Outlined.DeleteOutline, "Удалить ход диалога", { onDelete?.invoke(content) }, Modifier.size(24.dp), enabled = onDelete != null)
                            ChillerIconButton(Icons.Outlined.Edit, "Изменить сообщение", { onEdit?.invoke(content) }, Modifier.size(24.dp), enabled = onEdit != null)
                            ChillerIconButton(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, if (copied) "Скопировано" else "Копировать ответ", { clipboard.setText(AnnotatedString(content)); copied = true }, Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Streamed markdown with a soft blinking caret at the end. */
@Composable
private fun StreamingText(text: String) {
    val c = LocalChillerPalette.current
    val reduced = LocalChillerReducedMotion.current
    val alpha = if (reduced) 1f else rememberInfiniteTransition(label = "caret").animateFloat(1f, .15f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "a").value
    MarkdownText(text, tail = androidx.compose.ui.text.buildAnnotatedString {
        pushStyle(androidx.compose.ui.text.SpanStyle(color = c.accent.copy(alpha = alpha)))
        append(" ▍")
        pop()
    })
}

@Composable
private fun CodeBlock(code: String, language: String) {
    val c = LocalChillerPalette.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1600); copied = false } }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, c.border, shape).background(c.sidebar)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(language.ifBlank { "код" }, color = c.faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            if (copied) Text("Скопировано", color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(end = 2.dp))
            ChillerIconButton(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, "Копировать код", { clipboard.setText(AnnotatedString(code)); copied = true }, Modifier.size(26.dp))
        }
        Text(code, color = c.text, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
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
private fun SessionDeleteDialog(
    session: SessionInfo,
    preview: String?,
    loading: Boolean,
    previewError: String?,
    deleting: Boolean,
    deleteError: String?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalChillerPalette.current
    Dialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !deleting, dismissOnClickOutside = !deleting),
    ) {
        Column(
            Modifier.widthIn(min = 380.dp, max = 540.dp).clip(RoundedCornerShape(13.dp)).background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(13.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Удалить диалог?", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(session.title.ifBlank { "Новый диалог" }, color = c.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when {
                loading -> Text("Загружаю предварительный просмотр…", color = c.muted, fontSize = 12.sp, lineHeight = 18.sp)
                preview != null -> SelectionContainer {
                    Text(preview, color = c.text, fontSize = 12.sp, lineHeight = 19.sp,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).background(c.sidebar, RoundedCornerShape(8.dp)).padding(12.dp))
                }
                else -> Text(previewError ?: "Предварительный просмотр недоступен.", color = c.negative, fontSize = 12.sp, lineHeight = 18.sp)
            }
            if (previewError != null && preview != null) Text(previewError, color = c.negative, fontSize = 12.sp, lineHeight = 18.sp)
            if (deleteError != null) Text(deleteError, color = c.negative, fontSize = 12.sp, lineHeight = 18.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                ChillerControl("Отмена", onDismiss, enabled = !deleting)
                if (preview != null) {
                    Spacer(Modifier.width(8.dp))
                    ChillerControl("Удалить диалог", onDelete, icon = Icons.Outlined.DeleteOutline, style = ControlStyle.PRIMARY, enabled = !loading && !deleting)
                }
            }
        }
    }
}

@Composable
private fun MessageEditDialog(
    text: String, error: String?, busy: Boolean, onText: (String) -> Unit, onDismiss: () -> Unit, onSubmit: () -> Unit,
) {
    val c = LocalChillerPalette.current
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        Column(Modifier.widthIn(min = 360.dp, max = 560.dp).clip(RoundedCornerShape(13.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(13.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Text("Изменить сообщение", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text("Последующие ответы, инструменты и сообщения будут удалены из истории. Изменения файлов останутся на диске. Сохранение не запускает агента.", color = c.muted, fontSize = 12.sp, lineHeight = 18.sp)
            Text("Текст сообщения", color = c.muted, fontSize = 12.sp)
            BasicTextField(text, onText, textStyle = TextStyle(color = c.text, fontSize = 13.sp, lineHeight = 20.sp), cursorBrush = SolidColor(c.text),
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 300.dp).clip(RoundedCornerShape(7.dp)).background(c.canvas).border(1.dp, c.border, RoundedCornerShape(7.dp)).padding(11.dp).verticalScroll(rememberScrollState()))
            error?.let { Text(it, color = c.negative, fontSize = 12.sp, lineHeight = 18.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ChillerControl("Отмена", onDismiss, enabled = !busy)
                Spacer(Modifier.width(8.dp))
                ChillerControl("Сохранить", onSubmit, icon = Icons.Outlined.Save, style = ControlStyle.PRIMARY, enabled = !busy && text.isNotBlank())
            }
        }
    }
}

@Composable
private fun MessageDeleteDialog(text: String, error: String?, busy: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val c = LocalChillerPalette.current
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        Column(Modifier.widthIn(min = 340.dp, max = 500.dp).clip(RoundedCornerShape(13.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(13.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Text("Удалить ход диалога?", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            if (text.isNotBlank()) Text(text.take(400), color = c.muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
            Text("Это сообщение и связанные ответы и действия будут удалены из истории. Изменения, уже внесённые в файлы, останутся на диске.", color = c.muted, fontSize = 12.sp, lineHeight = 18.sp)
            error?.let { Text(it, color = c.negative, fontSize = 12.sp, lineHeight = 18.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ChillerControl("Отмена", onDismiss, enabled = !busy)
                Spacer(Modifier.width(8.dp))
                ChillerControl("Удалить ход", onDelete, icon = Icons.Outlined.DeleteOutline, style = ControlStyle.PRIMARY, enabled = !busy)
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


internal fun formatCheckTime(value: String?): String = runCatching {
    java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))
}.getOrDefault(value.orEmpty())
/** One line and a dot color describing whether the model can be reached. */
@Composable
internal fun connectionSummary(connection: ConnectionState, configured: Boolean): Pair<String, androidx.compose.ui.graphics.Color> {
    val c = LocalChillerPalette.current
    return when (connection.status) {
        "verified" -> "подключено" to c.positive
        "checking" -> "проверяю…" to c.muted
        "reachable" -> "сервер отвечает" to c.positive
        "failed" -> "ошибка подключения" to c.negative
        // Set up but not exercised yet: nothing is wrong, the first answer or an explicit check will confirm it.
        else -> if (configured) "настроено" to c.faint else "нет ключа API" to c.negative
    }
}

private fun friendlyModel(model: String) = modelLabel(model).ifBlank { "Выбрать модель" }

private fun statusLabel(status: String) = when (status) { "preparing" -> "Подготовка…"; "streaming" -> "Работает…"; "executing" -> "Выполняет действие…"; else -> "" }
internal fun changeId(output: String): String? = runCatching { Json.parseToJsonElement(output).jsonObject["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content }.getOrNull()
internal fun prettyOutput(output: String): String = runCatching {
    val root = Json.parseToJsonElement(output).jsonObject
    // A failed tool has no data: show the error code, message and recovery hint instead of "null".
    (root["error"] as? JsonObject)?.let { error ->
        val code = error["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val message = error["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val recovery = error["recovery"]?.jsonPrimitive?.contentOrNull
        return@runCatching listOfNotNull("$code · $message", recovery?.let { "Подсказка: $it" }).joinToString("\n")
    }
    val data = root["data"]
    (data as? JsonObject)?.get("output")?.jsonPrimitive?.content ?: Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), data ?: JsonPrimitive(output))
}.getOrDefault(output)
internal fun toolLabel(name: String) = when (name) {
    "web_search" -> "Поиск в интернете"; "web_fetch" -> "Веб-страница"; "create_directory" -> "Папка"; "show_file" -> "Файл"; "capture_window" -> "Снимок окна"; "list_capture_targets" -> "Окно для снимка"
    "git_status" -> "Состояние Git"; "git_diff" -> "Изменения Git"; "git_log" -> "История Git"; "git_show" -> "Файл из Git"; "find_symbols" -> "Объявления"; "read_symbol" -> "Функция"; "read_diagnostics" -> "Диагностика"
    "read_file" -> "Чтение"; "list_files" -> "Файлы"; "search_text" -> "Поиск"; "edit_file" -> "Правка"; "replace_text" -> "Замена"; "append_file" -> "Дозапись"; "update_plan" -> "План"; "spawn_agent" -> "Помощник"; "list_agents" -> "Помощники"; "wait_agents" -> "Ожидание"; "agent_result" -> "Отчёт"; "message_agent" -> "Сообщение"; "cancel_agent" -> "Стоп"; "create_file" -> "Создание"; "generate_image" -> "Картинка"
    "move_file" -> "Перемещение"; "delete_file" -> "Удаление"; "run_process" -> "Команда"; "read_process" -> "Вывод"; "stop_process" -> "Остановка"
    "read_artifact" -> "Результат"; "ask_user" -> "Вопрос"; "undo" -> "Откат"; else -> name
}

/** Plan and Edit alternate; Shift+Tab in the composer calls this. */
internal fun toggledMode(mode: ExecutionMode): ExecutionMode = if (mode == ExecutionMode.PLAN) ExecutionMode.WORKSPACE_EDIT else ExecutionMode.PLAN

/** What "Продолжить" sends after the step limit stopped a run. */
internal const val CONTINUE_PROMPT = "Продолжай с того места, где остановился."

/** The coordinator's message for a run stopped by the step limit (not a failure of the task itself). */
internal fun isStepLimitError(error: String): Boolean = error.startsWith("Достигнут лимит") && "шагов" in error
