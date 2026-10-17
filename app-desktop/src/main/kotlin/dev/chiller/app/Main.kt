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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import java.nio.file.Path
import java.awt.FileDialog
import java.awt.Frame
import javax.swing.JFileChooser

fun main() = application {
    val scope = rememberCoroutineScope()
    val directory = remember { System.getenv("CHILLER_DATA_DIR")?.let(Path::of) ?: if (java.lang.Boolean.getBoolean("chiller.offlinePreview")) Path.of(System.getProperty("java.io.tmpdir"), "chiller-preview") else Path.of(System.getProperty("user.home"), ".chiller") }
    val controller = remember { DesktopController(directory, if (java.lang.Boolean.getBoolean("chiller.offlinePreview")) null else LocalCredentials.load(),
        namedKeys = if (java.lang.Boolean.getBoolean("chiller.offlinePreview")) emptyMap() else LocalCredentials.loadNamed(dev.chiller.app.di.ProviderPresets.keyVariables),
        credentialStore = if (java.lang.Boolean.getBoolean("chiller.offlinePreview")) dev.chiller.app.di.CredentialStore.None else dev.chiller.app.di.CredentialStore.system()) }
    val state by controller.state.collectAsState()
    val actions = remember { MutableSharedFlow<DesktopAction>(extraBufferCapacity = 16) }
    val mac = remember { System.getProperty("os.name").startsWith("Mac") }
    val active = state.run.status in setOf("preparing", "streaming", "executing")
    LaunchedEffect(Unit) { controller.initialize() }
    Window(
        title = "Chiller",
        state = rememberWindowState(
            width = (if (java.lang.Boolean.getBoolean("chiller.offlinePreview")) System.getenv("CHILLER_PREVIEW_WIDTH")?.toIntOrNull()?.coerceIn(800, 1800) ?: 1280 else 1280).dp,
            height = (if (java.lang.Boolean.getBoolean("chiller.offlinePreview")) System.getenv("CHILLER_PREVIEW_HEIGHT")?.toIntOrNull()?.coerceIn(600, 1200) ?: 860 else 860).dp,
        ),
        onCloseRequest = { scope.launch { controller.close(); exitApplication() } },
    ) {
        // Codex/Claude-style window: transparent title bar, content under it, traffic lights over the sidebar.
        // The title stays set for Mission Control and the Window menu but is not drawn.
        if (mac) LaunchedEffect(Unit) {
            window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
            window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        }
        val titleBar = remember(mac) { if (!mac) null else TitleBarSpec(dragArea = { modifier -> WindowDraggableArea(modifier) { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) } }) }
        // A finished task while the window is in the background asks for attention once (Dock bounce / taskbar flash).
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
        MenuBar {
            Menu("Файл") {
                Item("Новый диалог", enabled = !active, shortcut = KeyShortcut(Key.N, meta = mac, ctrl = !mac), onClick = { actions.tryEmit(DesktopAction.NEW_CHAT) })
                Item("Открыть проект…", enabled = !active, shortcut = KeyShortcut(Key.O, meta = mac, ctrl = !mac), onClick = chooseWorkspace)
                Item("Закрыть проект", enabled = !active && state.settings.workspace != null, onClick = { scope.launch { controller.closeWorkspace() } })
                Separator()
                Item("Экспорт Markdown…", enabled = !active && state.run.messages.isNotEmpty(), onClick = { scope.launch { controller.prepareExport("markdown") } })
                Item("Экспорт JSON…", enabled = !active && state.run.messages.isNotEmpty(), onClick = { scope.launch { controller.prepareExport("json") } })
                Separator()
                Item("Настройки…", enabled = !active, shortcut = KeyShortcut(Key.Comma, meta = mac, ctrl = !mac), onClick = { actions.tryEmit(DesktopAction.SETTINGS) })
            }
            Menu("Справка") {
                Item("Демо: все инструменты", enabled = !active, onClick = { scope.launch { controller.startDemo() } })
            }
            Menu("Диалог") {
                Item("Отправить", enabled = !active, shortcut = KeyShortcut(Key.Enter, meta = mac, ctrl = !mac), onClick = { actions.tryEmit(DesktopAction.SEND) })
                Item("Остановить", enabled = active, shortcut = KeyShortcut(Key.Period, meta = mac, ctrl = !mac), onClick = { actions.tryEmit(DesktopAction.STOP) })
            }
            Menu("Вид") {
                Item("Focus", onClick = { actions.tryEmit(DesktopAction.FOCUS) })
                Item("Studio", onClick = { actions.tryEmit(DesktopAction.STUDIO) })
                Item("Боковая панель", onClick = { actions.tryEmit(DesktopAction.SIDEBAR) })
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
                secureStorageSupported = controller.secureStorageSupported, keySources = controller.keySources(), connection = state.connection, catalog = state.catalog, allowProcesses = state.allowProcesses,
                agent = state.settings.agent, recent = state.settings.recentWorkspaces, dataDir = controller.dataDirectory().toString(), demoCount = demoCount,
                version = dev.chiller.app.di.BuildInfo.version, build = BuildStamp.value, commit = dev.chiller.app.di.BuildInfo.commit,
                runtime = dev.chiller.app.di.BuildInfo.runtime, os = dev.chiller.app.di.BuildInfo.os,
            ),
            settingsActions = dev.chiller.app.ui.SettingsActions(
                saveCredential = { scope.launch { controller.saveCredential() } },
                deleteCredential = { scope.launch { controller.deleteCredential() } },
                agent = { options -> scope.launch { controller.updateAgentOptions(options) } },
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
