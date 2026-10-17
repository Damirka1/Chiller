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
}
