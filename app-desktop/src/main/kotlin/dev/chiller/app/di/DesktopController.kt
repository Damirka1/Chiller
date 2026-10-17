package dev.chiller.app.di

import dev.chiller.agent.*
import dev.chiller.contracts.*
import dev.chiller.providers.*
import dev.chiller.runtime.LocalProcessService
import dev.chiller.sessions.*
import dev.chiller.tools.BuiltinTools
import dev.chiller.tools.SubagentTools
import dev.chiller.workspace.LocalWorkspace
import dev.chiller.workspace.ProjectInstructions
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
data class DesktopSettings(val baseUrl: String = "https://api.deepseek.com", val model: String = "deepseek-flash", val thinking: Boolean = false, val workspace: String? = null, val rememberedModels: Map<String, List<String>> = emptyMap(), val recentWorkspaces: List<String> = emptyList(), val agent: AgentOptions = AgentOptions())

/** How the agent runs; applied from the next dialog. Bounded so a typo cannot start an unbounded run. */
@Serializable
data class AgentOptions(
    val maxRounds: Int = 24,
    val maxOutputTokens: Int = 4096,
    val subagents: Boolean = true,
    val projectInstructions: Boolean = true,
) {
    fun sanitized() = copy(maxRounds = maxRounds.coerceIn(4, 60), maxOutputTokens = maxOutputTokens.coerceIn(1024, 16_384))
}
data class SendReceipt(val accepted: Boolean, val sessionId: String, val clientRequestId: String, val reason: String? = null)
data class ConnectionState(val status: String = "unverified", val message: String = "", val checkedAt: String? = null)
data class ModelCatalogState(
    val status: String = "idle",
    val models: List<ProviderModel> = emptyList(),
    val message: String = "",
    val fetchedAt: String? = null,
    val stale: Boolean = false,
)
data class DesktopState(val run: RunState = RunState(), val sessions: List<SessionInfo> = emptyList(), val sessionId: String = newId(), val settings: DesktopSettings = DesktopSettings(), val apiKeyConfigured: Boolean = false, val allowProcesses: Boolean = false, val exportContent: String? = null, val exportFormat: String = "markdown", val exportSessionId: String? = null, val uiPreferences: UiPreferences = UiPreferences(), val connection: ConnectionState = ConnectionState(), val catalog: ModelCatalogState = ModelCatalogState(), val sessionWorkspaces: Map<String, String> = emptyMap(), val subagents: List<SubagentSnapshot> = emptyList())

