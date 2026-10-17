package dev.chiller.evals

import dev.chiller.agent.AgentCoordinator
import dev.chiller.agent.RunTraceSink
import dev.chiller.agent.SubagentSupervisor
import dev.chiller.tools.SubagentTools
import dev.chiller.contracts.InteractionBroker
import dev.chiller.contracts.UserQuestion
import dev.chiller.providers.ChatCompletionsProfile
import dev.chiller.providers.ChatCompletionsProvider
import dev.chiller.runtime.LocalProcessService
import dev.chiller.sessions.JsonlEventStore
import dev.chiller.sessions.LocalArtifactStore
import dev.chiller.sessions.TraceFile
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
)

@Serializable
data class EvalReport(val model: String, val endpoint: String, val startedAt: String, val maxRounds: Int, val maxOutputTokens: Int, val results: List<EvalResult>)

/**
 * Opt-in live evaluation of the agent on [EVAL_TASKS]. Never part of `check`.
 *
 * Environment:
 * - CHILLER_EVAL_BASE_URL (default https://api.deepseek.com), CHILLER_EVAL_MODEL (default deepseek-flash)
 * - key: CHILLER_EVAL_API_KEY, DEEPSEEK_API_KEY or API_TOKEN; may be absent only for a loopback endpoint
 * - CHILLER_EVAL_TASKS (comma-separated ids), CHILLER_EVAL_REPEATS (1–5), CHILLER_EVAL_MAX_ROUNDS (≤ 20),
 *   CHILLER_EVAL_MAX_OUTPUT (≤ 4096), CHILLER_EVAL_THINKING (true/false)
 * Reports and per-task traces go to reports/local/evals/ (git-ignored).
 */
fun main() = runBlocking {
    val env = System.getenv()
    val baseUrl = env["CHILLER_EVAL_BASE_URL"]?.takeIf { it.isNotBlank() } ?: "https://api.deepseek.com"
    val model = env["CHILLER_EVAL_MODEL"]?.takeIf { it.isNotBlank() } ?: "deepseek-flash"
    val key = listOf("CHILLER_EVAL_API_KEY", "DEEPSEEK_API_KEY", "API_TOKEN").firstNotNullOfOrNull { env[it]?.takeIf(String::isNotBlank) }
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

    val profile = if (host == "api.deepseek.com") ChatCompletionsProfile.deepSeekFlash(baseUrl, model)
        else ChatCompletionsProfile("generic", baseUrl, model, requiresApiKey = !loopback)
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val outDir = Path.of("reports/local/evals/$stamp-${model.replace(Regex("[^A-Za-z0-9._-]"), "_")}").toAbsolutePath()
    Files.createDirectories(outDir.resolve("traces"))
    val http = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) { connectTimeoutMillis = 20_000; socketTimeoutMillis = 180_000; requestTimeoutMillis = 300_000 }
    }
    val provider = ChatCompletionsProvider(http, profile) { key }
    val json = Json { prettyPrint = true; encodeDefaults = true }
    val results = mutableListOf<EvalResult>()
    println("=== CHILLER LIVE EVAL · $model · ${tasks.size} task(s) × $repeats · maxRounds=$maxRounds maxOutput=$maxOutput thinking=$thinking ===")
    try {
        for (task in tasks) for (attempt in 1..repeats) {
            val result = runTask(task, attempt, provider, model, thinking, maxRounds, maxOutput, outDir)
            results += result
            println("%-20s #%d %s  rounds=%-2d tools=%-2d errors=%-2d %5.1fs  %s".format(task.id, attempt, if (result.passed) "PASS" else "FAIL",
                result.rounds, result.toolCalls, result.failedToolCalls, result.durationMs / 1000.0, result.verdict.take(90)))
        }
    } finally { http.close() }
    val report = EvalReport(model, baseUrl, stamp, maxRounds, maxOutput, results)
    Files.writeString(outDir.resolve("report.json"), json.encodeToString(EvalReport.serializer(), report))
    Files.writeString(outDir.resolve("report.md"), renderMarkdown(report))
    val passed = results.count { it.passed }
    println("PASSED $passed/${results.size} · report: ${outDir.resolve("report.md")}")
}

