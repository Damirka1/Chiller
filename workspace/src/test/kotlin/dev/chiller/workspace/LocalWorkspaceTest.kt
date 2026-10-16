package dev.chiller.workspace

import dev.chiller.contracts.*
import kotlinx.serialization.json.*
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
        expectError("INVALID_RANGE") { ws.read(ReadFileRequest("many.txt", 206)) }
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
            val entries = result.data!!.jsonObject["entries"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }
            assertTrue("kept.txt" in entries)
            assertFalse("ignored.txt" in entries)
            assertFalse("private.txt" in entries)
        } finally { root.toFile().deleteRecursively() }
    }

    private fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
