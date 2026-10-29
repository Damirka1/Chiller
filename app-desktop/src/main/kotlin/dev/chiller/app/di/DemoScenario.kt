package dev.chiller.app.di

import dev.chiller.contracts.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * The demo dialog: a scripted "model" that drives the real agent loop on a throwaway project, so every
 * tool runs for real (files change, Python runs, the question waits for the user) and the UI shows
 * exactly what a live run shows. It never touches the network and is labelled in the UI as a script.
 *
 * Each round looks at the request: the step is the number of assistant turns after the user message,
 * and snapshot or process ids are read from the actual tool results, never invented.
 */
object DemoScenario {
    const val MODEL_ID = "chiller-demo"
    val PROMPT: String get() = tr("Show every Chiller tool on the demo project: find and fix a bug, add a module, check it, tidy up the files.", "Покажи все инструменты Chiller на демо-проекте: найди и почини баг, добавь модуль, проверь, наведи порядок в файлах.")

    fun createProject(root: Path) {
        Files.createDirectories(root)
        val files = mapOf(
            "greeting.py" to "def greet(name):\n    if not name.strip():\n        return 'Hello, '\n    return f'Hello, {name}'\n",
            "config.py" to "# Service settings\nRETRIES = 3\nTIMEOUT_SECONDS = 30\nDEBUG = False\n",
            "checks.py" to "from greeting import greet\nfrom stats import mean\nassert greet('') == 'Hello', repr(greet(''))\nassert greet('Ada') == 'Hello, Ada'\nassert mean([1, 2, 3, 4]) == 2.5\nprint('3 checks passed')\n",
            "helpers_old.py" to "def clamp(value, low, high):\n    return max(low, min(high, value))\n",
            "scratch.txt" to "temporary notes, safe to delete\n",
            "README.md" to "# Demo project\n\nA tiny project for the Chiller tool demo.\n",
        )
        files.forEach { (name, text) -> Files.writeString(root.resolve(name), text) }
    }

    class Provider(private val pacing: Boolean = true) : LlmProvider {
        override suspend fun probe() = CapabilityReport("demo-script", tools = true, reasoning = true)

        override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
            if (request.messages.firstOrNull()?.content?.startsWith("You are a Chiller subagent") == true) { child(request); return@flow }
            val lastUser = request.messages.indexOfLast { it.role == "user" }
            val turn = request.messages.drop(lastUser + 1)
            val step = turn.count { it.role == "assistant" }
            val results = turn.filter { it.role == "tool" }.mapNotNull { runCatching { Json.parseToJsonElement(it.content.orEmpty()).jsonObject }.getOrNull() }
            fun snapshot(path: String): String? = results.asReversed().firstNotNullOfOrNull { r ->
                val data = r["data"] as? JsonObject ?: return@firstNotNullOfOrNull null
                if (data["path"]?.jsonPrimitive?.contentOrNull != path) null
                else (data["snapshot_id"] ?: data["snapshotId"])?.jsonPrimitive?.contentOrNull
            }
            val answer = turn.lastOrNull { it.role == "tool" && it.content?.contains("\"answer\"") == true }?.content
                ?.let { runCatching { Json.parseToJsonElement(it).jsonObject["data"]?.jsonObject?.get("answer")?.jsonPrimitive?.content }.getOrNull() }
            // The script reports what the process actually returned, never an assumed success.
            val exitCode = results.asReversed().firstNotNullOfOrNull { r -> (r["data"] as? JsonObject)?.takeIf { "processId" in it && "exitCode" in it }?.get("exitCode")?.jsonPrimitive?.intOrNull }
            val processState = results.asReversed().firstNotNullOfOrNull { r -> (r["data"] as? JsonObject)?.takeIf { "processId" in it }?.get("state")?.jsonPrimitive?.contentOrNull }
            if (pacing) delay(if (step == 0) 500 else 650)

            suspend fun say(text: String) {
                if (!pacing) { emit(ModelEvent.TextDelta(text)); return }
                text.chunked(14).forEach { emit(ModelEvent.TextDelta(it)); delay(18) }
            }
            suspend fun calls(text: String, vararg calls: Pair<String, JsonObject>) {
                say(text)
                val toolCalls = calls.mapIndexed { i, (name, args) -> ToolCall("demo-$step-$i", name, args.toString()) }
                emit(ModelEvent.Completed(AgentMessage("assistant", text, toolCalls), "tool_calls"))
            }
            fun plan(vararg steps: Pair<String, String>) = "update_plan" to buildJsonObject {
                put("steps", buildJsonArray { steps.forEach { (s, st) -> add(buildJsonObject { put("step", s); put("status", st) }) } })
            }
            fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
                pairs.forEach { (k, v) -> when (v) { is String -> put(k, v); is Int -> put(k, v); is Boolean -> put(k, v); is List<*> -> put(k, buildJsonArray { v.forEach { add(it.toString()) } }); else -> put(k, v.toString()) } }
            }

