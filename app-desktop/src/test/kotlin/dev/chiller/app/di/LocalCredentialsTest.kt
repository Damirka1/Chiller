package dev.chiller.app.di

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalCredentialsTest {
    @TempDir lateinit var directory: Path

    @Test fun `environment keys take precedence and blank values are ignored`() {
        val file = envFile("API_TOKEN=file-token")

        assertEquals("deepseek-env", LocalCredentials.load(
            mapOf("DEEPSEEK_API_KEY" to " deepseek-env ", "API_TOKEN" to "api-env"), file,
        ))
        assertEquals("api-env", LocalCredentials.load(mapOf("DEEPSEEK_API_KEY" to "  ", "API_TOKEN" to " api-env "), file))
    }

    @Test fun `dotenv supports export quotes and comments for recognized names`() {
        val file = envFile(
            """
            # synthetic fixture
            export OTHER=value
            DEEPSEEK_API_KEY=wrong-order
            export API_TOKEN = ' chosen-token ' # trailing note
            """.trimIndent(),
        )

        assertEquals("chosen-token", LocalCredentials.load(emptyMap(), file))
    }

    @Test fun `dotenv uses key alias when token is absent and does not expand variables`() {
        val file = envFile("DEEPSEEK_API_KEY=\"${'$'}API_TOKEN\"")

        assertEquals("${'$'}API_TOKEN", LocalCredentials.load(emptyMap(), file))
    }

    @Test fun `missing malformed oversized and blank files resolve to no key`() {
        assertNull(LocalCredentials.load(emptyMap(), directory.resolve("missing.env")))

        val malformed = directory.resolve("malformed.env")
        Files.write(malformed, byteArrayOf(0xC3.toByte(), 0x28))
        assertNull(LocalCredentials.load(emptyMap(), malformed))

        val oversized = directory.resolve("oversized.env")
        Files.write(oversized, ByteArray(64 * 1024 + 1) { 'x'.code.toByte() })
        assertNull(LocalCredentials.load(emptyMap(), oversized))

        assertNull(LocalCredentials.load(emptyMap(), envFile("API_TOKEN=   ")))
    }

    private fun envFile(contents: String): Path = directory.resolve(".env").also {
        Files.writeString(it, contents)
    }
}
