package dev.chiller.app.di

import dev.chiller.contracts.ArtifactRef
import dev.chiller.contracts.ArtifactStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AttachmentRepositoryTest {
    @TempDir lateinit var temp: Path

    private class MemoryArtifacts : ArtifactStore {
        val content = mutableMapOf<Pair<String, String>, String>()
        override suspend fun put(sessionId: String, content: String): ArtifactRef {
            val id = MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
            this.content[sessionId to id] = content
            return ArtifactRef(id, sessionId, content.toByteArray().size.toLong())
        }
        override suspend fun read(sessionId: String, artifactId: String, offset: Int, limit: Int): String =
            content.getValue(sessionId to artifactId).substring(offset.coerceAtMost(content.getValue(sessionId to artifactId).length))
    }

    @Test fun `text import keeps raw bytes in app storage and exposes a readable artifact`() = runBlocking {
        val artifacts = MemoryArtifacts()
        val repo = AttachmentRepository(temp.resolve("attachments"), artifacts)
        val text = "Привет, мир\n"
        val attachment = repo.importText("session_1", text, "notes.csv")
        assertEquals("text/csv", attachment.mimeType)
        assertEquals(text, artifacts.read("session_1", attachment.artifactId, 0, 100))
        assertContentEquals(text.toByteArray(), Files.readAllBytes(assertNotNull(repo.rawPath("session_1", attachment.artifactId))))
        assertEquals("attachment:${attachment.artifactId}", repo.presentation("session_1", attachment).path)
        assertNull(repo.rawPath("../escape", attachment.artifactId))
    }

    @Test fun `file import detects image metadata while model artifact remains metadata only`() = runBlocking {
        val artifacts = MemoryArtifacts()
        val repo = AttachmentRepository(temp.resolve("appdata"), artifacts)
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/Z0cAAAAASUVORK5CYII=")
        val input = temp.resolve("pixel.png")
        Files.write(input, png)
        val attachment = repo.importFile("session", input)
        assertEquals("image/png", attachment.mimeType)
        assertTrue(artifacts.content.getValue("session" to attachment.artifactId).contains("pixels and binary contents are not readable"))
        assertContentEquals(png, assertNotNull(repo.preview("session", attachment.artifactId)))
    }

    @Test fun `pasted image validates PNG and pixel limit`() = runBlocking<Unit> {
        val repo = AttachmentRepository(temp.resolve("attachments"), MemoryArtifacts())
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/Z0cAAAAASUVORK5CYII=")
        val item = repo.importImage("s", png)
        assertEquals("image/png", item.mimeType)
        val oversizedHeader = png.copyOf().also {
            it[16] = 0x01; it[17] = 0x00; it[18] = 0x00; it[19] = 0x01
            it[20] = 0x01; it[21] = 0x00; it[22] = 0x00; it[23] = 0x01
        }
        assertFailsWith<IllegalArgumentException> { repo.importImage("s", oversizedHeader) }
        assertFailsWith<IllegalArgumentException> { repo.importImage("s", byteArrayOf(1, 2, 3)) }
    }

    @Test fun `rejects oversized or malformed UTF8 imports and avoids symlink session folders`() = runBlocking<Unit> {
        val repo = AttachmentRepository(temp.resolve("attachments"), MemoryArtifacts())
        assertFailsWith<IllegalArgumentException> { repo.importText("s", "x".repeat(AttachmentRepository.MAX_BYTES + 1)) }
        val invalid = temp.resolve("invalid.dat")
        Files.write(invalid, byteArrayOf(0xc3.toByte(), 0x28))
        val item = repo.importFile("s", invalid)
        assertEquals("application/octet-stream", item.mimeType)
        val link = temp.resolve("attachments").resolve("linked")
        Files.createSymbolicLink(link, temp)
        assertNull(repo.rawPath("linked", item.artifactId))
        assertFailsWith<IllegalArgumentException> { repo.importText("linked", "secret") }
    }

    @Test fun `raw path and preview reject unknown IDs and unsupported preview formats`() = runBlocking {
        val repo = AttachmentRepository(temp.resolve("attachments"), MemoryArtifacts())
        assertNull(repo.rawPath("s", "../bad"))
        assertNull(repo.preview("s", "a".repeat(64)))
        val text = repo.importText("s", "hello")
        assertNull(repo.preview("s", text.artifactId))
    }

    @Test fun `model image is downscaled flattened JPEG and absent for text`() = runBlocking {
        val repo = AttachmentRepository(temp.resolve("attachments"), MemoryArtifacts())
        val source = java.awt.image.BufferedImage(3000, 1000, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(source, "png", it) }.toByteArray()
        val item = repo.importImage("s", png)
        val image = assertNotNull(repo.modelImage("s", item.artifactId))
        assertEquals("image/jpeg", image.mimeType)
        kotlin.test.assertSame(image, repo.modelImage("s", item.artifactId), "prepared once, reused on later rounds")
        val decoded = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(image.base64)))
        assertEquals(AttachmentRepository.MODEL_IMAGE_EDGE, decoded.width)
        assertEquals(523, decoded.height)
        assertEquals(-1, decoded.getRGB(10, 10) or 0x030303, "transparent pixels become white, not black")
        assertNull(repo.modelImage("s", repo.importText("s", "hello").artifactId))
    }

    @Test fun `vision setting forces or follows the model id`() {
        assertTrue(visionEnabled(AgentOptions(), "gemma-4-26b-a4b"))
        assertTrue(!visionEnabled(AgentOptions(), "deepseek-flash"))
        assertTrue(visionEnabled(AgentOptions(vision = "on"), "deepseek-flash"))
        assertTrue(!visionEnabled(AgentOptions(vision = "off"), "gpt-4o"))
        assertEquals("auto", AgentOptions(vision = "maybe").sanitized().vision)
    }
}
