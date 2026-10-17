package dev.chiller.workspace

import dev.chiller.contracts.*
import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalWorkspaceTest {
    private suspend fun expectError(code: String, block: suspend () -> Unit) {
        try { block(); error("Expected ToolException($code)") }
        catch (e: ToolException) { assertEquals(code, e.code) }
    }

    private fun workspace(block: suspend (Path, LocalWorkspace) -> Unit) {
        kotlinx.coroutines.runBlocking {
            val root = Files.createTempDirectory("chiller-workspace-")
            try { block(root, LocalWorkspace(root)) } finally { root.toFile().deleteRecursively() }
        }
    }

    private class MemoryStores : EventStore, ArtifactStore {
        private val events = mutableListOf<StoredEvent>()
        private val blobs = mutableMapOf<String, String>()
        override suspend fun append(sessionId: String, type: String, payload: JsonObject): StoredEvent {
            val event = StoredEvent(sessionId = sessionId, seq = events.size.toLong(), type = type, payload = payload)
            events += event
            return event
        }
        override suspend fun read(sessionId: String) = events.filter { it.sessionId == sessionId }
        override suspend fun list() = emptyList<SessionInfo>()
        override suspend fun put(sessionId: String, content: String) = ArtifactRef(UUID.randomUUID().toString(), sessionId, content.length.toLong()).also { blobs[it.id] = content }
        override suspend fun read(sessionId: String, artifactId: String, offset: Int, limit: Int): String = blobs.getValue(artifactId).substring(offset.coerceAtMost(blobs.getValue(artifactId).length), (offset + limit).coerceAtMost(blobs.getValue(artifactId).length))
    }

    @Test fun undoOfDeleteRestoresTheFileAndRefusesAnOccupiedPath() = workspace { root, ws ->
        Files.writeString(root.resolve("notes.txt"), "keep me\n")
        val snapshot = ws.read(ReadFileRequest("notes.txt"))
        val deleted = ws.delete("notes.txt", snapshot.snapshotId)
        assertFalse(Files.exists(root.resolve("notes.txt")))
        ws.undo(deleted.id)
        assertEquals("keep me\n", Files.readString(root.resolve("notes.txt")))

        val again = ws.delete("notes.txt", ws.read(ReadFileRequest("notes.txt")).snapshotId)
        Files.writeString(root.resolve("notes.txt"), "someone else's file\n")
        expectError("UNDO_CONFLICT") { ws.undo(again.id) }
        assertEquals("someone else's file\n", Files.readString(root.resolve("notes.txt")))
    }

    @Test fun rejectsStaleSnapshotWithoutWriting() = workspace { root, ws ->
        Files.writeString(root.resolve("a.txt"), "old\n")
        val snapshot = ws.read(ReadFileRequest("a.txt"))
        Files.writeString(root.resolve("a.txt"), "external\n")
        expectError("STALE_SNAPSHOT") {
            ws.edit(EditFileRequest("a.txt", snapshot.snapshotId, listOf(FileEdit(startLine = 1, content = "new"))))
        }
        assertEquals("external\n", Files.readString(root.resolve("a.txt")))
    }

    @Test fun editsOneOfRepeatedLinesByObservedLineNumber() = workspace { root, ws ->
        Files.writeString(root.resolve("a.txt"), "same\nsame\nsame\n")
        val snapshot = ws.read(ReadFileRequest("a.txt", 2, 2))
        ws.edit(EditFileRequest("a.txt", snapshot.snapshotId, listOf(FileEdit(startLine = 2, endLine = 2, content = "chosen"))))
        assertEquals("same\nchosen\nsame\n", Files.readString(root.resolve("a.txt")))
    }

    @Test fun returnsUnifiedDiffWithChangedLineAndContext() = workspace { root, ws ->
        Files.writeString(root.resolve("a.txt"), "a\nb\nc\n")
        val snapshot = ws.read(ReadFileRequest("a.txt"))
        val change = ws.edit(EditFileRequest("a.txt", snapshot.snapshotId, listOf(FileEdit(startLine = 2, content = "B"))))
        assertTrue(change.diff.contains("@@ -1,3 +1,3 @@"))
        assertTrue(change.diff.contains(" a\n-b\n+B\n c\n"))
    }

    @Test fun preservesPosixExecutablePermissionOnReplace() = workspace { root, ws ->
        val file = root.resolve("script.sh")
        Files.writeString(file, "echo old\n")
        val view = Files.getFileAttributeView(file, PosixFileAttributeView::class.java)
        org.junit.jupiter.api.Assumptions.assumeTrue(view != null, "POSIX permissions are unavailable")
        val permissions = PosixFilePermissions.fromString("rwxr-x---")
        Files.setPosixFilePermissions(file, permissions)
        val snapshot = ws.read(ReadFileRequest("script.sh"))
        ws.edit(EditFileRequest("script.sh", snapshot.snapshotId, listOf(FileEdit(startLine = 1, content = "echo new"))))
        assertEquals(permissions, Files.getPosixFilePermissions(file))
    }

    @Test fun keepsCrLfBomAndMissingEofNewline() = workspace { root, ws ->
        val initial = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "one\r\ntwo".toByteArray()
        Files.write(root.resolve("a.txt"), initial)
        val snapshot = ws.read(ReadFileRequest("a.txt"))
        ws.edit(EditFileRequest("a.txt", snapshot.snapshotId, listOf(FileEdit(startLine = 2, content = "changed"))))
        val actual = Files.readAllBytes(root.resolve("a.txt"))
        assertContentEquals(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "one\r\nchanged".toByteArray(), actual)
    }

    @Test fun emptyFileCanReceiveFirstLine() = workspace { root, ws ->
        Files.write(root.resolve("empty"), byteArrayOf())
        val snapshot = ws.read(ReadFileRequest("empty"))
        ws.edit(EditFileRequest("empty", snapshot.snapshotId, listOf(FileEdit(op = "insert", beforeLine = 1, content = "first"))))
        assertEquals("first", Files.readString(root.resolve("empty")))
    }

    @Test fun rejectsTraversalAndSymlinkEscape() = workspace { root, ws ->
        expectError("PATH_OUTSIDE_ROOT") { ws.read(ReadFileRequest("../escape")) }
        val outside = Files.createTempFile("outside", ".txt")
        try {
            Files.createSymbolicLink(root.resolve("link"), outside)
            expectError("SYMLINK_NOT_ALLOWED") { ws.read(ReadFileRequest("link")) }
        } finally { Files.deleteIfExists(outside) }
    }

    @Test fun undoRefusesToOverwriteLaterUserEdit() = workspace { root, ws ->
        Files.writeString(root.resolve("a.txt"), "before")
        val snapshot = ws.read(ReadFileRequest("a.txt"))
        val change = ws.edit(EditFileRequest("a.txt", snapshot.snapshotId, listOf(FileEdit(startLine = 1, content = "after"))))
        Files.writeString(root.resolve("a.txt"), "user edit")
        expectError("UNDO_CONFLICT") { ws.undo(change.id) }
        assertTrue(Files.readString(root.resolve("a.txt")) == "user edit")
    }

    @Test fun undoOfDeleteRefusesToOverwriteRecreatedPath() = workspace { root, ws ->
        Files.writeString(root.resolve("a.txt"), "before")
        val snapshot = ws.read(ReadFileRequest("a.txt"))
        val change = ws.delete("a.txt", snapshot.snapshotId)
        Files.writeString(root.resolve("a.txt"), "user-created")
        expectError("UNDO_CONFLICT") { ws.undo(change.id) }
        assertEquals("user-created", Files.readString(root.resolve("a.txt")))
    }

    @Test fun readMarksOnlyReturnedLinesAsObserved() = workspace { root, ws ->
        Files.writeString(root.resolve("many.txt"), (1..205).joinToString("\n") { "line-$it" })
        val read = ws.read(ReadFileRequest("many.txt"))
        assertTrue(read.truncated)
        assertEquals(200, read.content.lines().size)
        expectError("UNOBSERVED_RANGE") { ws.edit(EditFileRequest("many.txt", read.snapshotId, listOf(FileEdit(startLine = 201, content = "changed")))) }
        val beyond = ws.read(ReadFileRequest("many.txt", 206))
        assertEquals("", beyond.content)
        assertEquals(205, beyond.totalLines)
        assertTrue(beyond.truncated)
    }

    @Test fun readClampsEndLinePastEofInsteadOfFailing() = workspace { root, ws ->
        Files.writeString(root.resolve("a.txt"), "one\ntwo\nthree\n")
        val clamped = ws.read(ReadFileRequest("a.txt", 2, 99))
        assertEquals("two\nthree", clamped.content)
        assertEquals(3, clamped.totalLines)
        assertTrue(clamped.truncated)
        val beyond = ws.read(ReadFileRequest("a.txt", 10))
        assertEquals("", beyond.content)
        assertEquals(3, beyond.totalLines)
        assertTrue(beyond.truncated)
        expectError("UNOBSERVED_RANGE") { ws.edit(EditFileRequest("a.txt", beyond.snapshotId, listOf(FileEdit(startLine = 1, content = "x")))) }
    }

    @Test fun readsEmptyFileWithoutSpecialCasingEndLine() = workspace { root, ws ->
        Files.write(root.resolve("empty"), byteArrayOf())
        val snapshot = ws.read(ReadFileRequest("empty"))
        assertEquals(0, snapshot.totalLines)
        assertEquals("", snapshot.content)
        val clamped = ws.read(ReadFileRequest("empty", 1, 5))
        assertEquals("", clamped.content)
        assertTrue(clamped.truncated)
        expectError("INVALID_RANGE") { ws.read(ReadFileRequest("empty", 2)) }
    }

    @Test fun chainedEditsReuseReturnedSnapshotWithoutRereading() = workspace { root, ws ->
        Files.writeString(root.resolve("a.txt"), "one\ntwo\nthree\n")
        val snapshot = ws.read(ReadFileRequest("a.txt"))
        val first = ws.edit(EditFileRequest("a.txt", snapshot.snapshotId, listOf(FileEdit(startLine = 2, content = "TWO"))))
        val next = first.snapshotId ?: error("A successful edit must return a snapshot id")
        assertTrue(next != snapshot.snapshotId)
        assertEquals("one\nTWO\nthree\n", Files.readString(root.resolve("a.txt")))
        val second = ws.edit(EditFileRequest("a.txt", next, listOf(FileEdit(startLine = 3, content = "THREE"))))
        assertEquals("one\nTWO\nTHREE\n", Files.readString(root.resolve("a.txt")))
        assertTrue(second.snapshotId != null)
    }

    @Test fun chainedSnapshotOnlyObservesLinesMappedThroughEdits() = workspace { root, ws ->
        Files.writeString(root.resolve("a.txt"), "1\n2\n3\n4\n5\n")
        val snapshot = ws.read(ReadFileRequest("a.txt", 1, 3))
        val change = ws.edit(EditFileRequest("a.txt", snapshot.snapshotId, listOf(FileEdit(startLine = 2, endLine = 3, content = "x\ny"))))
        val next = change.snapshotId ?: error("A successful edit must return a snapshot id")
        assertEquals("1\nx\ny\n4\n5\n", Files.readString(root.resolve("a.txt")))
        // Replacement lines 2-3 were authored, so they stay observed; untouched line 4 was never read.
        val again = ws.edit(EditFileRequest("a.txt", next, listOf(FileEdit(startLine = 2, content = "X"))))
        assertEquals("1\nX\ny\n4\n5\n", Files.readString(root.resolve("a.txt")))
        val afterAuthored = again.snapshotId ?: error("A successful edit must return a snapshot id")
        expectError("UNOBSERVED_RANGE") { ws.edit(EditFileRequest("a.txt", afterAuthored, listOf(FileEdit(startLine = 4, content = "4?")))) }
        assertEquals("1\nX\ny\n4\n5\n", Files.readString(root.resolve("a.txt")))
    }

    @Test fun insertAndDeleteShiftObservedLines() = workspace { root, ws ->
        Files.writeString(root.resolve("a.txt"), "1\n2\n")
        val snapshot = ws.read(ReadFileRequest("a.txt", 1, 1))
        val inserted = ws.edit(EditFileRequest("a.txt", snapshot.snapshotId, listOf(FileEdit(op = "insert", beforeLine = 2, content = "a\nb"))))
        assertEquals("1\na\nb\n2\n", Files.readString(root.resolve("a.txt")))
        val afterInsert = inserted.snapshotId ?: error("A successful edit must return a snapshot id")
        val edited = ws.edit(EditFileRequest("a.txt", afterInsert, listOf(FileEdit(startLine = 3, content = "B"))))
        assertEquals("1\na\nB\n2\n", Files.readString(root.resolve("a.txt")))
        val afterAuthored = edited.snapshotId ?: error("A successful edit must return a snapshot id")
        // "2" moved from line 2 to line 4 through the insertion and was never observed.
        expectError("UNOBSERVED_RANGE") { ws.edit(EditFileRequest("a.txt", afterAuthored, listOf(FileEdit(startLine = 4, content = "two")))) }

        Files.writeString(root.resolve("d.txt"), "1\n2\n3\n4\n")
        val readAll = ws.read(ReadFileRequest("d.txt"))
        val deleted = ws.edit(EditFileRequest("d.txt", readAll.snapshotId, listOf(FileEdit(op = "delete", startLine = 2, endLine = 3))))
        assertEquals("1\n4\n", Files.readString(root.resolve("d.txt")))
        val afterDelete = deleted.snapshotId ?: error("A successful edit must return a snapshot id")
        // Line 4 shifted up to line 2 and remains observed.
        ws.edit(EditFileRequest("d.txt", afterDelete, listOf(FileEdit(startLine = 2, content = "four"))))
        assertEquals("1\nfour\n", Files.readString(root.resolve("d.txt")))
    }

    @Test fun chainedSnapshotStillDetectsExternalWrite() = workspace { root, ws ->
        Files.writeString(root.resolve("a.txt"), "one\ntwo\n")
        val snapshot = ws.read(ReadFileRequest("a.txt"))
        val first = ws.edit(EditFileRequest("a.txt", snapshot.snapshotId, listOf(FileEdit(startLine = 1, content = "ONE"))))
        val next = first.snapshotId ?: error("A successful edit must return a snapshot id")
        Files.writeString(root.resolve("a.txt"), "external\n")
        expectError("STALE_SNAPSHOT") { ws.edit(EditFileRequest("a.txt", next, listOf(FileEdit(startLine = 2, content = "TWO")))) }
        assertEquals("external\n", Files.readString(root.resolve("a.txt")))
    }

    @Test fun searchReturnsCompactMatchesWithOptionalContext() = kotlinx.coroutines.runBlocking {
        val check = runCatching { ProcessBuilder("rg", "--version").start().waitFor() == 0 }.getOrDefault(false)
        org.junit.jupiter.api.Assumptions.assumeTrue(check, "ripgrep is required for workspace search")
        val root = Files.createTempDirectory("chiller-search-")
        try {
            Files.writeString(root.resolve("a.txt"), "alpha\nmatch-here   \ntrailing   \nomega\n")
            Files.createDirectories(root.resolve("sub"))
            Files.writeString(root.resolve("sub/b.txt"), "match-sub\n")
            val ws = LocalWorkspace(root)
            val plain = ws.search("match", limit = 10)
            assertTrue(plain.ok)
            val matches = plain.data!!.jsonObject["matches"]!!.jsonArray.map { it.jsonObject }
            assertEquals(2, matches.size)
            val byPath = matches.associateBy { it["path"]!!.jsonPrimitive.content }
            val main = byPath.getValue("a.txt")
            assertEquals(2, main["line"]!!.jsonPrimitive.int)
            assertEquals("match-here", main["text"]!!.jsonPrimitive.content)
            assertFalse(main.containsKey("context"))
            assertEquals(1, byPath.getValue("sub/b.txt")["line"]!!.jsonPrimitive.int)
            assertFalse(plain.truncated)
            val limited = ws.search("match", limit = 1)
            assertEquals(1, limited.data!!.jsonObject["matches"]!!.jsonArray.size)
            assertTrue(limited.truncated)
            val withContext = ws.search("match-here", limit = 10, context = 1)
            val entry = withContext.data!!.jsonObject["matches"]!!.jsonArray.single().jsonObject
            val context = entry["context"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("alpha", "trailing"), context)
            assertEquals("match-here", entry["text"]!!.jsonPrimitive.content)
            Files.writeString(root.resolve("long.txt"), "needle" + "x".repeat(500) + "\n")
            val longText = ws.search("needle", limit = 5).data!!.jsonObject["matches"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
            assertEquals(401, longText.length)
            assertTrue(longText.endsWith("…"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun rejectsSingleLineLargerThanVisibleReadLimit() = workspace { root, ws ->
        Files.writeString(root.resolve("huge.txt"), "x".repeat(6_001))
        expectError("FILE_LINE_TOO_LARGE") { ws.read(ReadFileRequest("huge.txt")) }
    }

    @Test fun restoresUndoFromDurableJournalAfterRestart() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("chiller-recovery-")
        try {
            val stores = MemoryStores()
            Files.writeString(root.resolve("a.txt"), "before")
            val first = LocalWorkspace(root, stores, stores, "s")
            val snap = first.read(ReadFileRequest("a.txt"))
            val change = first.edit(EditFileRequest("a.txt", snap.snapshotId, listOf(FileEdit(startLine = 1, content = "after"))))
            val reopened = LocalWorkspace(root, stores, stores, "s")
            assertEquals(emptyList(), reopened.recoverPending())
            reopened.undo(change.id)
            assertEquals("before", Files.readString(root.resolve("a.txt")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun reconcilesCompletedPrepareWithoutRepeatingWrite() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("chiller-reconcile-")
        try {
            val stores = MemoryStores()
            val before = "before".toByteArray(); val after = "after".toByteArray()
            val beforeArtifact = stores.put("s", Base64.getEncoder().encodeToString(before))
            Files.write(root.resolve("a.txt"), after)
            stores.append("s", "workspace.prepare", buildJsonObject {
                put("changeId", "prepared-1"); put("path", "a.txt"); put("operation", "write")
                put("beforeHash", sha(before)); put("afterHash", sha(after)); put("beforeArtifact", beforeArtifact.id)
            })
            val reopened = LocalWorkspace(root, stores, stores, "s")
            assertEquals(listOf("prepared-1:COMMITTED"), reopened.recoverPending())
            assertContentEquals(after, Files.readAllBytes(root.resolve("a.txt")))
            reopened.undo("prepared-1")
            assertContentEquals(before, Files.readAllBytes(root.resolve("a.txt")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun recoversAndUndoesMoveBetweenExistingDirectories() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("chiller-move-recovery-")
        try {
            val source = root.resolve("src/item.txt")
            val destination = root.resolve("archive/2026/item.txt")
            Files.createDirectories(source.parent)
            Files.createDirectories(destination.parent)
            val bytes = "preserve these bytes\r\n".toByteArray()
            Files.write(source, bytes)
            val stores = MemoryStores()
            val beforeRef = stores.put("s", Base64.getEncoder().encodeToString(bytes))
            val changeId = "move-before-crash"
            stores.append("s", "workspace.prepare", buildJsonObject {
                put("changeId", changeId)
                put("path", "src/item.txt")
                put("destination", "archive/2026/item.txt")
                put("operation", "move")
                put("beforeHash", sha(bytes))
                put("afterHash", sha(bytes))
                put("beforeArtifact", beforeRef.id)
            })
            // Model a crash after the no-clobber link and source unlink but before journal commit.
            Files.createLink(destination, source)
            Files.delete(source)

            val reopened = LocalWorkspace(root, stores, stores, "s")
            assertEquals(listOf("$changeId:COMMITTED"), reopened.recoverPending())
            assertEquals("preserve these bytes\r\n", Files.readString(destination))
            reopened.undo(changeId)
            assertEquals("preserve these bytes\r\n", Files.readString(source))
            assertFalse(Files.exists(destination))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun crossDirectoryMoveRejectsOccupiedDestinationAndUndoConflict() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("chiller-move-conflict-")
        try {
            Files.createDirectories(root.resolve("source"))
            Files.createDirectories(root.resolve("target/nested"))
            Files.writeString(root.resolve("source/item.txt"), "original")
            Files.writeString(root.resolve("target/nested/item.txt"), "keep destination")
            val ws = LocalWorkspace(root)
            var snapshot = ws.read(ReadFileRequest("source/item.txt"))
            expectError("ALREADY_EXISTS") {
                ws.move("source/item.txt", snapshot.snapshotId, "target/nested/item.txt")
            }
            assertEquals("original", Files.readString(root.resolve("source/item.txt")))
            assertEquals("keep destination", Files.readString(root.resolve("target/nested/item.txt")))

            Files.delete(root.resolve("target/nested/item.txt"))
            val change = ws.move("source/item.txt", snapshot.snapshotId, "target/nested/item.txt")
            assertFalse(Files.exists(root.resolve("source/item.txt")))
            assertEquals("original", Files.readString(root.resolve("target/nested/item.txt")))
            Files.writeString(root.resolve("source/item.txt"), "user replacement")
            expectError("UNDO_CONFLICT") { ws.undo(change.id) }
            assertEquals("user replacement", Files.readString(root.resolve("source/item.txt")))
            assertEquals("original", Files.readString(root.resolve("target/nested/item.txt")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun createFileMakesAndJournalsMissingParentsAndUndoLeavesEmptyDirectories() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("chiller-nested-create-")
        try {
            val stores = MemoryStores()
            val ws = LocalWorkspace(root, stores, stores, "s")
            val path = "src/main/kotlin/dev/example/Hello.kt"
            val change = ws.create(path, "package dev.example\n")
            assertEquals("package dev.example\n", Files.readString(root.resolve(path)))
            val events = stores.read("s")
            val prepare = events.single { it.type == "workspace.prepare" }.payload
            val planned = prepare["plannedDirectories"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(
                listOf("src", "src/main", "src/main/kotlin", "src/main/kotlin/dev", "src/main/kotlin/dev/example"),
                planned,
            )
            val commit = events.single { it.type == "workspace.commit" }.payload
            assertEquals(planned, commit["createdDirectories"]!!.jsonArray.map { it.jsonPrimitive.content })

            ws.undo(change.id)
            assertFalse(Files.exists(root.resolve(path)))
            assertTrue(Files.isDirectory(root.resolve("src/main/kotlin/dev/example")))
            assertTrue(Files.list(root.resolve("src/main/kotlin/dev/example")).use { !it.findAny().isPresent })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun createDirectoryIsIdempotentAndRejectsFilesAndSymlinkParents() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("chiller-create-directory-")
        val outside = Files.createTempDirectory("chiller-create-directory-outside-")
        try {
            val stores = MemoryStores()
            val ws = LocalWorkspace(root, stores, stores, "s")
            val made = ws.createDirectory("nested/a/b")
            assertTrue(made.ok)
            assertEquals(true, made.data!!.jsonObject["created"]!!.jsonPrimitive.boolean)
            assertEquals(listOf("nested", "nested/a", "nested/a/b"), stores.read("s").single { it.type == "workspace.prepare" }.payload["plannedDirectories"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("nested", "nested/a", "nested/a/b"), stores.read("s").single { it.type == "workspace.commit" }.payload["createdDirectories"]!!.jsonArray.map { it.jsonPrimitive.content })
            val repeated = ws.createDirectory("nested/a/b")
            assertTrue(repeated.ok)
            assertEquals(false, repeated.data!!.jsonObject["created"]!!.jsonPrimitive.boolean)
            assertEquals(2, stores.read("s").size, "An idempotent call should not add journal entries")

            Files.writeString(root.resolve("blocker"), "preserve")
            expectError("NOT_DIRECTORY") { ws.create("blocker/child/file.txt", "content") }
            expectError("ALREADY_EXISTS") { ws.create("blocker", "replace") }
            assertEquals("preserve", Files.readString(root.resolve("blocker")))

            val link = root.resolve("outside-link")
            try {
                Files.createSymbolicLink(link, outside)
            } catch (_: UnsupportedOperationException) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable")
            } catch (_: IOException) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable")
            } catch (_: SecurityException) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable")
            }
            expectError("SYMLINK_NOT_ALLOWED") { ws.create("outside-link/new/file.txt", "content") }
            expectError("SYMLINK_NOT_ALLOWED") { ws.createDirectory("outside-link/new") }
            assertFalse(Files.exists(outside.resolve("new")))
        } finally {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test fun recoversCompletedDirectoryCreationWithoutReplayingIt() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("chiller-mkdir-recovery-")
        try {
            Files.createDirectories(root.resolve("one/two"))
            val stores = MemoryStores()
            stores.append("s", "workspace.prepare", buildJsonObject {
                put("changeId", "mkdir-before-crash")
                put("path", "one/two")
                put("operation", "mkdir")
                put("plannedDirectories", buildJsonArray {
                    add(JsonPrimitive("one"))
                    add(JsonPrimitive("one/two"))
                })
            })
            val ws = LocalWorkspace(root, stores, stores, "s")
            assertEquals(listOf("mkdir-before-crash:COMMITTED"), ws.recoverPending())
            assertEquals(1L, Files.list(root).use { it.count() })
            val commit = stores.read("s").single { it.type == "workspace.commit" }.payload
            assertEquals(listOf("one", "one/two"), commit["createdDirectories"]!!.jsonArray.map { it.jsonPrimitive.content })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun describeFileSniffsKnownBinaryTypesAndReportsLargeMetadata() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("chiller-file-presentation-")
        try {
            val signatures = listOf(
                "png.bin" to (byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) to "image/png"),
                "jpeg.bin" to (byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00) to "image/jpeg"),
                "gif.bin" to ("GIF89a".toByteArray() to "image/gif"),
                "pdf.bin" to ("%PDF-1.7".toByteArray() to "application/pdf"),
            )
            signatures.forEach { (name, pair) ->
                val bytes = pair.first + byteArrayOf(1, 2, 3)
                Files.write(root.resolve(name), bytes)
                val presentation = LocalWorkspace(root).describeFile(name)
                assertEquals(name, presentation.path)
                assertEquals(name, presentation.name)
                assertEquals(pair.second, presentation.mimeType)
                assertEquals(bytes.size.toLong(), presentation.sizeBytes)
                assertFalse(presentation.path.startsWith(root.toString()))
            }

            val large = root.resolve("records.csv")
            val csv = "name,value\n" + "alpha,42\n".repeat(300_000)
            Files.writeString(large, csv)
            val presentation = LocalWorkspace(root).describeFile("records.csv")
            assertEquals("text/csv", presentation.mimeType)
            assertEquals(Files.size(large), presentation.sizeBytes)
            expectError("FILE_TOO_LARGE") { LocalWorkspace(root).read(ReadFileRequest("records.csv")) }

            Files.writeString(root.resolve("unknown.custom"), "plain bytes")
            assertEquals("application/octet-stream", LocalWorkspace(root).describeFile("unknown.custom").mimeType)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun describeFileRejectsOutsideMissingSymlinkAndNonRegularPaths() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("chiller-file-presentation-path-")
        val outside = Files.createTempFile("chiller-presentation-outside-", ".png")
        try {
            Files.writeString(root.resolve("regular.txt"), "text")
            Files.createDirectory(root.resolve("directory"))
            val ws = LocalWorkspace(root)
            expectError("PATH_OUTSIDE_ROOT") { ws.describeFile("../${outside.fileName}") }
            expectError("NOT_FOUND") { ws.describeFile("missing.png") }
            expectError("UNSUPPORTED_FILE") { ws.describeFile("directory") }
            val link = root.resolve("linked.png")
            try {
                Files.createSymbolicLink(link, outside)
            } catch (_: UnsupportedOperationException) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable")
            } catch (_: IOException) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable")
            } catch (_: SecurityException) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable")
            }
            expectError("SYMLINK_NOT_ALLOWED") { ws.describeFile("linked.png") }
        } finally {
            root.toFile().deleteRecursively()
            Files.deleteIfExists(outside)
        }
    }

    @Test fun listHonorsGitAndChillerIgnoreFiles() = kotlinx.coroutines.runBlocking {
        val check = runCatching { ProcessBuilder("rg", "--version").start().waitFor() == 0 }.getOrDefault(false)
        org.junit.jupiter.api.Assumptions.assumeTrue(check, "ripgrep is required for workspace listing")
        val root = Files.createTempDirectory("chiller-ignore-")
        try {
            Files.writeString(root.resolve(".gitignore"), "ignored.txt\n")
            Files.writeString(root.resolve(".chillerignore"), "private.txt\n")
            Files.writeString(root.resolve("kept.txt"), "ok")
            Files.writeString(root.resolve("ignored.txt"), "ignored")
            Files.writeString(root.resolve("private.txt"), "private")
            val result = LocalWorkspace(root).list(limit = 50)
            assertTrue(result.ok)
            val entries = result.data!!.jsonObject["entries"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue(entries.all { !it.startsWith("/") })
            assertTrue("kept.txt" in entries)
            assertFalse("ignored.txt" in entries)
            assertFalse("private.txt" in entries)
            val limited = LocalWorkspace(root).list(limit = 2)
            assertEquals(2, limited.data!!.jsonObject["entries"]!!.jsonArray.size)
            assertTrue(limited.truncated)
            val narrowed = LocalWorkspace(root).list(".", "*.txt", 50)
            assertEquals(setOf("kept.txt"), narrowed.data!!.jsonObject["entries"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun listPagesUseOneBoundedCaptureAndRejectDifferentQuery() = kotlinx.coroutines.runBlocking {
        val check = runCatching { ProcessBuilder("rg", "--version").start().waitFor() == 0 }.getOrDefault(false)
        org.junit.jupiter.api.Assumptions.assumeTrue(check, "ripgrep is required for workspace listing")
        val root = Files.createTempDirectory("chiller-list-pages-")
        try {
            (1..5).forEach { Files.writeString(root.resolve("$it.txt"), "file $it") }
            val ws = LocalWorkspace(root)
            val first = ws.listPage(limit = 2)
            assertTrue(first.ok)
            assertTrue(first.truncated)
            val cursor = first.nextCursor ?: error("Expected a continuation cursor")
            val mismatch = ws.listPage(glob = "*.txt", limit = 2, cursor = cursor)
            assertEquals("CURSOR_QUERY_MISMATCH", mismatch.error?.code)

            Files.writeString(root.resolve("6.txt"), "created after page one")
            val collected = mutableListOf<String>()
            collected += first.data!!.jsonObject["entries"]!!.jsonArray.map { it.jsonPrimitive.content }
            var next: String? = cursor
            while (next != null) {
                val page = ws.listPage(limit = 2, cursor = next)
                assertTrue(page.ok, page.error?.message.orEmpty())
                collected += page.data!!.jsonObject["entries"]!!.jsonArray.map { it.jsonPrimitive.content }
                next = page.nextCursor
            }
            assertEquals((1..5).map { "$it.txt" }.toSet(), collected.toSet())
            assertEquals(collected.size, collected.toSet().size, "A captured page sequence must not repeat entries")
            assertEquals("CURSOR_EXPIRED", ws.listPage(cursor = "unknown-page-token").error?.code)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun searchPagesReturnCapturedMatchesAfterWorkspaceChanges() = kotlinx.coroutines.runBlocking {
        val check = runCatching { ProcessBuilder("rg", "--version").start().waitFor() == 0 }.getOrDefault(false)
        org.junit.jupiter.api.Assumptions.assumeTrue(check, "ripgrep is required for workspace search")
        val root = Files.createTempDirectory("chiller-search-pages-")
        try {
            Files.writeString(root.resolve("hits.txt"), (1..5).joinToString("\n") { "needle-$it" })
            val ws = LocalWorkspace(root)
            val first = ws.searchPage("needle", limit = 2)
            val cursor = first.nextCursor ?: error("Expected a continuation cursor")
            Files.writeString(root.resolve("hits.txt"), "replacement with no results")

            val mismatch = ws.searchPage("different", limit = 2, cursor = cursor)
            assertEquals("CURSOR_QUERY_MISMATCH", mismatch.error?.code)
            val collected = first.data!!.jsonObject["matches"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }.toMutableList()
            var next: String? = cursor
            while (next != null) {
                val page = ws.searchPage("needle", limit = 2, cursor = next)
                assertTrue(page.ok, page.error?.message.orEmpty())
                collected += page.data!!.jsonObject["matches"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
                next = page.nextCursor
            }
            assertEquals((1..5).map { "needle-$it" }, collected)
            assertEquals("CURSOR_EXPIRED", ws.searchPage("needle", cursor = "unknown-page-token").error?.code)
        } finally { root.toFile().deleteRecursively() }
    }

    private fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
