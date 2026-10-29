package dev.chiller.app.ui

import dev.chiller.app.i18n.*
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
internal data class MessageActionTarget(val index: Int, val content: String, val role: String, val sessionId: String?, val historySize: Int)

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
    providers: List<dev.chiller.app.di.PickerProvider> = emptyList(), onSelectProviderModel: suspend (String, String) -> String? = { _, _ -> null },
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
    onEditMessage: suspend (Int, String, ExecutionMode) -> SendReceipt = { _, _, _ -> SendReceipt(false, "", "", I18n.current.app.editUnavailable) },
    onRecoverInterrupted: () -> Unit = {},
    /** Pinned and hidden projects, the dialog's folder state and project actions. */
    projects: ProjectsModel = ProjectsModel(),
    /** News from dialogs in the background; a click opens the dialog. */
    notices: List<dev.chiller.app.di.Notice> = emptyList(), onOpenNotice: (dev.chiller.app.di.Notice) -> Unit = {}, onDismissNotice: (String) -> Unit = {},
    /** The app menu's commands, offered in the command palette next to the window's own. */
    paletteMenu: List<AppMenuSection> = emptyList(),
    /** Dialogs whose messages contain the text typed into the sidebar search. */
    onSearchMessages: suspend (String) -> List<dev.chiller.sessions.SessionHit> = { emptyList() },
    /** Whether file changes wait for the user's yes or no, with the toggle and the decision. */
    editApproval: EditApproval = EditApproval(),
    /** Picks a folder and opens a dialog there that continues the shown one; returns its id and opening draft. */
    onContinueElsewhere: (suspend () -> Pair<String, String>?)? = null,
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
    var palette by remember { mutableStateOf(false) }
    // A dialog opened from a message search scrolls to the first message with the text.
    var jump by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Offered for an empty dialog in a git project; the controller checks the repository for real.
    val canIsolate by produceState(false, workspacePath, projects.folderLocked, projects.worktree, state.messages.isEmpty()) {
        value = workspacePath != null && projects.worktree == null && !projects.folderLocked && state.messages.isEmpty() &&
            withContext(Dispatchers.IO) { java.nio.file.Files.exists(java.nio.file.Path.of(workspacePath).resolve(".git")) }
    }
    // A dialog in its own working copy is listed under the project the copy was made from.
    val currentProject = projects.worktree?.repo ?: workspacePath
    val tree = remember(sessions, sessionWorkspaces, projects.origins, recentWorkspaces, projects.pinned, projects.hidden, currentProject) {
        projectTree(sessions, sessionWorkspaces + projects.origins, recentWorkspaces, projects.pinned, projects.hidden, currentProject, projects.excluded)
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
    // Each project keeps the mode it was last used in; a project seen for the first time keeps the current one.
    val modeProject = projects.worktree?.repo ?: workspacePath
    LaunchedEffect(modeProject, preferences.projectModes) {
        preferences.projectModes[modeProject]?.let { saved -> mode = if (saved == "edit") ExecutionMode.WORKSPACE_EDIT else ExecutionMode.PLAN }
    }
    LaunchedEffect(mode) {
        val code = if (mode == ExecutionMode.WORKSPACE_EDIT) "edit" else "plan"
        if (modeProject != null && view.projectModes[modeProject] != code) updateView(view.copy(projectModes = view.projectModes + (modeProject to code)))
    }
    val reducedMotion = view.reducedMotion
    var showCompactChanges by remember { mutableStateOf(false) }
    val inspectorWidth = view.inspectorWidth.dp
    val density = LocalDensity.current
    val inspectorResize = rememberDraggableState { delta -> view = view.copy(inspectorWidth = (inspectorWidth - with(density) { delta.toDp() }).value.coerceIn(280f, 480f)) }
    val active = pendingSend || state.status in setOf("preparing", "streaming", "executing")
    val demoCompleted = isCompletedDemo(isDemo, state.status)
    val empty = state.messages.none { it.role in setOf("user", "assistant") } && state.activities.isEmpty() && state.error == null
    val contextNote = state.contextEstimatedTokens?.let { tokens -> strings.app.contextNote(tokens, state.contextOutputReserve, state.contextReportedTokens, state.droppedContextMessages > 0 || state.prunedContextResults > 0) }
    // "Продолжить" after the step limit: the same task goes on in the current mode, with its history.
    val continueTask: () -> Unit = {
        if (!active && !pendingSend) {
            pendingSend = true; sendError = null
            scope.launch {
                try {
                    val receipt = onSend(CONTINUE_PROMPT, mode, newId())
                    if (!receipt.accepted && latestSession == receipt.sessionId) sendError = receipt.reason ?: I18n.current.app.messageNotAccepted
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { sendError = I18n.current.app.continueFailed }
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
                    } else if (latestSession == receipt.sessionId) sendError = receipt.reason ?: I18n.current.app.messageNotAccepted
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { sendError = I18n.current.app.sendFailed }
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
    val strings = stringsFor(Language.fromId(preferences.language))
    // Labels built outside composition (run status, sizes, tool names) read the same language; set before the children compose.
    I18n.current = strings
    ChillerTheme(darkTheme = dark, reducedMotion = reducedMotion, compact = compactDensity) { CompositionLocalProvider(LocalModelNames provides modelNames, LocalFilePreview provides onPreviewFile, LocalEditApproval provides editApproval, LocalStrings provides strings) {
        val c = LocalChillerPalette.current
        val menu = titleBar?.menu
        BoxWithConstraints(Modifier.fillMaxSize().then(titleBar?.hitTest ?: Modifier).background(c.canvas).focusRequester(rootFocus).chillerKeyboardFocusTracking().onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && (it.isMetaPressed || it.isCtrlPressed)) {
                when (it.key) {
                    Key.Enter -> { handleAction(DesktopAction.SEND); true }
                    Key.K -> { sidebar = true; runCatching { sidebarSearch.requestFocus() }; true }
                    Key.P -> { if (it.isShiftPressed) palette = true else if (!settings) switcher = true; true }
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
                                        if (preview.isNullOrBlank()) deletePreviewError = I18n.current.app.deletePreviewFailed
                                        else deletePreview = preview
                                    }
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) {
                                    if (deleteRequestVersion == requestVersion && deleteTarget?.id == session.id) deletePreviewError = I18n.current.app.deleteDetailsFailed
                                } finally {
                                    if (deleteRequestVersion == requestVersion && deleteTarget?.id == session.id) deletePreviewLoading = false
                                }
                            }
                        }
                    }, onExport = onExportSession, connection = connection, width = 264f * density.fontScale.coerceIn(1f, 1.2f),
                    running = active, searchFocus = sidebarSearch, titleBar = titleBar,
                    settingsOpen = settings && !active, settingsSection = settingsSection, onSettingsSection = { settingsSection = it }, onCloseSettings = { settings = false }, version = settingsData?.version,
                    onOpenPath = { onOpenWorkspace(it) },
                    onSearchMessages = onSearchMessages, onOpenHit = { hit, text -> jump = hit.sessionId to text; onSelectSession(hit.sessionId) },
                    expansion = view.projectExpansion, onExpansion = { updateView(view.copy(projectExpansion = it)) })
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.border.copy(alpha = .7f)))
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val compact = maxWidth < 860.dp || density.fontScale > 1.3f
                    Column(Modifier.fillMaxSize()) {
                        Toolbar(
                            titleBar, leadingInset = titleBar != null && titleBar.trafficLightsWidth > 0.dp && !showSidebar,
                            sessions.firstOrNull { it.id == selectedSessionId }?.title, workspacePath, layout, active, state.status, state.question != null || state.approval != null,
                            onLayout = { updateView(view.copy(layout = it.name.lowercase())) }, onSidebar = { sidebar = !sidebar }, onSettings = { openSettings() },
                            canConfigure = !active, compact = compact, compactChanges = showCompactChanges,
                            onCompactChanges = { showCompactChanges = !showCompactChanges },
                            showMenu = !showSidebar,
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
                                saveCustomProvider = base.saveCustomProvider, deleteCustomProvider = base.deleteCustomProvider, costBudget = base.costBudget, saveProxy = base.saveProxy,
                                refreshCaptureTargets = base.refreshCaptureTargets, selectCaptureTarget = base.selectCaptureTarget, saveMcpServers = base.saveMcpServers,
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
                                                !apiKeyConfigured -> ConnectionPrompt(strings.app.addKey) { openSettings() }
                                                connection.status == "checking" -> null
                                                else -> ConnectionPrompt(strings.app.check) { onCheckConnection() }
                                            },
                                        )
                                    } else {
                                        key(selectedSessionId) {
                                            Conversation(state, reducedMotion, onAnswerQuestion, onUndo, !active, jumpTo = jump?.takeIf { it.first == selectedSessionId }?.second, onRestoreDraft =
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
                                            projects.worktree?.let { WorktreeBar(it, active, projects) }
                                            if (projects.workspaceMissing && workspacePath != null)
                                                MissingFolderBanner(workspacePath, !active, { projects.onRelocate(workspacePath) }, { projects.onNewIn(null) })
                                            ContextStatus(state)

                                            if (demoCompleted) {
                                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.sidebar).border(1.dp, c.border, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Outlined.CheckCircleOutline, null, Modifier.size(17.dp), tint = c.positive)
                                                    Spacer(Modifier.width(9.dp))
                                                    Column(Modifier.weight(1f)) {
                                                        Text(if (state.status == "completed") strings.app.demoDone else strings.app.demoStopped, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                                        Text(strings.app.demoNote, color = c.muted, fontSize = 11.sp, lineHeight = 16.sp)
                                                    }
                                                    ChillerControl(strings.app.startNormalDialog, { if (!active) onNewSession() }, icon = Icons.AutoMirrored.Outlined.ArrowForward, style = ControlStyle.PRIMARY, enabled = !active)
                                                }
                                            } else Composer(draft, updateDraft, mode, { mode = it }, providerModel, active, send, onStop, { openSettings() }, Modifier.fillMaxWidth(), apiKeyConfigured, models, { selected ->
                                                val oldDraft = draft
                                                scope.launch { onSelectModel(selected)?.let { id -> drafts.putIfAbsent(id, oldDraft) } }
                                            }, { composing = it }, pendingSend, contextNote, catalog, onRefreshModels, onCancelCatalogRefresh, allowProcesses, onAllowProcessesChange,
                                                workspacePath, onChooseWorkspace, state.contextReportedTokens?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: state.contextEstimatedTokens, state.contextReportedTokens == null, state.contextWindowTokens, connectionSummary(connection, apiKeyConfigured).second,
                                                attachments = draftAttachments, attachmentOwnerKey = draftKey, vision = visionEnabled(settingsData?.agent ?: AgentOptions(), providerModel, catalog.entry(providerModel)),
                                                importing = (pendingImportsBySession[draftKey] ?: 0) > 0,
                                                onAppendAttachments = appendAttachments,
                                                providers = providers,
                                                onProviderModel = { url, selected ->
                                                    val oldDraft = draft
                                                    scope.launch { onSelectProviderModel(url, selected)?.let { id -> drafts.putIfAbsent(id, oldDraft) } }
                                                },
                                                onRemoveAttachment = removeAttachment,
                                                onImportState = updateImportCount,
                                                onImportTextAttachment = onImportTextAttachment, onImportFiles = onImportFiles, onImportImageAttachment = onImportImageAttachment,
                                                folderMenu = FolderMenu(workspacePath, tree.projects.map { it.path }, projects.folderLocked, projects.workspaceMissing,
                                                    onOpen = { if (!active) onOpenWorkspace(it) }, onChoose = onChooseWorkspace,
                                                    onDetach = { if (!active) { if (projects.folderLocked) projects.onNewIn(null) else onCloseWorkspace() } },
                                                    onRelocate = projects.onRelocate,
                                                    worktree = projects.worktree,
                                                    onIsolate = if (canIsolate) ({ projects.onIsolate() }) else null,
                                                    onContinueElsewhere = onContinueElsewhere?.let { pick -> {
                                                        if (!active) scope.launch { pick()?.let { (id, text) -> if (drafts[id].isNullOrBlank()) drafts[id] = text } }
                                                    } }))
                                        }
                                    }
                                }
                            }
                            AnimatedVisibility(
                                // An empty dialog has nothing to inspect yet: the start screen gets the whole width.
                                visible = layout == WorkspaceLayout.STUDIO && (!compact || showCompactChanges) && (!empty || subagents.isNotEmpty()),
                                enter = if (reducedMotion) EnterTransition.None else expandHorizontally(tween(160)) + fadeIn(tween(160)),
                                exit = if (reducedMotion) ExitTransition.None else shrinkHorizontally(tween(180)) + fadeOut(tween(110)),
                            ) {
                                Row(if (compact) Modifier.fillMaxSize() else Modifier.width(inspectorWidth).fillMaxHeight()) {
                                    if (!compact) Box(Modifier.width(10.dp).fillMaxHeight().background(c.sidebar).pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.E_RESIZE_CURSOR))).semantics { contentDescription = I18n.current.app.changesPanelWidth }.draggable(inspectorResize, Orientation.Horizontal, onDragStopped = { onPreferences(view) }))
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
                        deletePreviewError = I18n.current.app.cannotDeleteCurrent
                    } else {
                        deleteInFlight = true
                        deletePreviewError = null
                        scope.launch {
                            try {
                                if (onDeleteSession(target.id)) {
                                    deleteTarget = null
                                    deletePreview = null
                                    deletePreviewError = null
                                } else deletePreviewError = I18n.current.app.deleteNotFinished
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { deletePreviewError = I18n.current.app.deleteFailed }
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
                if (!targetStillCurrent(target)) messageActionError = I18n.current.app.historyChanged
                if (!messageActionBusy && !latestActive && targetStillCurrent(target)) {
                    messageActionBusy = true; messageActionError = null
                    scope.launch {
                        try {
                            if (!targetStillCurrent(target)) { messageActionError = I18n.current.app.historyChanged; return@launch }
                            val receipt = onEditMessage(target.index, messageEditText, mode)
                            if (receipt.accepted && receipt.sessionId == target.sessionId) { messageEditTarget = null; messageActionError = null }
                            else messageActionError = receipt.reason ?: I18n.current.app.editFailedSource
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { messageActionError = I18n.current.app.editFailed }
                        finally { messageActionBusy = false }
                    }
                }
            },
        ) }
        messageDeleteTarget?.let { target -> MessageDeleteDialog(
            text = target.content, error = messageActionError, busy = messageActionBusy,
            onDismiss = { if (!messageActionBusy) { messageDeleteTarget = null; messageActionError = null } },
            onDelete = {
                if (!targetStillCurrent(target)) messageActionError = I18n.current.app.historyChanged
                if (!messageActionBusy && !latestActive && targetStillCurrent(target)) {
                    messageActionBusy = true; messageActionError = null
                    scope.launch {
                        try {
                            if (!targetStillCurrent(target)) { messageActionError = I18n.current.app.historyChanged; return@launch }
                            if (onDeleteMessage(target.index)) messageDeleteTarget = null else messageActionError = I18n.current.app.deleteTurnFailed
                        }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { messageActionError = I18n.current.app.deleteTurnFailed }
                        finally { messageActionBusy = false }
                    }
                }
            },
        ) }
        if (exportPreview != null) ExportPreview(exportPreview, exportFormat, onDismissExport, onSaveExport)
        // A non-focusable popup: notices float over the page without taking keyboard focus from the composer.
        if (notices.isNotEmpty()) Popup(alignment = Alignment.TopEnd, properties = PopupProperties(focusable = false)) {
            NoticeStack(notices, onOpenNotice, onDismissNotice, Modifier.padding(top = 52.dp, end = 16.dp, start = 16.dp, bottom = 16.dp))
        }
        if (palette) {
            val approval = editApproval
            val commands = remember(paletteMenu, tree, mode, approval.enabled, allowProcesses, dark, workspacePath) {
                menuCommands(paletteMenu) + buildList {
                    add(PaletteCommand(strings.app.goToProject, strings.app.groupProjects, Icons.Outlined.FolderOpen, shortcut("P")) { switcher = true })
                    add(PaletteCommand(strings.app.newDialogNoFolder, strings.app.groupProjects, Icons.Outlined.Add) { projects.onNewIn(null) })
                    tree.projects.take(9).forEach { group -> add(PaletteCommand(strings.app.newDialogIn(folderName(group.path)), strings.app.groupProjects, Icons.Outlined.Add) { projects.onNewIn(group.path) }) }
                    if (workspacePath != null) {
                        add(PaletteCommand(if (mode == ExecutionMode.PLAN) strings.app.modeChanges else strings.app.modePlan, strings.app.groupDialog, if (mode == ExecutionMode.PLAN) Icons.Outlined.Edit else Icons.Outlined.Lightbulb) { mode = toggledMode(mode) })
                        add(PaletteCommand(if (approval.enabled) strings.app.stopConfirmingEdits else strings.app.confirmEdits, strings.app.groupDialog, Icons.Outlined.FactCheck) { approval.onChange(!approval.enabled) })
                        add(PaletteCommand(if (allowProcesses) strings.app.forbidProcesses else strings.app.allowProcesses, strings.app.groupDialog, Icons.Outlined.Terminal) { onAllowProcessesChange(!allowProcesses) })
                    }
                    add(PaletteCommand(if (dark) strings.app.lightTheme else strings.app.darkTheme, strings.app.groupView, Icons.Outlined.Contrast) { updateView(view.copy(theme = if (dark) "light" else "dark")) })
                    SettingsSection.entries.forEach { section -> add(PaletteCommand(section.title(strings.settings), strings.settings.title, section.icon) { settingsSection = section; settings = true }) }
                }
            }
            CommandPalette(commands) { palette = false; runCatching { rootFocus.requestFocus() } }
        }
        if (switcher) ProjectSwitcher(tree.projects, workspacePath, onPick = { path -> switcher = false; projects.onNewIn(path) }, onChoose = onChooseWorkspace, onDismiss = { switcher = false; runCatching { rootFocus.requestFocus() } },
            onOpenDialog = { id -> switcher = false; onSelectSession(id) })
    } }
}

