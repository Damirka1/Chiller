package dev.chiller.sessions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE

/**
 * Append-only JSONL trace of model rounds. Bounded: one record is capped at [maxRecordBytes]
 * (an oversized record is replaced by a stub naming its round) and the file stops growing at
 * [maxFileBytes]. Traces contain prompts and file contents, never credentials.
 */
class TraceFile(val path: Path, private val maxRecordBytes: Int = 2 * 1024 * 1024, private val maxFileBytes: Long = 64L * 1024 * 1024) {
    private val lock = Mutex()

    suspend fun append(entry: JsonObject) = lock.withLock {
        withContext(Dispatchers.IO) {
            Files.createDirectories(path.parent)
            val size = if (Files.exists(path)) Files.size(path) else 0L
            if (size >= maxFileBytes) return@withContext
            var line = entry.toString()
            if (line.toByteArray().size > maxRecordBytes) {
                line = JsonObject(entry.filterKeys { it in setOf("runId", "round", "timestamp", "model", "finishReason", "usage", "durationMs", "error") } +
                    ("omitted" to kotlinx.serialization.json.JsonPrimitive("record exceeded $maxRecordBytes bytes"))).toString()
            }
            Files.writeString(path, line + "\n", CREATE, APPEND)
        }
    }
}
