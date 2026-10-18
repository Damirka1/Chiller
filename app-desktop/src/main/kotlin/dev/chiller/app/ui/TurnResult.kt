package dev.chiller.app.ui

import dev.chiller.contracts.RunState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A file the agent changed in this turn, merged over all its changes. */
data class ChangedFile(val path: String, val kind: String, val added: Int, val removed: Int, val undone: Boolean = false)

/** A command the agent ran in this turn, with its last observed outcome. */
data class CommandRun(val command: String, val state: String, val exitCode: Int?, val isCheck: Boolean) {
    val succeeded: Boolean get() = state == "exited" && exitCode == 0
    val failed: Boolean get() = (state == "exited" && exitCode != null && exitCode != 0) || state == "timed_out"
}

/**
 * What a turn actually did, built only from tool calls and their results, never from the model's own text:
 * changed files, commands with exit codes, files shown to the user and what is left unresolved.
 */
data class TurnResult(
    val files: List<ChangedFile>,
    val commands: List<CommandRun>,
    val presented: List<String>,
    val issues: List<String>,
) {
    val isEmpty: Boolean get() = files.isEmpty() && commands.isEmpty() && presented.isEmpty() && issues.isEmpty()
}

private val FILE_CHANGE_TOOLS = setOf("edit_file", "replace_text", "create_file", "move_file", "delete_file", "create_directory")

/** Commands whose failure means the work is not done (unlike grep or ls, where a non-zero exit is normal). */
internal fun isCheckCommand(argv: List<String>): Boolean {
    val text = argv.joinToString(" ").lowercase()
    val words = listOf("test", "pytest", "check", "lint", "gradlew", "gradle", "mvn", "cargo", "go vet", "tsc", "mypy", "ruff",
        "flake8", "eslint", "ktlint", "detekt", "build", "verify", "compile", "unittest", "jest", "vitest", "make")
    return words.any { it in text }
}

/** Claims of verification in the final answer; a sentence with a negation ("не запускал тесты") is not a claim. */
private val CLAIM = Regex("""(тест\p{L}*|провер\p{L}*|прош(ли|ла|ёл|ел)|pass(ed|es)?|tests?|verified|checks?)""", RegexOption.IGNORE_CASE)
private val NEGATION = Regex("""(^|[\s(«"])(не|нет|без|not|no|without|cannot|can't|didn't|did not|unable)([\s,.)»"]|$)""", RegexOption.IGNORE_CASE)

internal fun claimsVerification(answer: String): Boolean =
    answer.split(Regex("""(?<=[.!?\n])\s+""")).any { sentence -> CLAIM.containsMatchIn(sentence) && !NEGATION.containsMatchIn(sentence) }

fun turnResult(state: RunState, undone: Set<String> = emptySet()): TurnResult {
    val start = state.messages.indexOfLast { it.role == "user" }
    val turn = if (start < 0) state.messages else state.messages.drop(start + 1)
    val calls = turn.flatMap { it.toolCalls }
    val callIds = calls.map { it.id }.toSet()
    val results = turn.filter { it.role == "tool" }.associateBy { it.toolCallId }
    val activities = state.activities.filter { it.toolCallId in callIds }

    // Files: completed changes merged per path, in first-change order.
    val files = linkedMapOf<String, ChangedFile>()
    for (activity in activities) {
        if (activity.name !in FILE_CHANGE_TOOLS || activity.status != "completed" || activity.name == "create_directory") continue
        val (added, removed) = activity.diff?.let(::diffStats) ?: (0 to 0)
        val kind = when (activity.name) { "create_file" -> "created"; "delete_file" -> "deleted"; "move_file" -> "moved"; else -> "edited" }
        val isUndone = changeId(activity.output)?.let { it in undone } == true
        val previous = files[activity.target]
        files[activity.target] = if (previous == null) ChangedFile(activity.target, kind, added, removed, isUndone)
            else previous.copy(added = previous.added + added, removed = previous.removed + removed, undone = previous.undone && isUndone,
                kind = if (previous.kind == "created" && kind == "deleted") "deleted" else previous.kind)
    }

    // Commands: run_process gives the argv and the process id; later reads update the outcome.
    val commandsById = linkedMapOf<String, CommandRun>()
    for (call in calls) {
        val data = results[call.id]?.content?.let { runCatching { Json.parseToJsonElement(it).jsonObject["data"] as? JsonObject }.getOrNull() } ?: continue
        val id = data["processId"]?.jsonPrimitive?.contentOrNull ?: continue
        val outcome = data["state"]?.jsonPrimitive?.contentOrNull ?: "running"
        val exit = data["exitCode"]?.jsonPrimitive?.intOrNull
        when (call.name) {
            "run_process" -> {
                val argv = runCatching { Json.parseToJsonElement(call.arguments).jsonObject["argv"]!!.jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList())
                commandsById[id] = CommandRun(argv.joinToString(" ").take(160), outcome, exit, isCheckCommand(argv))
            }
            "read_process", "stop_process" -> commandsById[id]?.let { commandsById[id] = it.copy(state = outcome, exitCode = exit ?: it.exitCode) }
        }
    }
    val commands = commandsById.values.toList()

    val presented = activities.filter { it.name in setOf("show_file", "capture_window") && it.status == "completed" }
        .map { presentedFileFromOutput(it.output)?.path ?: it.target }.filter { it.isNotBlank() }.distinct()

    val issues = buildList {
        // A failed check counts unless the same command later succeeded.
        commands.withIndex().filter { (index, run) -> run.isCheck && run.failed && commands.drop(index + 1).none { it.command == run.command && it.succeeded } }
            .map { it.value }.distinctBy { it.command }.forEach { run ->
                add(if (run.state == "timed_out") "«${run.command}» не уложилась во время и не перезапускалась успешно"
                    else "«${run.command}» завершилась с кодом ${run.exitCode} и после этого не прошла")
            }
        commands.filter { it.isCheck && it.state == "running" && commands.none { other -> other.command == it.command && (other.succeeded || other.failed) } }
            .distinctBy { it.command }.forEach { add("Результат «${it.command}» не прочитан: неизвестно, прошла ли проверка") }
        // A failed change counts unless the same path was changed successfully later.
        activities.withIndex().filter { (index, a) ->
            a.name in FILE_CHANGE_TOOLS && a.status == "failed" &&
                activities.drop(index + 1).none { it.name in FILE_CHANGE_TOOLS && it.status == "completed" && it.target == a.target }
        }.map { it.value.target }.distinct().forEach { add("Правка «$it» не применилась") }
        val answer = turn.lastOrNull { it.role == "assistant" && !it.content.isNullOrBlank() }?.content.orEmpty()
        if (state.status == "completed" && commands.none { it.isCheck } && claimsVerification(answer))
            add("В ответе говорится о проверке, но в этом ходе команды проверки не запускались")
    }
    return TurnResult(files.values.toList(), commands, presented, issues)
}

