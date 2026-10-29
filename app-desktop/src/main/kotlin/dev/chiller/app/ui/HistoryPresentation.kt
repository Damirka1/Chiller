package dev.chiller.app.ui

import dev.chiller.app.i18n.*
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

/** Short age for a history row in [s]'s language: "now", "12 min", "3 h", "yesterday", "5 d", or a date. Empty when unparseable. */
fun relativeAge(updatedAt: String, s: CommonStrings, clock: Clock = Clock.systemUTC(), zoneId: ZoneId = ZoneId.systemDefault()): String {
    val instant = runCatching { Instant.parse(updatedAt) }.getOrNull() ?: return ""
    val now = Instant.now(clock)
    val minutes = java.time.Duration.between(instant, now).toMinutes().coerceAtLeast(0)
    val day = instant.atZone(zoneId).toLocalDate()
    val today = LocalDate.now(clock.withZone(zoneId))
    return when {
        minutes < 1 -> s.ageNow
        minutes < 60 -> s.ageMinutes(minutes)
        day == today -> s.ageHours(minutes / 60)
        day == today.minusDays(1) -> s.ageYesterday
        day.isAfter(today.minusDays(7)) -> s.ageDays(java.time.temporal.ChronoUnit.DAYS.between(day, today))
        else -> s.ageDate(day)
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

data class ProjectGroup(val path: String, val sessions: List<SessionInfo>, val pinned: Boolean, val lastActive: String?)

/** The sidebar tree: projects with their dialogs, and dialogs without a folder. */
data class ProjectTree(val projects: List<ProjectGroup>, val loose: List<SessionInfo>)

/**
 * Pinned projects in the user's order, then the others in the order they were opened, then folders known only from
 * dialogs by their latest activity. The order changes when the user opens or pins a project, not when a dialog moves.
 * Hidden and [excluded] folders (the demo) drop out with their dialogs unless [current] is one of them.
 */
fun projectTree(
    sessions: List<SessionInfo>, roots: Map<String, String>, recent: List<String>, pinned: List<String>, hidden: List<String>,
    current: String?, excluded: (String) -> Boolean = { false },
): ProjectTree {
    val byRoot = sessions.groupBy { roots[it.id] ?: it.workspace }
    fun shown(path: String) = path == current || (path !in hidden && !excluded(path))
    fun group(path: String) = byRoot[path].orEmpty().sortedByDescending { it.updatedAt }.let { list -> ProjectGroup(path, list, path in pinned, list.firstOrNull()?.updatedAt) }
    val pinnedGroups = pinned.filter(::shown).map(::group)
    val opened = (listOfNotNull(current?.takeIf { it !in recent }) + recent).filter { it !in pinned && shown(it) }.distinct().map(::group)
    val known = byRoot.keys.filterNotNull().filter { it !in pinned && it !in recent && it != current && shown(it) }.map(::group).sortedByDescending { it.lastActive.orEmpty() }
    return ProjectTree(pinnedGroups + opened + known, byRoot[null].orEmpty())
}

/** One or two letters for a project's tile: «chiller» → «C», «spring-boot» → «SB», «myApp» → «MA». */
fun projectInitials(name: String): String {
    val words = name.split(Regex("""[^\p{L}\p{N}]+|(?<=\p{Ll})(?=\p{Lu})""")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> name.take(1).uppercase()
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}
