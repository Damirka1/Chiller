package dev.chiller.sessions

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class StoresTest {
    @Test fun tornTailCanRecoverAndAppend() = runTest {
        val root = Files.createTempDirectory("chiller-session")
        val store = JsonlEventStore(root)
        store.append("s1", "run.started", buildJsonObject { put("x", 1) })
        Files.writeString(root.resolve("s1/events.jsonl"), "{partial", java.nio.file.StandardOpenOption.APPEND)
        assertEquals(1, store.read("s1").size)
        store.append("s1", "run.completed", buildJsonObject {})
        assertEquals(listOf(0L, 1L), JsonlEventStore(root).read("s1").map { it.seq })
    }
    @Test fun appendsStayDenseWithoutReparsingHistory() = runTest {
        val root = Files.createTempDirectory("chiller-append")
        val store = JsonlEventStore(root)
        repeat(200) { store.append("s1", "message", buildJsonObject { put("n", it) }) }
        val reopened = JsonlEventStore(root).read("s1")
        assertEquals(200, reopened.size)
        assertEquals((0L until 200L).toList(), reopened.map { it.seq })
        // Cached tail still continues from the last durable record.
        assertEquals(200L, store.append("s1", "run.completed", buildJsonObject {}).seq)
    }

    @Test fun listingReflectsRenamesOfTheSameLength() = runTest {
        val root = Files.createTempDirectory("chiller-list")
        val store = JsonlEventStore(root)
        store.append("s", "message", buildJsonObject { put("role", "user"); put("content", "Prompt") })
        assertEquals("Prompt", store.list().single().title)
        store.append("s", "session.renamed", buildJsonObject { put("title", "First") })
        assertEquals("First", store.list().single().title)
        store.append("s", "session.renamed", buildJsonObject { put("title", "Other") })
        assertEquals("Other", store.list().single().title)
    }

    @Test fun listingCarriesTheLatestWorkspaceBinding() = runTest {
        val root = Files.createTempDirectory("chiller-bound")
        val store = JsonlEventStore(root)
        store.append("s", "message", buildJsonObject { put("role", "user"); put("content", "Prompt") })
        assertNull(store.list().single().workspace)
        store.append("s", "session.workspace", buildJsonObject { put("root", "/p/one") })
        assertEquals("/p/one", store.list().single().workspace)
        store.append("s", "session.workspace", buildJsonObject { put("root", "/p/moved") })
        assertEquals("/p/moved", JsonlEventStore(root).list().single().workspace)
    }

    @Test fun corruptInteriorIsNotSkipped() = runTest {        val root = Files.createTempDirectory("chiller-session")
        Files.createDirectories(root.resolve("s1"))
        Files.writeString(root.resolve("s1/events.jsonl"), "invalid\n")
        assertFailsWith<IllegalStateException> { JsonlEventStore(root).read("s1") }
    }
    @Test fun artifactsAreSessionScoped() = runTest {
        val store = LocalArtifactStore(Files.createTempDirectory("chiller-artifacts"))
        val a = store.put("s1", "hello")
        assertEquals("ello", store.read("s1", a.id, 1, 4))
        assertFails { store.read("s2", a.id) }
        assertFails { store.put("../escape", "no") }
    }

    @Test fun sessionListUsesLatestValidRenameAndFallsBackForInvalidTitlesAfterRestart() = runTest {
        val root = Files.createTempDirectory("chiller-rename")
        val store = JsonlEventStore(root)
        store.append("renamed", "message", buildJsonObject { put("role", "user"); put("content", "Original prompt") })
        store.append("renamed", "session.renamed", buildJsonObject { put("title", "  First title  ") })
        store.append("renamed", "session.renamed", buildJsonObject { put("title", " ") })
        store.append("renamed", "session.renamed", buildJsonObject { put("title", "x".repeat(121)) })
        assertEquals("First title", JsonlEventStore(root).list().single { it.id == "renamed" }.title)

        store.append("legacy", "message", buildJsonObject { put("role", "user"); put("content", "Legacy prompt") })
        store.append("legacy", "session.renamed", buildJsonObject { put("title", "\n  ") })
        assertEquals("Legacy prompt", JsonlEventStore(root).list().single { it.id == "legacy" }.title)
    }

    @Test fun sessionListUsesProjectedFirstUserMessageAfterLogicalDeletion() = runTest {
        val root = Files.createTempDirectory("chiller-projected-list")
        val store = JsonlEventStore(root)
        store.append("s", "message", buildJsonObject { put("role", "user"); put("content", "Deleted sensitive prompt") })
        store.append("s", "history.remove_turn", buildJsonObject { put("start", 0); put("end", 1); put("expectedCount", 1) })
        store.append("s", "message", buildJsonObject { put("role", "user"); put("content", "Visible prompt") })
        assertEquals("Visible prompt", JsonlEventStore(root).list().single().title)
    }
}
