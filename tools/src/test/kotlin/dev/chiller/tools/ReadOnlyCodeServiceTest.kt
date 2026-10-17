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
    @Test fun findsLexicalSymbolsInFreshNonGitWorkspaceWithIgnores() = runBlocking {
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

    @Test fun fixedGitReadsAndLexicalSymbolsStayBounded() = runBlocking {
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

    @Test fun refusesWorkspaceNestedInsideLargerRepository() = runBlocking {
        val parent = Files.createTempDirectory("chiller-parent-git")
        val nested = Files.createDirectories(parent.resolve("nested"))
        try {
            val init = ProcessBuilder("git", "init", "-q").directory(parent.toFile()).start()
            assertEquals(0, init.waitFor())
            assertFailsWith<ToolException> { LocalReadOnlyCodeService(nested.toString()).gitStatus() }
        } finally { parent.toFile().deleteRecursively() }
    }
}
