package dev.chiller.app.ui

import dev.chiller.contracts.SessionInfo
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryPresentationTest {
    @Test fun groupsByLocalDateAndSortsRealInstantsWithMalformedTimestampsLast() {
        val zone = ZoneId.of("America/New_York")
        val clock = Clock.fixed(Instant.parse("2026-01-01T05:30:00Z"), zone)
        val sessions = listOf(
            SessionInfo("today-old", "", "2026-01-01T05:05:00Z"),
            SessionInfo("yesterday-new", "", "2026-01-01T04:55:00Z"),
            SessionInfo("today-new", "", "2026-01-01T05:25:00Z"),
            SessionInfo("older", "", "2025-12-31T04:00:00Z"),
            SessionInfo("bad", "", "not-an-instant"),
        )

        val sections = historySections(sessions, clock, zone)
        assertEquals(listOf("Сегодня", "Вчера", "Ранее"), sections.map { it.title })
        assertEquals(listOf("today-new", "today-old"), sections[0].sessions.map { it.id })
        assertEquals(listOf("yesterday-new"), sections[1].sessions.map { it.id })
        assertEquals(listOf("older", "bad"), sections[2].sessions.map { it.id })
    }

    @Test fun relativeAgeIsShortAndLocal() {
        val zone = ZoneId.of("UTC")
        val clock = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), zone)
        assertEquals("сейчас", relativeAge("2026-10-17T11:59:40Z", clock, zone))
        assertEquals("12 мин", relativeAge("2026-10-17T11:48:00Z", clock, zone))
        assertEquals("3 ч", relativeAge("2026-10-17T09:00:00Z", clock, zone))
        assertEquals("вчера", relativeAge("2026-10-16T09:00:00Z", clock, zone))
        assertEquals("4 дн", relativeAge("2026-10-13T09:00:00Z", clock, zone))
        assertEquals("01.09", relativeAge("2026-09-01T09:00:00Z", clock, zone))
        assertEquals("", relativeAge("bad", clock, zone))
    }

    @Test fun filtersByTitleIgnoringCase() {
        val sessions = listOf(SessionInfo("a", "Почини тесты", "x"), SessionInfo("b", "Разбор UI", "x"))
        assertEquals(listOf("b"), filterSessions(sessions, " ui ").map { it.id })
        assertEquals(2, filterSessions(sessions, "").size)
    }

    @Test fun recentProjectsMergeDialogRootsAndSettings() {
        val sessions = listOf(SessionInfo("a", "x", "2026-10-17T10:00:00Z"), SessionInfo("b", "y", "2026-10-17T12:00:00Z"), SessionInfo("c", "z", "2026-10-16T09:00:00Z"))
        val roots = mapOf("a" to "/p/one", "b" to "/p/two", "c" to "/p/one")
        val projects = recentProjects(listOf("/p/three", "/p/one"), sessions, roots)
        assertEquals(listOf("/p/two", "/p/one", "/p/three"), projects.map { it.path })
        assertEquals(listOf(1, 2, 0), projects.map { it.dialogs })
    }

    @Test fun folderNameHandlesBothSeparators() {
        assertEquals("Chiller", folderName("D:\\SpringBoot\\Chiller"))
        assertEquals("Chiller", folderName("D:\\SpringBoot\\Chiller\\"))
        assertEquals("chiller", folderName("/Users/me/chiller/"))
        assertEquals("D:", folderName("D:\\"))
        assertEquals("/", folderName("/"))
    }

    @Test fun projectTreeKeepsPinnedThenOpenedOrderAndHidesRemovedFolders() {
        val sessions = listOf(
            SessionInfo("a", "A", "2026-01-03T00:00:00Z", "/p/active"),
            SessionInfo("b", "B", "2026-01-02T00:00:00Z", "/p/pinned"),
            SessionInfo("c", "C", "2026-01-04T00:00:00Z"),
            SessionInfo("d", "D", "2026-01-05T00:00:00Z", "/p/hidden"),
            SessionInfo("e", "E", "2026-01-01T00:00:00Z", "/p/opened"),
            SessionInfo("f", "F", "2026-01-06T00:00:00Z", "/data/demo/1"),
        )
        val tree = projectTree(sessions, emptyMap(), recent = listOf("/p/opened", "/p/empty"), pinned = listOf("/p/pinned"), hidden = listOf("/p/hidden"),
            current = "/p/new", excluded = { it.startsWith("/data/demo") })
        assertEquals(listOf("/p/pinned", "/p/new", "/p/opened", "/p/empty", "/p/active"), tree.projects.map { it.path })
        assertEquals(listOf("c"), tree.loose.map { it.id })
        assertEquals(listOf(true, false, false, false, false), tree.projects.map { it.pinned })
        assertEquals("2026-01-01T00:00:00Z", tree.projects.single { it.path == "/p/opened" }.lastActive)
        // A binding known before the listing catches up wins; the current folder shows even when hidden.
        val rebound = projectTree(sessions, mapOf("c" to "/p/hidden"), emptyList(), emptyList(), listOf("/p/hidden"), current = "/p/hidden")
        assertEquals(listOf("d", "c"), rebound.projects.single { it.path == "/p/hidden" }.sessions.map { it.id })
    }

    @Test fun projectInitialsSplitWordsAndCamelCase() {
        assertEquals("C", projectInitials("chiller"))
        assertEquals("SB", projectInitials("spring-boot"))
        assertEquals("MA", projectInitials("myApp"))
        assertEquals("П", projectInitials("проект"))
        assertEquals(".", projectInitials("..."))
    }
}
