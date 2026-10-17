package dev.chiller.app.ui

import dev.chiller.contracts.SessionInfo
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HistorySection(val title: String, val sessions: List<SessionInfo>)

/** Groups sessions by the user's local calendar day while sorting by their actual instants. */
fun historySections(
    sessions: List<SessionInfo>,
    clock: Clock = Clock.systemUTC(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<HistorySection> {
    val today = LocalDate.now(clock.withZone(zoneId))
    val dated = sessions.map { session ->
        session to runCatching { Instant.parse(session.updatedAt) }.getOrNull()
    }
    val buckets = linkedMapOf("Сегодня" to mutableListOf<Pair<SessionInfo, Instant?>>(),
        "Вчера" to mutableListOf(), "Ранее" to mutableListOf())
    for ((session, instant) in dated) {
        val section = when (instant?.atZone(zoneId)?.toLocalDate()) {
            today -> "Сегодня"
            today.minusDays(1) -> "Вчера"
            else -> "Ранее"
        }
        buckets.getValue(section).add(session to instant)
    }
    return buckets.mapNotNull { (title, entries) ->
        val sorted = entries.sortedWith { left, right ->
            when {
                left.second != null && right.second != null -> right.second!!.compareTo(left.second!!)
                left.second != null -> -1
                right.second != null -> 1
                else -> 0
            }
        }.map { it.first }
        if (sorted.isEmpty()) null else HistorySection(title, sorted)
    }
}
