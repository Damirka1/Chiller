package dev.chiller.app.di

import dev.chiller.agent.*
import dev.chiller.contracts.*
import dev.chiller.providers.*
import dev.chiller.app.ui.undoneChangeIds
import dev.chiller.runtime.LocalProcessService
import dev.chiller.sessions.*
import dev.chiller.tools.LocalReadOnlyCodeService
import dev.chiller.tools.BoundedWebService
import dev.chiller.tools.BuiltinTools
import dev.chiller.tools.SubagentTools
import dev.chiller.tools.ProfiledTools
import dev.chiller.workspace.LocalWorkspace
import dev.chiller.workspace.ReadLimits
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

// What the desktop controller keeps and shows: settings, agent options, run and connection state.

@Serializable
data class DesktopSettings(val baseUrl: String = "https://api.deepseek.com", val model: String = "deepseek-flash", val thinking: Boolean = false, val workspace: String? = null, val rememberedModels: Map<String, List<String>> = emptyMap(), val recentWorkspaces: List<String> = emptyList(), val agent: AgentOptions = AgentOptions(), val credentialOrigins: List<String> = emptyList(),
    /** Check command per project root, e.g. "./gradlew test"; used by auto-verify. */
    val verifyCommands: Map<String, String> = emptyMap(),
    val modelCatalogs: Map<String, CachedModelCatalog> = emptyMap(),
    val allowProcesses: Boolean = false,
    /** Providers defined on this machine; see [CustomProvider]. */
    val customProviders: List<CustomProvider> = emptyList(),
    /** Money limit per task with the user's prices for one endpoint and model; off by default. */
    val costBudget: CostBudgetSettings = CostBudgetSettings(),
    /** Projects kept at the top of the sidebar, in the user's order. */
    val pinnedWorkspaces: List<String> = emptyList(),
    /** Folders removed from the project list; their dialogs stay. Opening the folder again shows it again. */
    val hiddenWorkspaces: List<String> = emptyList(),
    /** Per project folder, what it remembers; see [ProjectPrefs]. */
    val projectPrefs: Map<String, ProjectPrefs> = emptyMap(),
    /** File changes in a project wait for the user's yes or no before they are written. */
    val confirmEdits: Boolean = false,
    /** MCP servers the user connected; their tools join every dialog's tools while the server runs. */
    val mcpServers: List<dev.chiller.tools.McpServerConfig> = emptyList(),
    /** How requests reach the network, e.g. through a corporate proxy; see [ProxySettings]. */
    val proxy: ProxySettings = ProxySettings())

/**
 * What a project remembers of how it was last worked in: the model on its endpoint (offered to new dialogs only on
 * that same endpoint), process and web permission. Null means "not chosen here yet".
 */
@Serializable
data class ProjectPrefs(val baseUrl: String? = null, val model: String? = null, val allowProcesses: Boolean? = null, val allowNetwork: Boolean? = null)

/** Another connected provider in the model menu, with the chat models known for it (listed or used before). */
data class PickerProvider(val baseUrl: String, val name: String, val models: List<String>)

@Serializable
data class CachedModelCatalog(val models: List<ProviderModel>, val fetchedAt: String)

/**
 * How the agent runs; applied from the next dialog. A limit of 0 means "no limit" ("model maximum" for the reply
 * length); every other value is bounded so a typo cannot start a runaway run.
 */
@Serializable
data class AgentOptions(
    val maxRounds: Int = 24,
    val maxOutputTokens: Int = 4096,
    val subagents: Boolean = true,
    val projectInstructions: Boolean = true,
    val maxRequests: Int = 48,
    val totalOutputTokens: Int = 32768,
    val allowNetwork: Boolean = false,
    val allowScreenshots: Boolean = false,
    /** "auto", "full" or "compact"; see ModelProfiles. */
    val profile: String = "auto",
    /** Overrides the profile's temperature; null keeps the profile/provider default. */
    val temperature: Double? = null,
    /** Extra top-level request fields as a JSON object, e.g. vLLM structured-output options. */
    val requestExtras: String = "",
    /** After an editing run, run the project's check command and send failures back to the agent. */
    val autoVerify: Boolean = false,
    val autoVerifyAttempts: Int = 2,
    /** "auto" (by model id), "on" or "off": whether attached images are sent as pixels. */
    val vision: String = "auto",
    /** Image model for generate_image: "auto" (the first one the catalog lists), "off", or a model id. */
    val imageModel: String = "auto",
    /** Endpoint generate_image calls, with that endpoint's own key; empty means the chat endpoint. */
    val imageBaseUrl: String = "",
    /** Wall-clock minutes one task may run; 0 is no limit. */
    val maxRunMinutes: Int = 10,
) {
    fun sanitized() = copy(autoVerifyAttempts = autoVerifyAttempts.coerceIn(1, 3), maxRounds = unlimitedOr(maxRounds, 4, 200), maxOutputTokens = unlimitedOr(maxOutputTokens, 1024, 1_048_576),
        maxRequests = unlimitedOr(maxRequests, 1, 500), totalOutputTokens = unlimitedOr(totalOutputTokens, 1024, 4_194_304),
        profile = profile.takeIf { it in setOf("auto", "full", "compact") } ?: "auto", temperature = temperature?.coerceIn(0.0, 2.0), requestExtras = requestExtras.take(4000),
        vision = vision.takeIf { it in setOf("auto", "on", "off") } ?: "auto", imageModel = imageModel.trim().take(200).ifEmpty { "auto" },
        imageBaseUrl = imageBaseUrl.trim().trimEnd('/').take(500).takeIf { it.isEmpty() || endpointAllowed(it) } ?: "",
        maxRunMinutes = unlimitedOr(maxRunMinutes, 1, 1_440))

    /** Model steps per task; "no limit" still stops at a backstop so a looping run cannot go on forever. */
    val effectiveMaxRounds: Int get() = if (maxRounds == UNLIMITED) UNLIMITED_ROUNDS_BACKSTOP else maxRounds
    val effectiveMaxRequests: Int get() = if (maxRequests == UNLIMITED) Int.MAX_VALUE else maxRequests
    val effectiveTotalOutputTokens: Int get() = if (totalOutputTokens == UNLIMITED) Int.MAX_VALUE else totalOutputTokens

    companion object {
        const val UNLIMITED = 0
        const val UNLIMITED_ROUNDS_BACKSTOP = 500
        private fun unlimitedOr(value: Int, min: Int, max: Int) = if (value == UNLIMITED) UNLIMITED else value.coerceIn(min, max)
    }
}

