package dev.chiller.sessions

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.chiller.contracts.StoredEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionMaintenanceTest {
    @Test fun `preview measures selected session and delete leaves other sessions untouched`() = runTest {
        val roots = roots()
        val store = JsonlEventStore(roots.sessions)
        store.append("keep", "message", buildJsonObject { put("role", "user"); put("content", "stay") })
        store.append("remove", "message", buildJsonObject { put("role", "user"); put("content", "remove") })
        LocalArtifactStore(roots.artifacts).put("remove", "artifact text")
        LocalArtifactStore(roots.artifacts).put("keep", "keep artifact")
        TraceFile(roots.traces.resolve("remove.jsonl")).append(buildJsonObject { put("round", 1) })

        val maintenance = roots.maintenance()
        val plan = maintenance.previewDelete(setOf("remove")).single()
        assertEquals("remove", plan.preview.sessionId)
        assertEquals(1, plan.preview.eventCount)
        assertEquals(1, plan.preview.artifactCount)
        assertEquals(1, plan.preview.traceCount)
        assertTrue(plan.preview.fileCount >= 3)
        assertTrue(plan.preview.totalBytes > 0)

        val result = maintenance.delete(plan)
        assertTrue(result.complete)
        assertEquals(plan.preview.totalBytes, result.deletedBytes)
        assertFalse(Files.exists(roots.sessions.resolve("remove")))
        assertFalse(Files.exists(roots.artifacts.resolve("remove")))
        assertFalse(Files.exists(roots.traces.resolve("remove.jsonl")))
        assertEquals("stay", JsonlEventStore(roots.sessions).list().single().title)
        assertTrue(Files.exists(roots.artifacts.resolve("keep")))
    }

    @Test fun `active and preserved sessions are rejected for preview and delete`() = runTest {
        val roots = roots()
        JsonlEventStore(roots.sessions).append("s", "message", buildJsonObject {})
        val maintenance = roots.maintenance()
        assertFailsWith<IllegalArgumentException> { maintenance.previewDelete(setOf("s"), activeSessionIds = setOf("s")) }
        assertFailsWith<IllegalArgumentException> { maintenance.previewDelete(setOf("s"), preservedSessionIds = setOf("s")) }
        val plan = maintenance.previewDelete(setOf("s")).single()
        assertFailsWith<IllegalArgumentException> { maintenance.delete(plan, activeSessionIds = setOf("s")) }
    }

    @Test fun `future journal schema is refused`() = runTest {
        val roots = roots()
        val event = StoredEvent(schemaVersion = 2, sessionId = "s", seq = 0, type = "run.started", payload = buildJsonObject {})
        Files.createDirectories(roots.sessions.resolve("s"))
        Files.writeString(roots.sessions.resolve("s/events.jsonl"), Json.encodeToString(event) + "\n")
        val error = assertFailsWith<IllegalArgumentException> { roots.maintenance().previewDelete(setOf("s")) }
        assertTrue(error.message.orEmpty().contains("schema version 2"))
        assertTrue(Files.exists(roots.sessions.resolve("s/events.jsonl")))
    }

    @Test fun `symlinks in selected data are rejected without touching target`() = runTest {
        val roots = roots()
        JsonlEventStore(roots.sessions).append("s", "message", buildJsonObject {})
        val outside = Files.createTempFile("session-outside", ".txt")
        Files.createSymbolicLink(roots.sessions.resolve("s/linked"), outside)
        assertFailsWith<IllegalArgumentException> { roots.maintenance().previewDelete(setOf("s")) }
        assertTrue(Files.exists(outside))
    }

    @Test fun `changed data invalidates the explicit preview`() = runTest {
        val roots = roots()
        JsonlEventStore(roots.sessions).append("s", "message", buildJsonObject {})
        val maintenance = roots.maintenance()
        val plan = maintenance.previewDelete(setOf("s")).single()
        Files.writeString(roots.sessions.resolve("s/extra"), "changed")
        assertFailsWith<IllegalArgumentException> { maintenance.delete(plan) }
        assertTrue(Files.exists(roots.sessions.resolve("s/events.jsonl")))
    }

    @Test fun `orphaned artifact tombstone can be explicitly retried`() = runTest {
        val roots = roots()
        val artifactDir = roots.artifacts.resolve("s")
        Files.createDirectories(artifactDir)
        Files.writeString(artifactDir.resolve("blob"), "old")
        Files.move(artifactDir, roots.artifacts.resolve(".chiller-deleting-s-s"), ATOMIC_MOVE)
        val maintenance = roots.maintenance()
        val plan = maintenance.previewDelete(setOf("s")).single()
        assertTrue(maintenance.delete(plan).complete)
        assertFalse(Files.exists(roots.artifacts.resolve(".chiller-deleting-s-s")))
    }

    @Test fun `interrupted session tombstone stays hidden and can be explicitly retried`() = runTest {
        val roots = roots()
        val original = roots.sessions.resolve("s")
        Files.createDirectories(original)
        val event = StoredEvent(sessionId = "s", seq = 0, type = "message", payload = buildJsonObject {})
        Files.writeString(original.resolve("events.jsonl"), Json.encodeToString(event) + "\n")
        val tombstone = roots.sessions.resolve(".chiller-deleting-s-s")
        Files.move(original, tombstone, ATOMIC_MOVE)

        assertTrue(JsonlEventStore(roots.sessions).list().isEmpty())
        val maintenance = roots.maintenance()
        val plan = maintenance.previewDelete(setOf("s")).single()
        assertTrue(maintenance.delete(plan).complete)
        assertFalse(Files.exists(tombstone))
    }

    @Test fun `optional per-session attachment and capture roots are counted and deleted`() = runTest {
        val roots = roots()
        JsonlEventStore(roots.sessions).append("s", "message", buildJsonObject { put("role", "user") })
        val attachments = roots.sessions.parent.resolve("attachments")
        val captures = roots.sessions.parent.resolve("captures")
        Files.createDirectories(attachments.resolve("s"))
        Files.createDirectories(captures.resolve("s"))
        Files.writeString(attachments.resolve("s/blob"), "private attachment")
        Files.writeString(captures.resolve("s/image.png"), "image bytes")
        val maintenance = SessionMaintenance(roots.sessions, roots.artifacts, roots.traces, listOf(attachments, captures))
        val plan = maintenance.previewDelete(setOf("s")).single()
        assertTrue(plan.preview.fileCount >= 3)
        assertTrue(plan.preview.totalBytes >= "private attachment".length + "image bytes".length)
        assertTrue(maintenance.delete(plan).complete)
        assertFalse(Files.exists(attachments.resolve("s")))
        assertFalse(Files.exists(captures.resolve("s")))
    }

    private fun roots(): Roots {
        val base = Files.createTempDirectory("chiller-maintenance").toRealPath()
        return Roots(base.resolve("sessions"), base.resolve("artifacts"), base.resolve("traces"))
    }

    private data class Roots(val sessions: Path, val artifacts: Path, val traces: Path) {
        fun maintenance() = SessionMaintenance(sessions, artifacts, traces)
    }
}
