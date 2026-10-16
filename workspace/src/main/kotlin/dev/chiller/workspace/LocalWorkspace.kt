package dev.chiller.workspace

import dev.chiller.contracts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.security.MessageDigest
import java.util.UUID
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Local filesystem implementation. Path policy is not an OS sandbox against other processes.
 * Atomic replacement preserves POSIX mode bits where available; ACLs and extended attributes
 * are not copied. Parent-directory forcing is best effort and recorded when journaling is enabled.
 */
class LocalWorkspace(
    rootPath: Path,
    private val events: EventStore? = null,
    private val artifacts: ArtifactStore? = null,
    private val sessionId: String = "workspace",
) : WorkspaceService {
    private val base = rootPath.toRealPath()
    override val root: String = base.toString()
    private val lock = Mutex()
    private val snapshots = ConcurrentHashMap<String, Snapshot>()
    private val changes = mutableMapOf<String, ChangeRecord>()
    private val maxReadBytes = 2 * 1024 * 1024
    private val maxVisibleLines = 200
    private val maxVisibleChars = 6_000
    private var loadedJournal = false

    private data class Snapshot(val path: Path, val hash: String, val observed: Set<Int>)
    private data class ChangeRecord(val path: Path, val before: ByteArray?, val afterHash: String?, val afterBytes: ByteArray?, val id: String, val restorePath: Path? = null)
    private data class TextFile(val bom: Boolean, val body: String, val lines: List<String>, val endings: List<String>, val finalNewline: Boolean)
    private data class Captured(val stdout: ByteArray, val stderr: ByteArray, val exitCode: Int, val truncated: Boolean)

    override suspend fun read(request: ReadFileRequest): FileReadResult = withContext(Dispatchers.IO) {
        val file = resolve(request.path, mustExist = true)
        val bytes = readBytes(file)
        val text = decode(bytes)
        val total = text.lines.size
        val start = request.startLine
        if (start < 1) fail("INVALID_RANGE", "Line numbers start at 1")
        if (total == 0) {
            if (start != 1 || request.endLine != null) fail("INVALID_RANGE", "Empty files can only be read from line 1")
            val id = snapshotId()
            lock.withLock { saveSnapshot(id, Snapshot(file, sha(bytes), emptySet())) }
            return@withContext FileReadResult(relative(file), id, 0, "", eol = "LF")
        }
        val requestedEnd = request.endLine ?: total
        if (start > total || requestedEnd < start || requestedEnd > total) fail("INVALID_RANGE", "Requested line range is outside the file")
        var visibleEnd = start - 1
        var visibleChars = 0
        for (lineNumber in start..requestedEnd) {
            val line = text.lines[lineNumber - 1]
            if (line.length > maxVisibleChars) fail("FILE_LINE_TOO_LARGE", "Line $lineNumber exceeds the 6000 character read limit")
            val addition = line.length + if (visibleEnd >= start) 1 else 0
            if (visibleChars + addition > maxVisibleChars || lineNumber - start >= maxVisibleLines) break
            visibleChars += addition
            visibleEnd = lineNumber
        }
        val content = if (visibleEnd < start) "" else text.lines.subList(start - 1, visibleEnd).joinToString("\n")
        val id = snapshotId()
        lock.withLock { saveSnapshot(id, Snapshot(file, sha(bytes), (if (visibleEnd < start) emptyList() else start..visibleEnd).toSet())) }
        FileReadResult(relative(file), id, total, content, truncated = visibleEnd < requestedEnd, eol = text.endings.firstOrNull() ?: "LF")
    }

    override suspend fun list(path: String, glob: String?, limit: Int): ToolResult = withContext(Dispatchers.IO) {
        val dir = resolve(path, true)
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return@withContext ToolResult.failure("NOT_DIRECTORY", "Path is not a directory")
        val max = limit.coerceIn(1, 1000)
        val args = mutableListOf("rg", "--files", "--null", "--color", "never", "--hidden", "--no-require-git", "--glob", "!.git/**", "--glob", "!**/.git/**")
        val ignore = base.resolve(".chillerignore")
        if (Files.isRegularFile(ignore, LinkOption.NOFOLLOW_LINKS)) { args += "--ignore-file"; args += ignore.toString() }
        if (glob != null) { args += "--glob"; args += glob }
        args += listOf("--", relative(dir).ifEmpty { "." })
        val captured = try { runRg(args, 4 * 1024 * 1024) }
        catch (e: IOException) { return@withContext ToolResult.failure("RG_UNAVAILABLE", "ripgrep could not be started: ${e.message}") }
        catch (e: ToolException) { return@withContext ToolResult.failure(e.code, e.message) }
        if (captured.exitCode !in 0..1 && !captured.truncated) return@withContext ToolResult.failure("LIST_FAILED", captured.stderr.toString(StandardCharsets.UTF_8).take(1000))
        val pathParts = captured.stdout.toString(StandardCharsets.UTF_8).split('\u0000').filter(String::isNotEmpty).toMutableList()
        if (captured.truncated && !captured.stdout.lastOrNull().let { it == 0.toByte() }) pathParts.removeLastOrNull()
        val paths = pathParts.map { raw -> Paths.get(raw).let { if (it.isAbsolute) it else base.resolve(it) } }.sorted()
        val all = paths.map { p -> buildJsonObject { put("path", relative(p)); put("type", "file"); runCatching { put("size", Files.size(p)) } } }
        val truncated = captured.truncated || all.size > max
        ToolResult(true, buildJsonObject { put("entries", JsonArray(all.take(max))); put("truncated", truncated) }, truncated = truncated)
    }

    override suspend fun search(query: String, path: String, glob: String?, regex: Boolean, limit: Int): ToolResult = withContext(Dispatchers.IO) {
        if (query.isEmpty()) return@withContext ToolResult.failure("INVALID_QUERY", "Search query must not be empty")
        val dir = resolve(path, true)
        val args = mutableListOf("rg", "--json", "--line-number", "--color", "never", "--no-require-git", "--sort", "path", "--max-filesize", "2M", "--max-columns", "2000", "--max-columns-preview")
        val ignore = base.resolve(".chillerignore")
        if (Files.isRegularFile(ignore, LinkOption.NOFOLLOW_LINKS)) { args += "--ignore-file"; args += ignore.toString() }
        if (!regex) args += "--fixed-strings"
        if (glob != null) { args += "--glob"; args += glob }
        args += listOf("--", query, relative(dir).ifEmpty { "." })
        val captured = try { runRg(args, 4 * 1024 * 1024) }
        catch (e: IOException) { return@withContext ToolResult.failure("RG_UNAVAILABLE", "ripgrep could not be started: ${e.message}") }
        catch (e: ToolException) { return@withContext ToolResult.failure(e.code, e.message) }
        if (captured.exitCode !in 0..1 && !captured.truncated) return@withContext ToolResult.failure("SEARCH_FAILED", captured.stderr.toString(StandardCharsets.UTF_8).take(1000))
        val maxMatches = limit.coerceIn(1, 1000)
        val records = captured.stdout.toString(StandardCharsets.UTF_8).lineSequence().filter { it.startsWith("{\"type\":\"match\"") }.take(maxMatches + 1).mapNotNull { runCatching { Json.parseToJsonElement(it) }.getOrNull() }.toList()
        // Preserve rg's structured records; callers can decode match records without losing byte offsets.
        val truncated = records.size > maxMatches
        ToolResult(true, buildJsonObject { put("matches", JsonArray(records.take(maxMatches))); put("truncated", truncated || captured.truncated) }, truncated = truncated || captured.truncated)
    }

    private suspend fun runRg(args: List<String>, maxOutput: Int): Captured = coroutineScope {
        val process = ProcessBuilder(args).directory(base.toFile()).start()
        val stdoutJob = async(Dispatchers.IO) { process.inputStream.use { it.readNBytes(maxOutput + 1) } }
        val stderrJob = async(Dispatchers.IO) { process.errorStream.use { it.readNBytes(8193) } }
        try {
            val stdout = withTimeoutOrNull(10_000) { stdoutJob.await() }
                ?: run { process.destroyForcibly(); throw ToolException("RG_TIMEOUT", "ripgrep exceeded the 10 second limit") }
            val truncated = stdout.size > maxOutput
            if (truncated) process.destroyForcibly()
            val finished = withContext(Dispatchers.IO) { process.waitFor(2, TimeUnit.SECONDS) }
            if (!finished) { process.destroyForcibly(); throw ToolException("RG_TIMEOUT", "ripgrep exceeded the 10 second limit") }
            val stderr = withTimeoutOrNull(500) { stderrJob.await() } ?: byteArrayOf()
            Captured(stdout.copyOf(minOf(stdout.size, maxOutput)), stderr, process.exitValue(), truncated)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            stdoutJob.cancel(); stderrJob.cancel()
        }
    }

    override suspend fun edit(request: EditFileRequest): FileChange = withContext(Dispatchers.IO) { lock.withLock {
        val file = resolve(request.path, true)
        val snap = snapshots[request.snapshotId] ?: fail("UNKNOWN_SNAPSHOT", "Snapshot expired or unknown; read the file again")
        if (snap.path != file) fail("SNAPSHOT_PATH_MISMATCH", "Snapshot belongs to another path")
        val before = readBytes(file)
        if (sha(before) != snap.hash) fail("STALE_SNAPSHOT", "File changed after it was read; no edit was applied")
        val original = decode(before)
        val next = applyEdits(original, request.edits, snap.observed)
        val nextBytes = encode(next)
        commit(file, before, nextBytes)
    } }

    override suspend fun create(path: String, content: String): FileChange = withContext(Dispatchers.IO) { lock.withLock {
        val file = resolve(path, false)
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) fail("ALREADY_EXISTS", "Destination already exists")
        val parent = file.parent ?: fail("INVALID_PATH", "A parent directory is required")
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) fail("NOT_DIRECTORY", "Parent directory does not exist")
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        val id = UUID.randomUUID().toString()
        durablePrepare(id, file, null, bytes, operation = "create")
        atomicWriteNew(file, bytes)
        durableCommit(id, file, null, bytes, operation = "create")
        makeChange(file, null, bytes, id)
    } }

    override suspend fun move(path: String, snapshotId: String, destination: String): FileChange = withContext(Dispatchers.IO) { lock.withLock {
        val from = resolve(path, true); val to = resolve(destination, false)
        val snap = requireSnapshot(snapshotId, from); val before = readBytes(from)
        val sourceIdentity = fileIdentity(from)
        if (sha(before) != snap.hash) fail("STALE_SNAPSHOT", "File changed after it was read")
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) fail("ALREADY_EXISTS", "Destination already exists")
        if (to.parent != from.parent) fail("UNSUPPORTED_MOVE", "Move is supported only within one directory")
        if (!Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(from)) fail("UNSUPPORTED_FILE", "Only regular files can be moved")
        val id = UUID.randomUUID().toString(); durablePrepare(id, from, before, before, operation = "move", destination = to)
        if (sha(readBytes(from)) != snap.hash || !sameIdentity(sourceIdentity, fileIdentity(from))) fail("STALE_SNAPSHOT", "File changed while preparing move")
        try {
            Files.createLink(to, from)
            if (!Files.isSameFile(from, to) || sha(readBytes(to)) != snap.hash) {
                if (Files.exists(to, LinkOption.NOFOLLOW_LINKS) && sha(readBytes(to)) == snap.hash) Files.delete(to)
                fail("STALE_SNAPSHOT", "Source changed while preparing move")
            }
            Files.delete(from)
        } catch (e: FileAlreadyExistsException) { fail("ALREADY_EXISTS", "Destination already exists") }
        catch (e: UnsupportedOperationException) { fail("ATOMIC_MOVE_UNSUPPORTED", "Filesystem does not support safe no-clobber moves") }
        catch (e: IOException) { fail("ATOMIC_MOVE_UNSUPPORTED", "Safe no-clobber move failed: ${e.message}") }
        durableCommit(id, from, before, before, operation = "move", destination = to)
        val change = makeChange(to, before, before, id); changes[id] = ChangeRecord(to, before, sha(before), before, id, from); change.copy(path = "${relative(from)} -> ${relative(to)}")
    } }

    override suspend fun delete(path: String, snapshotId: String): FileChange = withContext(Dispatchers.IO) { lock.withLock {
        val file = resolve(path, true); val snap = requireSnapshot(snapshotId, file); val before = readBytes(file)
        val sourceIdentity = fileIdentity(file)
        if (sha(before) != snap.hash) fail("STALE_SNAPSHOT", "File changed after it was read")
        val id = UUID.randomUUID().toString(); durablePrepare(id, file, before, null, operation = "delete")
        if (sha(readBytes(file)) != snap.hash || !sameIdentity(sourceIdentity, fileIdentity(file))) fail("STALE_SNAPSHOT", "File changed while preparing delete")
        Files.delete(file); durableCommit(id, file, before, null, operation = "delete")
        makeChange(file, before, null, id)
    } }

    override suspend fun undo(changeId: String): FileChange = withContext(Dispatchers.IO) { lock.withLock {
        ensureChangesLoaded()
        val change = changes[changeId] ?: fail("UNKNOWN_CHANGE", "Change cannot be undone in this workspace")
        val file = change.path
        change.restorePath?.let { original ->
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) fail("UNDO_CONFLICT", "Moved file is absent")
            val movedBytes = readBytes(file)
            if (sha(movedBytes) != change.afterHash) fail("UNDO_CONFLICT", "Moved file changed since this change")
            val movedIdentity = fileIdentity(file)
            if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) fail("UNDO_CONFLICT", "Original path is now occupied")
            val id = UUID.randomUUID().toString(); durablePrepare(id, file, movedBytes, movedBytes, operation = "move", destination = original, undoOf = changeId)
            if (sha(readBytes(file)) != change.afterHash || !sameIdentity(movedIdentity, fileIdentity(file))) fail("UNDO_CONFLICT", "Moved file changed while undoing")
            try { Files.createLink(original, file); if (!Files.isSameFile(file, original) || sha(readBytes(file)) != change.afterHash) fail("UNDO_CONFLICT", "Moved file changed while undoing"); Files.delete(file) }
            catch (e: FileAlreadyExistsException) { fail("UNDO_CONFLICT", "Original path is now occupied") }
            catch (e: UnsupportedOperationException) { fail("ATOMIC_MOVE_UNSUPPORTED", "Filesystem does not support safe no-clobber moves") }
            catch (e: IOException) { fail("ATOMIC_MOVE_UNSUPPORTED", "Safe no-clobber move failed: ${e.message}") }
            durableCommit(id, file, movedBytes, movedBytes, operation = "move", destination = original, undoOf = changeId)
            changes.remove(changeId)
            val result = makeChange(original, movedBytes, movedBytes, id)
            changes[id] = ChangeRecord(original, movedBytes, sha(movedBytes), movedBytes, id, file)
            return@withLock result
        }
        if (change.before == null) {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) fail("UNDO_CONFLICT", "Created file is already absent")
            val current = readBytes(file); if (sha(current) != change.afterHash) fail("UNDO_CONFLICT", "File changed since this change")
            val currentIdentity = fileIdentity(file)
            val id = UUID.randomUUID().toString(); durablePrepare(id, file, current, null, operation = "delete", undoOf = changeId)
            if (sha(readBytes(file)) != change.afterHash || !sameIdentity(currentIdentity, fileIdentity(file))) fail("UNDO_CONFLICT", "File changed while undoing")
            Files.delete(file); durableCommit(id, file, current, null, operation = "delete", undoOf = changeId)
            changes.remove(changeId); makeChange(file, current, null, id)
        } else {
            val exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS)
            if (change.afterHash == null && exists) fail("UNDO_CONFLICT", "Deleted path is now occupied")
            if (change.afterHash != null && (!exists || sha(readBytes(file)) != change.afterHash)) fail("UNDO_CONFLICT", "File changed since this change")
            val current = if (exists) readBytes(file) else null
            val currentIdentity = if (exists) fileIdentity(file) else null
            val id = UUID.randomUUID().toString(); durablePrepare(id, file, current, change.before, operation = "undo", undoOf = changeId)
            val stillExists = Files.exists(file, LinkOption.NOFOLLOW_LINKS)
            if (stillExists != exists || (exists && (sha(readBytes(file)) != change.afterHash || !sameIdentity(currentIdentity, fileIdentity(file))))) fail("UNDO_CONFLICT", "File changed while undoing")
            atomicReplace(file, change.before); durableCommit(id, file, current, change.before, operation = "undo", undoOf = changeId)
            changes.remove(changeId); makeChange(file, current, change.before, id)
        }
    } }

    /** Reconciles journaled prepares by observation only; it never replays a filesystem mutation. */
    suspend fun recoverPending(): List<String> = withContext(Dispatchers.IO) { lock.withLock {
        val store = events ?: return@withLock emptyList()
        val journal = store.read(sessionId)
        loadChanges(journal)
        loadedJournal = true
        val prepared = journal.filter { it.type == "workspace.prepare" }.mapNotNull { event -> event.payload.string("changeId")?.let { it to event } }.toMap()
        val committed = journal.filter { it.type == "workspace.commit" }.mapNotNull { it.payload.string("changeId") }.toSet()
        val reconciled = journal.filter { it.type == "workspace.reconciled" }.mapNotNull { it.payload.string("changeId") }.toSet()
        val outcomes = mutableListOf<String>()
        for ((id, event) in prepared) {
            if (id.isBlank() || id in committed || id in reconciled) continue
            val p = event.payload
            val rel = p.string("path") ?: continue
            val destination = p.string("destination")
            val operation = p.string("operation") ?: "write"
            val sourcePath = try { resolve(rel, false) } catch (e: ToolException) { outcomes += "$id:CONFLICT"; continue }
            val destPath = destination?.let { try { resolve(it, false) } catch (e: ToolException) { null } }
            val beforeHash = p.string("beforeHash")
            val afterHash = p.string("afterHash")
            val sourceHash = fingerprint(sourcePath)
            val destHash = destPath?.let(::fingerprint)
            val isAfter = when (operation) {
                "move" -> sourceHash == null && destPath != null && destHash == afterHash
                "delete" -> sourceHash == null && afterHash == null
                else -> sourceHash == afterHash
            }
            val isBefore = when (operation) {
                "move" -> sourceHash == beforeHash && destPath != null && destHash == null
                else -> sourceHash == beforeHash
            }
            when {
                isAfter -> {
                    store.append(sessionId, "workspace.commit", buildJsonObject {
                        put("changeId", id); put("path", rel); put("destination", destination); put("operation", operation)
                        put("beforeHash", beforeHash); put("afterHash", afterHash); put("undoOf", p.string("undoOf"))
                    })
                    p.string("undoOf")?.let(changes::remove)
                    restoreChangeFromPrepare(p, sourcePath, destPath)
                    outcomes += "$id:COMMITTED"
                }
                isBefore -> {
                    store.append(sessionId, "workspace.reconciled", buildJsonObject { put("changeId", id); put("state", "not_applied") })
                    outcomes += "$id:NOT_APPLIED"
                }
                else -> outcomes += "$id:CONFLICT"
            }
        }
        outcomes
    } }

    private suspend fun ensureChangesLoaded() {
        if (loadedJournal) return
        val store = events ?: run { loadedJournal = true; return }
        loadChanges(store.read(sessionId))
        loadedJournal = true
    }

    private suspend fun loadChanges(journal: List<StoredEvent>) {
        if (loadedJournal) return
        val prepares = journal.filter { it.type == "workspace.prepare" }.associateBy { it.payload.string("changeId") ?: "" }
        val commits = journal.filter { it.type == "workspace.commit" }
        val superseded = commits.mapNotNull { it.payload.string("undoOf") }.toSet()
        for (commit in commits) {
            val id = commit.payload.string("changeId") ?: continue
            if (id in superseded) continue
            if (id in changes) continue
            val prep = prepares[id]?.payload ?: continue
            val rel = prep.string("path") ?: continue
            val source = runCatching { resolve(rel, false) }.getOrNull() ?: continue
            val dest = prep.string("destination")?.let { runCatching { resolve(it, false) }.getOrNull() }
            restoreChangeFromPrepare(prep, source, dest)
        }
    }

    private suspend fun restoreChangeFromPrepare(payload: JsonObject, path: Path, destination: Path?) {
        val op = payload.string("operation") ?: "write"
        val beforeHash = payload.string("beforeHash")
        val afterHash = payload.string("afterHash")
        val ref = payload.string("beforeArtifact")
        val artifactStore = artifacts
        val before = if (ref != null && artifactStore != null) runCatching {
            val encodedBuilder = StringBuilder()
            var offset = 0
            while (offset <= maxReadBytes * 2 + 32) {
                val chunk = artifactStore.read(sessionId, ref, offset, 16_384)
                if (chunk.isEmpty()) break
                encodedBuilder.append(chunk)
                offset += chunk.length
                if (chunk.length < 16_384) break
            }
            val encoded = encodedBuilder.toString()
            java.util.Base64.getDecoder().decode(encoded).also { if (beforeHash != null && sha(it) != beforeHash) error("before image hash mismatch") }
        }.getOrNull() else null
        val id = payload.string("changeId") ?: return
        when (op) {
            "create" -> changes[id] = ChangeRecord(path, null, afterHash, null, id)
            "delete" -> if (before != null) changes[id] = ChangeRecord(path, before, null, null, id)
            "move" -> if (before != null && destination != null) changes[id] = ChangeRecord(destination, before, afterHash, before, id, path)
            "write", "undo" -> if (before != null) changes[id] = ChangeRecord(path, before, afterHash, null, id)
        }
    }

    private fun fingerprint(path: Path): String? = if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) null else runCatching { sha(readBytes(path)) }.getOrElse { "<unreadable>" }
    private fun saveSnapshot(id: String, snapshot: Snapshot) {
        if (snapshots.size >= 512) snapshots.keys.firstOrNull()?.let(snapshots::remove)
        snapshots[id] = snapshot
    }

    private fun applyEdits(f: TextFile, edits: List<FileEdit>, observed: Set<Int>): TextFile {
        if (edits.isEmpty()) fail("EMPTY_EDIT", "At least one edit is required")
        data class Op(val start: Int, val end: Int, val lines: List<String>)
        val ops = edits.map { e ->
            when (e.op) {
                "replace", "delete" -> {
                    val s = e.startLine ?: fail("INVALID_EDIT", "startLine is required"); val end = e.endLine ?: s
                    if (s < 1 || end < s || end > f.lines.size) fail("INVALID_RANGE", "Edit range is outside the file")
                    if ((s..end).any { it !in observed }) fail("UNOBSERVED_RANGE", "Read the lines before editing them")
                    Op(s - 1, end, if (e.op == "delete") emptyList() else contentLines(e.content))
                }
                "insert" -> {
                    val before = e.beforeLine ?: fail("INVALID_EDIT", "beforeLine is required")
                    if (before < 1 || before > f.lines.size + 1) fail("INVALID_RANGE", "Insert position is outside the file")
                    val boundary = if (before <= f.lines.size) before else f.lines.size
                    if (boundary > 0 && boundary !in observed && boundary - 1 !in observed) fail("UNOBSERVED_RANGE", "Read a boundary line before inserting")
                    Op(before - 1, before - 1, contentLines(e.content))
                }
                else -> fail("INVALID_EDIT", "Unsupported operation '${e.op}'")
            }
        }.sortedBy { it.start }
        for (i in 1 until ops.size) if (ops[i].start < ops[i - 1].end || (ops[i].start == ops[i - 1].start && ops[i].start == ops[i - 1].end && ops[i - 1].start == ops[i - 1].end)) fail("OVERLAPPING_EDITS", "Edits overlap or have ambiguous insertion positions")
        val lines = f.lines.toMutableList()
        val endings = f.endings.toMutableList()
        val eol = f.endings.firstOrNull() ?: "LF"
        for (op in ops.asReversed()) {
            val removed = op.end - op.start
            repeat(removed) { lines.removeAt(op.start); if (op.start < endings.size) endings.removeAt(op.start) }
            lines.addAll(op.start, op.lines)
            repeat(op.lines.size) { index -> endings.add((op.start + index).coerceAtMost(endings.size), eol) }
        }
        // A line ending belongs to the line before it. Retain the original EOF convention.
        val wantsEnding = f.finalNewline
        while (endings.size > lines.size) endings.removeAt(endings.lastIndex)
        while (endings.size < lines.size) endings += eol
        if (!wantsEnding && endings.isNotEmpty()) endings.removeAt(endings.lastIndex)
        if (wantsEnding && lines.isNotEmpty() && endings.size < lines.size) endings += eol
        return f.copy(body = render(lines, endings), lines = lines, endings = endings)
    }

    private suspend fun commit(file: Path, before: ByteArray, after: ByteArray): FileChange {
        val expectedIdentity = fileIdentity(file)
        val id = UUID.randomUUID().toString(); durablePrepare(id, file, before, after)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) fail("UNSUPPORTED_FILE", "Only regular files can be edited")
        if (sha(readBytes(file)) != sha(before) || !sameIdentity(expectedIdentity, fileIdentity(file))) fail("STALE_SNAPSHOT", "File changed while preparing edit")
        atomicReplace(file, after); durableCommit(id, file, before, after)
        return makeChange(file, before, after, id)
    }

    private suspend fun makeChange(path: Path, before: ByteArray?, after: ByteArray?, id: String): FileChange {
        val beforeHash = before?.let(::sha); val afterHash = after?.let(::sha)
        val diff = unifiedDiff(path, before, after)
        changes[id] = ChangeRecord(path, before, afterHash, after, id)
        return FileChange(id, relative(path), beforeHash, afterHash, diff)
    }

    private fun unifiedDiff(path: Path, before: ByteArray?, after: ByteArray?): String {
        val old = before?.let(::decode)
        val new = after?.let(::decode)
        val oldLines = old?.lines.orEmpty()
        val newLines = new?.lines.orEmpty()
        val oldFinal = old?.finalNewline ?: false
        val newFinal = new?.finalNewline ?: false
        var prefix = 0
        while (prefix < minOf(oldLines.size, newLines.size) && oldLines[prefix] == newLines[prefix] && !(oldFinal != newFinal && prefix == oldLines.lastIndex && prefix == newLines.lastIndex)) prefix++
        var suffix = 0
        while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix && oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix] && !(oldFinal != newFinal && suffix == 0 && oldLines.lastIndex == newLines.lastIndex)) suffix++
        val contextStart = maxOf(0, prefix - 3)
        val oldChangeEnd = oldLines.size - suffix
        val newChangeEnd = newLines.size - suffix
        val oldEnd = minOf(oldLines.size, oldChangeEnd + 3)
        val newEnd = minOf(newLines.size, newChangeEnd + 3)
        val oldCount = oldEnd - contextStart
        val newCount = newEnd - contextStart
        val oldStart = if (oldCount == 0) contextStart else contextStart + 1
        val newStart = if (newCount == 0) contextStart else contextStart + 1
        val out = StringBuilder()
        out.append("--- ").append(if (before == null) "/dev/null" else "a/${relative(path)}").append('\n')
        out.append("+++ ").append(if (after == null) "/dev/null" else "b/${relative(path)}").append('\n')
        if (oldLines == newLines && oldFinal == newFinal) return out.toString()
        out.append("@@ -").append(oldStart).append(',').append(oldCount)
            .append(" +").append(newStart).append(',').append(newCount).append(" @@\n")
        fun record(prefix: Char, text: String, noNewline: Boolean = false) {
            out.append(prefix).append(text).append('\n')
            if (noNewline) out.append("\\ No newline at end of file\n")
        }
        for (index in contextStart until prefix) record(' ', oldLines[index])
        for (index in prefix until oldChangeEnd) record('-', oldLines[index], index == oldLines.lastIndex && !oldFinal)
        for (index in prefix until newChangeEnd) record('+', newLines[index], index == newLines.lastIndex && !newFinal)
        val sharedSuffixCount = minOf(oldEnd - oldChangeEnd, newEnd - newChangeEnd)
        for (offset in 0 until sharedSuffixCount) {
            val oldIndex = oldChangeEnd + offset
            record(' ', oldLines[oldIndex], oldIndex == oldLines.lastIndex && !oldFinal || oldIndex == newLines.lastIndex && !newFinal)
        }
        return out.toString()
    }

    private suspend fun durablePrepare(id: String, path: Path, before: ByteArray?, after: ByteArray?, operation: String = "write", destination: Path? = null, undoOf: String? = null) {
        val beforeRef = before?.let { artifacts?.put(sessionId, java.util.Base64.getEncoder().encodeToString(it))?.id }
        events?.append(sessionId, "workspace.prepare", buildJsonObject {
            put("changeId", id); put("path", relative(path)); put("destination", destination?.let(::relative)); put("operation", operation); put("undoOf", undoOf)
            put("beforeHash", before?.let(::sha)); put("afterHash", after?.let(::sha)); put("beforeArtifact", beforeRef)
        })
    }
    private suspend fun durableCommit(id: String, path: Path, before: ByteArray?, after: ByteArray?, operation: String = "write", destination: Path? = null, undoOf: String? = null) {
        val parentForced = path.parent?.let(::forceDirectoryBestEffort) ?: false
        events?.append(sessionId, "workspace.commit", buildJsonObject {
            put("changeId", id); put("path", relative(path)); put("destination", destination?.let(::relative)); put("operation", operation); put("undoOf", undoOf)
            put("beforeHash", before?.let(::sha)); put("afterHash", after?.let(::sha)); put("parentDirectoryForced", parentForced)
        })
    }

    private fun atomicReplace(path: Path, bytes: ByteArray) {
        val permissions = posixPermissions(path)
        val temp = Files.createTempFile(path.parent, ".chiller-", ".tmp")
        try {
            setPosixPermissions(temp, permissions)
            FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (e: AtomicMoveNotSupportedException) { fail("ATOMIC_REPLACE_UNSUPPORTED", "Filesystem does not support atomic replace") }
            forceDirectoryBestEffort(path.parent)
        } finally { Files.deleteIfExists(temp) }
    }
    private fun atomicWriteNew(path: Path, bytes: ByteArray) {
        val temp = Files.createTempFile(path.parent, ".chiller-", ".tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try { Files.createLink(path, temp) }
            catch (e: FileAlreadyExistsException) { fail("ALREADY_EXISTS", "Destination already exists") }
            catch (e: UnsupportedOperationException) { fail("ATOMIC_CREATE_UNSUPPORTED", "Filesystem does not support atomic no-clobber creation") }
            catch (e: IOException) { fail("ATOMIC_CREATE_UNSUPPORTED", "Atomic no-clobber creation failed: ${e.message}") }
            forceDirectoryBestEffort(path.parent)
        }
        finally { Files.deleteIfExists(temp) }
    }

    private fun posixPermissions(path: Path): Set<java.nio.file.attribute.PosixFilePermission>? {
        val view = try { Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) }
        catch (_: UnsupportedOperationException) { return null } ?: return null
        return view.readAttributes().permissions()
    }
    private fun setPosixPermissions(path: Path, permissions: Set<java.nio.file.attribute.PosixFilePermission>?) {
        if (permissions == null) return
        val view = try { Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) }
        catch (_: UnsupportedOperationException) { return } ?: return
        view.setPermissions(permissions)
    }
    private fun forceDirectoryBestEffort(directory: Path): Boolean = try {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        true
    } catch (_: IOException) { false } catch (_: UnsupportedOperationException) { false }

    private fun resolve(raw: String, mustExist: Boolean): Path {
        if (raw.isBlank() || raw.contains('\\')) fail("INVALID_PATH", "Use a non-empty relative path with '/' separators")
        val rel = Paths.get(raw)
        if (rel.isAbsolute || rel.any { it.toString() == ".." }) fail("PATH_OUTSIDE_ROOT", "Path must stay inside workspace root")
        var p = base.resolve(rel).normalize()
        if (!p.startsWith(base)) fail("PATH_OUTSIDE_ROOT", "Path must stay inside workspace root")
        var cursor = base
        for (part in base.relativize(p)) {
            cursor = cursor.resolve(part)
            if (Files.isSymbolicLink(cursor)) fail("SYMLINK_NOT_ALLOWED", "Symbolic links are not allowed")
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                val real = cursor.toRealPath(LinkOption.NOFOLLOW_LINKS)
                if (!real.startsWith(base)) fail("PATH_OUTSIDE_ROOT", "Path resolves outside workspace root")
            }
        }
        if (mustExist && !Files.exists(p, LinkOption.NOFOLLOW_LINKS)) fail("NOT_FOUND", "Path does not exist")
        return p
    }
    private fun relative(path: Path) = base.relativize(path).toString().replace('\\', '/')
    private fun fileIdentity(path: Path): Any? = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey() }.getOrNull()
    private fun sameIdentity(expected: Any?, actual: Any?): Boolean = expected == null || actual == null || expected == actual
    private fun readBytes(path: Path): ByteArray {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) fail("UNSUPPORTED_FILE", "Only regular files can be read")
        val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(maxReadBytes + 1) }
        if (bytes.size > maxReadBytes) fail("FILE_TOO_LARGE", "File exceeds the 2 MiB workspace read limit")
        return bytes
    }
    private fun decode(bytes: ByteArray): TextFile {
        val bom = bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()
        val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val body = try { decoder.decode(ByteBuffer.wrap(bytes, if (bom) 3 else 0, bytes.size - if (bom) 3 else 0)).toString() } catch (_: Exception) { fail("BINARY_OR_INVALID_UTF8", "File is not valid UTF-8 text") }
        if ('\u0000' in body) fail("BINARY_OR_INVALID_UTF8", "Binary files are not supported")
        val lines = mutableListOf<String>(); val endings = mutableListOf<String>(); var start = 0; var i = 0
        while (i < body.length) { if (body[i] == '\r' || body[i] == '\n') { lines += body.substring(start, i); val e = if (body[i] == '\r' && i + 1 < body.length && body[i + 1] == '\n') "CRLF" else if (body[i] == '\r') "CR" else "LF"; endings += e; if (e == "CRLF") i++; start = i + 1 }; i++ }
        val final = start == body.length && endings.isNotEmpty()
        if (!final && start < body.length) lines += body.substring(start)
        if (body.isEmpty()) lines.clear()
        return TextFile(bom, body, lines, endings, final)
    }
    private fun render(lines: List<String>, endings: List<String>): String = buildString {
        lines.forEachIndexed { index, line ->
            append(line)
            endings.getOrNull(index)?.let { append(when (it) { "CRLF" -> "\r\n"; "CR" -> "\r"; else -> "\n" }) }
        }
    }
    private fun encode(file: TextFile): ByteArray {
        val bytes = file.body.toByteArray(StandardCharsets.UTF_8)
        return if (file.bom) byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + bytes else bytes
    }
    private fun contentLines(s: String): List<String> { if (s.isEmpty()) return listOf(""); if ('\r' in s) fail("INVALID_EDIT", "Edit content must use LF line separators"); return s.split('\n') }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun snapshotId() = UUID.randomUUID().toString().replace("-", "").take(12)
    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun requireSnapshot(id: String, path: Path): Snapshot = (snapshots[id] ?: fail("UNKNOWN_SNAPSHOT", "Snapshot expired or unknown")).also { if (it.path != path) fail("SNAPSHOT_PATH_MISMATCH", "Snapshot belongs to another path") }
    private fun fail(code: String, message: String): Nothing = throw ToolException(code, message)
}