class DesktopController(
    private val directory: Path, initialCredential: String? = LocalCredentials.load(), connectionChecker: ConnectionChecker? = null, catalogClient: ModelCatalogClient? = null,
    /** Keys found in the environment or .env by variable name (OPENAI_API_KEY, …); mapped to their presets' origins. */
    namedKeys: Map<String, String> = emptyMap(),
) {
    private val commands = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val events = JsonlEventStore(directory.resolve("sessions"))
    private val artifacts = LocalArtifactStore(directory.resolve("artifacts"))
    private val exporter = SessionExporter(events)
    private var subagentService: SubagentService? = null
    private var subagentObserver: Job? = null

    /** The user's stop button on a helper agent. */
    suspend fun cancelSubagent(id: String) { subagentService?.cancelByUser(id) }

    /** The project the user chose (persisted), as opposed to a folder shown for a selected or demo dialog. */
    private var savedWorkspace: String? = null
    /** Keys per endpoint origin: a key is only ever sent to the origin it belongs to. In memory only. */
    private val credentials = java.util.concurrent.ConcurrentHashMap<String, String>().apply {
        ProviderPresets.all.forEach { preset -> preset.keyVariables.firstNotNullOfOrNull { namedKeys[it] }?.let { key -> originOf(preset.baseUrl)?.let { put(it, key) } } }
        initialCredential?.takeIf { it.isNotBlank() }?.let { key -> originOf("https://api.deepseek.com")?.let { put(it, key) } }
    }
    private val envOrigins: Set<String> = credentials.keys.toSet()
    private var credential: String?
        get() = originOf(currentBaseUrl())?.let { credentials[it] }
        set(value) { originOf(currentBaseUrl())?.let { origin -> if (value.isNullOrBlank()) credentials.remove(origin) else credentials[origin] = value } }
    private fun currentBaseUrl(): String = _state.value.settings.baseUrl
    /** A loopback endpoint (Ollama, LM Studio, vLLM) runs without a key. */
    private fun keyless(url: String = currentBaseUrl()) = isLoopback(url)
    private fun ready() = credential != null || keyless()
    private val _state = (originOf(DesktopSettings().baseUrl)?.let { credentials[it] } != null).let { hasKey ->
        MutableStateFlow(DesktopState(apiKeyConfigured = hasKey, connection = ConnectionState(if (hasKey) "unverified" else "missing", if (hasKey) "Подключение ещё не проверено" else "Не задан API-ключ")))
    }
    val state: StateFlow<DesktopState> = _state.asStateFlow()
    private var coordinator: AgentCoordinator? = null
    private var processService: LocalProcessService? = null
    private var workspaceService: LocalWorkspace? = null
    private var observer: Job? = null
    private var questions = PendingQuestions()
    private val preferencesStore = UiPreferencesStore(directory)
    private val preferencesMutex = Mutex()
    private val checker = connectionChecker ?: KtorConnectionChecker()
    private var connectionCheckJob: Job? = null
    private val connectionLifecycleLock = Any()
    private val connectionRevision = AtomicLong(0)
    private val injectedCatalogClient = catalogClient
    private val catalog: ModelCatalogClient by lazy { injectedCatalogClient ?: KtorModelCatalogClient(http) }
    private var catalogJob: Job? = null
    private var catalogRevision = AtomicLong(0)
    private val catalogLifecycleLock = Any()
    private val sendReceipts = LinkedHashMap<String, SendReceipt>()
    private val sendReceiptsLock = Any()
    private val http = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) { connectTimeoutMillis = 15_000; socketTimeoutMillis = 120_000; requestTimeoutMillis = 600_000 }
    }

    suspend fun initialize() {
        val file = directory.resolve("settings.json")
        var settings = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString<DesktopSettings>(Files.readString(file)) }.getOrDefault(DesktopSettings())
        }
        settings = rememberModel(settings, settings.baseUrl, settings.model)
        // A demo folder must never come back as the project on the next launch.
        if (settings.workspace?.let(::isDemoFolder) == true) settings = settings.copy(workspace = null)
        savedWorkspace = settings.workspace
        refreshSessions()
        _state.update { it.copy(settings = settings, uiPreferences = preferencesStore.load()) }
        _state.update { it.copy(apiKeyConfigured = ready(), connection = connectionForUnverified()) }
        saveSettings(settings)
    }

    suspend fun configure(url: String, model: String, thinking: Boolean, key: String?) = commands.withLock { configureLocked(url, model, thinking, key) }
    suspend fun chooseWorkspace(path: Path) = commands.withLock { chooseWorkspaceLocked(path) }
    suspend fun newSession() = commands.withLock { newSessionLocked() }
    suspend fun selectSession(id: String) = commands.withLock { selectSessionLocked(id) }
    suspend fun send(text: String, mode: ExecutionMode, clientRequestId: String = newId()): SendReceipt = commands.withLock {
        cachedReceipt(clientRequestId)?.let { return@withLock it }
        sendLocked(text, mode, clientRequestId).also(::rememberReceipt)
    }
    suspend fun undo(id: String) = commands.withLock { undoLocked(id) }
    suspend fun prepareExport(format: String, sessionId: String = state.value.sessionId) = commands.withLock { prepareExportLocked(format, sessionId) }

    fun configuredModels(): List<String> {
        val settings = state.value.settings
        val discovered = state.value.catalog.models.map { it.id }
        return (listOf(settings.model) + discovered + settings.rememberedModels[normalizeEndpoint(settings.baseUrl)].orEmpty()).distinct().take(20)
    }

    /** Explicit model listing. It is a separate user action from the paid connection check. */
    fun refreshModels(): Job = synchronized(catalogLifecycleLock) {
        val revision = catalogRevision.incrementAndGet()
        catalogJob?.cancel()
        scope.launch {
            val snapshot = commands.withLock {
                if (revision != catalogRevision.get()) return@withLock null
                val profile = _state.value.settings
                val key = credential?.takeIf { it.isNotBlank() }
                _state.update { it.copy(catalog = it.catalog.copy(status = "loading", message = "")) }
                profile to key
            } ?: return@launch
            val (profile, key) = snapshot
            val result = try {
                catalog.discover(profile.baseUrl, key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ModelCatalogResult(errorCode = "NETWORK", message = "Network error or request timed out")
            }
            commands.withLock {
                if (revision != catalogRevision.get() || profile != _state.value.settings) return@withLock
                _state.update { state ->
                    val previous = state.catalog
                    state.copy(catalog = if (result.ok) {
                        ModelCatalogState(status = "ready", models = result.models, fetchedAt = Instant.now().toString())
                    } else {
                        previous.copy(status = "failed", message = catalogMessage(result, key), stale = previous.models.isNotEmpty())
                    })
                }
            }
        }.also { catalogJob = it }
    }

    fun cancelCatalogRefresh() = synchronized(catalogLifecycleLock) { invalidateCatalogLocked() }

    private fun invalidateCatalog() = synchronized(catalogLifecycleLock) { invalidateCatalogLocked() }

    private fun invalidateCatalogLocked() {
        catalogRevision.incrementAndGet()
        catalogJob?.cancel()
        catalogJob = null
        _state.update { it.copy(catalog = ModelCatalogState()) }
    }

    private fun catalogMessage(result: ModelCatalogResult, key: String?) = when (result.errorCode) {
        "AUTHENTICATION_FAILED" -> "Провайдер отклонил ключ"
        "UNSUPPORTED" -> "Этот адрес не отдаёт список моделей — укажите ID вручную"
        "RATE_LIMITED" -> "Провайдер ограничил частоту запросов"
        "RESPONSE_TOO_LARGE" -> "Ответ со списком моделей слишком большой"
        "MALFORMED_RESPONSE" -> "Провайдер вернул неожиданный формат списка"
        "PROVIDER_TIMEOUT", "NETWORK" -> "Список моделей не получен: таймаут или сеть"
        "PROVIDER_HTTP_ERROR" -> "Провайдер не отдал список моделей"
        else -> (result.message ?: "Не удалось получить список моделей").let { message -> if (key.isNullOrBlank()) message else message.replace(key, "[СКРЫТО]") }.take(200)
    }

    suspend fun selectModel(model: String): String? = commands.withLock {
        val selected = model.trim()
        if (busy() || selected.isEmpty() || selected.length > MAX_MODEL_LENGTH) return@withLock null
        if (selected == _state.value.settings.model) return@withLock _state.value.sessionId
        configureLocked(_state.value.settings.baseUrl, selected, _state.value.settings.thinking, null)
        _state.value.sessionId
    }

    suspend fun updateUiPreferences(value: UiPreferences) = preferencesMutex.withLock {
        preferencesStore.save(value)
        _state.update { it.copy(uiPreferences = preferencesStore.load()) }
    }

    suspend fun renameSession(id: String, title: String): Boolean = commands.withLock {
        val normalized = title.trim()
        if (normalized.length !in 1..120) return@withLock false
        try {
            events.append(id, "session.renamed", buildJsonObject { put("title", normalized) })
            _state.update { it.copy(sessions = events.list().map { session -> if (session.id == id) session.copy(title = normalized) else session }) }
            true
        } catch (_: Exception) { false }
    }

    fun checkConnection(): Job = synchronized(connectionLifecycleLock) {
        val revision = connectionRevision.incrementAndGet()
        connectionCheckJob?.cancel()
        scope.launch(start = CoroutineStart.LAZY) {
            val snapshot = commands.withLock {
                if (revision != connectionRevision.get()) return@withLock null
                val key = credential?.takeIf { it.isNotBlank() } ?: if (keyless()) "" else null
                if (key == null) {
                    _state.update { it.copy(connection = ConnectionState("missing", "Не задан API-ключ")) }
                    null
                } else {
                    val profile = _state.value.settings
                    _state.update { it.copy(connection = ConnectionState("checking", "Проверяется один запрос")) }
                    profile to key
                }
            } ?: return@launch
            val (profile, key) = snapshot
            val result = try { checker.check(profile.baseUrl, profile.model, key, profile.thinking) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ConnectionCheckResult(false, "Network error or request timed out") }
            commands.withLock {
                if (revision == connectionRevision.get() && profile == _state.value.settings && key == (credential ?: "")) {
                    val safeMessage = localizeConnectionMessage((if (key.isEmpty()) result.message else result.message.replace(key, "[СКРЫТО]")).take(240))
                    _state.update { it.copy(connection = ConnectionState(if (result.success) "verified" else "failed", safeMessage, if (result.success) Instant.now().toString() else null)) }
                }
            }
        }.also { connectionCheckJob = it; it.start() }
    }

    fun cancelConnectionCheck() = synchronized(connectionLifecycleLock) {
        invalidateConnectionCheckLocked()
        _state.update { it.copy(connection = connectionForUnverified()) }
    }

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
        val oldSettings = _state.value.settings
        val oldCredential = credential
        val nextUrl = url.trimEnd('/')
        // Keys belong to origins: switching endpoints picks that endpoint's own key (or none), never carries one over.
        originOf(nextUrl)?.let { origin -> if (key != null) { if (key.isBlank()) credentials.remove(origin) else credentials[origin] = key } }
        var next = oldSettings.copy(baseUrl = nextUrl, model = model.trim(), thinking = thinking)
        next = rememberModel(next, next.baseUrl, next.model)
        val nextCredential = originOf(nextUrl)?.let { credentials[it] }
        val profileChanged = oldSettings.baseUrl != next.baseUrl || oldSettings.model != next.model || oldSettings.thinking != next.thinking || oldCredential != nextCredential
        if (profileChanged) invalidateConnectionCheck()
        if (oldSettings.baseUrl != next.baseUrl || oldCredential != nextCredential) invalidateCatalog()
        _state.update { it.copy(settings = next, sessionId = newId(), run = RunState()) }
        _state.update { it.copy(apiKeyConfigured = ready(), connection = if (profileChanged) connectionForUnverified() else it.connection) }
        saveSettings(next)
        resetEngine()
    }

    private suspend fun chooseWorkspaceLocked(path: Path) {
        if (busy()) return
        try {
            val canonical = withContext(Dispatchers.IO) { path.toRealPath() }
            require(Files.isDirectory(canonical))
            val current = _state.value.settings
            val recent = (listOf(canonical.toString()) + current.recentWorkspaces).distinct().take(MAX_RECENT_WORKSPACES)
            val settings = current.copy(workspace = canonical.toString(), recentWorkspaces = recent)
            savedWorkspace = canonical.toString()
            resetEngine()
            _state.update { it.copy(settings = settings, sessionId = newId(), run = RunState()) }
            saveSettings(settings)
        } catch (_: Exception) { showError("Не удалось открыть рабочую папку.") }
    }

    private suspend fun newSessionLocked() {
        if (busy()) return
        resetEngine()
        // Leaving the demo returns to the project the user had open, not the throwaway demo folder.
        val current = _state.value.settings.workspace
        val workspace = if (current != null && isDemoFolder(current)) savedWorkspace?.takeIf { Files.isDirectory(Path.of(it)) } else current
        _state.update { it.copy(sessionId = newId(), run = RunState(), settings = it.settings.copy(workspace = workspace)) }
    }

    /** Leaves the current project: a new dialog on the start screen without a folder. */
    suspend fun closeWorkspace() = commands.withLock {
        if (busy()) return@withLock
        resetEngine()
        val settings = _state.value.settings.copy(workspace = null)
        savedWorkspace = null
        _state.update { it.copy(settings = settings, sessionId = newId(), run = RunState()) }
        saveSettings(settings)
    }

    private fun isDemoFolder(path: String): Boolean {
        val demo = directory.resolve("demo")
        // Real paths on both sides: on macOS /var is a link to /private/var.
        val base = runCatching { demo.toRealPath() }.getOrElse { demo.toAbsolutePath().normalize() }
        val target = runCatching { Path.of(path).toRealPath() }.getOrElse { Path.of(path).toAbsolutePath().normalize() }
        return target.startsWith(base)
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

    private suspend fun sendLocked(text: String, mode: ExecutionMode, requestId: String): SendReceipt {
        val session = _state.value.sessionId
        fun rejected(reason: String) = SendReceipt(false, session, requestId, reason)
        if (requestId.isBlank() || requestId.length > 100) return rejected("Недопустимый ID отправки")
        if (text.isBlank()) return rejected("Сообщение пустое")
        if (busy()) return rejected("Уже выполняется другая задача")
        if (events.findEarly(session, "session.demo") != null) return rejected("Это демо-диалог: в нём отвечает сценарий, а не модель. Начните новый диалог.")
        if (!ready()) {
            val user = AgentMessage("user", text)
            val answer = AgentMessage("assistant", "Это offline-режим Chiller. Интерфейс готов к работе; подключите API в настройках, чтобы выполнять задачи в проекте. Ключ хранится только на время запуска.", origin = MessageOrigin(MessageOriginKind.OFFLINE))
            try { withContext(NonCancellable) { events.append(session, "message", json.encodeToJsonElement(AgentMessage.serializer(), user).jsonObject) } }
            catch (_: Exception) { return rejected("Не удалось сохранить сообщение") }
            val receipt = SendReceipt(true, session, requestId)
            rememberReceipt(receipt)
            _state.update { it.copy(run = it.run.copy(status = "preparing", messages = it.run.messages + user)) }
            try {
                events.append(session, "message", json.encodeToJsonElement(AgentMessage.serializer(), answer).jsonObject)
                _state.update { it.copy(run = it.run.copy(status = "completed", messages = it.run.messages + answer), sessions = events.list()) }
            } catch (_: Exception) {
                _state.update { it.copy(run = it.run.copy(status = "failed", error = "Сообщение сохранено, но не удалось сохранить offline-ответ.")) }
            }
            return receipt
        }
        if (_state.value.settings.workspace == null) return rejected("Перед отправкой выберите рабочую папку")
        return try {
            val engine = coordinator ?: buildEngine().also { it.restore() }
            val accepted = CompletableDeferred<Boolean>()
            val acceptedReceipt = SendReceipt(true, session, requestId)
            val job = engine.send(text, mode, _state.value.allowProcesses) {
                rememberReceipt(acceptedReceipt)
                accepted.complete(true)
            }
            job.invokeOnCompletion { if (!accepted.isCompleted) accepted.complete(false); scope.launch { refreshSessions() } }
            val wasAccepted = accepted.await()
            if (wasAccepted) SendReceipt(true, session, requestId) else rejected("Задача не приняла сообщение")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { rejected("Не удалось начать задачу") }
    }

    suspend fun stop() { coordinator?.stop(); questions.cancel() }
    private suspend fun prepareExportLocked(format: String, sessionId: String) {
        if (busy()) return
        try {
            val text = if (format == "json") exporter.json(sessionId) else exporter.markdown(sessionId)
            _state.update { it.copy(exportContent = text, exportFormat = format, exportSessionId = sessionId) }
        } catch (_: Exception) { showError("Не удалось подготовить экспорт сессии.") }
    }
    fun dismissExport() { _state.update { it.copy(exportContent = null, exportSessionId = null) } }
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

    /** Per-session trace of model rounds under the app data directory. */
    fun tracePath(sessionId: String): Path = directory.resolve("traces").resolve("$sessionId.jsonl")
    private fun traceSink(sessionId: String): RunTraceSink { val file = TraceFile(tracePath(sessionId)); return RunTraceSink { file.append(it) } }

    /**
     * Starts the scripted demo on a fresh throwaway project under the app data directory. It runs the
     * real agent loop with every tool, Python included, but no model and no network. The folder is
     * not added to recent projects and the dialog refuses later real messages.
     */
    suspend fun startDemo() = commands.withLock {
        if (busy()) return@withLock
        try {
            val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val root = withContext(Dispatchers.IO) { directory.resolve("demo").resolve(stamp).resolve("chiller-demo").also { DemoScenario.createProject(it) }.toRealPath() }
            resetEngine()
            _state.update { it.copy(settings = it.settings.copy(workspace = root.toString()), sessionId = newId(), run = RunState()) }
            val engine = buildEngine(DemoScenario.Provider(), DemoScenario.MODEL_ID)
            events.append(_state.value.sessionId, "session.demo", buildJsonObject { put("scenario", "all-tools") })
            val job = engine.send(DemoScenario.PROMPT, ExecutionMode.WORKSPACE_EDIT, allowProcesses = true)
            job.invokeOnCompletion { scope.launch { refreshSessions() } }
            refreshSessions()
        } catch (_: Exception) { showError("Не удалось запустить демо.") }
    }

    private suspend fun buildEngine(providerOverride: LlmProvider? = null, modelOverride: String? = null): AgentCoordinator {
        val s = _state.value
        val options = s.settings.agent.sanitized()
        val root = Path.of(s.settings.workspace ?: throw IllegalStateException("Choose workspace"))
        val workspace = LocalWorkspace(root, events, artifacts, s.sessionId)
        workspace.recoverPending()
        workspaceService = workspace
        val processes = LocalProcessService(root); processService = processes
        questions = PendingQuestions()
        val builtin = BuiltinTools(workspace, processes, artifacts, questions)
        val profile = if (java.net.URI(s.settings.baseUrl).host == "api.deepseek.com")
            ChatCompletionsProfile.deepSeekFlash(s.settings.baseUrl, s.settings.model)
        else ChatCompletionsProfile("generic", s.settings.baseUrl, s.settings.model, requiresApiKey = !keyless(s.settings.baseUrl))
        // The engine's provider is bound to its own endpoint's key, read at request time.
        val engineOrigin = originOf(s.settings.baseUrl)
        val provider = providerOverride ?: ChatCompletionsProvider(http, profile) { engineOrigin?.let { credentials[it] } }
        // Children use the same provider and model, read-only tools and the plain executor (no delegation).
        val supervisor = SubagentSupervisor(provider, builtin, events, s.sessionId, modelOverride ?: s.settings.model, scope)
        subagentService = supervisor
        val tools: ToolExecutor = if (options.subagents) SubagentTools(builtin, supervisor) else builtin
        // Read once per engine: an edit to AGENTS.md applies from the next dialog.
        val instructions = if (options.projectInstructions) withContext(Dispatchers.IO) { ProjectInstructions.load(root) } else null
        val engine = AgentCoordinator(provider, tools, events, processes, s.sessionId, modelOverride ?: s.settings.model, s.settings.thinking, maxRounds = options.maxRounds, maxOutputTokens = options.maxOutputTokens,
            projectInstructions = instructions?.text, traces = traceSink(s.sessionId), subagents = supervisor.takeIf { options.subagents }, scope = scope)
        coordinator = engine
        if (instructions != null) events.append(s.sessionId, "context.instructions", buildJsonObject {
            put("sources", JsonArray(instructions.sources.map(::JsonPrimitive))); put("truncated", instructions.truncated)
        })
        if (events.read(s.sessionId).none { it.type == "session.workspace" })
            events.append(s.sessionId, "session.workspace", buildJsonObject { put("root", workspace.root) })
        _state.update { it.copy(sessionWorkspaces = it.sessionWorkspaces + (s.sessionId to workspace.root)) }
        observer = scope.launch { combine(engine.state, questions.question) { run, question -> run.copy(question = run.question ?: question) }.collect { run -> _state.update { it.copy(run = run) } } }
        subagentObserver = scope.launch { supervisor.state.collect { list -> _state.update { it.copy(subagents = list) } } }
        return engine
    }

    private suspend fun resetEngine() {
        observer?.cancelAndJoin(); observer = null
        subagentObserver?.cancelAndJoin(); subagentObserver = null; subagentService = null
        _state.update { it.copy(subagents = emptyList()) }
        coordinator?.stop(); coordinator = null
        processService?.close(); processService = null; workspaceService = null
    }
    private suspend fun saveSettings(settings: DesktopSettings) = withContext(Dispatchers.IO) {
        Files.createDirectories(directory)
        val target = directory.resolve("settings.json")
        val temp = Files.createTempFile(directory, "settings", ".tmp")
        try {
            Files.writeString(temp, json.encodeToString(settings))
            runCatching { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                .onFailure { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
    }
    private suspend fun refreshSessions() {
        val sessions = events.list()
        val known = _state.value.sessionWorkspaces
        val roots = known + sessions.take(MAX_SESSION_ROOTS).filter { it.id !in known }.mapNotNull { session ->
            runCatching { events.findEarly(session.id, "session.workspace")?.payload?.get("root")?.jsonPrimitive?.contentOrNull }.getOrNull()?.let { session.id to it }
        }
        _state.update { it.copy(sessions = sessions, sessionWorkspaces = roots) }
    }
    private fun showError(message: String) { _state.update { it.copy(run = it.run.copy(status = "failed", error = message)) } }
    private fun cachedReceipt(requestId: String): SendReceipt? = synchronized(sendReceiptsLock) { sendReceipts[requestId] }
    private fun rememberReceipt(receipt: SendReceipt) = synchronized(sendReceiptsLock) {
        sendReceipts[receipt.clientRequestId] = receipt
        while (sendReceipts.size > 500) sendReceipts.remove(sendReceipts.keys.first())
    }
    private fun rememberModel(settings: DesktopSettings, endpoint: String, model: String): DesktopSettings {
        val key = normalizeEndpoint(endpoint)
        val models = LinkedHashMap<String, List<String>>()
        settings.rememberedModels.entries.toList().takeLast(15).forEach { (savedEndpoint, savedModels) ->
            models[normalizeEndpoint(savedEndpoint)] = savedModels.filter { it.isNotBlank() && it.length <= MAX_MODEL_LENGTH }.distinct().take(20)
        }
        val saved = (listOf(model) + models[key].orEmpty()).filter { it.isNotBlank() && it.length <= MAX_MODEL_LENGTH }.distinct().take(20)
        models.remove(key); models[key] = saved
        while (models.size > 16) models.remove(models.keys.first())
        return settings.copy(rememberedModels = models)
    }
    private fun normalizeEndpoint(endpoint: String): String = runCatching {
        val uri = java.net.URI(endpoint.trim()).normalize()
        val scheme = uri.scheme.lowercase(); val host = uri.host.lowercase()
        val port = if ((scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)) -1 else uri.port
        java.net.URI(scheme, null, host, port, uri.path.trimEnd('/'), null, null).toASCIIString().trimEnd('/')
    }.getOrDefault(endpoint.trim().trimEnd('/').lowercase())
    private fun sameOrigin(first: String, second: String): Boolean = runCatching {
        val a = java.net.URI(first); val b = java.net.URI(second)
        a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && effectivePort(a) == effectivePort(b)
    }.getOrDefault(false)
    private fun effectivePort(uri: java.net.URI) = uri.port.takeIf { it >= 0 } ?: if (uri.scheme.equals("https", true)) 443 else 80
    private fun connectionForUnverified() = if (!ready()) ConnectionState("missing", "Не задан API-ключ") else ConnectionState("unverified", if (credential == null) "Локальный сервер без ключа · не проверено" else "Подключение ещё не проверено")

    suspend fun updateAgentOptions(options: AgentOptions) = commands.withLock {
        val settings = _state.value.settings.copy(agent = options.sanitized())
        _state.update { it.copy(settings = settings) }
        saveSettings(settings)
    }

    suspend fun removeRecentWorkspace(path: String) = commands.withLock {
        val settings = _state.value.settings.let { it.copy(recentWorkspaces = it.recentWorkspaces - path) }
        _state.update { it.copy(settings = settings) }
        saveSettings(settings)
    }

    /** Deletes the app's own throwaway demo projects, except the one shown right now. Returns how many were removed. */
    suspend fun deleteDemoProjects(): Int = commands.withLock {
        if (busy()) return@withLock 0
        val current = _state.value.settings.workspace
        withContext(Dispatchers.IO) {
            val demo = directory.resolve("demo")
            if (!Files.isDirectory(demo)) return@withContext 0
            Files.list(demo).use { it.toList() }.filter { run -> current == null || !Path.of(current).startsWith(run.toRealPath()) }.count { run ->
                runCatching { Files.walk(run).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }.isSuccess
            }
        }
    }

    fun dataDirectory(): Path = directory
    fun demoProjectCount(): Int = runCatching { Files.list(directory.resolve("demo")).use { it.count().toInt() } }.getOrDefault(0)

    /** Where each endpoint's key came from: "env" (environment or .env) or "ui" (typed in settings). */
    fun keySources(): Map<String, String> = credentials.keys.associateWith { if (it in envOrigins) "env" else "ui" }

    /** Which endpoints have a key in memory; never the keys themselves. */
    fun keyedOrigins(): Set<String> = credentials.keys.toSet()
    private fun invalidateConnectionCheck() {
        synchronized(connectionLifecycleLock) { invalidateConnectionCheckLocked() }
    }
    private fun invalidateConnectionCheckLocked() {
        connectionRevision.incrementAndGet()
        connectionCheckJob?.cancel()
        connectionCheckJob = null
    }
    suspend fun close() {
        val pendingCheck = synchronized(connectionLifecycleLock) {
            val pending = connectionCheckJob
            invalidateConnectionCheckLocked()
            pending
        }
        pendingCheck?.cancelAndJoin()
        synchronized(catalogLifecycleLock) { catalogRevision.incrementAndGet(); catalogJob?.cancel(); catalogJob = null }
        resetEngine(); (checker as? AutoCloseable)?.close(); http.close(); credentials.clear(); scope.cancel()
    }

    private fun localizeConnectionMessage(message: String): String = when {
        message == "Connection and model access verified" || message == "verified" -> "Подключение и доступ к модели подтверждены"
        message.startsWith("Authentication failed") -> "Провайдер отклонил API-ключ"
        message.startsWith("Rate limit reached") -> "Провайдер ограничил частоту запросов"
        message.startsWith("Model or endpoint not found") -> "Модель или адрес провайдера не найдены"
        message.startsWith("Provider server error") -> "Ошибка сервера провайдера"
        message.startsWith("Provider rejected the request") -> "Провайдер отклонил запрос"
        message.startsWith("Network error") -> "Сетевая ошибка или истекло время ожидания"
        message.startsWith("Invalid or unsupported endpoint") -> "Адрес провайдера недопустим или не поддерживается"
        message == "Model ID is required" -> "Укажите ID модели"
        message == "API key is required" -> "Укажите API-ключ"
        message.isBlank() -> "Проверка подключения не выполнена"
        else -> message
    }

    private companion object { const val MAX_MODEL_LENGTH = 200; const val MAX_RECENT_WORKSPACES = 6; const val MAX_SESSION_ROOTS = 60 }
}