@Composable
internal fun Toolbar(titleBar: TitleBarSpec?, leadingInset: Boolean, title: String?, workspace: String?, layout: WorkspaceLayout, active: Boolean, status: String, waitingForUser: Boolean, onLayout: (WorkspaceLayout) -> Unit, onSidebar: () -> Unit, onSettings: () -> Unit, canConfigure: Boolean, compact: Boolean, compactChanges: Boolean, onCompactChanges: () -> Unit,
    /** The app menu is in the toolbar only while the sidebar, its usual place, is hidden. */
    showMenu: Boolean = true) {
    val c = LocalChillerPalette.current
    // Empty toolbar space drags the window; controls on top keep their clicks.
    Box(Modifier.fillMaxWidth()) {
    titleBar?.dragArea?.invoke(Modifier.matchParentSize())
    // Windows caption buttons sit flush with the window's top-right corner; the toolbar ends a little before them.
    val end = titleBar?.captionButtonsWidth?.takeIf { it > 0.dp }?.plus(6.dp) ?: 15.dp
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = if (leadingInset) titleBar!!.trafficLightsWidth else 15.dp, end = end, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showMenu) titleBar?.menu?.let { AppMenuButton(it); Spacer(Modifier.width(2.dp)) }
        ChillerIconButton(Icons.Outlined.VerticalSplit, strings.app.sidebar, onSidebar)
        Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(workspace?.let(::folderName) ?: "Chiller", color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
            Text(" / ", color = c.faint, fontSize = 13.sp)
            Text(title?.take(44) ?: strings.common.newDialog, color = c.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (active) { Text(if (waitingForUser) strings.app.waitingAnswer else statusLabel(status), color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp).padding(end = 12.dp)) }
        Row(Modifier.clip(RoundedCornerShape(7.dp)).background(c.sidebar).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ChillerControl("Focus", { onLayout(WorkspaceLayout.FOCUS) }, style = if (layout == WorkspaceLayout.FOCUS) ControlStyle.SELECTED else ControlStyle.QUIET)
            ChillerControl("Studio", { onLayout(WorkspaceLayout.STUDIO) }, style = if (layout == WorkspaceLayout.STUDIO) ControlStyle.SELECTED else ControlStyle.QUIET)
        }
        if (compact && layout == WorkspaceLayout.STUDIO) {
            Spacer(Modifier.width(5.dp))
            ChillerIconButton(if (compactChanges) Icons.AutoMirrored.Outlined.Chat else Icons.Outlined.Difference, strings.app.dialogOrChanges, onCompactChanges)
        }
        Spacer(Modifier.width(7.dp))
        ChillerIconButton(Icons.Outlined.Tune, strings.app.connectionSettings, onSettings, enabled = canConfigure)
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
        "verified" -> I18n.current.app.connVerified to c.positive
        "checking" -> I18n.current.app.connChecking to c.muted
        "reachable" -> I18n.current.app.connReachable to c.positive
        "failed" -> I18n.current.app.connFailed to c.negative
        // Set up but not exercised yet: nothing is wrong, the first answer or an explicit check will confirm it.
        else -> if (configured) I18n.current.app.connConfigured to c.faint else I18n.current.app.connNoKey to c.negative
    }
}

