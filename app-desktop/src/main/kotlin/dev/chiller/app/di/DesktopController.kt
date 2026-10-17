package dev.chiller.app.di

import dev.chiller.agent.*
import dev.chiller.contracts.*
import dev.chiller.providers.*
import dev.chiller.runtime.LocalProcessService
import dev.chiller.sessions.*
import dev.chiller.tools.BuiltinTools
import dev.chiller.workspace.LocalWorkspace
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class DesktopSettings(val baseUrl: String = "https://api.deepseek.com", val model: String = "deepseek-flash", val thinking: Boolean = false, val workspace: String? = null)
data class DesktopState(val run: RunState = RunState(), val sessions: List<SessionInfo> = emptyList(), val sessionId: String = newId(), val settings: DesktopSettings = DesktopSettings(), val apiKeyConfigured: Boolean = false, val allowProcesses: Boolean = false, val exportContent: String? = null, val exportFormat: String = "markdown")

class DesktopController(private val directory: Path, initialCredential: String? = LocalCredentials.load()) {
    private val commands = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val events = JsonlEventStore(directory.resolve("sessions"))
    private val artifacts = LocalArtifactStore(directory.resolve("artifacts"))
    private val exporter = SessionExporter(events)
    private var credential: String? = initialCredential?.takeIf { it.isNotBlank() }
    private val _state = MutableStateFlow(DesktopState(apiKeyConfigured = credential != null))
    val state: StateFlow<DesktopState> = _state.asStateFlow()
    private var coordinator: AgentCoordinator? = null
    private var processService: LocalProcessService? = null
    private var workspaceService: LocalWorkspace? = null
    private var observer: Job? = null
    private var questions = PendingQuestions()
    private val http = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) { connectTimeoutMillis = 15_000; socketTimeoutMillis = 120_000; requestTimeoutMillis = 600_000 }
    }

    suspend fun initialize() {
        val file = directory.resolve("settings.json")
        val settings = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString<DesktopSettings>(Files.readString(file)) }.getOrDefault(DesktopSettings())
        }
        _state.update { it.copy(settings = settings, sessions = events.list()) }
    }

    suspend fun configure(url: String, model: String, thinking: Boolean, key: String?) = commands.withLock { configureLocked(url, model, thinking, key) }
    suspend fun chooseWorkspace(path: Path) = commands.withLock { chooseWorkspaceLocked(path) }
    suspend fun newSession() = commands.withLock { newSessionLocked() }
    suspend fun selectSession(id: String) = commands.withLock { selectSessionLocked(id) }
    suspend fun send(text: String, mode: ExecutionMode) = commands.withLock { sendLocked(text, mode) }
    suspend fun undo(id: String) = commands.withLock { undoLocked(id) }
    suspend fun prepareExport(format: String) = commands.withLock { prepareExportLocked(format) }

    private fun busy() = coordinator?.isRunning == true || listOf(_state.value.run.status, coordinator?.state?.value?.status).any { it in setOf("preparing", "streaming", "executing") }

    private suspend fun configureLocked(url: String, model: String, thinking: Boolean, key: String?) {
        if (busy()) return
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        if (uri == null || uri.host == null || uri.userInfo != null || uri.query != null || uri.fragment != null || uri.scheme !in setOf("https", "http") || model.isBlank()) {
            showError("Укажите корректный HTTP(S) URL и модель."); return
        }
        if (uri.scheme == "http" && uri.host !in setOf("localhost", "127.0.0.1", "::1")) {
            showError("Для удалённого провайдера требуется HTTPS. HTTP доступен только localhost."); return
        }
        if (key != null) credential = key.takeIf { it.isNotBlank() }
        val next = _state.value.settings.copy(baseUrl = url.trimEnd('/'), model = model.trim(), thinking = thinking)
        _state.update { it.copy(settings = next, apiKeyConfigured = credential != null, sessionId = newId(), run = RunState()) }
        saveSettings(next)
        resetEngine()
    }

    private suspend fun chooseWorkspaceLocked(path: Path) {
        if (busy()) return
        try {
            val canonical = withContext(Dispatchers.IO) { path.toRealPath() }
            require(Files.isDirectory(canonical))
            val settings = _state.value.settings.copy(workspace = canonical.toString())
            resetEngine()
            _state.update { it.copy(settings = settings, sessionId = newId(), run = RunState()) }
            saveSettings(settings)
        } catch (_: Exception) { showError("Не удалось открыть рабочую папку.") }
    }

    private suspend fun newSessionLocked() {
        if (busy()) return
        resetEngine()
        _state.update { it.copy(sessionId = newId(), run = RunState()) }
    }

    private suspend fun selectSessionLocked(id: String) {
        if (busy()) return
        try {
            resetEngine()
            val history = events.read(id)
            val savedRoot = history.firstOrNull { it.type == "session.workspace" }?.payload?.get("root")?.jsonPrimitive?.contentOrNull
            _state.update { it.copy(sessionId = id, settings = it.settings.copy(workspace = savedRoot), run = RunState()) }
            if (savedRoot == null) {
                val messages = history.filter { it.type == "message" }.map { json.decodeFromJsonElement<AgentMessage>(it.payload) }
                _state.update { it.copy(run = RunState(messages = messages)) }
            } else buildEngine().restore()
        } catch (_: Exception) { showError("Сессию не удалось восстановить. Её данные сохранены для проверки.") }
    }

    private suspend fun sendLocked(text: String, mode: ExecutionMode) {
        if (busy()) return
        if (credential == null) {
            // Explicit offline preview never performs user-requested filesystem changes.
            val reply = "Это offline-режим Chiller. Интерфейс готов к работе; подключите API в настройках, чтобы выполнять задачи в проекте. Ключ хранится только на время запуска."
            val user = AgentMessage("user", text)
            val answer = AgentMessage("assistant", reply, origin = MessageOrigin(MessageOriginKind.OFFLINE))
            for (message in listOf(user, answer)) events.append(_state.value.sessionId, "message", json.encodeToJsonElement(message).jsonObject)
            val sessions = events.list()
            _state.update { it.copy(run = it.run.copy(status = "completed", messages = it.run.messages + listOf(user, answer)), sessions = sessions) }
            return
        }
        if (_state.value.settings.workspace == null) { showError("Сначала выберите рабочую папку."); return }
        try {
            val engine = coordinator ?: buildEngine().also { it.restore() }
            engine.send(text, mode, _state.value.allowProcesses).invokeOnCompletion { scope.launch { refreshSessions() } }
        } catch (_: Exception) { showError("Не удалось начать задачу. Проверьте рабочую папку и настройки.") }
    }

    suspend fun stop() { coordinator?.stop(); questions.cancel() }
    private suspend fun prepareExportLocked(format: String) {
        if (busy()) return
        try {
            val text = if (format == "json") exporter.json(_state.value.sessionId) else exporter.markdown(_state.value.sessionId)
            _state.update { it.copy(exportContent = text, exportFormat = format) }
        } catch (_: Exception) { showError("Не удалось подготовить экспорт сессии.") }
    }
    fun dismissExport() { _state.update { it.copy(exportContent = null) } }
    suspend fun saveExport(path: Path) {
        val content = _state.value.exportContent ?: return
        try {
            withContext(Dispatchers.IO) { Files.writeString(path, content) }
            dismissExport()
        } catch (_: Exception) { showError("Не удалось сохранить экспорт. Выберите доступную папку.") }
    }
    fun answer(text: String) { questions.answer(text) }
    fun allowProcesses(value: Boolean) { if (!busy()) _state.update { it.copy(allowProcesses = value) } }
    private suspend fun undoLocked(id: String) {
        if (busy()) return
        try {
            val w = workspaceService ?: run { buildEngine(); workspaceService!! }
            val change = w.undo(id)
            _state.update { it.copy(run = it.run.copy(activities = it.run.activities + Activity(name = "undo", target = change.path, status = "completed", diff = change.diff))) }
        } catch (e: ToolException) { showError("${e.code}: ${e.message}") }
        catch (_: Exception) { showError("Откат не выполнен. Файл мог измениться.") }
    }

    private suspend fun buildEngine(): AgentCoordinator {
        val s = _state.value
        val root = Path.of(s.settings.workspace ?: throw IllegalStateException("Choose workspace"))
        val workspace = LocalWorkspace(root, events, artifacts, s.sessionId)
        workspace.recoverPending()
        workspaceService = workspace
        val processes = LocalProcessService(root); processService = processes
        questions = PendingQuestions()
        val tools = BuiltinTools(workspace, processes, artifacts, questions)
        val profile = if (java.net.URI(s.settings.baseUrl).host == "api.deepseek.com")
            ChatCompletionsProfile.deepSeekFlash(s.settings.baseUrl, s.settings.model)
        else ChatCompletionsProfile("generic", s.settings.baseUrl, s.settings.model)
        val provider = ChatCompletionsProvider(http, profile) { credential }
        val engine = AgentCoordinator(provider, tools, events, processes, s.sessionId, s.settings.model, s.settings.thinking, scope)
        coordinator = engine
        if (events.read(s.sessionId).none { it.type == "session.workspace" })
            events.append(s.sessionId, "session.workspace", buildJsonObject { put("root", workspace.root) })
        observer = scope.launch { combine(engine.state, questions.question) { run, question -> run.copy(question = run.question ?: question) }.collect { run -> _state.update { it.copy(run = run) } } }
        return engine
    }

    private suspend fun resetEngine() {
        observer?.cancelAndJoin(); observer = null
        coordinator?.stop(); coordinator = null
        processService?.close(); processService = null; workspaceService = null
    }
    private suspend fun saveSettings(settings: DesktopSettings) = withContext(Dispatchers.IO) {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("settings.json"), json.encodeToString(settings))
    }
    private suspend fun refreshSessions() { val sessions = events.list(); _state.update { it.copy(sessions = sessions) } }
    private fun showError(message: String) { _state.update { it.copy(run = it.run.copy(status = "failed", error = message)) } }
    suspend fun close() { resetEngine(); http.close(); credential = null; scope.cancel() }
}
