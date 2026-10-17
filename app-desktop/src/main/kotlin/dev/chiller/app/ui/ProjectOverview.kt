package dev.chiller.app.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** What the start screen shows about an open folder. Everything is read from disk; nothing runs a process. */
data class ProjectOverview(
    val name: String,
    val path: String,
    val branch: String?,
    val stack: List<String>,
    /** Files counted outside ignored folders; null when the walk was cut off at [limit]. */
    val files: Int?,
    val limit: Int,
)

private val IGNORED = setOf(".git", ".gradle", ".idea", "build", "out", "node_modules", "target", "dist", ".venv", "venv", "__pycache__", ".next")

private val MARKERS = listOf(
    "build.gradle.kts" to "Gradle · Kotlin DSL", "build.gradle" to "Gradle", "pom.xml" to "Maven",
    "package.json" to "Node.js", "pyproject.toml" to "Python", "requirements.txt" to "Python",
    "Cargo.toml" to "Rust", "go.mod" to "Go", "Package.swift" to "Swift", "Gemfile" to "Ruby",
    "composer.json" to "PHP", "CMakeLists.txt" to "CMake", "Makefile" to "Make",
)

fun inspectProject(root: Path, limit: Int = 20_000): ProjectOverview {
    val stack = MARKERS.filter { (file, _) -> Files.exists(root.resolve(file)) }.map { it.second }.distinct()
    return ProjectOverview(root.name.ifBlank { root.toString() }, root.toString(), gitBranch(root), stack, countFiles(root, limit), limit)
}

/** Reads `.git/HEAD` directly: a branch name, a short detached hash, or null outside git. */
internal fun gitBranch(root: Path): String? = runCatching {
    val git = root.resolve(".git")
    val dir = if (git.isDirectory()) git else Files.readString(git).trim().removePrefix("gitdir:").trim().let { root.resolve(it) }
    val head = Files.readString(dir.resolve("HEAD")).trim()
    if (head.startsWith("ref:")) head.substringAfterLast("refs/heads/") else head.take(7)
}.getOrNull()

private fun countFiles(root: Path, limit: Int): Int? {
    var count = 0
    val stack = ArrayDeque(listOf(root))
    while (stack.isNotEmpty()) {
        val dir = stack.removeLast()
        val entries = runCatching { Files.newDirectoryStream(dir).use { it.toList() } }.getOrDefault(emptyList())
        for (entry in entries) {
            if (Files.isSymbolicLink(entry)) continue
            if (Files.isDirectory(entry)) { if (entry.name !in IGNORED) stack.addLast(entry) }
            else if (++count > limit) return null
        }
    }
    return count
}

/** Starter tasks tuned to the detected stack. They fill the composer; nothing is sent automatically. */
data class Starter(val title: String, val detail: String, val prompt: String, val needsEdits: Boolean = false)

fun starters(project: ProjectOverview): List<Starter> {
    val tests = when {
        project.stack.any { it.startsWith("Gradle") } -> "./gradlew test"
        "Maven" in project.stack -> "mvn test"
        "Node.js" in project.stack -> "npm test"
        "Python" in project.stack -> "pytest"
        "Rust" in project.stack -> "cargo test"
        "Go" in project.stack -> "go test ./..."
        else -> null
    }
    return listOf(
        Starter("Разобрать структуру", "Модули, точки входа, зависимости", "Разбери структуру проекта ${project.name}: модули, точки входа, ключевые зависимости. Дай краткую карту."),
        Starter("Найти слабые места", "Ошибки, риски, долги", "Просмотри код проекта и найди вероятные ошибки и рискованные места. Для каждого укажи файл, строку и почему это проблема."),
        Starter(
            "Починить тесты", tests?.let { "Запустит $it и исправит падения" } ?: "Найдёт и исправит падающие тесты",
            "Запусти тесты${tests?.let { " ($it)" } ?: ""}, разберись в падениях и исправь их. Перед правками объясни причину.", needsEdits = true,
        ),
        Starter("Объяснить модуль", "Как устроена выбранная часть", "Объясни, как устроен модуль "),
    )
}

/** Workspace-relative file paths for @-mentions, skipping build/VCS folders and symlinks. Bounded. */
fun listProjectFiles(root: Path, limit: Int = 5_000): List<String> {
    val result = mutableListOf<String>()
    val stack = ArrayDeque(listOf(root))
    while (stack.isNotEmpty() && result.size < limit) {
        val dir = stack.removeLast()
        val entries = runCatching { Files.newDirectoryStream(dir).use { it.toList() } }.getOrDefault(emptyList()).sortedBy { it.name }
        for (entry in entries) {
            if (Files.isSymbolicLink(entry)) continue
            if (Files.isDirectory(entry)) { if (entry.name !in IGNORED && !entry.name.startsWith(".")) stack.addLast(entry) }
            else if (result.size < limit) result += root.relativize(entry).toString().replace('\\', '/')
        }
    }
    return result.sorted()
}

/** The @-mention being typed at [cursor]: start of the '@' and the query after it, or null. */
fun mentionAt(text: String, cursor: Int): Pair<Int, String>? {
    if (cursor < 1 || cursor > text.length) return null
    val start = text.lastIndexOf('@', cursor - 1).takeIf { it >= 0 } ?: return null
    if (start > 0 && !text[start - 1].isWhitespace()) return null
    val query = text.substring(start + 1, cursor)
    if (query.any { it.isWhitespace() } || query.length > 120) return null
    return start to query
}

/** Best matches first: file name prefix, then name contains, then path contains; shorter paths win ties. */
fun rankFiles(files: List<String>, query: String, limit: Int = 8): List<String> {
    val q = query.lowercase()
    return files.mapNotNull { path ->
        val name = path.substringAfterLast('/').lowercase()
        val score = when { q.isEmpty() -> 3; name.startsWith(q) -> 0; q in name -> 1; q in path.lowercase() -> 2; else -> return@mapNotNull null }
        Triple(score, path.length, path)
    }.sortedWith(compareBy({ it.first }, { it.second })).take(limit).map { it.third }
}

/** Paths mentioned as @path in [text] that exist in [files]. */
fun mentionedFiles(text: String, files: Collection<String>): List<String> =
    Regex("(?<=^|\\s)@([^\\s@]+)").findAll(text).map { it.groupValues[1].trimEnd('.', ',', ';', ':', ')') }.filter { it in files }.distinct().toList()
