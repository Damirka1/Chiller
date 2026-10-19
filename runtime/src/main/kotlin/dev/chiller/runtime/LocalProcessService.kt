package dev.chiller.runtime

import dev.chiller.contracts.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.ArrayDeque
import java.util.LinkedHashSet

class LocalProcessService(root: Path) : ProcessService {
    private val root = root.toRealPath()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val records = ConcurrentHashMap<String, Record>()
    private val lifecycleLock = Any()
    private val finishedOrder = ArrayDeque<String>()
    private val expiredIds = LinkedHashSet<String>()
    private val closeCompleted = CompletableDeferred<Unit>()
    private var closed = false
    private val maxRetainedFinished = 64
    private val maxExpiredIds = 256
    private class Record(val id: String, val runId: String, val process: Process, val background: Boolean) {
        val text = StringBuilder()
        var truncated = false
        var state = "running"
        var exitCode: Int? = null
        val exited = CompletableDeferred<Unit>()
    }

    override suspend fun start(request: StartProcessRequest, runId: String): ProcessOutput = withContext(Dispatchers.IO) {
        if (request.argv.isEmpty() || request.argv.any { it.contains('\u0000') } || request.timeoutMs !in 100..600_000)
            throw ToolException("INVALID_ARGUMENTS", "Provide argv and a timeout from 100 to 600000 ms")
        val relative = Path.of(request.cwd)
        if (relative.isAbsolute) throw ToolException("PATH_OUTSIDE_WORKSPACE", "cwd must be relative to workspace")
        val cwd = try { root.resolve(relative).normalize().toRealPath() }
            catch (_: java.io.IOException) { throw ToolException("INVALID_CWD", "Working directory is unavailable") }
        if (!cwd.startsWith(root) || !Files.isDirectory(cwd)) throw ToolException("PATH_OUTSIDE_WORKSPACE", "Invalid cwd")
        val record = synchronized(lifecycleLock) {
            if (closed) throw ToolException("PROCESS_SERVICE_CLOSED", "Process service is closed; create a new run service")
            pruneFinishedLocked()
            if (records.values.count { it.process.isAlive } >= 8)
                throw ToolException("PROCESS_LIMIT", "At most eight running processes are supported")
            val process = try { ProcessBuilder(request.argv).directory(cwd.toFile()).start() }
            catch (e: java.io.IOException) { throw startFailure(request.argv.first(), e) }
            process.outputStream.close()
            Record(newId(), runId, process, request.background).also { started ->
                records[started.id] = started
                val stdout = pump(started, process.inputStream, "stdout")
                val stderr = pump(started, process.errorStream, "stderr")
                scope.launch {
                    try {
                        val exit = runInterruptible { process.waitFor() }
                        synchronized(started) { started.exitCode = exit; if (started.state == "running") started.state = "exited" }
                        val drained = withTimeoutOrNull(1_000) { joinAll(stdout, stderr); true } ?: false
                        if (!drained) {
                            synchronized(started) { started.truncated = true }
                            process.inputStream.close(); process.errorStream.close()
                            stdout.cancel(); stderr.cancel()
                        }
                    } finally {
                        started.exited.complete(Unit)
                        retainFinished(started)
                    }
                }
                scope.launch {
                    // A completed process must not leave a timeout coroutine alive for the full timeout.
                    val completed = async { started.exited.await() }
                    val timedOut = withTimeoutOrNull(request.timeoutMs) { completed.await(); false } ?: true
                    if (timedOut && process.isAlive) {
                        synchronized(started) { if (started.state == "running") started.state = "timed_out" }
                        terminate(started)
                    }
                }
            }
        }
        try {
            if (!request.background) record.exited.await()
            page(record, 0)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { terminate(record) }
            throw cancelled
        }
    }

