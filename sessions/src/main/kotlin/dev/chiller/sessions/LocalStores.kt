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
    init { Files.createDirectories(directory) }

    override suspend fun append(sessionId: String, type: String, payload: JsonObject): StoredEvent = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = file(sessionId)
            Files.createDirectories(file.parent)
            val history = readFile(file)
            val event = StoredEvent(sessionId = sessionId, seq = (history.lastOrNull()?.seq ?: -1) + 1, type = type, payload = payload)
            // Remove an incomplete final record before appending a new, complete one.
            if (Files.exists(file)) {
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
            event
        }
    }

    override suspend fun read(sessionId: String): List<StoredEvent> = mutex.withLock {
        withContext(Dispatchers.IO) { readFile(file(sessionId)) }
    }

    override suspend fun list(): List<SessionInfo> = mutex.withLock {
        withContext(Dispatchers.IO) {
            Files.list(directory).use { paths -> paths.filter { Files.isDirectory(it) }.map { path ->
                val events = runCatching { readFile(path.resolve("events.jsonl")) }.getOrNull().orEmpty()
                val first = events.firstOrNull { it.type == "message" && it.payload["role"]?.jsonPrimitive?.contentOrNull == "user" }
                val renamed = events.asReversed().firstNotNullOfOrNull { event ->
                    if (event.type != "session.renamed") return@firstNotNullOfOrNull null
                    event.payload["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.length in 1..120 }
                }
                val title = renamed ?: first?.payload?.get("content")?.jsonPrimitive?.contentOrNull?.take(64) ?: "Новый диалог"
                SessionInfo(path.fileName.toString(), title, events.lastOrNull()?.timestamp ?: "")
            }.toList().sortedByDescending { it.updatedAt } }
        }
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
