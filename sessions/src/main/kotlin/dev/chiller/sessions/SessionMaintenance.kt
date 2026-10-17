package dev.chiller.sessions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest

data class SessionDeletePreview(
    val sessionId: String,
    val eventCount: Int,
    val artifactCount: Int,
    val traceCount: Int,
    val fileCount: Int,
    val totalBytes: Long,
)

/** A snapshot-backed confirmation token. Paths are deliberately not accepted from callers. */
class SessionDeletePlan internal constructor(
    val preview: SessionDeletePreview,
    internal val roots: List<RootSnapshot>,
)

internal data class RootSnapshot(val canonical: Path, val source: Path, val fingerprint: List<String>, val bytes: Long, val files: Int)

data class SessionDeleteResult(val sessionId: String, val deletedBytes: Long, val complete: Boolean, val pendingPaths: List<Path>)

/**
 * Explicit maintenance for one caller-supplied session id at a time. This class never finds or
 * cleans data outside its configured roots. Callers must supply active/preserved ids both
 * when previewing and deleting so stale confirmation plans cannot bypass those protections.
 */
class SessionMaintenance(
    sessionsDirectory: Path,
    artifactsDirectory: Path,
    tracesDirectory: Path,
    extraSessionDirectories: List<Path> = emptyList(),
) {
    private val sessions = sessionsDirectory.toAbsolutePath().normalize()
    private val artifacts = artifactsDirectory.toAbsolutePath().normalize()
    private val traces = tracesDirectory.toAbsolutePath().normalize()
    private val extras = extraSessionDirectories.map { it.toAbsolutePath().normalize() }.distinct()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        val configuredRoots = listOf(sessions, artifacts, traces) + extras
        require(configuredRoots.indices.none { i -> configuredRoots.indices.any { j ->
            i != j && (configuredRoots[i].startsWith(configuredRoots[j]) || configuredRoots[j].startsWith(configuredRoots[i]))
        } }) { "Session maintenance roots must not overlap" }
    }

    fun previewDelete(
        sessionIds: Set<String>,
        activeSessionIds: Set<String> = emptySet(),
        preservedSessionIds: Set<String> = emptySet(),
    ): List<SessionDeletePlan> {
        require(sessionIds.isNotEmpty()) { "Select at least one session" }
        rejectProtected(sessionIds, activeSessionIds, preservedSessionIds)
        return sessionIds.sorted().map { id -> createPlan(id) }
    }

    fun delete(
        plan: SessionDeletePlan,
        activeSessionIds: Set<String> = emptySet(),
        preservedSessionIds: Set<String> = emptySet(),
    ): SessionDeleteResult {
        val id = plan.preview.sessionId
        rejectProtected(setOf(id), activeSessionIds, preservedSessionIds)
        val current = createPlan(id)
        require(current.roots == plan.roots) { "Session data changed after preview; create a new preview" }

        val tombstones = mutableListOf<Path>()
        val newlyDetached = mutableListOf<Pair<Path, Path>>()
        val detachFailures = mutableListOf<Path>()
        // Detach every selected component before deleting anything. A failed detach rolls back
        // this attempt so the selected session cannot be left half-live.
        for (root in current.roots) {
            if (root.source == root.canonical) {
                val tombstone = tombstonePath(root.canonical, id)
                try {
                    move(root.canonical, tombstone)
                    newlyDetached.add(root.canonical to tombstone)
                    tombstones.add(tombstone)
                } catch (_: Exception) {
                    detachFailures.add(root.canonical)
                }
            } else tombstones.add(root.source)
        }
        if (detachFailures.isNotEmpty()) {
            val rollbackFailures = mutableListOf<Path>()
            newlyDetached.asReversed().forEach { (canonical, tombstone) ->
                try { move(tombstone, canonical) } catch (_: Exception) { rollbackFailures.add(tombstone) }
            }
            val alreadyDetached = current.roots.filter { it.source != it.canonical }.map { it.source }
            return SessionDeleteResult(id, 0, false, detachFailures + rollbackFailures + alreadyDetached)
        }

        val pending = mutableListOf<Path>()
        var deletedBytes = 0L
        for (path in tombstones) {
            try {
                deletedBytes += deleteTree(path)
            } catch (_: Exception) {
                pending.add(path)
            }
        }
        return SessionDeleteResult(id, deletedBytes, pending.isEmpty(), pending)
    }

    private fun createPlan(id: String): SessionDeletePlan {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,100}"))) { "Invalid session id" }
        val canonical = listOf(sessions.resolve(id), artifacts.resolve(id), traces.resolve("$id.jsonl")) + extras.map { it.resolve(id) }
        val roots = canonical.mapNotNull { path ->
            checkRoot(path.parent)
            val tombstone = tombstonePath(path, id)
            require(!(exists(path) && exists(tombstone))) { "Both session data and its deletion tombstone exist; refusing cleanup" }
            val source = when {
                exists(path) -> path
                exists(tombstone) -> tombstone
                else -> return@mapNotNull null
            }
            when {
                path == canonical[2] -> require(Files.isRegularFile(source, NOFOLLOW_LINKS)) { "Trace path is not a regular file" }
                path == canonical[1] || path in canonical.drop(3) -> require(Files.isDirectory(source, NOFOLLOW_LINKS)) { "Session data path is not a directory" }
            }
            val snapshot = scan(source)
            if (path == canonical.first()) validateJournal(source.resolve("events.jsonl"), id)
            RootSnapshot(path, source, snapshot.first, snapshot.second, snapshot.third)
        }
        require(roots.isNotEmpty()) { "No session data found for '$id' in the configured roots" }
        val sessionRoot = roots.firstOrNull { it.canonical == canonical.first() }
        if (sessionRoot != null) require(Files.isDirectory(sessionRoot.source, NOFOLLOW_LINKS)) { "Session journal path is not a directory" }
        // A session must have a journal directory on the first pass. Orphaned attachment/trace
        // data is accepted only because the caller explicitly supplied its session id.
        val journalExists = roots.any { it.canonical == canonical.first() }
        if (!journalExists) require(roots.any { it.canonical == canonical[1] || it.canonical == canonical[2] || it.canonical in canonical.drop(3) }) { "Session journal directory is missing" }
        val events = roots.firstOrNull { it.canonical == canonical.first() }?.source?.resolve("events.jsonl")
            ?.let { countJournalEvents(it) } ?: 0
        val artifactRoot = roots.firstOrNull { it.canonical == canonical[1] }
        val artifactsCount = artifactRoot?.files ?: 0
        val traceRoot = roots.firstOrNull { it.canonical == canonical[2] }
        val preview = SessionDeletePreview(id, events, artifactsCount, if (traceRoot == null) 0 else 1,
            roots.sumOf { it.files }, roots.sumOf { it.bytes })
        return SessionDeletePlan(preview, roots)
    }

    private fun validateJournal(journal: Path, expectedId: String) {
        if (!exists(journal)) return
        require(Files.isRegularFile(journal, NOFOLLOW_LINKS)) { "Session journal is not a regular file" }
        require(Files.size(journal) <= MAX_JOURNAL_BYTES) { "Session journal exceeds 64 MiB; refusing deletion preview" }
        val text = Files.readString(journal)
        require(text.isEmpty() || text.endsWith("\n")) { "Session journal has an incomplete final record; refusing deletion" }
        text.lineSequence().filter(String::isNotBlank).forEachIndexed { index, line ->
            val event = try { json.parseToJsonElement(line).jsonObject } catch (_: Exception) {
                throw IllegalStateException("Session journal is corrupt at record ${index + 1}; refusing deletion")
            }
            val schema = event["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1
            require(schema == 1) { "Unsupported session schema version $schema; refusing deletion" }
            val storedId = event["sessionId"]?.jsonPrimitive?.contentOrNull
            require(storedId == expectedId) { "Session journal id does not match its directory" }
        }
    }

    private fun countJournalEvents(journal: Path): Int {
        if (!exists(journal)) return 0
        require(Files.size(journal) <= MAX_JOURNAL_BYTES) { "Session journal exceeds 64 MiB; refusing deletion preview" }
        var count = 0
        Files.newBufferedReader(journal).useLines { lines -> lines.forEach { if (it.isNotBlank()) count++ } }
        return count
    }

    private fun rejectProtected(ids: Set<String>, active: Set<String>, preserved: Set<String>) {
        require(ids.none { it in active }) { "An active session cannot be deleted" }
        require(ids.none { it in preserved }) { "A preserved session cannot be deleted" }
    }

    private fun checkRoot(root: Path) {
        var current: Path? = root.toAbsolutePath().normalize()
        while (current != null) {
            require(!Files.isSymbolicLink(current)) { "Configured data path contains a symbolic link: $current" }
            if (exists(current)) require(Files.isDirectory(current, NOFOLLOW_LINKS)) { "Configured data path is not a directory: $current" }
            current = current.parent
        }
    }

    /** Returns sorted relative path/content fingerprints, total bytes, and file count. */
    private fun scan(path: Path): Triple<List<String>, Long, Int> {
        require(!Files.isSymbolicLink(path)) { "Symbolic links are not allowed in session data" }
        val entries = mutableListOf<String>()
        var bytes = 0L
        var files = 0
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
            entries += ".\u0000directory\u0000${Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toMillis()}"
            Files.walk(path).use { stream ->
                stream.forEach { item ->
                    require(!Files.isSymbolicLink(item)) { "Symbolic links are not allowed in session data" }
                    if (item != path) {
                        if (Files.isDirectory(item, NOFOLLOW_LINKS)) {
                            entries += "${path.relativize(item)}\u0000directory\u0000${Files.getLastModifiedTime(item, NOFOLLOW_LINKS).toMillis()}"
                            return@forEach
                        }
                        require(Files.isRegularFile(item, NOFOLLOW_LINKS)) { "Unsupported file in session data" }
                        require(files < MAX_SCAN_FILES) { "Session data contains more than $MAX_SCAN_FILES files; refusing deletion preview" }
                        val size = Files.size(item)
                        val modified = Files.getLastModifiedTime(item, NOFOLLOW_LINKS).toMillis()
                        val contentHash = digest(item)
                        require(Files.size(item) == size && Files.getLastModifiedTime(item, NOFOLLOW_LINKS).toMillis() == modified) {
                            "Session data changed while previewing; try again"
                        }
                        bytes += size
                        files++
                        entries += "${path.relativize(item)}\u0000$size\u0000$modified\u0000$contentHash"
                    }
                }
            }
        } else {
            require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Session data is not a regular file or directory" }
            val size = Files.size(path)
            val modified = Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toMillis()
            val contentHash = digest(path)
            require(Files.size(path) == size && Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toMillis() == modified) {
                "Session data changed while previewing; try again"
            }
            bytes = size
            files = 1
            entries += "${path.fileName}\u0000$size\u0000$modified\u0000$contentHash"
        }
        entries.sort()
        return Triple(entries, bytes, files)
    }

    private fun tombstonePath(path: Path, id: String): Path = path.resolveSibling(".chiller-deleting-$id-${path.fileName}")

    private fun exists(path: Path): Boolean = Files.exists(path, NOFOLLOW_LINKS)

    private fun digest(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun move(source: Path, target: Path) {
        require(!Files.isSymbolicLink(source)) { "Symbolic links are not allowed in session data" }
        require(!exists(target)) { "Deletion tombstone already exists" }
        try { Files.move(source, target, ATOMIC_MOVE) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(source, target) }
    }

    private fun deleteTree(path: Path): Long {
        if (!exists(path)) return 0
        var bytes = 0L
        require(!Files.isSymbolicLink(path)) { "Refusing to follow a symbolic link" }
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
            Files.walk(path).use { stream ->
                val all = stream.sorted(Comparator.reverseOrder()).toList()
                for (item in all) {
                    require(!Files.isSymbolicLink(item)) { "Refusing to follow a symbolic link" }
                    if (Files.isRegularFile(item, NOFOLLOW_LINKS)) bytes += Files.size(item)
                    Files.delete(item)
                }
            }
        } else {
            require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Refusing to remove unsupported session data" }
            bytes = Files.size(path)
            Files.delete(path)
        }
        return bytes
    }

    private companion object {
        const val MAX_JOURNAL_BYTES = 64L * 1024 * 1024
        const val MAX_SCAN_FILES = 50_000
    }
}