    private fun pump(record: Record, input: java.io.InputStream, channel: String): Job = scope.launch {
        try {
            input.reader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(2048)
                while (isActive) {
                    val count = runInterruptible { reader.read(buffer) }
                    if (count < 0) break
                    var limit = false
                    synchronized(record) {
                        val available = 1_048_576 - record.text.length
                        if (available > 0) {
                            val chunk = "[$channel] " + String(buffer, 0, count)
                            record.text.append(chunk.take(available))
                        }
                        if (record.text.length >= 1_048_576) { record.truncated = true; record.state = "output_limit"; limit = true }
                    }
                    if (limit) { terminate(record); break }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: java.io.IOException) { /* Pipe may close during termination. */ }
    }

    override suspend fun read(processId: String, cursor: Long): ProcessOutput {
        if (cursor < 0) throw ToolException("INVALID_ARGUMENTS", "Cursor must be non-negative")
        return page(find(processId), cursor)
    }

    override suspend fun stop(processId: String): ProcessOutput {
        val record = find(processId)
        synchronized(record) { if (record.process.isAlive) record.state = "stopped" }
        terminate(record)
        withTimeoutOrNull(2_000) { record.exited.await() }
        return page(record, 0)
    }

    override suspend fun stopRun(runId: String) {
        val ownedActive = synchronized(lifecycleLock) { records.values.filter { it.runId == runId && it.process.isAlive }.toList() }
        ownedActive.forEach { stop(it.id) }
    }

    override suspend fun close() {
        val (active, owner) = synchronized(lifecycleLock) {
            if (closed) emptyList<Record>() to false
            else {
                closed = true
                records.values.filterNot { it.exited.isCompleted }.toList() to true
            }
        }
        if (!owner) return withContext(NonCancellable) { closeCompleted.await() }
        withContext(NonCancellable) {
            try {
                active.filter { it.process.isAlive }.forEach { record ->
                    synchronized(record) { if (record.state == "running") record.state = "stopped" }
                    terminate(record)
                }
                active.forEach { withTimeoutOrNull(2_000) { it.exited.await() } }
                scope.cancel()
            } finally {
                closeCompleted.complete(Unit)
            }
        }
    }

    private fun find(id: String): Record = synchronized(lifecycleLock) {
        records[id] ?: if (id in expiredIds) {
            throw ToolException("PROCESS_EXPIRED", "Output for this completed process has expired", recovery = "Output is no longer retained. Consult the journal or saved artifacts; do not repeat a side-effecting command to recover output")
        } else {
            throw ToolException("NOT_FOUND", "Unknown process id")
        }
    }

    private fun retainFinished(record: Record) = synchronized(lifecycleLock) {
        if (records[record.id] !== record || record.process.isAlive) return@synchronized
        finishedOrder.remove(record.id)
        finishedOrder.addLast(record.id)
        pruneFinishedLocked()
    }

    private fun pruneFinishedLocked() {
        while (finishedOrder.size > maxRetainedFinished) {
            val id = finishedOrder.removeFirst()
            val record = records[id] ?: continue
            if (record.process.isAlive || !record.exited.isCompleted) continue
            records.remove(id, record)
            expiredIds.remove(id)
            expiredIds.add(id)
            while (expiredIds.size > maxExpiredIds) expiredIds.remove(expiredIds.first())
        }
    }
    private fun page(record: Record, cursor: Long): ProcessOutput = synchronized(record) {
        val start = cursor.coerceAtMost(record.text.length.toLong()).toInt()
        val end = (start + 16_384).coerceAtMost(record.text.length)
        ProcessOutput(record.id, record.text.substring(start, end), end.toLong(), record.state, record.exitCode, record.truncated || end < record.text.length)
    }

    private suspend fun terminate(record: Record) = withContext(Dispatchers.IO) {
        // Best-effort descendants, not an OS sandbox; process groups/job objects are a beta gate.
        val descendants = record.process.descendants().use { it.toList() }
        descendants.asReversed().forEach { it.destroy() }
        record.process.destroy()
        withTimeoutOrNull(500) { while (record.process.isAlive) delay(20) }
        descendants.asReversed().filter { it.isAlive }.forEach { it.destroyForcibly() }
        if (record.process.isAlive) record.process.destroyForcibly()
    }
}

/**
 * Why a program did not start, in words a model can act on. A missing program was reported as a generic tool
 * failure, and the model kept trying other launchers (xdg-open, firefox, sh -c) on Windows.
 */
internal fun startFailure(program: String, error: java.io.IOException, osName: String = System.getProperty("os.name").orEmpty()): ToolException {
    val message = error.message.orEmpty()
    val windows = osName.startsWith("Windows", ignoreCase = true)
    val missing = "error=2," in message || "error=2 " in message || message.endsWith("error=2") || "No such file or directory" in message
    return when {
        missing -> ToolException("EXECUTABLE_NOT_FOUND", "Program '$program' was not found on PATH",
            if (windows) "Shell built-ins (start, dir, echo) need [\"cmd\", \"/c\", ...]; to open a file with its default app use [\"cmd\", \"/c\", \"start\", \"\", \"<file>\"]. Check the program name or use its full path."
            else "Check the program name or use its full path; shell syntax needs [\"sh\", \"-c\", \"...\"]; to open a file use xdg-open (Linux) or open (macOS).")
        "error=5" in message || "Permission denied" in message -> ToolException("PERMISSION_DENIED", "Program '$program' could not be started: access denied")
        else -> ToolException("PROCESS_START_FAILED", "Program '$program' could not be started")
    }
}
