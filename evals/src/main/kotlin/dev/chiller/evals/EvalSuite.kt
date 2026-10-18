package dev.chiller.evals

import dev.chiller.agent.ModelProfiles
import dev.chiller.providers.ChatCompletionsProfile
import dev.chiller.providers.ChatCompletionsProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** One evaluation run: a model with a profile over a set of tasks. Shared by the CLI runner and the app. */
data class SuiteSpec(
    val baseUrl: String,
    val model: String,
    /** Resolved at request time; never stored in reports. */
    val key: () -> String?,
    val profileId: String = "auto",
    val taskIds: Set<String>? = null,
    val repeats: Int = 1,
    val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT,
    val thinking: Boolean = false,
    val requestExtras: JsonObject = JsonObject(emptyMap()),
    val outDir: Path,
    /** Overrides the profile's project map, for A/B runs; null keeps the profile's choice. */
    val projectMap: Boolean? = null,
) {
    companion object {
        const val DEFAULT_MAX_ROUNDS = 14
        const val DEFAULT_MAX_OUTPUT = 2048
    }
}

/**
 * Runs the tasks sequentially and writes report.json/report.md plus traces into [SuiteSpec.outDir].
 * [onResult] sees every finished attempt, e.g. to update a live table. Cancellation stops between steps.
 */
suspend fun runEvalSuite(spec: SuiteSpec, onResult: suspend (EvalResult) -> Unit = {}): EvalReport {
    val tasks = EVAL_TASKS.filter { spec.taskIds == null || it.id in spec.taskIds }
    require(tasks.isNotEmpty()) { "No tasks selected; known: ${EVAL_TASKS.joinToString { it.id }}" }
    val modelProfile = ModelProfiles.resolve(spec.profileId, spec.model).let { p -> spec.projectMap?.let { p.copy(projectMap = it) } ?: p }
    val host = runCatching { URI(spec.baseUrl).host }.getOrNull().orEmpty()
    val loopback = host in setOf("localhost", "127.0.0.1", "::1")
    val extras = buildJsonObject {
        modelProfile.temperature?.let { put("temperature", it) }
        modelProfile.parallelToolCalls?.let { put("parallel_tool_calls", it) }
        spec.requestExtras.forEach { (k, v) -> put(k, v) }
    }
    val profile = (if (host == "api.deepseek.com") ChatCompletionsProfile.deepSeekFlash(spec.baseUrl, spec.model)
        else ChatCompletionsProfile("generic", spec.baseUrl, spec.model, requiresApiKey = !loopback)).withExtras(extras)
    Files.createDirectories(spec.outDir.resolve("traces"))
    val http = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) { connectTimeoutMillis = 20_000; socketTimeoutMillis = 180_000; requestTimeoutMillis = 300_000 }
    }
    val provider = ChatCompletionsProvider(http, profile, spec.key)
    val results = mutableListOf<EvalResult>()
    val startedAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    try {
        for (task in tasks) for (attempt in 1..spec.repeats.coerceIn(1, 5)) {
            currentCoroutineContext().ensureActive()
            val result = runTask(task, attempt, provider, spec.model, spec.thinking, spec.maxRounds, spec.maxOutputTokens, spec.outDir, modelProfile)
            results += result
            onResult(result)
        }
    } finally { http.close() }
    val report = EvalReport(spec.model, spec.baseUrl, startedAt, spec.maxRounds, spec.maxOutputTokens, spec.repeats, results, summarize(results), profile = modelProfile.id)
    val json = Json { prettyPrint = true; encodeDefaults = true }
    Files.writeString(spec.outDir.resolve("report.json"), json.encodeToString(EvalReport.serializer(), report))
    Files.writeString(spec.outDir.resolve("report.md"), renderMarkdown(report))
    return report
}

fun summarize(results: List<EvalResult>): List<EvalSummary> = results.groupBy { it.task }.map { (task, attempts) ->
    EvalSummary(
        task = task, attempts = attempts.size, passed = attempts.count { it.passed },
        passRate = attempts.count { it.passed }.toDouble() / attempts.size,
        durationMs = attempts.sumOf { it.durationMs },
        promptTokens = attempts.takeIf { rows -> rows.all { it.promptTokens != null } }?.sumOf { it.promptTokens!! },
        completionTokens = attempts.takeIf { rows -> rows.all { it.completionTokens != null } }?.sumOf { it.completionTokens!! },
        cachedPromptTokens = attempts.takeIf { rows -> rows.all { it.cachedPromptTokens != null } }?.sumOf { it.cachedPromptTokens!! },
        repeatedReads = attempts.sumOf { it.loss.repeatedReads },
        failedEdits = attempts.sumOf { it.loss.failedEdits },
        toolOutputChars = attempts.sumOf { it.loss.toolOutputChars },
    )
}

/** Task ids and titles for pickers. */
val EVAL_TASK_INDEX: List<Pair<String, String>> get() = EVAL_TASKS.map { it.id to it.title }
