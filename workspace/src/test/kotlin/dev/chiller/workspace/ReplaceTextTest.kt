package dev.chiller.workspace

import dev.chiller.contracts.ReadFileRequest
import dev.chiller.contracts.ToolException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplaceTextTest {
    private fun workspace(block: suspend (Path, LocalWorkspace) -> Unit) = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("chiller-replace-")
        try { block(root, LocalWorkspace(root)) } finally { root.toFile().deleteRecursively() }
    }
    private suspend fun expectError(code: String, block: suspend () -> Unit) {
        try { block(); error("Expected ToolException($code)") } catch (e: ToolException) { assertEquals(code, e.code) }
    }

    @Test fun replacesUniqueMultilineTextAndKeepsCrlf() = workspace { root, ws ->
        Files.writeString(root.resolve("a.py"), "def f():\r\n    return 1\r\n\r\nprint(f())\r\n")
        val snap = ws.read(ReadFileRequest("a.py"))
        val change = ws.replaceText("a.py", snap.snapshotId, "def f():\n    return 1", "def f():\n    value = 2\n    return value")
        assertEquals("def f():\r\n    value = 2\r\n    return value\r\n\r\nprint(f())\r\n", Files.readString(root.resolve("a.py")))
        // Lineage: the returned snapshot allows a second replacement without re-reading.
        ws.replaceText("a.py", change.snapshotId!!, "print(f())", "print(f() + 1)")
        assertEquals("def f():\r\n    value = 2\r\n    return value\r\n\r\nprint(f() + 1)\r\n", Files.readString(root.resolve("a.py")))
    }

    @Test fun refusesAmbiguousOrMissingTextWithoutWriting() = workspace { root, ws ->
        Files.writeString(root.resolve("a.txt"), "x = 1\nx = 1\n")
        val snap = ws.read(ReadFileRequest("a.txt"))
        expectError("MATCH_COUNT_MISMATCH") { ws.replaceText("a.txt", snap.snapshotId, "x = 1", "x = 2") }
        expectError("NO_MATCH") { ws.replaceText("a.txt", snap.snapshotId, "x =  1", "x = 2") }
        assertEquals("x = 1\nx = 1\n", Files.readString(root.resolve("a.txt")))
        ws.replaceText("a.txt", snap.snapshotId, "x = 1", "x = 2", expectedCount = 2)
        assertEquals("x = 2\nx = 2\n", Files.readString(root.resolve("a.txt")))
    }

    @Test fun replacesSeveralMatchesOnOneLineAndRemovesWholeLines() = workspace { root, ws ->
        Files.writeString(root.resolve("a.txt"), "a-a\nkeep\ndrop\nend\n")
        val snap = ws.read(ReadFileRequest("a.txt"))
        val next = ws.replaceText("a.txt", snap.snapshotId, "a", "b", expectedCount = 2)
        ws.replaceText("a.txt", next.snapshotId!!, "drop\n", "")
        assertEquals("b-b\nkeep\nend\n", Files.readString(root.resolve("a.txt")))
    }

    @Test fun requiresTheMatchedLinesToHaveBeenRead() = workspace { root, ws ->
        Files.writeString(root.resolve("a.txt"), "one\ntwo\nthree\n")
        val snap = ws.read(ReadFileRequest("a.txt", 1, 1))
        expectError("UNOBSERVED_RANGE") { ws.replaceText("a.txt", snap.snapshotId, "three", "3") }
        Files.writeString(root.resolve("a.txt"), "one\ntwo\nthree!\n")
        expectError("STALE_SNAPSHOT") { ws.replaceText("a.txt", snap.snapshotId, "one", "1") }
    }
}
