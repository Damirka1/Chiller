package dev.chiller.workspace

import dev.chiller.contracts.ReadFileRequest
import dev.chiller.contracts.ToolException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test fun matchesTextThroughTheFinalLineBreak() = workspace { root, ws ->
        // Live Gemma runs copied the last function with its final line break and got NO_MATCH for an exact copy.
        val source = "def a():\n    raise NotImplementedError\n\n\ndef b():\n    raise NotImplementedError\n"
        Files.writeString(root.resolve("a.py"), source)
        val snap = ws.read(ReadFileRequest("a.py"))
        val change = ws.replaceText("a.py", snap.snapshotId, "def b():\n    raise NotImplementedError\n", "def b():\n    return 2\n")
        assertEquals("def a():\n    raise NotImplementedError\n\n\ndef b():\n    return 2\n", Files.readString(root.resolve("a.py")))
        ws.replaceText("a.py", change.snapshotId!!, "def a():\n    raise NotImplementedError\n\n\ndef b():\n    return 2\n", "def a():\n    return 1\n\n\ndef b():\n    return 2\n\n\ndef c():\n    return 3\n")
        assertEquals("def a():\n    return 1\n\n\ndef b():\n    return 2\n\n\ndef c():\n    return 3\n", Files.readString(root.resolve("a.py")))

        Files.writeString(root.resolve("crlf.txt"), "one\r\ntwo\r\n")
        val crlf = ws.read(ReadFileRequest("crlf.txt"))
        val next = ws.replaceText("crlf.txt", crlf.snapshotId, "two\n", "2\nthree\n")
        assertEquals("one\r\n2\r\nthree\r\n", Files.readString(root.resolve("crlf.txt")))
        // Dropping the final line break is not something the line model can express; it is refused, not guessed.
        expectError("INVALID_EDIT") { ws.replaceText("crlf.txt", next.snapshotId!!, "three\n", "3") }
        assertEquals("one\r\n2\r\nthree\r\n", Files.readString(root.resolve("crlf.txt")))
    }

    @Test fun failedMatchesExplainWhatToCopyWithoutApplyingAnything() = workspace { root, ws ->
        val source = "def should_retry(attempt):\n    raise NotImplementedError\n\n\ndef wait(attempt):\n    raise NotImplementedError\n"
        Files.writeString(root.resolve("retry.py"), source)
        val snap = ws.read(ReadFileRequest("retry.py"))
        val blankLines = assertFailsWith<ToolException> { ws.replaceText("retry.py", snap.snapshotId, "def should_retry(attempt):\n    raise NotImplementedError\n\ndef wait(attempt):", "x") }
        assertEquals("NO_MATCH", blankLines.code)
        assertEquals("Only blank lines differ: lines 1-5 have 2 blank line(s), old_text has 1. Simplest fix: change the text in parts, one replace_text per part, " +
            "each old_text without any blank line. Their exact text:\ndef should_retry(attempt):\n    raise NotImplementedError\n\n\ndef wait(attempt):", blankLines.recovery)
        val indented = assertFailsWith<ToolException> { ws.replaceText("retry.py", snap.snapshotId, "def wait(attempt):\n  raise NotImplementedError", "x") }
        assertTrue(indented.recovery.orEmpty().startsWith("Lines 5-6 match old_text when indentation"), indented.recovery)
        val escapedTwice = assertFailsWith<ToolException> { ws.replaceText("retry.py", snap.snapshotId, "def wait(attempt):\\n    raise NotImplementedError", "x") }
        assertTrue("escaped twice" in escapedTwice.recovery.orEmpty(), escapedTwice.recovery)
        val prefixed = assertFailsWith<ToolException> { ws.replaceText("retry.py", snap.snapshotId, "5 | def wait(attempt):", "x") }
        assertTrue("line-number prefixes" in prefixed.recovery.orEmpty(), prefixed.recovery)
        val count = assertFailsWith<ToolException> { ws.replaceText("retry.py", snap.snapshotId, "def wait(attempt):", "x", expectedCount = 2) }
        assertEquals("MATCH_COUNT_MISMATCH", count.code)
        assertTrue("omit expected_count" in count.recovery.orEmpty() && "line 5" in count.recovery.orEmpty(), count.recovery)
        assertEquals(source, Files.readString(root.resolve("retry.py")))
    }

    @Test fun linesReadInPartsOfTheSameContentCountForTheEditChain() = workspace { root, ws ->
        // A live run read a big file in two parts, edited the first part, then got UNOBSERVED_RANGE for the second.
        Files.writeString(root.resolve("a.txt"), "one\ntwo\nthree\n")
        val head = ws.read(ReadFileRequest("a.txt", 1, 1))
        ws.read(ReadFileRequest("a.txt", 3, 3))
        val next = ws.replaceText("a.txt", head.snapshotId, "one", "1")
        ws.replaceText("a.txt", next.snapshotId!!, "three", "3")
        assertEquals("1\ntwo\n3\n", Files.readString(root.resolve("a.txt")))
        expectError("UNOBSERVED_RANGE") { ws.replaceText("a.txt", ws.read(ReadFileRequest("a.txt", 1, 1)).snapshotId, "two", "2") }
    }

    @Test fun aLongFileIsWrittenInPartsWithoutReading() = workspace { root, ws ->
        // A live run resent the whole file after every cut-off; create's snapshot plus append write it part by part.
        val created = ws.create("squares.py", "SQUARES = [\n    0,\n    1,\n")
        val second = ws.append("squares.py", created.snapshotId!!, "    4,\n    9,\n")
        val third = ws.append("squares.py", second.snapshotId!!, "]")
        assertEquals("SQUARES = [\n    0,\n    1,\n    4,\n    9,\n]\n", Files.readString(root.resolve("squares.py")))
        ws.replaceText("squares.py", third.snapshotId!!, "    9,", "    9,\n    16,")
        assertEquals("SQUARES = [\n    0,\n    1,\n    4,\n    9,\n    16,\n]\n", Files.readString(root.resolve("squares.py")))
        // A stale snapshot or an unread end is refused; nothing is appended blindly.
        expectError("STALE_SNAPSHOT") { ws.append("squares.py", created.snapshotId!!, "x") }
        Files.writeString(root.resolve("long.txt"), "one\ntwo\nthree")
        val head = ws.read(ReadFileRequest("long.txt", 1, 1))
        val unread = assertFailsWith<ToolException> { ws.append("long.txt", head.snapshotId, "four") }
        assertEquals("UNOBSERVED_RANGE", unread.code)
        assertEquals("Call read_file with start_line=3 and end_line=3, then retry with the snapshot_id it returns", unread.recovery)
        ws.append("long.txt", ws.read(ReadFileRequest("long.txt", 3, 3)).snapshotId, "four")
        assertEquals("one\ntwo\nthree\nfour", Files.readString(root.resolve("long.txt")))
    }

    @Test fun requiresTheMatchedLinesToHaveBeenRead() = workspace { root, ws ->
        Files.writeString(root.resolve("a.txt"), "one\ntwo\nthree\n")
        val snap = ws.read(ReadFileRequest("a.txt", 1, 1))
        val unread = assertFailsWith<ToolException> { ws.replaceText("a.txt", snap.snapshotId, "three", "3") }
        assertEquals("UNOBSERVED_RANGE", unread.code)
        assertEquals("Call read_file with start_line=3 and end_line=3, then edit with the snapshot_id it returns", unread.recovery)
        Files.writeString(root.resolve("a.txt"), "one\ntwo\nthree!\n")
        expectError("STALE_SNAPSHOT") { ws.replaceText("a.txt", snap.snapshotId, "one", "1") }
    }

    @Test fun blankLinesHoldingSpacesAreNamed() = workspace { root, ws ->
        Files.writeString(root.resolve("t.py"), "class T:\n    def a(self):\n        pass\n    \n    def b(self):\n        pass\n")
        val snap = ws.read(ReadFileRequest("t.py"))
        val error = assertFailsWith<ToolException> { ws.replaceText("t.py", snap.snapshotId, "        pass\n\n    def b(self):", "        pass\n\n    def c(self):") }
        assertTrue(error.recovery.orEmpty().startsWith("Only the spaces on blank lines differ: line(s) 4 contain only spaces."), error.recovery)
    }

    @Test fun longOldTextNamesTheFirstLineThatDiffers() = workspace { root, ws ->
        Files.writeString(root.resolve("d.py"), "def f(x):\n    a = 1\n    b = 2\n    return a + b + x\n\n\ndef g():\n    return f(1)\n")
        val snap = ws.read(ReadFileRequest("d.py"))
        val error = assertFailsWith<ToolException> {
            ws.replaceText("d.py", snap.snapshotId, "def f(x):\n    a = 1\n    b = 3\n    return a + b + x\n", "def f(x):\n    return 3 + x\n")
        }
        assertTrue(error.recovery.orEmpty().startsWith("old_text matches lines 1-2 of the file, then differs: line 3 of the file is \"    b = 2\" but old_text has \"    b = 3\"."), error.recovery)
    }

    @Test fun readSizeScalesWithTheContextWindow() = kotlinx.coroutines.runBlocking {
        assertEquals(ReadLimits(), ReadLimits.forContextWindow(null))
        assertEquals(ReadLimits(), ReadLimits.forContextWindow(32_768))
        val wide = ReadLimits.forContextWindow(131_072)
        assertTrue(wide.lines >= 700 && wide.chars > 20_000, wide.toString())
        val root = Files.createTempDirectory("chiller-read-")
        try {
            Files.writeString(root.resolve("big.py"), (1..471).joinToString("") { "value_%03d = compute(%03d)  # note\n".format(it, it) })
            assertTrue(LocalWorkspace(root).read(ReadFileRequest("big.py")).truncated, "200 lines by default")
            assertFalse(LocalWorkspace(root, readLimits = wide).read(ReadFileRequest("big.py")).truncated, "the whole file at 128k")
        } finally { root.toFile().deleteRecursively() }
    }
}