            // Script order: overview, delegate to two helpers, own work, collect helpers, tidy up, question, finish.
            val SPAWN = 100; val WAIT = 101; val RESULTS = 102
            val scripted = when { step <= 2 -> step; step == 3 -> SPAWN; step in 4..8 -> step - 1; step == 9 -> WAIT; step == 10 -> RESULTS; else -> step - 3 }
            fun agentId(task: String) = results.firstNotNullOfOrNull { r -> (r["data"] as? JsonObject)?.takeIf { it["taskId"]?.jsonPrimitive?.contentOrNull == task }?.get("id")?.jsonPrimitive?.contentOrNull } ?: "missing"
            when (scripted) {
                SPAWN -> calls(tr("While I fix the code, I hand two independent questions to helpers: they only read and work in parallel (their progress is in Studio → Agents).", "Пока я чиню код, отдам два независимых вопроса помощникам — они только читают и работают параллельно (их прогресс виден в Studio → Агенты)."),
                    "spawn_agent" to args("task_id" to "review-greeting", "goal" to tr("Check greeting.py: which edge cases of the name are not handled? Give a list with line numbers.", "Проверь greeting.py: какие крайние случаи имени не обработаны? Дай список с номерами строк."), "paths" to listOf("greeting.py"), "max_rounds" to 3),
                    "spawn_agent" to args("task_id" to "config-usage", "goal" to tr("Find where the project uses the settings from config.py and describe the risks of changing the timeout.", "Найди, где в проекте используются настройки из config.py, и опиши риски изменения таймаута."), "max_rounds" to 3))
                WAIT -> calls(tr("My part is done; collecting the helpers' results.", "Свою часть сделал — собираю результаты помощников."), "wait_agents" to args("after_cursor" to 0, "timeout_ms" to 20_000))
                RESULTS -> calls(tr("Reading their reports.", "Читаю их отчёты."), "agent_result" to args("agent_id" to agentId("review-greeting")), "agent_result" to args("agent_id" to agentId("config-usage")))
                0 -> {
                    emit(ModelEvent.ReasoningDelta(tr("This is a demo scenario. First the plan, then a project overview: files and function definitions are read in parallel.", "Это демо-сценарий. Сначала план, потом обзор проекта: файлы и определения функций читаются параллельно.")))
                    calls(tr("I will start with a plan so the steps are visible.", "Начну с плана, чтобы шаги были видны."), plan(tr("Look at the project", "Осмотреть проект") to "in_progress", tr("Fix the greeting", "Починить приветствие") to "pending", tr("Add the stats module", "Добавить модуль stats") to "pending", tr("Check and tidy up", "Проверить и прибраться") to "pending"))
                }
                1 -> calls(tr("Looking at what is in the project and where functions are defined.", "Смотрю, что в проекте и где определены функции."),
                    "list_files" to args(), "search_text" to args("query" to "def ", "context" to 1))
                2 -> calls(tr("Reading the greeting and the settings.", "Читаю приветствие и настройки."),
                    "read_file" to args("path" to "greeting.py"), "read_file" to args("path" to "config.py"))
                3 -> calls(tr("I will fix the empty name. First a deliberately inexact fragment, to see what a tool error looks like.", "Исправлю пустое имя. Сначала намеренно неточный фрагмент — посмотрим, как выглядит ошибка инструмента."),
                    "replace_text" to args("path" to "greeting.py", "snapshot_id" to (snapshot("greeting.py") ?: "missing"), "old_text" to "return 'Hello,'", "new_text" to "return 'Hello'"))
                4 -> calls(tr("The tool did not find the text and changed nothing. I copy the line exactly and also raise the timeout with a line-number edit.", "Инструмент не нашёл текст и ничего не изменил. Копирую строку точно; заодно поднимаю таймаут правкой по номеру строки."),
                    "replace_text" to args("path" to "greeting.py", "snapshot_id" to (snapshot("greeting.py") ?: "missing"), "old_text" to "return 'Hello, '", "new_text" to "return 'Hello'"),
                    "edit_file" to buildJsonObject {
                        put("path", "config.py"); put("snapshot_id", snapshot("config.py") ?: "missing")
                        put("edits", buildJsonArray { add(buildJsonObject { put("op", "replace"); put("start_line", 3); put("end_line", 3); put("content", "TIMEOUT_SECONDS = 45") }) })
                    })
                5 -> calls(tr("Now a new module with the mean.", "Теперь новый модуль со средним значением."),
                    "create_file" to args("path" to "stats.py", "content" to "def mean(values):\n    if not values:\n        raise ValueError('empty input')\n    return sum(values) / len(values)\n"),
                    plan(tr("Look at the project", "Осмотреть проект") to "done", tr("Fix the greeting", "Починить приветствие") to "done", tr("Add the stats module", "Добавить модуль stats") to "done", tr("Check and tidy up", "Проверить и прибраться") to "in_progress"))
                6 -> calls(tr("Running the checks in the background.", "Запускаю проверки в фоне."), "run_process" to args("argv" to listOf("python3", "checks.py"), "background" to true, "timeout_ms" to 30_000))
                7 -> {
                    if (pacing) delay(700)
                    val process = results.asReversed().firstNotNullOfOrNull { (it["data"] as? JsonObject)?.get("processId")?.jsonPrimitive?.contentOrNull } ?: "missing"
                    calls(tr("Reading the process output.", "Читаю вывод процесса."), "read_process" to args("process_id" to process))
                }
                8 -> calls(when { exitCode == 0 -> tr("The checks passed (code 0).", "Проверки прошли (код 0)."); exitCode != null -> tr("The checks failed with code $exitCode; in the demo I leave it as is.", "Проверки упали с кодом $exitCode — в демо оставлю как есть."); else -> tr("The process has not finished yet (${processState ?: "state unknown"}); I do not make up the result.", "Процесс ещё не завершился (${processState ?: "состояние неизвестно"}) — результат не выдумываю.") } +
                    tr(" Before tidying up I read the old files: without reading them the tool will not let me touch them.", " Перед уборкой читаю старые файлы — без чтения инструмент не даст их трогать."),
                    "read_file" to args("path" to "helpers_old.py"), "read_file" to args("path" to "scratch.txt"))
                9 -> calls(tr("Renaming the module and deleting the draft (the deletion can be undone from the conversation).", "Переименовываю модуль и удаляю черновик (удаление можно отменить из ленты)."),
                    "move_file" to args("path" to "helpers_old.py", "snapshot_id" to (snapshot("helpers_old.py") ?: "missing"), "destination" to "helpers.py"),
                    "delete_file" to args("path" to "scratch.txt", "snapshot_id" to (snapshot("scratch.txt") ?: "missing")))
                10 -> calls(tr("One question is left for you to decide.", "Остался вопрос, который решаете вы."),
                    "ask_user" to args("question" to tr("Add a section to the README on how to run the checks?", "Добавить в README раздел о том, как запускать проверки?"), "options" to listOf(tr("Yes, add it", "Да, добавь"), tr("No, not needed", "Нет, не нужно"))))
                11 -> if (answer?.startsWith(tr("Yes", "Да")) == true) calls(tr("Adding the section to the README.", "Добавляю раздел в README."), "read_file" to args("path" to "README.md"))
                    // Without the README section the last step is still done: close the plan before the answer.
                    else calls(tr("Closing the plan.", "Закрываю план."), plan(tr("Look at the project", "Осмотреть проект") to "done", tr("Fix the greeting", "Починить приветствие") to "done", tr("Add the stats module", "Добавить модуль stats") to "done", tr("Check and tidy up", "Проверить и прибраться") to "done"))
                12 -> if (answer?.startsWith(tr("Yes", "Да")) == true && snapshot("README.md") != null) calls(tr("Writing the section.", "Дописываю раздел."),
                    "replace_text" to args("path" to "README.md", "snapshot_id" to snapshot("README.md")!!, "old_text" to "A tiny project for the Chiller tool demo.", "new_text" to "A tiny project for the Chiller tool demo.\n\n## Checks\n\n```sh\npython3 checks.py\n```"),
                    plan(tr("Look at the project", "Осмотреть проект") to "done", tr("Fix the greeting", "Починить приветствие") to "done", tr("Add the stats module", "Добавить модуль stats") to "done", tr("Check and tidy up", "Проверить и прибраться") to "done"))
                    else finish(answer, exitCode)
                else -> finish(answer, exitCode)
            }
        }

        /** A helper: one look at the code, then a short report. Paced so its progress is visible in Studio. */
        private suspend fun kotlinx.coroutines.flow.FlowCollector<ModelEvent>.child(request: ModelRequest) {
            val goal = request.messages.first { it.role == "user" }.content.orEmpty()
            val tools = request.messages.count { it.role == "tool" }
            val greeting = "greeting" in goal
            if (pacing) delay(900)
            if (tools == 0 && request.tools.isNotEmpty()) {
                val call = if (greeting) ToolCall("child-read", "read_file", buildJsonObject { put("path", "greeting.py") }.toString())
                    else ToolCall("child-search", "search_text", buildJsonObject { put("query", "config"); put("context", 1) }.toString())
                emit(ModelEvent.Completed(AgentMessage("assistant", null, listOf(call)), "tool_calls")); return
            }
            val report = if (greeting) tr("Edge cases in `greeting.py`:\n\n- line 2: a name of only spaces already gives `Hello` after the fix;\n- `None` raises `AttributeError` on `.strip()`, not handled;\n- the name is not trimmed: `greet(' Ada ')` → `Hello,  Ada `.\n\nChecked by reading the file, without running it.","Крайние случаи в `greeting.py`:\n\n- строка 2: имя из одних пробелов уже даёт `Hello` после правки;\n- `None` вызовет `AttributeError` на `.strip()` — не обработан;\n- имя не обрезается по краям: `greet(' Ada ')` → `Hello,  Ada `.\n\nПроверено чтением файла, без запуска.")
                else tr("`config.py` is not imported anywhere: a search for `config` found only the file itself and the README.\n\n- Changing `TIMEOUT_SECONDS` affects nothing right now.\n- Risk: code may appear that waits 45 s instead of 30.\n\nChecked by searching the project.","`config.py` нигде не импортируется: поиск `config` нашёл только сам файл и README.\n\n- Изменение `TIMEOUT_SECONDS` сейчас ни на что не влияет.\n- Риск: появится код, который будет ждать 45 с вместо 30.\n\nПроверено поиском по проекту.")
            if (pacing) report.chunked(24).forEach { delay(10) }
            emit(ModelEvent.Completed(AgentMessage("assistant", report), "stop"))
        }

        private suspend fun kotlinx.coroutines.flow.FlowCollector<ModelEvent>.finish(answer: String?, exitCode: Int?) {
            val text = buildString {
                appendLine(tr("Done. What was done in the demo project:", "Готово. Что было сделано в демо-проекте:"))
                appendLine()
                appendLine(tr("- `greeting.py`: an empty name now gives `Hello` (with `replace_text`; the first attempt showed the `NO_MATCH` error).", "- `greeting.py`: пустое имя теперь даёт `Hello` (через `replace_text`; первая попытка показала ошибку `NO_MATCH`)."))
                appendLine(tr("- `config.py`: `TIMEOUT_SECONDS = 45` (with `edit_file` by line number).", "- `config.py`: `TIMEOUT_SECONDS = 45` (через `edit_file` по номеру строки)."))
                appendLine(tr("- `stats.py`: a new module with `mean`.", "- `stats.py`: новый модуль с `mean`."))
                appendLine(tr("- `helpers_old.py` → `helpers.py`, `scratch.txt` deleted.", "- `helpers_old.py` → `helpers.py`, `scratch.txt` удалён."))
                appendLine(tr("- Two helpers checked the greeting's edge cases and the use of the settings in parallel; their reports are in Studio → Agents.", "- Два помощника параллельно проверили крайние случаи приветствия и использование настроек; их отчёты — в Studio → Агенты."))
                if (answer?.startsWith(tr("Yes", "Да")) == true) appendLine(tr("- `README.md`: added a section on the checks.", "- `README.md`: добавлен раздел о проверках."))
                appendLine()
                appendLine(when (exitCode) { 0 -> tr("The check ran as a process and exited with code 0:", "Проверка запускалась процессом и завершилась с кодом 0:"); null -> tr("The check ran, but no exit code was received:", "Проверка запускалась, но код завершения не получен:"); else -> tr("The check ran and exited with code $exitCode:", "Проверка запускалась и завершилась с кодом $exitCode:") })
                appendLine()
                appendLine("```sh")
                appendLine("python3 checks.py")
                appendLine("```")
                appendLine()
                append(tr("Any edit can be undone in the conversation or in the “Changes” tab in Studio. This was a script, not a model: the token metrics here are empty.", "Любую правку можно отменить в ленте или во вкладке «Изменения» в Studio. Это был сценарий, а не модель: метрики токенов здесь пустые."))
            }
            text.chunked(16).forEach { emit(ModelEvent.TextDelta(it)); if (pacing) delay(14) }
            emit(ModelEvent.Completed(AgentMessage("assistant", text), "stop"))
        }
    }
}
