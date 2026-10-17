package dev.chiller.sessions

import dev.chiller.contracts.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest

private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private fun safeId(id: String): String {
    require(id.matches(Regex("[a-zA-Z0-9_-]{1,100}"))) { "Invalid storage identifier" }
    return id
}

class JsonlEventStore(private val directory: Path) : EventStore {
    private val mutex = Mutex()
    private data class Tail(val size: Long, val seq: Long)
    private data class IndexEntry(val size: Long, val modified: Long, val info: SessionInfo)
    // Sized-validated per-session tail so append does not re-parse the whole journal (O(n) per append).
    private val tails = HashMap<String, Tail>()
    // Listing cache; every local append invalidates its own entry.
    private val index = HashMap<String, IndexEntry>()
    init { Files.createDirectories(directory) }

    override suspend fun append(sessionId: String, type: String, payload: JsonObject): StoredEvent = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = file(sessionId)
            Files.createDirectories(file.parent)
            val size = if (Files.exists(file)) Files.size(file) else 0L
            val cached = tails[sessionId]?.takeIf { it.size == size }
            val lastSeq = cached?.seq ?: (readFile(file).lastOrNull()?.seq ?: -1L)
            val event = StoredEvent(sessionId = sessionId, seq = lastSeq + 1, type = type, payload = payload)
            // Remove an incomplete final record before appending a new, complete one.
            if (Files.exists(file) && cached == null) {
                val bytes = Files.readAllBytes(file)
                if (bytes.isNotEmpty() && bytes.last() != 10.toByte()) {
                    val boundary = bytes.indexOfLast { it == 10.toByte() } + 1
                    FileChannel.open(file, WRITE).use { it.truncate(boundary.toLong()); it.force(true) }
                }
            }
            val bytes = (codec.encodeToString(event) + "\n").toByteArray(Charsets.UTF_8)
            FileChannel.open(file, CREATE, WRITE, APPEND).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            tails[sessionId] = Tail(Files.size(file), event.seq)
            index.remove(sessionId)
            event
        }
    }

    override suspend fun read(sessionId: String): List<StoredEvent> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = file(sessionId)
            val events = readFile(file)
            if (events.isNotEmpty() && endsWithNewline(file)) tails[sessionId] = Tail(Files.size(file), events.last().seq)
            else tails.remove(sessionId) // A torn tail must never be trusted by the next append.
            events
        }
    }

    private fun endsWithNewline(file: Path): Boolean = runCatching {
        val size = Files.size(file)
        if (size <= 0L) false else FileChannel.open(file, READ).use { channel ->
            val buffer = ByteBuffer.allocate(1)
            channel.read(buffer, size - 1)
            buffer.get(0) == 10.toByte()
        }
    }.getOrDefault(false)

    /**
     * The first event of [type] within the first [maxLines] journal lines, without reading the whole session.
     * Used for metadata written early in a session, such as `session.workspace`. Malformed lines are skipped.
     */
    suspend fun findEarly(sessionId: String, type: String, maxLines: Int = 400): StoredEvent? = withContext(Dispatchers.IO) {
        val file = file(sessionId)
        if (!Files.isRegularFile(file)) return@withContext null
        Files.newBufferedReader(file, Charsets.UTF_8).use { reader ->
            generateSequence { reader.readLine() }.take(maxLines)
                .filter { it.contains("\"$type\"") }
                .firstNotNullOfOrNull { line -> runCatching { codec.decodeFromString<StoredEvent>(line) }.getOrNull()?.takeIf { it.type == type } }
        }
    }

    override suspend fun list(): List<SessionInfo> = mutex.withLock {
        withContext(Dispatchers.IO) {
            // Maintenance tombstones and unrelated directory entries are never sessions.
            val ids = Files.list(directory).use { paths -> paths.filter { Files.isDirectory(it) && it.fileName.toString().matches(Regex("[a-zA-Z0-9_-]{1,100}")) }.map { it.fileName.toString() }.toList() }
            ids.map { id -> cachedInfo(id) }.sortedByDescending { it.updatedAt }
        }
    }

    /** Session listing is recomputed only when the journal file actually changed. */
    private fun cachedInfo(id: String): SessionInfo {
        val file = file(id)
        val size = runCatching { Files.size(file) }.getOrDefault(0L)
        val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
        index[id]?.let { entry -> if (entry.size == size && entry.modified == modified) return entry.info }
        val events = runCatching { readFile(file) }.getOrNull().orEmpty()
        val first = runCatching { projectConversation(events).firstOrNull { it.role == "user" } }.getOrNull()
        val renamed = events.asReversed().firstNotNullOfOrNull { event ->
            if (event.type != "session.renamed") return@firstNotNullOfOrNull null
            event.payload["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.length in 1..120 }
        }
        val title = renamed ?: first?.content?.takeIf { it.isNotBlank() }?.take(64) ?: first?.attachments?.firstOrNull()?.name?.take(64) ?: "Новый диалог"
        val info = SessionInfo(id, title, events.lastOrNull()?.timestamp ?: "")
        index[id] = IndexEntry(size, modified, info)
        return info
    }

    private fun file(id: String) = directory.resolve(safeId(id)).resolve("events.jsonl")
    private fun readFile(file: Path): List<StoredEvent> {
        if (!Files.exists(file)) return emptyList()
        require(Files.size(file) <= 64L * 1024 * 1024) { "Session exceeds 64 MiB; archive it before continuing" }
        val bytes = Files.readAllBytes(file)
        val completeEnd = bytes.indexOfLast { it == 10.toByte() } + 1
        if (completeEnd == 0) return emptyList()
        val lines = bytes.copyOfRange(0, completeEnd).toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
        var previous = -1L
        return lines.mapIndexed { index, line ->
            val event = try { codec.decodeFromString<StoredEvent>(line) }
                catch (_: Exception) { throw IllegalStateException("Corrupt session record ${index + 1}; no automatic replay") }
            require(event.schemaVersion == 1 && event.seq == previous + 1 && event.sessionId == file.parent.fileName.toString()) { "Invalid event ordering or schema" }
            previous = event.seq
            event
        }
    }
}

class LocalArtifactStore(private val directory: Path) : ArtifactStore {
    init { Files.createDirectories(directory) }
    override suspend fun put(sessionId: String, content: String): ArtifactRef = withContext(Dispatchers.IO) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8 * 1024 * 1024) { "Artifact exceeds 8 MiB" }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val folder = directory.resolve(safeId(sessionId)); Files.createDirectories(folder)
        val target = folder.resolve(hash)
        if (!Files.exists(target)) {
            try {
                FileChannel.open(target, CREATE_NEW, WRITE).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
            } catch (_: java.nio.file.FileAlreadyExistsException) { /* Immutable identical content. */ }
        }
        ArtifactRef(hash, sessionId, bytes.size.toLong())
    }

    override suspend fun read(sessionId: String, artifactId: String, offset: Int, limit: Int): String = withContext(Dispatchers.IO) {
        require(offset >= 0 && limit in 1..65_536) { "Invalid artifact range" }
        require(artifactId.matches(Regex("[a-f0-9]{64}"))) { "Invalid artifact id" }
        val file = directory.resolve(safeId(sessionId)).resolve(artifactId)
        require(Files.size(file) <= 8 * 1024 * 1024) { "Artifact exceeds limit" }
        val text = Files.readString(file)
        text.substring(offset.coerceAtMost(text.length), (offset.toLong() + limit).coerceAtMost(text.length.toLong()).toInt())
    }
}
