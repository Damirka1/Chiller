package dev.chiller.tools

import dev.chiller.contracts.ToolException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReadOnlyCodeServiceTest {
    @org.junit.jupiter.api.Test fun `file stamps hash touched files even when ignored and detect same metadata overwrite`() = kotlinx.coroutines.runBlocking<Unit> {
        val project = java.nio.file.Files.createTempDirectory("file-stamp-hash")
        try {
            java.nio.file.Files.writeString(project.resolve(".gitignore"), "ignored.txt\n")
            val file = java.nio.file.Files.writeString(project.resolve("ignored.txt"), "first")
            val stamp = java.nio.file.Files.getLastModifiedTime(file)
            val service = LocalReadOnlyCodeService(project.toString())
            val first = service.fileStamps(hashPaths = setOf("ignored.txt"))!!.getValue("ignored.txt")
            java.nio.file.Files.writeString(file, "other")
            java.nio.file.Files.setLastModifiedTime(file, stamp)
            val second = service.fileStamps(hashPaths = setOf("ignored.txt"))!!.getValue("ignored.txt")
            kotlin.test.assertEquals(first.size, second.size)
            kotlin.test.assertEquals(first.modifiedMillis, second.modifiedMillis)
            kotlin.test.assertNotNull(first.contentHash)
            kotlin.test.assertNotEquals(first.contentHash, second.contentHash)
        } finally { project.toFile().deleteRecursively() }
    }

    @Test fun findsLexicalSymbolsInFreshNonGitWorkspaceWithIgnores() = runBlocking<Unit> {
        val root = Files.createTempDirectory("chiller-symbols-no-git")
        val outside = Files.createTempFile("chiller-outside-symbol", ".kt")
        try {
            Files.writeString(outside, "class OutsideSymbol\n")
            Files.writeString(root.resolve(".gitignore"), "ignored.kt\n")
            Files.writeString(root.resolve(".chillerignore"), "private.kt\n")
            Files.writeString(root.resolve("visible.kt"), "class Visible\nfun <T> Box<T>.receive() = Unit\n")
            Files.writeString(root.resolve("ignored.kt"), "class Ignored\n")
            Files.writeString(root.resolve("private.kt"), "class Private\n")
            val service = LocalReadOnlyCodeService(root.toString())
            val symbols = service.findSymbols("receive", null, 10).data!!.jsonObject
            assertTrue(symbols.getValue("matches").toString().contains("visible.kt"))
            assertFalse(service.findSymbols("Ignored", null, 10).data!!.toString().contains("Ignored"))
            assertFalse(service.findSymbols("Private", null, 10).data!!.toString().contains("Private"))
            runCatching { Files.createSymbolicLink(root.resolve("linked.kt"), outside) }.onSuccess {
                assertFalse(service.findSymbols("OutsideSymbol", null, 10).data!!.toString().contains("OutsideSymbol"))
            }
        } finally { root.toFile().deleteRecursively(); Files.deleteIfExists(outside) }
    }

    @Test fun fixedGitReadsAndLexicalSymbolsStayBounded() = runBlocking<Unit> {
        val root = Files.createTempDirectory("chiller-readonly-git")
        try {
            val init = ProcessBuilder("git", "init", "-q").directory(root.toFile()).start()
            assertEquals(0, init.waitFor())
            Files.writeString(root.resolve("Thing.kt"), "package demo\nclass Thing\nfun usefulThing() = Unit\n")
            Files.writeString(root.resolve(".gitignore"), "Ignored.kt\n")
            Files.writeString(root.resolve("Ignored.kt"), "class IgnoredThing\n")
            val add = ProcessBuilder("git", "add", "Thing.kt").directory(root.toFile()).start()
            assertEquals(0, add.waitFor())
            val commit = ProcessBuilder("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "initial").directory(root.toFile()).start()
            assertEquals(0, commit.waitFor())
            Files.writeString(root.resolve("Thing.kt"), "package demo\nclass Thing\nfun changedThing() = Unit\n")
            val service = LocalReadOnlyCodeService(root.toString())
            val hostileConfig = ProcessBuilder("git", "config", "diff.external", "/path/that/must/not/run").directory(root.toFile()).start()
            assertEquals(0, hostileConfig.waitFor())
            val textconvConfig = ProcessBuilder("git", "config", "diff.hostile.textconv", "/path/that/must/not/run").directory(root.toFile()).start()
            assertEquals(0, textconvConfig.waitFor())
            val fsmonitorConfig = ProcessBuilder("git", "config", "core.fsmonitor", "/path/that/must/not/run").directory(root.toFile()).start()
            assertEquals(0, fsmonitorConfig.waitFor())
            Files.writeString(root.resolve(".gitattributes"), "*.kt diff=hostile\n")
            val status = service.gitStatus().data!!.jsonObject.getValue("output").jsonPrimitive.content
            assertTrue(status.contains("Thing.kt"))
            val diff = service.gitDiff(false, "Thing.kt").data!!.jsonObject.getValue("output").jsonPrimitive.content
            assertTrue(diff.contains("changedThing"))
            val log = service.gitLog(3, null).data!!.jsonObject.getValue("output").jsonPrimitive.content
            assertTrue(log.contains("initial"))
            val show = service.gitShow("HEAD", "Thing.kt").data!!.jsonObject.getValue("output").jsonPrimitive.content
            assertTrue(show.contains("usefulThing"))
            val symbols = service.findSymbols("Thing", null, 10).data!!.jsonObject
            assertTrue(symbols.getValue("lexical").jsonPrimitive.content.toBoolean())
            assertTrue(symbols.getValue("matches").toString().contains("Thing"))
            assertFalse(service.findSymbols("IgnoredThing", null, 10).data!!.toString().contains("IgnoredThing"))
            assertFailsWith<ToolException> { service.gitDiff(false, "../outside") }
            assertFailsWith<ToolException> { service.gitShow("--upload-pack=evil", "Thing.kt") }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun refusesWorkspaceNestedInsideLargerRepository() = runBlocking<Unit> {
        val parent = Files.createTempDirectory("chiller-parent-git")
        val nested = Files.createDirectories(parent.resolve("nested"))
        try {
            val init = ProcessBuilder("git", "init", "-q").directory(parent.toFile()).start()
            assertEquals(0, init.waitFor())
            assertFailsWith<ToolException> { LocalReadOnlyCodeService(nested.toString()).gitStatus() }
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test fun projectMapListsFilesWithTopLevelDeclarationsAndCutsToBudget() = runBlocking<Unit> {
        val root = Files.createTempDirectory("chiller-map")
        try {
            Files.createDirectories(root.resolve("src"))
            Files.writeString(root.resolve("src/cart.py"), "import math\n\nclass Cart:\n    def total(self):\n        pass\n\ndef apply_discount(cart):\n    pass\n")
            Files.writeString(root.resolve("README.md"), "# demo\n")
            Files.writeString(root.resolve(".gitignore"), "build/\n")
            Files.createDirectories(root.resolve("build")); Files.writeString(root.resolve("build/out.py"), "class Generated:\n    pass\n")
            val service = LocalReadOnlyCodeService(root.toString())
            val map = service.projectMap()!!
            assertTrue("src/\n  cart.py — class Cart:3, def apply_discount:7" in map, map)
            assertTrue("README.md" in map, map)
            assertFalse("total" in map, "nested methods stay out of the map")
            assertFalse("Generated" in map, "ignored folders stay out of the map")
            val cut = service.projectMap(maxChars = 90)!!
            assertTrue(cut.startsWith("3 files, map cut to fit") || cut.startsWith("4 files, map cut to fit"), cut)
            assertTrue(cut.length <= 90, cut)
            assertTrue(service.projectMap(maxChars = 200)!!.length <= 200)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun largeProjectsAreListedForSymbolsAndStamps() = runBlocking<Unit> {
        val root = Files.createTempDirectory("chiller-large-list")
        try {
            val dir = Files.createDirectories(root.resolve("src/some_rather_long_module_directory_name"))
            repeat(3000) { Files.writeString(dir.resolve("generated_source_file_with_a_long_name_$it.txt"), "x") }
            Files.writeString(dir.resolve("Target.kt"), "class NeedleInLargeProject\n")
            val service = LocalReadOnlyCodeService(root.toString())
            assertTrue("NeedleInLargeProject" in service.findSymbols("Needle", null, 10).data!!.toString(), "find_symbols works past the old 48 KiB listing cap")
            val stamps = service.fileStamps()!!
            assertEquals(3001, stamps.size)
            assertEquals(1L, stamps.getValue("src/some_rather_long_module_directory_name/generated_source_file_with_a_long_name_7.txt").size)
            assertEquals(null, service.fileStamps(limit = 100), "over the limit there is no partial snapshot")
        } finally { root.toFile().deleteRecursively() }
    }
}
