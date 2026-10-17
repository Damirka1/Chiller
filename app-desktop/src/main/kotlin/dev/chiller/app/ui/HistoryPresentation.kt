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

/** Short age for a history row: «сейчас», «12 мин», «3 ч», «вчера», «5 дн», or a date. Empty when unparseable. */
fun relativeAge(updatedAt: String, clock: Clock = Clock.systemUTC(), zoneId: ZoneId = ZoneId.systemDefault()): String {
    val instant = runCatching { Instant.parse(updatedAt) }.getOrNull() ?: return ""
    val now = Instant.now(clock)
    val minutes = java.time.Duration.between(instant, now).toMinutes().coerceAtLeast(0)
    val day = instant.atZone(zoneId).toLocalDate()
    val today = LocalDate.now(clock.withZone(zoneId))
    return when {
        minutes < 1 -> "сейчас"
        minutes < 60 -> "$minutes мин"
        day == today -> "${minutes / 60} ч"
        day == today.minusDays(1) -> "вчера"
        day.isAfter(today.minusDays(7)) -> "${java.time.temporal.ChronoUnit.DAYS.between(day, today)} дн"
        else -> "%02d.%02d".format(day.dayOfMonth, day.monthValue)
    }
}

/** Case-insensitive title filter for the sidebar search. */
fun filterSessions(sessions: List<SessionInfo>, query: String): List<SessionInfo> {
    val q = query.trim()
    return if (q.isEmpty()) sessions else sessions.filter { it.title.contains(q, ignoreCase = true) }
}

data class RecentProject(val path: String, val dialogs: Int, val lastActive: String?)

/**
 * Recent projects from both sources: folders opened in settings and folders recorded by dialogs.
 * Ordered by the latest dialog activity; folders without dialogs keep their settings order after them.
 */
fun recentProjects(settingsRecent: List<String>, sessions: List<SessionInfo>, sessionWorkspaces: Map<String, String>, limit: Int = 5): List<RecentProject> {
    val byRoot = sessions.mapNotNull { s -> sessionWorkspaces[s.id]?.let { it to s } }.groupBy({ it.first }, { it.second })
    val active = byRoot.map { (root, list) -> RecentProject(root, list.size, list.maxOfOrNull { it.updatedAt }) }
        .sortedByDescending { it.lastActive.orEmpty() }
    val idle = settingsRecent.filter { it !in byRoot }.map { RecentProject(it, 0, null) }
    return (active + idle).distinctBy { it.path }.take(limit)
}
