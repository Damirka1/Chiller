package dev.chiller.app.di

import java.nio.file.Files
import java.nio.file.Path

/**
 * State of the project's check command after an editing run: the "definition of done" loop. The command is
 * the user's own, runs as argv (no shell) in the project root, and only when process permission is on.
 */
data class VerifyState(
    val status: String = "idle", // idle, running, passed, failed, gave_up, error
    val command: String = "",
    val exitCode: Int? = null,
    val durationMs: Long? = null,
    val attempt: Int = 0,
    val maxAttempts: Int = 2,
    val outputTail: String = "",
    val message: String = "",
)

/** Splits a command line into argv: whitespace separates, single or double quotes group. No shell expansion. */
fun splitCommand(line: String): List<String> {
    val args = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var started = false
    for (ch in line.trim()) {
        when {
            quote != null && ch == quote -> quote = null
            quote != null -> current.append(ch)
            ch == '"' || ch == '\'' -> { quote = ch; started = true }
            ch.isWhitespace() -> { if (started || current.isNotEmpty()) { args += current.toString(); current.clear(); started = false } }
            else -> { current.append(ch); started = true }
        }
    }
    require(quote == null) { "Unclosed quote" }
    if (started || current.isNotEmpty()) args += current.toString()
    return args
}

/** A likely test command from the project's build files, or null when nothing is recognised. */
fun suggestVerifyCommand(root: Path): String? {
    fun has(name: String) = Files.exists(root.resolve(name))
    return when {
        has("gradlew") && (has("build.gradle.kts") || has("build.gradle")) -> "./gradlew test"
        has("pom.xml") -> "mvn -q test"
        has("package.json") -> "npm test"
        has("Cargo.toml") -> "cargo test"
        has("go.mod") -> "go test ./..."
        has("pyproject.toml") || has("pytest.ini") || has("setup.cfg") || has("tests") -> "python3 -m pytest -q"
        has("Makefile") -> "make test"
        else -> null
    }
}

/** What the agent is told when the check fails: the facts, the output tail, and one clear request. */
fun verificationFollowUp(command: String, exitCode: Int?, state: String, tail: String, attempt: Int, maxAttempts: Int): String = buildString {
    append("Автопроверка после твоих правок не прошла (попытка $attempt из $maxAttempts).\n\n")
    append("Команда: `").append(command).append("`\n")
    append(when { state == "timed_out" -> "Итог: превышено время ожидания"; exitCode != null -> "Код завершения: $exitCode"; else -> "Итог: $state" }).append("\n\n")
    append("Конец вывода:\n```\n").append(tail.trim().takeLast(4000)).append("\n```\n\n")
    append("Найди причину по выводу, исправь её и проверь снова. Не меняй тесты, чтобы они просто проходили, если задача этого не требует.")
}
