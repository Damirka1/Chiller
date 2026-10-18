package dev.chiller.workspace

import java.nio.file.Files
import kotlin.test.*

class RipgrepTest {
    @Test fun sourceRunsUsePath() { assertEquals("rg", Ripgrep.executable(null)) }
    @Test fun packagedRunsRequireTheirOwnBinary() {
        val root = Files.createTempDirectory("chiller инструменты ")
        try {
            assertFailsWith<IllegalStateException> { Ripgrep.executable(root.toString(), true) }
            val binary = Files.createDirectories(root.resolve("tools")).resolve("rg.exe")
            Files.writeString(binary, "fixture")
            assertEquals(binary.toAbsolutePath().toString(), Ripgrep.executable(root.toString(), true))
        } finally { root.toFile().deleteRecursively() }
    }
}
