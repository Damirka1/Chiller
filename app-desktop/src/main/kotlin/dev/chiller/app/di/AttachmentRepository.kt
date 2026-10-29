package dev.chiller.app.di

import dev.chiller.app.i18n.*
import dev.chiller.contracts.ArtifactStore
import dev.chiller.contracts.ComposerAttachment
import dev.chiller.contracts.FilePresentation
import dev.chiller.contracts.ImageData
import dev.chiller.contracts.imageMimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Locale
import dev.chiller.providers.ModelImage
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** App-private input storage. Imported bytes never enter the selected project workspace. */
class AttachmentRepository(directory: Path, private val artifacts: ArtifactStore) {
    private val root = directory.toAbsolutePath().normalize()

    init {
        Files.createDirectories(root)
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) { "Attachment storage must be a real directory" }
    }

    suspend fun importText(sessionId: String, text: String, name: String = I18n.current.di.pastedTextName): ComposerAttachment {
        // Megabytes of text are encoded once, off the UI thread.
        return withContext(Dispatchers.IO) {
            val bytes = encodeUtf8Strict(text)
            require(bytes.size <= MAX_BYTES) { "Attachment exceeds 8 MiB" }
            val attachmentName = safeName(name)
            val mime = mimeForText(attachmentName, text)
            val ref = artifacts.put(validSession(sessionId), text)
            persist(validSession(sessionId), ref.id, bytes)
            ComposerAttachment(ref.id, attachmentName, mime, bytes.size.toLong())
        }
    }

    suspend fun importFile(sessionId: String, path: Path): ComposerAttachment = withContext(Dispatchers.IO) {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) { "Attachment must be a regular file" }
        val bytes = readBoundedNoFollow(path, MAX_BYTES)
        val name = safeName(path.fileName?.toString() ?: "attachment")
        val id = validSession(sessionId)
        val decoded = decodeText(bytes)
        val text = decoded?.takeIf { looksLikeText(path.fileName?.toString().orEmpty(), it) }
        val mime: String
        val refId: String
        if (text != null) {
            mime = mimeForText(name, text)
            refId = artifacts.put(id, text).id
        } else {
            mime = sniffImageMime(bytes) ?: "application/octet-stream"
            val summary = binarySummary(name, mime, bytes)
            refId = artifacts.put(id, summary).id
        }
        persist(id, refId, bytes)
        ComposerAttachment(refId, name, mime, bytes.size.toLong())
    }

    suspend fun importImage(sessionId: String, png: ByteArray): ComposerAttachment = withContext(Dispatchers.IO) {
        require(png.size <= MAX_IMAGE_BYTES) { "Image exceeds 4 MiB" }
        val dimensions = pngDimensions(png)?.takeIf { imageDimensions(png, "image/png") == it }
            ?: throw IllegalArgumentException("Invalid PNG image")
        require(dimensions.first.toLong() * dimensions.second <= MAX_PIXELS) { "Image exceeds 16 megapixels" }
        val id = validSession(sessionId)
        val name = I18n.current.di.pastedImageName
        val mime = "image/png"
        val artifactId = artifacts.put(id, binarySummary(name, mime, png)).id
        persist(id, artifactId, png)
        ComposerAttachment(artifactId, name, mime, png.size.toLong())
    }

    /** Resolves only an already stored attachment; callers should resolve again immediately before opening. */
    fun rawPath(sessionId: String, artifactId: String): Path? {
        val safeArtifact = try { validArtifact(artifactId) } catch (_: IllegalArgumentException) { return null }
        val folder = try { sessionDirectory(sessionId) } catch (_: IllegalArgumentException) { return null } ?: return null
        val file = folder.resolve(safeArtifact)
        return if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file) && Files.size(file) <= MAX_BYTES) file else null
    }

    /** The stored original of an image attachment, for editing by the image model; null for other files. */
    suspend fun originalImage(sessionId: String, artifactId: String): ImageData? = withContext(Dispatchers.IO) {
        val file = rawPath(sessionId, artifactId) ?: return@withContext null
        val bytes = readBoundedNoFollow(file, MAX_BYTES)
        imageMimeType(bytes)?.let { ImageData(bytes, it) }
    }

    fun presentation(sessionId: String, attachment: ComposerAttachment): FilePresentation =
        FilePresentation("attachment:${validArtifact(attachment.artifactId)}", attachment.name, attachment.mimeType, attachment.sizeBytes)

    /** Returns bounded PNG/JPEG bytes for UI preview; all other formats deliberately have no preview. */
    fun preview(sessionId: String, artifactId: String): ByteArray? {
        val path = rawPath(sessionId, artifactId) ?: return null
        val bytes = try { readBoundedNoFollow(path, MAX_IMAGE_BYTES) } catch (_: IllegalArgumentException) { return null }
        val mime = sniffImageMime(bytes) ?: return null
        if (mime !in setOf("image/png", "image/jpeg")) return null
        val dimensions = imageDimensions(bytes, mime) ?: return null
        if (dimensions.first.toLong() * dimensions.second > MAX_PIXELS) return null
        return bytes
    }

    /**
     * Prepares a stored PNG/JPEG for a vision model: long edge at most [MODEL_IMAGE_EDGE], alpha flattened on white,
     * re-encoded as JPEG. Returns null for anything [preview] would reject.
     */
    fun modelImage(sessionId: String, artifactId: String): ModelImage? {
        // Every model round resends the newest images; prepare each stored file once, not once per round.
        val path = rawPath(sessionId, artifactId) ?: return null
        val key = "$sessionId/$artifactId/${Files.size(path)}/${Files.getLastModifiedTime(path).toMillis()}"
        synchronized(modelImages) { modelImages[key] }?.let { return it }
        return prepareModelImage(sessionId, artifactId)?.also { image -> synchronized(modelImages) { modelImages[key] = image } }
    }

    private val modelImages = object : LinkedHashMap<String, ModelImage>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ModelImage>?) = size > 8
    }

    private fun prepareModelImage(sessionId: String, artifactId: String): ModelImage? {
        val bytes = preview(sessionId, artifactId) ?: return null
        val source = try { ImageIO.read(ByteArrayInputStream(bytes)) } catch (_: Exception) { null } ?: return null
        val scale = minOf(1.0, MODEL_IMAGE_EDGE.toDouble() / maxOf(source.width, source.height))
        val width = maxOf(1, Math.round(source.width * scale).toInt()); val height = maxOf(1, Math.round(source.height * scale).toInt())
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = target.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.color = Color.WHITE; g.fillRect(0, 0, width, height)
            g.drawImage(source, 0, 0, width, height, null)
        } finally { g.dispose() }
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: return null
        try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val params = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.85f }
                writer.write(null, IIOImage(target, null, null), params)
            }
        } finally { writer.dispose() }
        return ModelImage("image/jpeg", Base64.getEncoder().encodeToString(out.toByteArray()))
    }

    private fun persist(sessionId: String, artifactId: String, bytes: ByteArray) {
        val folder = ensureSessionDirectory(sessionId)
        val target = folder.resolve(validArtifact(artifactId))
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) { "Attachment target is not a regular file" }
            return
        }
        val temp = Files.createTempFile(folder, ".attachment-", ".tmp")
        try {
            FileChannel.open(temp, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try { Files.createLink(target, temp) }
            catch (_: java.nio.file.FileAlreadyExistsException) { /* Content-addressed artifact already persisted. */ }
        } finally { Files.deleteIfExists(temp) }
    }

    private fun ensureSessionDirectory(sessionId: String): Path {
        val folder = root.resolve(validSession(sessionId)).normalize()
        require(folder.parent == root)
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(folder)
        require(Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(folder)) { "Invalid attachment directory" }
        return folder
    }

    private fun sessionDirectory(sessionId: String): Path? {
        val folder = root.resolve(validSession(sessionId)).normalize()
        if (folder.parent != root || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(folder)) return null
        return folder
    }

    private fun validSession(id: String): String {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,100}"))) { "Invalid session id" }
        return id
    }

    private fun validArtifact(id: String): String {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid artifact id" }
        return id
    }

    private fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' && it != '\u007f' }.take(160)
        return base.ifBlank { "attachment" }
    }

    private fun binarySummary(name: String, mime: String, bytes: ByteArray): String =
        "Binary attachment: $name\nMIME type: $mime\nSize: ${bytes.size} bytes\nSHA-256: ${sha256(bytes)}\nModel access: file metadata only; image pixels and binary contents are not readable through this artifact."

    private fun mimeForText(name: String, text: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "txt", "log" -> "text/plain"
        "md", "markdown" -> "text/markdown"
        "csv" -> "text/csv"
        "tsv" -> "text/tab-separated-values"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs" -> "text/javascript"
        "yaml", "yml" -> "text/yaml"
        "toml" -> "application/toml"
        else -> if (text.startsWith("{") || text.startsWith("[")) "application/json" else "text/plain"
    }

    private fun encodeUtf8Strict(text: String): ByteArray = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(java.nio.CharBuffer.wrap(text)).let { buffer -> ByteArray(buffer.remaining()).also(buffer::get) }

    private fun decodeText(bytes: ByteArray): String? {
        if (bytes.any { it == 0.toByte() }) return null
        return try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { null }
    }

    private fun looksLikeText(name: String, text: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in setOf("txt", "log", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "css", "js", "mjs", "yaml", "yml", "toml", "kt", "kts", "java", "py", "rs", "c", "h", "cpp", "sh")) return true
        return text.none { it == '\u0000' || (it < ' ' && it !in "\t\n\r\u000c") }
    }

    private fun readBoundedNoFollow(path: Path, maxBytes: Int): ByteArray {
        FileChannel.open(path, READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            require(channel.size() <= maxBytes) { "Attachment exceeds ${maxBytes / (1024 * 1024)} MiB" }
            val buffer = ByteBuffer.allocate(maxBytes + 1)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
            require(buffer.position() <= maxBytes && channel.read(ByteBuffer.allocate(1)) < 0) { "Attachment exceeds ${maxBytes / (1024 * 1024)} MiB" }
            return buffer.array().copyOf(buffer.position())
        }
    }

    private fun imageDimensions(bytes: ByteArray, mime: String): Pair<Int, Int>? {
        return try {
            val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
            stream.use {
                val reader = ImageIO.getImageReaders(it).asSequence().firstOrNull() ?: return null
                try {
                    reader.input = it
                    val format = reader.formatName.lowercase(Locale.ROOT)
                    if ((mime == "image/png" && format != "png") || (mime == "image/jpeg" && format !in setOf("jpeg", "jpg"))) return null
                    val width = reader.getWidth(0); val height = reader.getHeight(0)
                    if (width <= 0 || height <= 0) null else width to height
                } finally { reader.dispose() }
            }
        } catch (_: Exception) { null }
    }

    private fun pngDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24 || !bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))) return null
        val width = readInt(bytes, 16); val height = readInt(bytes, 20)
        return if (width > 0 && height > 0) width to height else null
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 24) or ((bytes[offset + 1].toInt() and 255) shl 16) or ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)

    private fun sniffImageMime(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10)) -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
        else -> null
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        const val MAX_PIXELS = 16_000_000L
        /** Long edge sent to vision models; larger images cost tokens without helping most models. */
        const val MODEL_IMAGE_EDGE = 1568
    }
}
