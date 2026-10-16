package dev.chiller.app

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import dev.chiller.app.di.DesktopController
import dev.chiller.app.ui.ChillerApp
import kotlinx.coroutines.launch
import java.nio.file.Path
import javax.swing.JFileChooser

fun main() = application {
    val scope = rememberCoroutineScope()
    val directory = remember { System.getenv("CHILLER_DATA_DIR")?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".chiller") }
    val controller = remember { DesktopController(directory) }
    val state by controller.state.collectAsState()
    LaunchedEffect(Unit) { controller.initialize() }
    Window(
        title = "Chiller",
        state = rememberWindowState(width = 1280.dp, height = 860.dp),
        onCloseRequest = { scope.launch { controller.close(); exitApplication() } },
    ) {
        ChillerApp(
            state = state.run, sessions = state.sessions, selectedSessionId = state.sessionId,
            workspacePath = state.settings.workspace, isDemo = !state.apiKeyConfigured,
            providerModel = state.settings.model, providerBaseUrl = state.settings.baseUrl,
            thinkingEnabled = state.settings.thinking, apiKeyConfigured = state.apiKeyConfigured,
            onSend = { text, mode -> scope.launch { controller.send(text, mode) } },
            onStop = { scope.launch { controller.stop() } },
            onNewSession = { scope.launch { controller.newSession() } },
            onSelectSession = { scope.launch { controller.selectSession(it) } },
            onChooseWorkspace = {
                val picker = JFileChooser(state.settings.workspace ?: System.getProperty("user.home"))
                picker.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                if (picker.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) scope.launch { controller.chooseWorkspace(picker.selectedFile.toPath()) }
            },
            onConfigureProvider = { url, model, thinking, key -> scope.launch { controller.configure(url, model, thinking, key) } },
            onAnswerQuestion = controller::answer,
            onUndo = { scope.launch { controller.undo(it) } },
            onAllowProcessesChange = controller::allowProcesses, allowProcesses = state.allowProcesses,
        )
    }
}
