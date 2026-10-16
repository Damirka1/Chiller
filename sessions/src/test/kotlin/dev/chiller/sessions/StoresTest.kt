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
    @Test fun corruptInteriorIsNotSkipped() = runTest {
        val root = Files.createTempDirectory("chiller-session")
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
}
