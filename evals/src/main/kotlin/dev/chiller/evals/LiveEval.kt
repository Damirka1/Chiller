package dev.chiller.evals

import dev.chiller.agent.RunBudgetProvider
import dev.chiller.agent.AgentCoordinator
import dev.chiller.agent.RunTraceSink
import dev.chiller.agent.SubagentSupervisor
import dev.chiller.agent.ModelProfile
import dev.chiller.agent.ModelProfiles
import dev.chiller.contracts.ToolExecutor
import dev.chiller.tools.ProfiledTools
import dev.chiller.tools.SubagentTools
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.chiller.contracts.ImageService
import dev.chiller.contracts.plus
import dev.chiller.contracts.InteractionBroker
import dev.chiller.contracts.UserQuestion
import dev.chiller.providers.ChatCompletionsProfile
import dev.chiller.providers.ChatCompletionsProvider
import dev.chiller.runtime.LocalProcessService
import dev.chiller.sessions.JsonlEventStore
import dev.chiller.sessions.LocalArtifactStore
import dev.chiller.sessions.TraceFile
import dev.chiller.tools.LocalReadOnlyCodeService
import dev.chiller.tools.BuiltinTools
import dev.chiller.workspace.LocalWorkspace
import dev.chiller.workspace.ProjectInstructions
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Comparator
import kotlin.system.exitProcess

@Serializable
data class EvalResult(
    val task: String, val attempt: Int, val passed: Boolean, val verdict: String, val status: String, val error: String?,
    val rounds: Int, val toolCalls: Int, val failedToolCalls: Int, val toolErrors: Map<String, Int>, val toolsUsed: List<String>,
    val durationMs: Long, val promptTokens: Long?, val completionTokens: Long?, val cachedPromptTokens: Long?, val finalAnswer: String,
    val subagents: Int = 0, val subagentsCompleted: Int = 0,
    val loss: LossMetrics = LossMetrics(),
    /** The provider, not the model, ended the attempt (connection, TLS, credentials, quota, server error). */
    val infrastructure: Boolean = false,
    /** Steps the model's last plan of the turn left neither done nor skipped. */
    val openPlanSteps: Int = 0,
)

@Serializable
data class EvalSummary(
    val task: String,
    val attempts: Int,
    val passed: Int,
    val passRate: Double,
    val durationMs: Long,
    val promptTokens: Long?,
    val completionTokens: Long?,
    val cachedPromptTokens: Long?,
    val repeatedReads: Int = 0,
    val failedEdits: Int = 0,
    val toolOutputChars: Long = 0,
)

@Serializable
data class EvalReport(val model: String, val endpoint: String, val startedAt: String, val maxRounds: Int, val maxOutputTokens: Int, val repeats: Int, val results: List<EvalResult>, val summaries: List<EvalSummary>, val profile: String = "full",
    /** Tasks the profile cannot attempt (a required tool is not in its toolbox). */
    val skipped: List<String> = emptyList())

/**
 * Opt-in live evaluation of the agent on [EVAL_TASKS]. Never part of `check`.
 *
 * Environment:
 * - CHILLER_EVAL_BASE_URL (default https://api.deepseek.com), CHILLER_EVAL_MODEL (default deepseek-flash)
 * - key: CHILLER_EVAL_API_KEY, DEEPSEEK_API_KEY or API_TOKEN; may be absent only for a loopback endpoint
 * - CHILLER_EVAL_TASKS (comma-separated ids), CHILLER_EVAL_REPEATS (1–5), CHILLER_EVAL_MAX_ROUNDS (≤ 20),
 *   CHILLER_EVAL_MAX_OUTPUT (≤ 4096), CHILLER_EVAL_THINKING (true/false), CHILLER_EVAL_PROJECT_MAP (true/false, A/B override)
 * Reports and per-task traces go to reports/local/evals/ (git-ignored).
 */
