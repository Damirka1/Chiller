package dev.chiller.workspace

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectInstructionsTest {
    @Test fun loadsKnownFilesInOrderAndBoundsSize() {
        val root = Files.createTempDirectory("instructions")
        assertNull(ProjectInstructions.load(root))
        Files.createDirectories(root.resolve(".chiller"))
        Files.writeString(root.resolve(".chiller/instructions.md"), "Use tabs.")
        Files.writeString(root.resolve("AGENTS.md"), "Run ./gradlew check.")
        val loaded = ProjectInstructions.load(root)!!
        assertEquals(listOf("AGENTS.md", ".chiller/instructions.md"), loaded.sources)
        assertTrue(loaded.text.indexOf("Run ./gradlew") < loaded.text.indexOf("Use tabs"))
        Files.writeString(root.resolve("CLAUDE.md"), "x".repeat(50))
        val small = ProjectInstructions.load(root, maxCharacters = 40)!!
        assertTrue(small.truncated && small.text.endsWith("[truncated]"))
    }

    @Test fun skipsSymlinksAndBinary() {
        val root = Files.createTempDirectory("instructions-unsafe")
        val outside = Files.createTempFile("secret", ".md").also { Files.writeString(it, "outside") }
        Files.createSymbolicLink(root.resolve("AGENTS.md"), outside)
        Files.write(root.resolve("CLAUDE.md"), byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x00))
        assertNull(ProjectInstructions.load(root))
    }
}
