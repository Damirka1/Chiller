package dev.chiller.app.ui

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectOverviewTest {
    @Test fun `detects stack, branch and counts files outside ignored folders`() {
        val root = Files.createTempDirectory("overview")
        Files.writeString(root.resolve("build.gradle.kts"), "")
        Files.createDirectories(root.resolve("src/main"))
        Files.writeString(root.resolve("src/main/A.kt"), "")
        Files.createDirectories(root.resolve("build/tmp"))
        Files.writeString(root.resolve("build/tmp/ignored.class"), "")
        Files.createDirectories(root.resolve(".git"))
        Files.writeString(root.resolve(".git/HEAD"), "ref: refs/heads/feature/ui\n")
        val overview = inspectProject(root)
        assertEquals(listOf("Gradle · Kotlin DSL"), overview.stack)
        assertEquals("feature/ui", overview.branch)
        assertEquals(2, overview.files)
        assertTrue(starters(overview).any { "./gradlew test" in it.prompt })
    }

    @Test fun `stops counting at the limit and tolerates no git`() {
        val root = Files.createTempDirectory("overview-limit")
        repeat(5) { Files.writeString(root.resolve("f$it.txt"), "") }
        val overview = inspectProject(root, limit = 3)
        assertNull(overview.files)
        assertNull(overview.branch)
    }
}
