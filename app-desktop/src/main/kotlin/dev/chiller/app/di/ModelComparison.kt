package dev.chiller.app.di

import dev.chiller.app.i18n.*
import dev.chiller.agent.ModelProfiles
import dev.chiller.evals.EvalReport
import dev.chiller.evals.EvalResult
import dev.chiller.evals.SuiteSpec
import dev.chiller.evals.runEvalSuite
import dev.chiller.evals.summarize
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
    val label: String get() = "$model · ${I18n.current.settings.profileName(ModelProfiles.resolve(profile, model).id)}"
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
        val records = readHistory()
        _state.update { it.copy(history = records) }
    }

    private fun readHistory(): List<ComparisonRecord> = runCatching {
        if (!Files.isDirectory(folder)) emptyList() else Files.list(folder).use { files ->
            files.filter { it.fileName.toString().endsWith(".json") }.toList()
        }.mapNotNull { runCatching { json.decodeFromString(ComparisonRecord.serializer(), Files.readString(it)) }.getOrNull() }
    }.getOrDefault(emptyList()).sortedByDescending { it.startedAt }.take(20)

    fun show(record: ComparisonRecord?) { _state.update { it.copy(shown = record) } }

    fun start(endpoint: String, key: () -> String?, sides: List<ComparisonSide>, tasks: Set<String>, repeats: Int, requestExtras: JsonObject) {
        if (job?.isActive == true || sides.isEmpty() || tasks.isEmpty()) return
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        // Two starts within one second must not share a record.
        val id = "$stamp-${java.util.UUID.randomUUID().toString().take(6)}"
        _state.update { it.copy(running = true, sides = sides, tasks = tasks.toList(), results = emptyMap(), current = null, error = null, shown = null) }
        // ATOMIC: a stop pressed before the coroutine starts still runs its finally, so `running` never sticks.
        job = scope.launch(start = CoroutineStart.ATOMIC) {
            val reports = mutableListOf<EvalReport>()
            var complete = false
            // Every finished attempt is written at once: a stop, an error or a crash keeps what was paid for.
            fun save() {
                val partial = (reports.size until sides.size).mapNotNull { index ->
                    _state.value.results[index]?.takeIf { it.isNotEmpty() }?.let { rows ->
                        EvalReport(sides[index].model, endpoint, stamp, SuiteSpec.DEFAULT_MAX_ROUNDS, SuiteSpec.DEFAULT_MAX_OUTPUT, repeats, rows, summarize(rows),
                            profile = ModelProfiles.resolve(sides[index].profile, sides[index].model).id)
                    }
                }
                val record = ComparisonRecord(id, stamp, endpoint, sides, tasks.toList(), repeats, reports + partial, complete)
                try { writeReplacing(folder.resolve("$id.json"), json.encodeToString(ComparisonRecord.serializer(), record)) }
                catch (error: Exception) { _state.update { it.copy(error = I18n.current.di.resultsNotSaved("${error.javaClass.simpleName}: ${error.message?.take(200)}")) } }
            }
            try {
                sides.forEachIndexed { index, side ->
                    _state.update { it.copy(current = side.label) }
                    val outDir = folder.resolve(id).resolve("side-${index + 1}")
                    reports += withContext(Dispatchers.IO) {
                        runEvalSuite(SuiteSpec(endpoint, side.model, key, side.profile, tasks, repeats, requestExtras = requestExtras, outDir = outDir)) { result ->
                            _state.update { s -> s.copy(current = "${side.label} · ${result.task}", results = s.results + (index to (s.results[index].orEmpty() + result))) }
                            save()
                        }
                    }
                }
                complete = true
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(error = I18n.current.di.stopped) }
            } catch (error: Exception) {
                _state.update { it.copy(error = (error.message ?: error.javaClass.simpleName).take(300)) }
            } finally {
                withContext(NonCancellable) {
                    // Partial results are kept too, including the stopped side: they cost money and still say something.
                    withContext(Dispatchers.IO) { save() }
                    // One update: whoever sees the run finished also sees its record in the history.
                    val records = withContext(Dispatchers.IO) { readHistory() }
                    _state.update { it.copy(running = false, current = null, history = records) }
                }
            }
        }
    }

    fun cancel() { job?.cancel() }

    /**
     * Atomic replace that tolerates Windows: a file written a moment ago may still be open in an antivirus or
     * indexer scan, so the rename is retried briefly, then falls back to a plain write rather than losing data.
     */
    private fun writeReplacing(target: Path, text: String) {
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".comparison-", ".tmp")
        try {
            Files.writeString(temporary, text)
            var last: java.io.IOException? = null
            repeat(5) { attempt ->
                try {
                    try { Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                    catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                    return
                } catch (error: java.io.IOException) { last = error; Thread.sleep(20L * (attempt + 1)) }
            }
            try { Files.writeString(target, text) } catch (error: java.io.IOException) { last?.let(error::addSuppressed); throw error }
        } finally { Files.deleteIfExists(temporary) }
    }

    /** Back to the setup form; does nothing while a comparison runs. */
    fun reset() { if (job?.isActive != true) _state.update { it.copy(results = emptyMap(), sides = emptyList(), tasks = emptyList(), error = null, shown = null) } }
}