fun main() = runBlocking {
    val env = System.getenv()
    val baseUrl = env["CHILLER_EVAL_BASE_URL"]?.takeIf { it.isNotBlank() } ?: "https://api.deepseek.com"
    val model = env["CHILLER_EVAL_MODEL"]?.takeIf { it.isNotBlank() } ?: "deepseek-flash"
    val keyNames = listOf("CHILLER_EVAL_API_KEY", "DEEPSEEK_API_KEY", "API_TOKEN")
    val key = keyNames.firstNotNullOfOrNull { env[it]?.takeIf(String::isNotBlank) } ?: dotenvKey(keyNames)
    val host = runCatching { URI(baseUrl).host }.getOrNull().orEmpty()
    val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
    if (key == null && !loopback) {
        System.err.println("LIVE EVAL SKIPPED: set CHILLER_EVAL_API_KEY, DEEPSEEK_API_KEY or API_TOKEN (only a loopback endpoint may run without a key).")
        exitProcess(2)
    }
    val selected = env["CHILLER_EVAL_TASKS"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
    val tasks = EVAL_TASKS.filter { selected == null || it.id in selected }
    require(tasks.isNotEmpty()) { "No tasks match CHILLER_EVAL_TASKS; known: ${EVAL_TASKS.joinToString { it.id }}" }
    val repeats = (env["CHILLER_EVAL_REPEATS"]?.toIntOrNull() ?: 1).coerceIn(1, 5)
    val maxRounds = (env["CHILLER_EVAL_MAX_ROUNDS"]?.toIntOrNull() ?: 14).coerceIn(1, 20)
    val maxOutput = (env["CHILLER_EVAL_MAX_OUTPUT"]?.toIntOrNull() ?: 2048).coerceIn(256, 4096)
    val thinking = env["CHILLER_EVAL_THINKING"] == "true"

    val profileId = env["CHILLER_EVAL_PROFILE"]?.trim()?.ifEmpty { null } ?: "auto"
    val catalog = listedModels(baseUrl, key)
    val listed = catalog.firstOrNull { it.id == model }
    val imageModel = catalog.firstOrNull { it.imageGeneration == true }
    val resolved = ModelProfiles.resolve(profileId, model, listed?.displayName)
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val outDir = Path.of("reports/local/evals/$stamp-${model.replace(Regex("[^A-Za-z0-9._-]"), "_")}-${resolved.id}").toAbsolutePath()
    println("=== CHILLER LIVE EVAL · $model${listed?.displayName?.let { " ($it)" } ?: ""} · profile ${resolved.id} · ${tasks.size} task(s) × $repeats · maxRounds=$maxRounds maxOutput=$maxOutput thinking=$thinking" +
        " · context ${listed?.contextWindow ?: "unknown"} · images ${imageModel?.id ?: "none"} ===")
    val report = runEvalSuite(SuiteSpec(baseUrl, model, { key }, profileId, tasks.map { it.id }.toSet(), repeats, maxRounds, maxOutput, thinking, outDir = outDir,
        projectMap = env["CHILLER_EVAL_PROJECT_MAP"]?.toBooleanStrictOrNull(), listed = listed, imageModel = imageModel)) { result ->
        println("%-20s #%d %s  rounds=%-2d tools=%-2d errors=%-2d reread=%-2d badEdits=%-2d out=%4dk %5.1fs  %s".format(result.task, result.attempt, outcomeLabel(result),
            result.rounds, result.toolCalls, result.failedToolCalls, result.loss.repeatedReads, result.loss.failedEdits, result.loss.toolOutputChars / 1000, result.durationMs / 1000.0,
            (if (result.infrastructure) result.error.orEmpty() else result.verdict).take(80)))
    }
    report.skipped.forEach { println("%-20s    SKIP  a required tool is not available (${report.profile} profile, images ${imageModel?.id ?: "none"})".format(it)) }
    val passed = report.results.count { it.passed }
    val infrastructure = report.results.count { it.infrastructure }
    println("PASSED $passed/${report.results.size} (${if (report.results.isEmpty()) 0 else passed * 100 / report.results.size}%)" +
        (if (infrastructure > 0) " · provider errors $infrastructure (not model failures)" else "") + " · report: ${outDir.resolve("report.md")}")
}

/**
 * The first named key from the nearest `.env` in the working directory or its parents (up to 6 levels),
 * so a run from a worktree or module folder finds the repository's file. Values are never printed.
 */
fun dotenvKey(names: List<String>, start: Path = Path.of("").toAbsolutePath()): String? {
    val file = generateSequence(start) { it.parent }.take(7).map { it.resolve(".env") }.firstOrNull { Files.isRegularFile(it) && Files.size(it) < 65_536 } ?: return null
    val values = runCatching { Files.readAllLines(file) }.getOrDefault(emptyList()).mapNotNull { raw ->
        val line = raw.trim().removePrefix("export ").trim()
        if (line.startsWith("#") || '=' !in line) return@mapNotNull null
        val name = line.substringBefore('=').trim()
        val value = line.substringAfter('=').trim().substringBefore(" #").trim().removeSurrounding("\"").removeSurrounding("'")
        (name to value).takeIf { name in names && value.isNotEmpty() }
    }.toMap()
    return names.firstNotNullOfOrNull { values[it] }
}

internal suspend fun runTask(task: EvalTask, attempt: Int, provider: ChatCompletionsProvider, model: String, thinking: Boolean, maxRounds: Int, maxOutput: Int, outDir: Path, modelProfile: ModelProfile = ModelProfiles.FULL,
    contextWindowTokens: Int? = null, images: ImageService? = null): EvalResult {
    val root = Files.createTempDirectory("chiller-eval-${task.id}-")
    val project = root.resolve("project").also { Files.createDirectories(it) }
    task.materialize(project)
    val session = "${task.id}-$attempt"
    val store = JsonlEventStore(root.resolve("sessions"))
    val artifacts = LocalArtifactStore(root.resolve("artifacts"))
    val processes = LocalProcessService(project)
    val code = LocalReadOnlyCodeService(project.toString())
    val builtin = BuiltinTools(LocalWorkspace(project, store, artifacts, session), processes, artifacts, object : InteractionBroker {
        override suspend fun ask(question: UserQuestion) = "No answer is available in an unattended evaluation. Make a reasonable assumption and state it."
    }, code = code, images = images)
    val budget = RunBudgetProvider(provider, maxRequests = 48, maxOutputTokens = 32768)
    val traceFile = TraceFile(outDir.resolve("traces/$session.jsonl"))
    val started = System.nanoTime()
    var failure: String? = null
    return kotlinx.coroutines.coroutineScope {
        val supervisor = SubagentSupervisor(budget, builtin, store, session, model, this)
        // Same wiring as the desktop app: the profile narrows tools, may drop helpers, swaps the prompt and caps output.
        val helpersAllowed = modelProfile.tools == null || "spawn_agent" in modelProfile.tools!!
        val delegating: ToolExecutor = if (helpersAllowed) SubagentTools(builtin, supervisor) else builtin
        val tools: ToolExecutor = modelProfile.tools?.let { ProfiledTools(delegating, it) } ?: delegating
        val output = ModelProfiles.replyTokens(modelProfile.maxOutputTokens?.let { minOf(it, maxOutput) } ?: maxOutput, thinking)
        // A fresh engine over the same journal and workspace; after a "restart" it restores the conversation from disk.
        suspend fun newEngine() = AgentCoordinator(budget, tools, store, processes, session, model, thinking, maxRounds = maxRounds, maxOutputTokens = output,
            projectInstructions = ProjectInstructions.load(project)?.text, traces = RunTraceSink { traceFile.append(it) }, subagents = supervisor.takeIf { helpersAllowed },
            systemPrompt = modelProfile.systemPrompt, projectMap = if (modelProfile.projectMap) code.projectMap() else null, scope = this,
            contextWindowTokens = task.contextWindowTokens ?: contextWindowTokens)
        var engine = newEngine()
        // Turns run in one session; totals add up over them, and the first failing turn ends the attempt.
        var rounds = 0; var toolCalls = 0; var failedToolCalls = 0
        var usage = dev.chiller.contracts.TokenUsage()
        val turns = task.turns
        var verdict = Verdict(false, "not run")
        for ((index, turn) in turns.withIndex()) {
            turn.before(project)
            if (turn.restartBefore && index > 0) { engine = newEngine(); engine.restore() }
            try {
                withTimeout(TURN_TIMEOUT_MS) { engine.send(turn.prompt, turn.mode ?: task.mode, turn.allowProcesses ?: task.allowProcesses).join() }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) { failure = "timeout after ${TURN_TIMEOUT_MS / 60_000} min in turn ${index + 1}"; engine.stop() }
            catch (error: Exception) { failure = error.javaClass.simpleName }
            val turnState = engine.state.value
            rounds += turnState.modelRounds; toolCalls += turnState.toolCalls; failedToolCalls += turnState.failedToolCalls; usage = usage + turnState.usage
            val turnAnswer = turnState.messages.lastOrNull { it.role == "assistant" && !it.content.isNullOrBlank() }?.content.orEmpty()
            val checked = runCatching { turn.check(project, turnAnswer) }.getOrElse { Verdict(false, "check crashed: ${it.message}") }
            val label = if (turns.size > 1) "turn ${index + 1}/${turns.size}: " else ""
            verdict = Verdict(checked.passed && turnState.status == "completed", label + checked.detail)
            if (!verdict.passed || failure != null) break
        }
        val state = engine.state.value
        val answer = state.messages.lastOrNull { it.role == "assistant" && !it.content.isNullOrBlank() }?.content.orEmpty()
        val openSteps = dev.chiller.agent.openPlanSteps(state.messages).size
        if (verdict.passed && task.requireClosedPlan && openSteps > 0) verdict = Verdict(false, "${verdict.detail}; plan left $openSteps step(s) open")
        // A memory scenario that never compacted did not test memory: it cannot pass.
        if (verdict.passed && task.requireContextCompaction && store.read(session).none { it.type == "context.summary" })
            verdict = Verdict(false, "${verdict.detail}; the context was never compacted, so memory was not exercised")
        val errors = state.activities.filter { it.status == "failed" }.mapNotNull { activity ->
            runCatching { Json.parseToJsonElement(activity.output).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content }.getOrNull()?.let { "${activity.name}:$it" }
        }.groupingBy { it }.eachCount()
        processes.close()
        Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        val helpers = supervisor.state.value
        val error = failure ?: state.error?.take(300)
        EvalResult(
            task.id, attempt, verdict.passed && state.status == "completed", verdict.detail, state.status, error,
            rounds, toolCalls, failedToolCalls, errors, state.activities.map { it.name }.distinct(),
            (System.nanoTime() - started) / 1_000_000, usage.promptTokens, usage.completionTokens, usage.cachedPromptTokens, answer.take(1500),
            subagents = helpers.size, subagentsCompleted = helpers.count { it.status == dev.chiller.contracts.SubagentStatus.COMPLETED },
            loss = lossMetrics(state.messages, state.activities),
            infrastructure = state.status != "completed" && error?.substringBefore(':')?.trim() in INFRASTRUCTURE_CODES,
            openPlanSteps = openSteps,
        )
    }
}

/** Provider failures that say nothing about the model or the harness's tools. */
private val INFRASTRUCTURE_CODES = setOf("PROVIDER_TRANSPORT_ERROR", "PROVIDER_TLS_ERROR", "PROVIDER_TIMEOUT", "PROVIDER_HTTP_ERROR",
    "AUTHENTICATION_FAILED", "RATE_LIMITED", "MISSING_CREDENTIAL")

internal fun outcomeLabel(result: EvalResult): String = when { result.passed -> "PASS"; result.infrastructure -> "ERROR"; else -> "FAIL" }

internal fun renderMarkdown(report: EvalReport): String = buildString {
    val results = report.results
    val passed = results.count { it.passed }
    appendLine("# Chiller eval · ${report.model}")
    appendLine()
    appendLine("Endpoint `${report.endpoint}` · started ${report.startedAt} · repeats ${report.repeats} · maxRounds ${report.maxRounds} · maxOutput ${report.maxOutputTokens}")
    appendLine()
    val infrastructure = results.count { it.infrastructure }
    appendLine("**Passed ${passed}/${results.size}**" + (if (infrastructure > 0) " (provider errors $infrastructure)" else "") + " · rounds ${results.sumOf { it.rounds }} · tool calls ${results.sumOf { it.toolCalls }} (failed ${results.sumOf { it.failedToolCalls }}) · " +
        "time ${"%.1f".format(results.sumOf { it.durationMs } / 1000.0)} s · prompt ${results.sumOf { it.promptTokens ?: 0 }} · completion ${results.sumOf { it.completionTokens ?: 0 }} · cached ${results.sumOf { it.cachedPromptTokens ?: 0 }} tokens")
    appendLine()
    appendLine("Losses: repeated reads ${results.sumOf { it.loss.repeatedReads }} · failed edits ${results.sumOf { it.loss.failedEdits }} · tool output sent ${results.sumOf { it.loss.toolOutputChars } / 1000}k chars" +
        " · attempts ending with an open plan ${results.count { it.openPlanSteps > 0 }}")
    appendLine()
    appendLine("| Task | # | Result | Rounds | Tools (failed) | Re-reads | Failed edits | Tool output, k chars | Helpers | Time, s | Prompt / completion tok. | Verdict |")
    appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
    results.forEach { r ->
        appendLine("| ${r.task} | ${r.attempt} | ${outcomeLabel(r)} | ${r.rounds} | ${r.toolCalls} (${r.failedToolCalls}) | ${r.loss.repeatedReads} | ${r.loss.failedEdits} | ${"%.1f".format(r.loss.toolOutputChars / 1000.0)} | ${if (r.subagents > 0) "${r.subagentsCompleted}/${r.subagents}" else "—"} | ${"%.1f".format(r.durationMs / 1000.0)} | ${r.promptTokens ?: "—"} / ${r.completionTokens ?: "—"} | ${r.verdict.replace("|", "\\|").take(120)} |")
    }
    appendLine()
    appendLine("## Per-task aggregate")
    appendLine()
    appendLine("| Task | Passed | Rate | Total time, s | Prompt / completion / cached tokens |")
    appendLine("|---|---:|---:|---:|---:|")
    report.summaries.forEach { summary ->
        appendLine("| ${summary.task} | ${summary.passed}/${summary.attempts} | ${"%.0f".format(summary.passRate * 100)}% | ${"%.1f".format(summary.durationMs / 1000.0)} | ${summary.promptTokens ?: "—"} / ${summary.completionTokens ?: "—"} / ${summary.cachedPromptTokens ?: "—"} |")
    }
    val errors = results.flatMap { it.toolErrors.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
    if (errors.isNotEmpty()) {
        appendLine()
        appendLine("Tool errors: " + errors.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ×${it.value}" })
    }
    results.filter { !it.passed && it.error != null }.forEach { appendLine(); appendLine("- ${it.task} #${it.attempt}: status ${it.status}, ${it.error}") }
    if (report.skipped.isNotEmpty()) { appendLine(); appendLine("Skipped (a required tool is not available in the ${report.profile} profile or on the endpoint): ${report.skipped.joinToString()}") }
}

/** The app gives a task ten minutes; with thinking a turn of slow reasoning rounds needs the same here. */
private const val TURN_TIMEOUT_MS = 10 * 60_000L
