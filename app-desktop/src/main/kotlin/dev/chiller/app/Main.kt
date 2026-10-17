package dev.chiller.app

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.*
import dev.chiller.app.di.DesktopController
import dev.chiller.app.di.LocalCredentials
import dev.chiller.app.ui.ChillerApp
import dev.chiller.app.ui.DesktopAction
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import java.nio.file.Path
import java.awt.FileDialog
import java.awt.Frame
import javax.swing.JFileChooser

fun main() = application {
    val scope = rememberCoroutineScope()
    val directory = remember { System.getenv("CHILLER_DATA_DIR")?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".chiller") }
    val controller = remember { DesktopController(directory, if (java.lang.Boolean.getBoolean("chiller.offlinePreview")) null else LocalCredentials.load()) }
    val state by controller.state.collectAsState()
    val actions = remember { MutableSharedFlow<DesktopAction>(extraBufferCapacity = 16) }
    val mac = remember { System.getProperty("os.name").startsWith("Mac") }
    val active = state.run.status in setOf("preparing", "streaming", "executing")
    LaunchedEffect(Unit) { controller.initialize() }
    Window(
        title = "Chiller",
        state = rememberWindowState(width = 1280.dp, height = 860.dp),
        onCloseRequest = { scope.launch { controller.close(); exitApplication() } },
    ) {
        val chooseWorkspace = {
            chooseDirectory(window, state.settings.workspace, mac)?.let { path -> scope.launch { controller.chooseWorkspace(path) } }
            Unit
        }
        MenuBar {
            Menu("Файл") {
                Item("Новый диалог", enabled = !active, shortcut = KeyShortcut(Key.N, meta = mac, ctrl = !mac), onClick = { actions.tryEmit(DesktopAction.NEW_CHAT) })
                Item("Открыть проект…", enabled = !active, shortcut = KeyShortcut(Key.O, meta = mac, ctrl = !mac), onClick = chooseWorkspace)
                Separator()
                Item("Экспорт Markdown…", enabled = !active && state.run.messages.isNotEmpty(), onClick = { scope.launch { controller.prepareExport("markdown") } })
                Item("Экспорт JSON…", enabled = !active && state.run.messages.isNotEmpty(), onClick = { scope.launch { controller.prepareExport("json") } })
                Separator()
                Item("Настройки…", enabled = !active, shortcut = KeyShortcut(Key.Comma, meta = mac, ctrl = !mac), onClick = { actions.tryEmit(DesktopAction.SETTINGS) })
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
        ChillerApp(
            state = state.run, sessions = state.sessions, selectedSessionId = state.sessionId,
            workspacePath = state.settings.workspace, isDemo = !state.apiKeyConfigured,
            providerModel = state.settings.model, providerBaseUrl = state.settings.baseUrl,
            thinkingEnabled = state.settings.thinking, apiKeyConfigured = state.apiKeyConfigured,
            onSend = { text, mode -> scope.launch { controller.send(text, mode) } },
            onStop = { scope.launch { controller.stop() } },
            onNewSession = { scope.launch { controller.newSession() } },
            onSelectSession = { scope.launch { controller.selectSession(it) } },
            onChooseWorkspace = chooseWorkspace,
            onConfigureProvider = { url, model, thinking, key -> scope.launch { controller.configure(url, model, thinking, key) } },
            onAnswerQuestion = controller::answer,
            onUndo = { scope.launch { controller.undo(it) } },
            onAllowProcessesChange = controller::allowProcesses, allowProcesses = state.allowProcesses,
            actions = actions,
            exportPreview = state.exportContent, exportFormat = state.exportFormat,
            onDismissExport = controller::dismissExport,
            onSaveExport = {
                val extension = if (state.exportFormat == "json") "json" else "md"
                val picker = FileDialog(window, "Сохранить экспорт", FileDialog.SAVE)
                picker.file = "chiller-${state.sessionId.take(8)}.$extension"
                picker.isVisible = true
                val path = picker.files.firstOrNull()?.toPath()
                picker.dispose()
                if (path != null) scope.launch { controller.saveExport(path) }
            },
        )
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
