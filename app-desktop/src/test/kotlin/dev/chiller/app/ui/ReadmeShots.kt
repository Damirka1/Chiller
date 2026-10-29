package dev.chiller.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.chiller.agent.AgentCoordinator
import dev.chiller.agent.SubagentSupervisor
import dev.chiller.app.di.AgentOptions
import dev.chiller.app.di.DemoScenario
import dev.chiller.app.di.SendReceipt
import dev.chiller.app.di.UiPreferences
import dev.chiller.contracts.*
import dev.chiller.runtime.LocalProcessService
import dev.chiller.sessions.JsonlEventStore
import dev.chiller.sessions.LocalArtifactStore
import dev.chiller.tools.BuiltinTools
import dev.chiller.tools.SubagentTools
import dev.chiller.workspace.LocalWorkspace
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities

/**
 * Renders the README screenshots: the scripted demo runs through the real agent loop and real files, then the
 * actual ChillerApp composable is drawn offscreen. No provider, key or network is involved.
 * Run with `./gradlew :app-desktop:readmeShots`; images land in docs/images.
 */
fun main() {
    val output = Path.of(System.getProperty("chiller.readmeOut") ?: "docs/images"); Files.createDirectories(output)
    // README.md shows the English interface, README.ru.md the Russian one (files with a -ru suffix).
    for (language in dev.chiller.app.i18n.Language.entries) {
        dev.chiller.contracts.CoreLanguage.russian = language == dev.chiller.app.i18n.Language.RU
        dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.stringsFor(language)
        render(output, language.id, if (language == dev.chiller.app.i18n.Language.RU) "-ru" else "")
    }
    println("README screenshots rendered to $output (scripted demo, no live calls)")
}

private fun render(output: Path, language: String, suffix: String) {
    val ru = language == "ru"
    val root = Files.createTempDirectory("chiller-readme")
    val project = root.resolve("demo-shop").also { DemoScenario.createProject(it) }.toRealPath()
    val (state, helpers) = runBlocking { runDemo(root, project) }
    val backend = Files.createDirectories(root.resolve("backend-api")).toString()
    val landing = Files.createDirectories(root.resolve("landing-page")).toString()
    val sessions = listOf(
        SessionInfo("demo", if (ru) "Демо: все инструменты" else "Demo: all tools", "2026-10-17T10:00:00Z", project.toString()),
        SessionInfo("s2", if (ru) "Починить расчёт скидки" else "Fix the discount calculation", "2026-10-17T09:12:00Z", project.toString()),
        SessionInfo("s3", if (ru) "Ревью модуля оплаты" else "Review the payment module", "2026-10-16T18:40:00Z", project.toString()),
        SessionInfo("s4", if (ru) "Тесты для парсера CSV" else "Tests for the CSV parser", "2026-10-16T15:05:00Z", backend),
        SessionInfo("s5", if (ru) "Миграция на Ktor 3" else "Migrate to Ktor 3", "2026-10-15T11:20:00Z", backend),
        SessionInfo("s6", if (ru) "Как работает structured concurrency" else "How structured concurrency works", "2026-10-16T21:10:00Z"),
        SessionInfo("s7", if (ru) "Письмо клиентам о релизе" else "Release letter to customers", "2026-10-14T08:45:00Z"),
    )
    @Composable fun app(prefs: UiPreferences, settings: String? = null) = ChillerApp(
        state, sessions, "demo", project.toString(), isDemo = false,
        providerModel = "gemma-4-26b-a4b", providerBaseUrl = "http://localhost:8000/v1", thinkingEnabled = false, apiKeyConfigured = true,
        onSend = { _, _, id -> SendReceipt(false, "demo", id) }, onStop = {}, onNewSession = {}, onSelectSession = {}, onChooseWorkspace = {},
        onConfigureProvider = { _, _, _, _ -> null }, onAnswerQuestion = {}, onUndo = {}, onAllowProcessesChange = {}, allowProcesses = true,
        preferences = prefs, models = listOf("gemma-4-26b-a4b", "deepseek-flash"), subagents = helpers,
        sessionWorkspaces = mapOf("demo" to project.toString()),
        recentWorkspaces = listOf(project.toString(), landing), projects = ProjectsModel(pinned = listOf(backend)),
        settingsData = if (settings == null) null else SettingsData("http://localhost:8000/v1", "gemma-4-26b-a4b", false, true, allowProcesses = true,
            agent = AgentOptions(), preferences = prefs),
        openSettingsOnStart = settings,
    )
    try {
        shot(output.resolve("focus-light$suffix.png")) { app(UiPreferences(theme = "light", reducedMotion = true, language = language)) }
        shot(output.resolve("focus-dark$suffix.png")) { app(UiPreferences(theme = "dark", reducedMotion = true, language = language)) }
        shot(output.resolve("studio-dark$suffix.png")) { app(UiPreferences(theme = "dark", layout = "studio", reducedMotion = true, language = language)) }
        shot(output.resolve("settings-light$suffix.png")) { app(UiPreferences(theme = "light", reducedMotion = true, language = language), settings = "AGENT") }
    } finally { root.toFile().deleteRecursively() }
}

private suspend fun runDemo(root: Path, project: Path): Pair<RunState, List<SubagentSnapshot>> = kotlinx.coroutines.coroutineScope {
    val store = JsonlEventStore(root.resolve("sessions"))
    val artifacts = LocalArtifactStore(root.resolve("artifacts"))
    val processes = LocalProcessService(project)
    val builtin = BuiltinTools(LocalWorkspace(project, store, artifacts, "demo"), processes, artifacts, object : InteractionBroker {
        override suspend fun ask(question: UserQuestion): String = question.options.first()
    })
    try {
        val provider = DemoScenario.Provider(pacing = true)
        val supervisor = SubagentSupervisor(provider, builtin, store, "demo", DemoScenario.MODEL_ID, this)
        val engine = AgentCoordinator(provider, SubagentTools(builtin, supervisor), store, processes, "demo", DemoScenario.MODEL_ID, subagents = supervisor, scope = this)
        withTimeout(60_000) { engine.send(DemoScenario.PROMPT, ExecutionMode.WORKSPACE_EDIT, allowProcesses = true).join() }
        engine.state.value to supervisor.state.value
    } finally { processes.close() }
}

/** Renders at 2x density so the images stay sharp on GitHub. */
private fun shot(file: Path, content: @Composable () -> Unit) {
    lateinit var scene: ImageComposeScene
    SwingUtilities.invokeAndWait { scene = ImageComposeScene(2880, 1800, Density(2f)) { content() } }
    try {
        repeat(20) { frame ->
            SwingUtilities.invokeAndWait { scene.render(frame * 100_000_000L).close() }
            Thread.sleep(25)
        }
        SwingUtilities.invokeAndWait {
            scene.render(3_000_000_000L).use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
        }
    } finally { SwingUtilities.invokeAndWait { scene.close() } }
}
