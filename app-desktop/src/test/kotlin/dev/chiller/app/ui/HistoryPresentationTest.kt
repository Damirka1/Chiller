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
}
