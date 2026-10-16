package dev.chiller.runtime

import dev.chiller.contracts.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class LocalProcessService(root: Path) : ProcessService {
    private val root = root.toRealPath()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val records = ConcurrentHashMap<String, Record>()
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
        if (records.values.count { synchronized(it) { it.state == "running" } } >= 8)
            throw ToolException("PROCESS_LIMIT", "At most eight running processes are supported")
        val relative = Path.of(request.cwd)
        if (relative.isAbsolute) throw ToolException("PATH_OUTSIDE_WORKSPACE", "cwd must be relative to workspace")
        val cwd = try { root.resolve(relative).normalize().toRealPath() }
            catch (_: java.io.IOException) { throw ToolException("INVALID_CWD", "Working directory is unavailable") }
        if (!cwd.startsWith(root) || !Files.isDirectory(cwd)) throw ToolException("PATH_OUTSIDE_WORKSPACE", "Invalid cwd")
        val process = ProcessBuilder(request.argv).directory(cwd.toFile()).start()
        process.outputStream.close()
        val record = Record(newId(), runId, process, request.background)
        records[record.id] = record
        val stdout = pump(record, process.inputStream, "stdout")
        val stderr = pump(record, process.errorStream, "stderr")
        scope.launch {
            try {
                val exit = runInterruptible { process.waitFor() }
                synchronized(record) { record.exitCode = exit; if (record.state == "running") record.state = "exited" }
                val drained = withTimeoutOrNull(1_000) { joinAll(stdout, stderr); true } ?: false
                if (!drained) {
                    synchronized(record) { record.truncated = true }
                    process.inputStream.close(); process.errorStream.close()
                    stdout.cancel(); stderr.cancel()
                }
            } finally { record.exited.complete(Unit) }
        }
        scope.launch {
            delay(request.timeoutMs)
            if (process.isAlive) { synchronized(record) { record.state = "timed_out" }; terminate(record) }
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
        records.values.filter { it.runId == runId && !it.background }.forEach { stop(it.id) }
    }

    override suspend fun close() {
        withContext(NonCancellable) { records.values.forEach { terminate(it) } }
        scope.cancel()
    }

    private fun find(id: String) = records[id] ?: throw ToolException("NOT_FOUND", "Unknown process id")
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
