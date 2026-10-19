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

private val FILE_CHANGE_TOOLS = setOf("edit_file", "replace_text", "create_file", "move_file", "delete_file", "create_directory", "generate_image")

/** Recognize explicit check invocations by executable/subcommand, never words in arbitrary arguments.
 * Unknown scripts stay ordinary commands. This describes what was run, not test coverage or correctness.
 */
internal fun isCheckCommand(argv: List<String>): Boolean = checkCommand(argv, 0)

private fun checkCommand(argv: List<String>, depth: Int): Boolean {
    if (argv.isEmpty() || depth > 2) return false
    fun name(value: String) = value.replace('\\', '/').substringAfterLast('/').lowercase().removeSuffix(".exe").removeSuffix(".cmd").removeSuffix(".bat")
    val executable = name(argv.first())
    val args = argv.drop(1)
    if (args.any { it in setOf("--help", "-h", "--version", "-V") }) return false
    if (executable in setOf("sh", "bash", "zsh", "cmd")) {
        if (args.size != 2 || args[0].lowercase() !in setOf("-c", "/c")) return false
        val script = args[1]
        if (script.any { it in ";|&<>$`\n\r()" }) return false
        val parsed = runCatching { dev.chiller.app.di.splitCommand(script) }.getOrNull() ?: return false
        return checkCommand(parsed, depth + 1)
    }
    if (executable.matches(Regex("python([23](\\.\\d+)?)?"))) {
        if (args.firstOrNull() == "-m") return args.getOrNull(1) in setOf("pytest", "unittest", "mypy", "compileall", "ruff", "flake8")
        val script = args.firstOrNull()?.let(::name) ?: return false
        return script.matches(Regex("(checks?|tests?(_[a-z0-9_]+)?|[a-z0-9_]+_test)\\.py"))
    }
    val task = args.firstOrNull() ?: return executable in setOf("pytest", "jest", "vitest", "tsc", "mypy", "flake8", "eslint", "ktlint", "detekt")
    return when (executable) {
        "pytest", "jest", "vitest", "tsc", "mypy", "flake8", "eslint", "ktlint", "detekt" -> !args.any { it in setOf("--help", "-h", "--version", "-V") }
        "ruff" -> task in setOf("check", "format") && (task != "format" || "--check" in args)
        "cargo" -> task in setOf("test", "check", "build", "clippy")
        "go" -> task in setOf("test", "vet", "build")
        "npm", "pnpm", "yarn", "bun" -> (if (task == "run") args.getOrNull(1) else task) in setOf("test", "check", "lint", "build", "typecheck", "type-check")
        "gradlew", "gradle" -> args.any { !it.startsWith("-") && it.substringAfterLast(':').let { t -> t in setOf("test", "check", "build", "assemble", "lint", "detekt", "ktlintCheck") || t.matches(Regex("(compile|test)[A-Z][A-Za-z0-9]*")) } }
        "mvn", "mvnw" -> args.any { it in setOf("test", "verify", "package", "compile") }
        "make", "gmake" -> task in setOf("test", "check", "lint", "build", "verify")
        else -> false
    }
}

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
        val kind = when (activity.name) { "create_file", "generate_image" -> "created"; "delete_file" -> "deleted"; "move_file" -> "moved"; else -> "edited" }
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
                commandsById[id] = CommandRun(argv.joinToString(" "), outcome, exit, isCheckCommand(argv))
            }
            "read_process", "stop_process" -> commandsById[id]?.let { commandsById[id] = it.copy(state = outcome, exitCode = exit ?: it.exitCode) }
        }
    }
    val commands = commandsById.values.toList()

    val presented = activities.filter { it.name in setOf("show_file", "capture_window", "generate_image") && it.status == "completed" }
        .map { presentedFileFromOutput(it.output)?.path ?: it.target }.filter { it.isNotBlank() }.distinct()

    val issues = buildList {
        // A failed check counts unless the same command later succeeded.
        commands.withIndex().filter { (index, run) -> run.isCheck && run.failed && commands.drop(index + 1).none { it.command == run.command && it.succeeded } }
            .map { it.value }.distinctBy { it.command }.forEach { run ->
                add(if (run.state == "timed_out") "«${run.command}» не уложилась во время и не перезапускалась успешно"
                    else "«${run.command}» завершилась с кодом ${run.exitCode} и после этого не прошла")
            }
        commands.filter { it.isCheck && !it.succeeded && !it.failed && commands.none { other -> other.command == it.command && (other.succeeded || other.failed) } }
            .distinctBy { it.command }.forEach { add("Результат «${it.command}» не прочитан: неизвестно, прошла ли проверка") }
        // A failed change counts unless the same path was changed successfully later.
        activities.withIndex().filter { (index, a) ->
            a.name in FILE_CHANGE_TOOLS && a.status == "failed" &&
                activities.drop(index + 1).none { it.name in FILE_CHANGE_TOOLS && it.status == "completed" && it.target == a.target }
        }.map { it.value.target }.distinct().forEach { add("Правка «$it» не применилась") }
        // A plan left with open steps after the turn: the answer may present unfinished work as done.
        if (state.status in setOf("completed", "failed", "cancelled", "interrupted"))
            currentTurnPlan(state.messages)?.filter { it.status !in setOf("done", "skipped") }?.takeIf { it.isNotEmpty() }?.let { open ->
                add("План не закрыт: " + open.joinToString("; ") { "«${it.title}»" })
            }

    }
    return TurnResult(files.values.toList(), commands, presented, issues)
}

