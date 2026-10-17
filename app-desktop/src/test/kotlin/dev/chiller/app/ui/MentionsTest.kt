package dev.chiller.app.ui

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MentionsTest {
    @Test fun detectsTheMentionUnderTheCursor() {
        assertEquals(0 to "gre", mentionAt("@gre", 4))
        assertEquals(5 to "", mentionAt("Fix  @", 6))
        assertNull(mentionAt("mail@host", 9), "an @ inside a word is not a mention")
        assertNull(mentionAt("@a b", 4), "whitespace ends the mention")
    }

    @Test fun ranksNamePrefixFirst() {
        val files = listOf("src/regreet.py", "greeting.py", "docs/greeting-notes.md", "src/app/Greeter.kt")
        assertEquals(listOf("greeting.py", "src/app/Greeter.kt", "docs/greeting-notes.md", "src/regreet.py"), rankFiles(files, "gree"))
        assertEquals(listOf("greeting.py"), mentionedFiles("Look at @greeting.py, and @missing.py", files))
    }

    @Test fun listsFilesWithoutBuildFolders() {
        val root = Files.createTempDirectory("mentions")
        Files.createDirectories(root.resolve("src")); Files.writeString(root.resolve("src/A.kt"), "")
        Files.createDirectories(root.resolve("build")); Files.writeString(root.resolve("build/B.class"), "")
        Files.createDirectories(root.resolve(".git")); Files.writeString(root.resolve(".git/HEAD"), "")
        Files.writeString(root.resolve("README.md"), "")
        assertEquals(listOf("README.md", "src/A.kt"), listProjectFiles(root))
    }
}