/**
 * Reply length per model step. "Model maximum" asks for as much as the context allows (the coordinator caps it at half the
 * window) and the provider clamps it to the model's real ceiling; an explicit choice also lifts the profile's own cap.
 */
fun effectiveMaxOutput(options: AgentOptions, profileCap: Int?): Int =
    if (options.maxOutputTokens == AgentOptions.UNLIMITED) Int.MAX_VALUE
    else profileCap?.let { minOf(it, options.maxOutputTokens) } ?: options.maxOutputTokens
data class SendReceipt(val accepted: Boolean, val sessionId: String, val clientRequestId: String, val reason: String? = null)

internal data class AutoVerifyContext(
    val sessionId: String,
    val root: String,
    val allowProcesses: Boolean,
    val processService: ProcessService,
    val command: String,
    val options: AgentOptions,
    val epoch: Long,
) {
    /**
     * The result still belongs to the same dialog engine and run, and the user changed nothing it depends on: the
     * project's check command, the agent options and process permission. Which dialog is shown does not matter.
     */
    fun isCurrent(settings: DesktopSettings, allowProcesses: Boolean, currentProcessService: ProcessService?, currentEpoch: Long): Boolean =
        settings.verifyCommands[root] == command && settings.agent.sanitized() == options && allowProcesses == this.allowProcesses &&
            currentProcessService === processService && currentEpoch == epoch
}

/** A short message about a dialog that is not on screen: its run ended, its check finished, or it waits for an answer. */
data class Notice(val id: String, val sessionId: String, val title: String, val message: String, val tone: String /* positive, negative, attention, neutral */)
data class ConnectionState(val status: String = "unverified", val message: String = "", val checkedAt: String? = null)
data class ModelCatalogState(
    val status: String = "idle",
    val models: List<ProviderModel> = emptyList(),
    val message: String = "",
    val fetchedAt: String? = null,
    val stale: Boolean = false,
) {
    /** Models that can hold a conversation; image-only models are listed for their own setting, never for chat. */
    val chatModels: List<ProviderModel> get() = models.filter { it.chat != false }
}
data class DesktopState(val run: RunState = RunState(), val sessions: List<SessionInfo> = emptyList(), val sessionId: String = newId(), val settings: DesktopSettings = DesktopSettings(), val apiKeyConfigured: Boolean = false, val allowProcesses: Boolean = false, val exportContent: String? = null, val exportFormat: String = "markdown", val exportSessionId: String? = null, val uiPreferences: UiPreferences = UiPreferences(), val connection: ConnectionState = ConnectionState(), val catalog: ModelCatalogState = ModelCatalogState(), val sessionWorkspaces: Map<String, String> = emptyMap(), val subagents: List<SubagentSnapshot> = emptyList(), val captureTargets: List<CaptureTarget> = emptyList(), val selectedCaptureTarget: String? = null, val activeProfile: String? = null, val verify: VerifyState = VerifyState(), val undoneChanges: Set<String> = emptySet(), val outside: OutsideChanges? = null,
    /** The dialog's folder is fixed: it is bound and has turns, or file tools already wrote into its own folder. */
    val folderLocked: Boolean = false,
    /** The bound folder of the shown dialog no longer exists; it can be pointed at the new location. */
    val workspaceMissing: Boolean = false,
    /** Dialogs whose run is in progress, shown or in the background. */
    val runningSessions: Set<String> = emptySet(),
    /** Dialogs whose run waits for the user's answer. */
    val waitingSessions: Set<String> = emptySet(),
    /** News from dialogs in the background, newest last. */
    val notices: List<Notice> = emptyList(),
    /** Dialogs whose run or check finished while they were not on screen and that were not opened since. */
    val unreadSessions: Set<String> = emptySet(),
    /** The project each dialog in its own working copy was made from, by dialog id. */
    val sessionOrigins: Map<String, String> = emptyMap(),
    /** The shown dialog's own working copy, when it has one. */
    val worktree: WorktreeInfo? = null,
    /** Each MCP server's state by its id, for the settings page. */
    val mcp: Map<String, dev.chiller.tools.McpServerState> = emptyMap())