private suspend fun runTask(task: EvalTask, attempt: Int, provider: ChatCompletionsProvider, model: String, thinking: Boolean, maxRounds: Int, maxOutput: Int, outDir: Path): EvalResult {
    val root = Files.createTempDirectory("chiller-eval-${task.id}-")
    val project = root.resolve("project").also { Files.createDirectories(it) }
    task.materialize(project)
    val session = "${task.id}-$attempt"
    val store = JsonlEventStore(root.resolve("sessions"))
    val artifacts = LocalArtifactStore(root.resolve("artifacts"))
    val processes = LocalProcessService(project)
    val builtin = BuiltinTools(LocalWorkspace(project, store, artifacts, session), processes, artifacts, object : InteractionBroker {
        override suspend fun ask(question: UserQuestion) = "No answer is available in an unattended evaluation. Make a reasonable assumption and state it."
    })
    val traceFile = TraceFile(outDir.resolve("traces/$session.jsonl"))
    val started = System.nanoTime()
    var failure: String? = null
    return kotlinx.coroutines.coroutineScope {
        val supervisor = SubagentSupervisor(provider, builtin, store, session, model, this)
        val engine = AgentCoordinator(provider, SubagentTools(builtin, supervisor), store, processes, session, model, thinking, maxRounds = maxRounds, maxOutputTokens = maxOutput,
            projectInstructions = ProjectInstructions.load(project)?.text, traces = RunTraceSink { traceFile.append(it) }, subagents = supervisor, scope = this)
        try {
            withTimeout(5 * 60_000L) { engine.send(task.prompt, task.mode, task.allowProcesses).join() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) { failure = "timeout after 5 min"; engine.stop() }
        catch (error: Exception) { failure = error.javaClass.simpleName }
        val state = engine.state.value
        val answer = state.messages.lastOrNull { it.role == "assistant" && !it.content.isNullOrBlank() }?.content.orEmpty()
        val verdict = runCatching { task.check(project, answer) }.getOrElse { Verdict(false, "check crashed: ${it.message}") }
        val errors = state.activities.filter { it.status == "failed" }.mapNotNull { activity ->
            runCatching { Json.parseToJsonElement(activity.output).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content }.getOrNull()?.let { "${activity.name}:$it" }
        }.groupingBy { it }.eachCount()
        processes.close()
        Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        val helpers = supervisor.state.value
        EvalResult(
            task.id, attempt, verdict.passed && state.status == "completed", verdict.detail, state.status, failure ?: state.error?.take(300),
            state.modelRounds, state.toolCalls, state.failedToolCalls, errors, state.activities.map { it.name }.distinct(),
            (System.nanoTime() - started) / 1_000_000, state.usage.promptTokens, state.usage.completionTokens, state.usage.cachedPromptTokens, answer.take(1500),
            subagents = helpers.size, subagentsCompleted = helpers.count { it.status == dev.chiller.contracts.SubagentStatus.COMPLETED },
        )
    }
}

internal fun renderMarkdown(report: EvalReport): String = buildString {
    val results = report.results
    val passed = results.count { it.passed }
    appendLine("# Chiller eval · ${report.model}")
    appendLine()
    appendLine("Endpoint `${report.endpoint}` · started ${report.startedAt} · maxRounds ${report.maxRounds} · maxOutput ${report.maxOutputTokens}")
    appendLine()
    appendLine("**Passed ${passed}/${results.size}** · rounds ${results.sumOf { it.rounds }} · tool calls ${results.sumOf { it.toolCalls }} (failed ${results.sumOf { it.failedToolCalls }}) · " +
        "time ${"%.1f".format(results.sumOf { it.durationMs } / 1000.0)} s · prompt ${results.sumOf { it.promptTokens ?: 0 }} · completion ${results.sumOf { it.completionTokens ?: 0 }} · cached ${results.sumOf { it.cachedPromptTokens ?: 0 }} tokens")
    appendLine()
    appendLine("| Task | # | Result | Rounds | Tools (failed) | Helpers | Time, s | Prompt / completion tok. | Verdict |")
    appendLine("|---|---|---|---|---|---|---|---|---|")
    results.forEach { r ->
        appendLine("| ${r.task} | ${r.attempt} | ${if (r.passed) "PASS" else "FAIL"} | ${r.rounds} | ${r.toolCalls} (${r.failedToolCalls}) | ${if (r.subagents > 0) "${r.subagentsCompleted}/${r.subagents}" else "—"} | ${"%.1f".format(r.durationMs / 1000.0)} | ${r.promptTokens ?: "—"} / ${r.completionTokens ?: "—"} | ${r.verdict.replace("|", "\\|").take(120)} |")
    }
    val errors = results.flatMap { it.toolErrors.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
    if (errors.isNotEmpty()) {
        appendLine()
        appendLine("Tool errors: " + errors.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ×${it.value}" })
    }
    results.filter { !it.passed && it.error != null }.forEach { appendLine(); appendLine("- ${it.task} #${it.attempt}: status ${it.status}, ${it.error}") }
}
