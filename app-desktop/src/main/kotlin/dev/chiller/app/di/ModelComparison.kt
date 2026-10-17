package dev.chiller.app.di

import dev.chiller.agent.ModelProfiles
import dev.chiller.evals.EvalReport
import dev.chiller.evals.EvalResult
import dev.chiller.evals.SuiteSpec
import dev.chiller.evals.runEvalSuite
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** One column of a comparison: a model with a harness profile. */
@Serializable
data class ComparisonSide(val model: String, val profile: String = "auto") {
    val label: String get() = "$model · ${ModelProfiles.resolve(profile, model).name.lowercase()}"
}

/** A finished (or stopped) comparison as stored on disk. Keys are never part of it. */
@Serializable
data class ComparisonRecord(
    val id: String, val startedAt: String, val endpoint: String, val sides: List<ComparisonSide>, val tasks: List<String>,
    val repeats: Int, val reports: List<EvalReport>, val complete: Boolean,
)

data class ComparisonState(
    val running: Boolean = false,
    val sides: List<ComparisonSide> = emptyList(),
    val tasks: List<String> = emptyList(),
    /** Results per side index, in arrival order. */
    val results: Map<Int, List<EvalResult>> = emptyMap(),
    /** What is running now, e.g. "deepseek-flash · fix-bug". */
    val current: String? = null,
    val error: String? = null,
    val history: List<ComparisonRecord> = emptyList(),
    val shown: ComparisonRecord? = null,
)

/**
 * Runs the eval suite for several model/profile sides on the current endpoint, one side after another,
 * and keeps the last 20 comparisons under the data directory. Every task sends real, paid requests;
 * the UI asks before starting and shows an upper bound.
 */
class ModelComparison(private val directory: Path, private val scope: CoroutineScope) {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(ComparisonState())
    val state: StateFlow<ComparisonState> = _state.asStateFlow()
    private var job: Job? = null
    private val folder get() = directory.resolve("comparisons")

    fun loadHistory() {
        val records = runCatching {
            if (!Files.isDirectory(folder)) emptyList() else Files.list(folder).use { files ->
                files.filter { it.fileName.toString().endsWith(".json") }.toList()
            }.mapNotNull { runCatching { json.decodeFromString(ComparisonRecord.serializer(), Files.readString(it)) }.getOrNull() }
        }.getOrDefault(emptyList()).sortedByDescending { it.startedAt }.take(20)
        _state.update { it.copy(history = records) }
    }

    fun show(record: ComparisonRecord?) { _state.update { it.copy(shown = record) } }

    fun start(endpoint: String, key: () -> String?, sides: List<ComparisonSide>, tasks: Set<String>, repeats: Int, requestExtras: JsonObject) {
        if (job?.isActive == true || sides.isEmpty() || tasks.isEmpty()) return
        val id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        _state.update { it.copy(running = true, sides = sides, tasks = tasks.toList(), results = emptyMap(), current = null, error = null, shown = null) }
        job = scope.launch {
            val reports = mutableListOf<EvalReport>()
            var complete = false
            try {
                sides.forEachIndexed { index, side ->
                    _state.update { it.copy(current = side.label) }
                    val outDir = folder.resolve(id).resolve("side-${index + 1}")
                    reports += withContext(Dispatchers.IO) {
                        runEvalSuite(SuiteSpec(endpoint, side.model, key, side.profile, tasks, repeats, requestExtras = requestExtras, outDir = outDir)) { result ->
                            _state.update { s -> s.copy(current = "${side.label} · ${result.task}", results = s.results + (index to (s.results[index].orEmpty() + result))) }
                        }
                    }
                }
                complete = true
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(error = "Остановлено") }
            } catch (error: Exception) {
                _state.update { it.copy(error = (error.message ?: error.javaClass.simpleName).take(300)) }
            } finally {
                withContext(NonCancellable) {
                    // Partial results are kept too: they cost money and still say something.
                    val record = ComparisonRecord(id, id, endpoint, sides, tasks.toList(), repeats, reports, complete)
                    runCatching {
                        Files.createDirectories(folder)
                        Files.writeString(folder.resolve("$id.json"), json.encodeToString(ComparisonRecord.serializer(), record))
                    }
                    _state.update { it.copy(running = false, current = null) }
                    loadHistory()
                }
            }
        }
    }

    fun cancel() { job?.cancel() }

    /** Back to the setup form; does nothing while a comparison runs. */
    fun reset() { if (job?.isActive != true) _state.update { it.copy(results = emptyMap(), sides = emptyList(), tasks = emptyList(), error = null, shown = null) } }
}