internal fun friendlyModel(model: String) = modelLabel(model).ifBlank { I18n.current.app.chooseModel }

internal fun statusLabel(status: String) = I18n.current.app.headerStatus(status)
internal fun changeId(output: String): String? = runCatching { Json.parseToJsonElement(output).jsonObject["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content }.getOrNull()
internal fun prettyOutput(output: String): String = runCatching {
    val root = Json.parseToJsonElement(output).jsonObject
    // A failed tool has no data: show the error code, message and recovery hint instead of "null".
    (root["error"] as? JsonObject)?.let { error ->
        val code = error["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val message = error["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val recovery = error["recovery"]?.jsonPrimitive?.contentOrNull
        return@runCatching listOfNotNull("$code · $message", recovery?.let { I18n.current.app.hint(it) }).joinToString("\n")
    }
    val data = root["data"]
    (data as? JsonObject)?.get("output")?.jsonPrimitive?.content ?: Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), data ?: JsonPrimitive(output))
}.getOrDefault(output)

internal fun toolLabel(name: String) = I18n.current.run.toolLabel(name)

/** Plan and Edit alternate; Shift+Tab in the composer calls this. */
internal fun toggledMode(mode: ExecutionMode): ExecutionMode = if (mode == ExecutionMode.PLAN) ExecutionMode.WORKSPACE_EDIT else ExecutionMode.PLAN

/** What "Продолжить" sends after the step limit stopped a run. */
internal val CONTINUE_PROMPT: String get() = I18n.current.app.continuePrompt

/** The coordinator's message for a run stopped by the step limit (not a failure of the task itself). */
internal fun isStepLimitError(error: String): Boolean = error.startsWith("Достигнут лимит") && "шагов" in error
