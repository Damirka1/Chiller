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

    @Test fun aLongRequestIsTitledByItsFirstSentenceAndKeptWholeForHover() = runTest {
        val root = Files.createTempDirectory("chiller-title")
        val store = JsonlEventStore(root)
        val request = "Create dir deepseek and do it. Create a complete landing page with 77 icons,\nchecks and previews."
        store.append("s", "message", buildJsonObject { put("role", "user"); put("content", request) })
        val info = store.list().single()
        assertEquals("Create dir deepseek and do it", info.title)
        assertEquals("Create dir deepseek and do it. Create a complete landing page with 77 icons, checks and previews.", info.summary)
    }

    @Test fun titlesKeepShortAndUnsplittableRequestsReadable() {
        assertEquals("Привет! Слушай, а мы можем сыграть в игру", dev.chiller.contracts.titleFromRequest("Привет! Слушай, а мы можем сыграть в игру"), "a greeting is not a title")
        assertEquals("Почини расчёт скидки", dev.chiller.contracts.titleFromRequest("Почини расчёт скидки\nв файле cart.py версии 1.2"))
        assertEquals("Обнови до v1.2.3 и проверь", dev.chiller.contracts.titleFromRequest("Обнови до v1.2.3 и проверь"), "dots inside a version do not end a sentence")
        val long = dev.chiller.contracts.titleFromRequest("x".repeat(100))
        assertEquals(64, long.length); assertTrue(long.endsWith("…"))
    }

    @Test fun searchFindsShownMessagesWithASnippetAroundTheMatch() = runTest {
        val root = Files.createTempDirectory("chiller-search")
        val store = JsonlEventStore(root)
        fun message(role: String, content: String) = buildJsonObject { put("role", role); put("content", content) }
        store.append("old", "message", message("user", "Как настроить Gradle?"))
        store.append("old", "message", message("assistant", "Добавьте плагин Kotlin.\nПотом настройте Gradle wrapper."))
        store.append("new", "message", message("user", "Почини тесты парсера CSV"))
        store.append("new", "message", message("tool", "gradle test output"))

        val hits = store.search("gradle")
        assertEquals(listOf("old"), hits.map { it.sessionId }, "tool output is not searched")
        val hit = hits.single()
        assertEquals(2, hit.matches)
        assertEquals("user", hit.role); assertEquals(0, hit.messageIndex)
        assertEquals("Gradle", hit.snippet.substring(hit.matchStart, hit.matchStart + hit.matchLength))
        assertEquals(listOf("new"), store.search("ПАРСЕРА").map { it.sessionId }, "case does not matter")
        assertTrue(store.search("x").isEmpty(), "one character is too little to search")
        // A later message is found without restarting: the text cache follows the journal.
        store.append("new", "message", message("assistant", "Готово, Gradle зелёный"))
        assertEquals(setOf("old", "new"), store.search("gradle").map { it.sessionId }.toSet())
        // Long messages give a window around the match.
        store.append("long", "message", message("user", "a".repeat(500) + " needle " + "b".repeat(500)))
        val long = store.search("needle").single()
        assertTrue(long.snippet.startsWith("…") && long.snippet.endsWith("…") && long.snippet.length < 140)
        assertEquals("needle", long.snippet.substring(long.matchStart, long.matchStart + long.matchLength))
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
        // A dialog in its own working copy belongs to the project it was made from, until the copy is removed.
        store.append("s", "session.worktree", buildJsonObject { put("repo", "/p/moved"); put("path", "/data/wt/s/moved"); put("branch", "chiller/s") })
        store.append("s", "session.workspace", buildJsonObject { put("root", "/data/wt/s/moved") })
        assertEquals("/p/moved", store.list().single().origin)
        store.append("s", "session.worktree", buildJsonObject { put("repo", "/p/moved"); put("path", "/data/wt/s/moved"); put("removed", true) })
        store.append("s", "session.workspace", buildJsonObject { put("root", "/p/moved") })
        assertNull(store.list().single().origin)
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
