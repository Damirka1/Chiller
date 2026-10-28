package dev.chiller.app

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.*
import dev.chiller.app.di.DesktopController
import dev.chiller.app.di.LocalCredentials
import dev.chiller.app.di.BuildStamp
import dev.chiller.app.ui.ChillerApp
import dev.chiller.app.ui.TitleBarSpec
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.foundation.layout.fillMaxSize
import dev.chiller.app.ui.DesktopAction
import dev.chiller.app.ui.AppMenuItem
import dev.chiller.app.ui.AppMenuSection
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import java.nio.file.Path
import java.awt.FileDialog
import java.awt.Frame
import javax.swing.JFileChooser

fun main() {
    // The JDK's java.net.http clients (web tools) trust the system certificate store as the provider clients do.
    javax.net.ssl.SSLContext.setDefault(dev.chiller.providers.SystemTrust.sslContext)
    chillerApplication()
}

private fun chillerApplication() = application {
    val scope = rememberCoroutineScope()
    val directory = remember { System.getenv("CHILLER_DATA_DIR")?.let(Path::of) ?: if (java.lang.Boolean.getBoolean("chiller.offlinePreview")) Path.of(System.getProperty("java.io.tmpdir"), "chiller-preview") else Path.of(System.getProperty("user.home"), ".chiller") }
    val controller = remember { DesktopController(directory, if (java.lang.Boolean.getBoolean("chiller.offlinePreview")) null else LocalCredentials.load(),
        namedKeys = if (java.lang.Boolean.getBoolean("chiller.offlinePreview")) emptyMap() else LocalCredentials.loadNamed(dev.chiller.app.di.ProviderPresets.keyVariables),
        credentialStore = if (java.lang.Boolean.getBoolean("chiller.offlinePreview")) dev.chiller.app.di.CredentialStore.None else dev.chiller.app.di.CredentialStore.system(),
        keyLookup = { name -> if (java.lang.Boolean.getBoolean("chiller.offlinePreview")) null else LocalCredentials.loadNamed(listOf(name))[name] }) }
    val state by controller.state.collectAsState()
    val comparisonState by controller.comparison.state.collectAsState()
    val actions = remember { MutableSharedFlow<DesktopAction>(extraBufferCapacity = 16) }
    val mac = remember { System.getProperty("os.name").startsWith("Mac") }
    val active = state.run.status in setOf("preparing", "streaming", "executing")
    LaunchedEffect(Unit) { controller.initialize() }
    val preview = java.lang.Boolean.getBoolean("chiller.offlinePreview")
    val savedPreferences by produceState<dev.chiller.app.di.UiPreferences?>(null, directory) {
        value = dev.chiller.app.di.UiPreferencesStore(directory).load()
    }
    val initialPreferences = savedPreferences ?: return@application
    val windowState = rememberWindowState(
        width = (if (preview) System.getenv("CHILLER_PREVIEW_WIDTH")?.toIntOrNull()?.coerceIn(800, 1800)?.toFloat() ?: 1280f else initialPreferences.windowWidth).dp,
        height = (if (preview) System.getenv("CHILLER_PREVIEW_HEIGHT")?.toIntOrNull()?.coerceIn(600, 1200)?.toFloat() ?: 860f else initialPreferences.windowHeight).dp,
        placement = if (!preview && initialPreferences.windowMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
    )
    var normalSize by remember { mutableStateOf(androidx.compose.ui.unit.DpSize(initialPreferences.windowWidth.dp, initialPreferences.windowHeight.dp)) }
    LaunchedEffect(windowState) {
        snapshotFlow { Triple(windowState.size, windowState.placement, windowState.isMinimized) }
            .collect { (size, placement, minimized) ->
                if (placement == WindowPlacement.Floating && !minimized) normalSize = size
            }
    }
    Window(
        title = "Chiller",
        icon = androidx.compose.ui.res.painterResource("chiller-icon.png"),
        state = windowState,
        onCloseRequest = { scope.launch {
            if (!preview) {
                val size = if (windowState.placement == WindowPlacement.Floating && !windowState.isMinimized) windowState.size else normalSize
                try {
                    controller.updateUiPreferences(controller.state.value.uiPreferences.copy(
                        windowWidth = size.width.value,
                        windowHeight = size.height.value,
                        windowMaximized = windowState.placement == WindowPlacement.Maximized,
                    ))
                } catch (_: java.io.IOException) {
                    System.err.println("Chiller: could not save window size")
                }
            }
            controller.close()
            exitApplication()
        } },
    ) {
        // Codex/Claude-style window: transparent title bar, content under it, traffic lights over the sidebar.
        // The title stays set for Mission Control and the Window menu but is not drawn.
        if (mac) LaunchedEffect(Unit) {
            window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
            window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        }
        val macTitleBar = remember(mac) { if (!mac) null else TitleBarSpec(dragArea = { modifier -> WindowDraggableArea(modifier) { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) } }) }
        // A finished task while the window is in the background asks for attention once (Dock bounce / taskbar flash).
        // A background dialog finishing does the same.
        var backgroundRuns by remember { mutableStateOf(emptySet<String>()) }
        LaunchedEffect(state.runningSessions) {
            val finished = backgroundRuns - state.runningSessions - state.sessionId
            if (finished.isNotEmpty() && !window.isActive) runCatching {
                if (java.awt.Taskbar.isTaskbarSupported() && java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.USER_ATTENTION))
                    java.awt.Taskbar.getTaskbar().requestUserAttention(true, false)
            }
            backgroundRuns = state.runningSessions
        }
        var wasActive by remember { mutableStateOf(false) }
        LaunchedEffect(active, state.run.status) {
            if (wasActive && !active && state.run.status in setOf("completed", "failed") && !window.isActive) runCatching {
                if (java.awt.Taskbar.isTaskbarSupported() && java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.USER_ATTENTION))
                    java.awt.Taskbar.getTaskbar().requestUserAttention(true, false)
            }
            wasActive = active
        }
        var demoCount by remember { mutableStateOf(controller.demoProjectCount()) }
        LaunchedEffect(state.sessionId) { demoCount = controller.demoProjectCount() }
        val chooseWorkspace = {
            chooseDirectory(window, state.settings.workspace, mac)?.let { path -> scope.launch { controller.chooseWorkspace(path) } }
            Unit
        }
        val menu = listOf(
            AppMenuSection("Файл", listOf(
                listOf(
                    // A running dialog goes on in the background, so these stay available.
                    AppMenuItem("Новый диалог", Key.N, "N") { actions.tryEmit(DesktopAction.NEW_CHAT) },
                    AppMenuItem("Открыть проект…", Key.O, "O", onClick = chooseWorkspace),
                    AppMenuItem("Закрыть проект", enabled = state.settings.workspace != null) { scope.launch { controller.closeWorkspace() } },
                ),
                listOf(
                    AppMenuItem("Экспорт Markdown…", enabled = !active && state.run.messages.isNotEmpty()) { scope.launch { controller.prepareExport("markdown") } },
                    AppMenuItem("Экспорт JSON…", enabled = !active && state.run.messages.isNotEmpty()) { scope.launch { controller.prepareExport("json") } },
                ),
                listOf(AppMenuItem("Настройки…", Key.Comma, ",", enabled = !active) { actions.tryEmit(DesktopAction.SETTINGS) }),
            )),
            AppMenuSection("Диалог", listOf(listOf(
                AppMenuItem("Отправить", Key.Enter, "↵", enabled = !active) { actions.tryEmit(DesktopAction.SEND) },
                AppMenuItem("Остановить", Key.Period, ".", enabled = active) { actions.tryEmit(DesktopAction.STOP) },
            ))),
            AppMenuSection("Вид", listOf(listOf(
                AppMenuItem("Focus") { actions.tryEmit(DesktopAction.FOCUS) },
                AppMenuItem("Studio") { actions.tryEmit(DesktopAction.STUDIO) },
                AppMenuItem("Боковая панель") { actions.tryEmit(DesktopAction.SIDEBAR) },
            ))),
            AppMenuSection("Инструменты", listOf(listOf(AppMenuItem("Сравнение моделей", enabled = !active) { actions.tryEmit(DesktopAction.COMPARE) }))),
            AppMenuSection("Справка", listOf(listOf(AppMenuItem("Демо: все инструменты", enabled = !active) { scope.launch { controller.startDemo() } }))),
        )
        // Windows on the JetBrains Runtime: the app draws its own title bar under the native caption buttons, and the menu
        // moves into the toolbar. Anywhere else the system frame and menu bar stay.
        val nativeTitleBar = remember(mac) { if (mac) null else WindowsTitleBar.install(window) }
        val titleBar = if (mac) macTitleBar else nativeTitleBar?.spec(menu)
        if (nativeTitleBar == null) MenuBar {
            menu.forEach { section ->
                Menu(section.title) {
                    section.groups.forEachIndexed { index, group ->
                        if (index > 0) Separator()
                        group.forEach { item ->
                            Item(item.title, enabled = item.enabled, shortcut = item.key?.let { KeyShortcut(it, meta = mac, ctrl = !mac) }, onClick = item.onClick)
                        }
                    }
                }
            }
        }
        val platformDensity = LocalDensity.current
        val previewFontScale = if (java.lang.Boolean.getBoolean("chiller.offlinePreview"))
            System.getenv("CHILLER_PREVIEW_FONT_SCALE")?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(1f, 2f) ?: platformDensity.fontScale
            else platformDensity.fontScale
        CompositionLocalProvider(LocalDensity provides Density(platformDensity.density, previewFontScale)) {
        ChillerApp(
            state = state.run, sessions = state.sessions, selectedSessionId = state.sessionId,
            workspacePath = state.settings.workspace, isDemo = !state.apiKeyConfigured,
            providerModel = state.settings.model, providerBaseUrl = state.settings.baseUrl,
            thinkingEnabled = state.settings.thinking, apiKeyConfigured = state.apiKeyConfigured,
            onSend = { text, mode, requestId -> controller.send(text, mode, requestId) },
            onSendWithAttachments = { text, mode, requestId, attachments -> controller.send(text, mode, requestId, attachments) },
            onImportTextAttachment = controller::importTextAttachmentForSession,
            onImportFiles = controller::importFilesForSession,
            onImportImageAttachment = controller::importImageAttachmentForSession,
            onPreviewFile = controller::previewFile,
            onRevealFile = { path -> scope.launch { controller.revealFile(path) } },
            onDeleteMessage = controller::deleteMessage,
            onRecoverInterrupted = { scope.launch { controller.recoverInterrupted() } },
            projects = dev.chiller.app.ui.ProjectsModel(
                pinned = state.settings.pinnedWorkspaces, hidden = state.settings.hiddenWorkspaces,
                folderLocked = state.folderLocked, workspaceMissing = state.workspaceMissing,
                excluded = controller::isDemoFolder,
                running = state.runningSessions, waiting = state.waitingSessions,
                onStop = { id -> scope.launch { controller.stopSession(id) } },
                onNewIn = { root -> scope.launch { controller.newSessionIn(root) } },
                onPin = { root, pinned -> scope.launch { controller.pinWorkspace(root, pinned) } },
                onRemove = { root -> scope.launch { controller.removeRecentWorkspace(root) } },
                onReveal = { root -> runCatching { java.awt.Desktop.getDesktop().open(java.io.File(root)) } },
                onRelocate = { root ->
                    val parent = generateSequence(java.io.File(root)) { it.parentFile }.firstOrNull { it.isDirectory }?.path
                    chooseDirectory(window, parent, mac)?.let { path -> scope.launch { controller.relocateWorkspace(root, path) } }
                },
            ),
            onEditMessage = controller::editMessage,
            onStop = { scope.launch { controller.stop() } },
            onNewSession = { scope.launch { controller.newSession() } },
            onSelectSession = { scope.launch { controller.selectSession(it) } },
            onChooseWorkspace = chooseWorkspace,
            recentWorkspaces = state.settings.recentWorkspaces, sessionWorkspaces = state.sessionWorkspaces,
            onStartDemo = { scope.launch { controller.startDemo() } },
            onCloseWorkspace = { scope.launch { controller.closeWorkspace() } },
            subagents = state.subagents, onCancelSubagent = { id -> scope.launch { controller.cancelSubagent(id) } },
            settingsData = dev.chiller.app.ui.SettingsData(
                state.settings.baseUrl, state.settings.model, state.settings.thinking, state.apiKeyConfigured,
                secureStorageSupported = controller.secureStorageSupported, secureStorageName = controller.secureStorageName, keySources = controller.keySources(), connection = state.connection, catalog = state.catalog, allowProcesses = state.allowProcesses,
                captureTargets = state.captureTargets, selectedCaptureTarget = state.selectedCaptureTarget, screenshotSupported = controller.screenshotSupported,
                workspace = state.settings.workspace, verifyCommand = state.settings.workspace?.let { state.settings.verifyCommands[it] }.orEmpty(),
                suggestedVerifyCommand = state.settings.workspace?.let { root -> runCatching { dev.chiller.app.di.suggestVerifyCommand(java.nio.file.Path.of(root)) }.getOrNull() },
                agent = state.settings.agent, customProviders = state.settings.customProviders,
                costBudget = state.settings.costBudget, imageModel = dev.chiller.app.di.imageModelFor(state.settings.agent, state.catalog)?.id, recent = state.settings.recentWorkspaces, dataDir = controller.dataDirectory().toString(), demoCount = demoCount,
                version = dev.chiller.app.di.BuildInfo.displayVersion, build = dev.chiller.app.di.BuildInfo.build, commit = dev.chiller.app.di.BuildInfo.commit,
                runtime = dev.chiller.app.di.BuildInfo.runtime, os = dev.chiller.app.di.BuildInfo.os,
            ),
            onRewindTurn = { ids -> scope.launch { controller.rewindTurn(ids) } },
            verify = state.verify, onDismissVerify = controller::dismissVerify, undoneChanges = state.undoneChanges, outsideChanges = state.outside,
            comparison = dev.chiller.app.ui.ComparisonModel(
                comparisonState, state.settings.baseUrl,
                (listOf(state.settings.model) + state.catalog.chatModels.map { it.id } + controller.configuredModels()).filter { it.isNotBlank() }.distinct(),
                dev.chiller.evals.EVAL_TASK_INDEX,
                dev.chiller.app.ui.ComparisonActions(
                    start = { sides, tasks, repeats -> controller.startComparison(sides, tasks, repeats) },
                    cancel = { controller.comparison.cancel() }, show = { controller.comparison.show(it) }, reset = { controller.comparison.reset() },
                ),
            ),
            settingsActions = dev.chiller.app.ui.SettingsActions(
                saveCredential = { scope.launch { controller.saveCredential() } },
                saveCustomProvider = { provider, key, done -> scope.launch { done(controller.saveCustomProvider(provider, key)) } },
                deleteCustomProvider = { id -> scope.launch { controller.deleteCustomProvider(id) } },
                costBudget = { budget, done -> scope.launch { done(controller.updateCostBudget(budget)) } },
                deleteCredential = { scope.launch { controller.deleteCredential() } },
                agent = { options -> scope.launch { controller.updateAgentOptions(options) } },
                setVerifyCommand = { root, command -> controller.setVerifyCommand(root, command) },
                refreshCaptureTargets = { scope.launch { controller.refreshCaptureTargets() } },
                selectCaptureTarget = { id -> scope.launch { controller.selectCaptureTarget(id) } },
                removeRecent = { path -> scope.launch { controller.removeRecentWorkspace(path) } },
                openPath = { path -> runCatching { java.io.File(path).also { it.mkdirs() }.let(java.awt.Desktop.getDesktop()::open) } },
                openUrl = { url -> runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } },
                deleteDemos = { scope.launch { controller.deleteDemoProjects(); demoCount = controller.demoProjectCount() } },
            ),
            titleBar = titleBar, buildStamp = BuildStamp.value,
            onOpenTrace = {
                val trace = controller.tracePath(state.sessionId).toFile()
                runCatching {
                    val desktop = java.awt.Desktop.getDesktop()
                    if (trace.exists() && desktop.isSupported(java.awt.Desktop.Action.BROWSE_FILE_DIR)) desktop.browseFileDirectory(trace)
                    else trace.parentFile.also { it.mkdirs() }.let(desktop::open)
                }
                Unit
            },
            onOpenWorkspace = { path -> scope.launch { controller.chooseWorkspace(java.nio.file.Path.of(path)) } },
            onConfigureProvider = { url, model, thinking, key -> controller.configure(url, model, thinking, key); controller.state.value.sessionId },
            onAnswerQuestion = controller::answer,
            onUndo = { scope.launch { controller.undo(it) } },
            onAllowProcessesChange = controller::allowProcesses, allowProcesses = state.allowProcesses,
            actions = actions,
            preferences = state.uiPreferences, onPreferences = { scope.launch { controller.updateUiPreferences(it) } },
            models = controller.configuredModels(), onSelectModel = controller::selectModel,
            onRenameSession = controller::renameSession,
            onPreviewDeleteSession = controller::previewSessionDeletion,
            onDeleteSession = controller::deleteSession,
            onExportSession = { id, format -> scope.launch { controller.prepareExport(format, id) } },
            connection = state.connection, onCheckConnection = { controller.checkConnection() }, onCancelConnection = controller::cancelConnectionCheck,
            catalog = state.catalog, onRefreshModels = { controller.refreshModels() }, onCancelCatalogRefresh = controller::cancelCatalogRefresh,
            exportPreview = state.exportContent, exportFormat = state.exportFormat,
            onDismissExport = controller::dismissExport,
            onSaveExport = {
                val extension = if (state.exportFormat == "json") "json" else "md"
                val picker = FileDialog(window, "Сохранить экспорт", FileDialog.SAVE)
                picker.file = "chiller-${(state.exportSessionId ?: state.sessionId).take(8)}.$extension"
                picker.isVisible = true
                val path = picker.files.firstOrNull()?.toPath()
                picker.dispose()
                if (path != null) scope.launch { controller.saveExport(path) }
            },
        )
        }
    }
}

private fun chooseDirectory(owner: Frame, current: String?, mac: Boolean): Path? {
    if (!mac) {
        val picker = JFileChooser(current ?: System.getProperty("user.home"))
        picker.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        return if (picker.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) picker.selectedFile.toPath() else null
    }
    val key = "apple.awt.fileDialogForDirectories"
    val previous = System.getProperty(key)
    return try {
        System.setProperty(key, "true")
        val picker = FileDialog(owner, "Открыть проект", FileDialog.LOAD)
        picker.directory = current ?: System.getProperty("user.home")
        picker.isVisible = true
        val result = picker.files.firstOrNull()?.toPath()
        picker.dispose()
        result
    } finally { if (previous == null) System.clearProperty(key) else System.setProperty(key, previous) }
}
