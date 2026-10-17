package dev.chiller.app.di

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoVerifyTest {
    @Test fun `commands split like argv without a shell`() {
        assertEquals(listOf("./gradlew", "test", "--tests", "*Foo*"), splitCommand("./gradlew test --tests '*Foo*'"))
        assertEquals(listOf("python3", "-c", "print(1 + 1)"), splitCommand("python3 -c \"print(1 + 1)\""))
        assertEquals(listOf("echo", ""), splitCommand("echo ''"))
        assertFailsWith<IllegalArgumentException> { splitCommand("echo 'open") }
    }

    @Test fun `suggestions follow the build files`() {
        val root = Files.createTempDirectory("verify")
        assertNull(suggestVerifyCommand(root))
        Files.writeString(root.resolve("pyproject.toml"), "")
        assertEquals("python3 -m pytest -q", suggestVerifyCommand(root))
        Files.writeString(root.resolve("gradlew"), ""); Files.writeString(root.resolve("build.gradle.kts"), "")
        assertEquals("./gradlew test", suggestVerifyCommand(root))
    }

    @Test fun `follow up carries command, exit code and output tail`() {
        val text = verificationFollowUp("npm test", 1, "exited", "x".repeat(5000) + "FAIL src/a.test.js", 1, 2)
        assertTrue("`npm test`" in text && "Код завершения: 1" in text && text.contains("FAIL src/a.test.js") && "попытка 1 из 2" in text)
    }
}
