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
    const val PROMPT = "Покажи все инструменты Chiller на демо-проекте: найди и почини баг, добавь модуль, проверь, наведи порядок в файлах."

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
                SPAWN -> calls("Пока я чиню код, отдам два независимых вопроса помощникам — они только читают и работают параллельно (их прогресс виден в Studio → Агенты).",
                    "spawn_agent" to args("task_id" to "review-greeting", "goal" to "Проверь greeting.py: какие крайние случаи имени не обработаны? Дай список с номерами строк.", "paths" to listOf("greeting.py"), "max_rounds" to 3),
                    "spawn_agent" to args("task_id" to "config-usage", "goal" to "Найди, где в проекте используются настройки из config.py, и опиши риски изменения таймаута.", "max_rounds" to 3))
                WAIT -> calls("Свою часть сделал — собираю результаты помощников.", "wait_agents" to args("after_cursor" to 0, "timeout_ms" to 20_000))
                RESULTS -> calls("Читаю их отчёты.", "agent_result" to args("agent_id" to agentId("review-greeting")), "agent_result" to args("agent_id" to agentId("config-usage")))
                0 -> {
                    emit(ModelEvent.ReasoningDelta("Это демо-сценарий. Сначала план, потом обзор проекта: файлы и определения функций читаются параллельно."))
                    calls("Начну с плана, чтобы шаги были видны.", plan("Осмотреть проект" to "in_progress", "Починить приветствие" to "pending", "Добавить модуль stats" to "pending", "Проверить и прибраться" to "pending"))
                }
                1 -> calls("Смотрю, что в проекте и где определены функции.",
                    "list_files" to args(), "search_text" to args("query" to "def ", "context" to 1))
                2 -> calls("Читаю приветствие и настройки.",
                    "read_file" to args("path" to "greeting.py"), "read_file" to args("path" to "config.py"))
                3 -> calls("Исправлю пустое имя. Сначала намеренно неточный фрагмент — посмотрим, как выглядит ошибка инструмента.",
                    "replace_text" to args("path" to "greeting.py", "snapshot_id" to (snapshot("greeting.py") ?: "missing"), "old_text" to "return 'Hello,'", "new_text" to "return 'Hello'"))
                4 -> calls("Инструмент не нашёл текст и ничего не изменил. Копирую строку точно; заодно поднимаю таймаут правкой по номеру строки.",
                    "replace_text" to args("path" to "greeting.py", "snapshot_id" to (snapshot("greeting.py") ?: "missing"), "old_text" to "return 'Hello, '", "new_text" to "return 'Hello'"),
                    "edit_file" to buildJsonObject {
                        put("path", "config.py"); put("snapshot_id", snapshot("config.py") ?: "missing")
                        put("edits", buildJsonArray { add(buildJsonObject { put("op", "replace"); put("start_line", 3); put("end_line", 3); put("content", "TIMEOUT_SECONDS = 45") }) })
                    })
                5 -> calls("Теперь новый модуль со средним значением.",
                    "create_file" to args("path" to "stats.py", "content" to "def mean(values):\n    if not values:\n        raise ValueError('empty input')\n    return sum(values) / len(values)\n"),
                    plan("Осмотреть проект" to "done", "Починить приветствие" to "done", "Добавить модуль stats" to "done", "Проверить и прибраться" to "in_progress"))
                6 -> calls("Запускаю проверки в фоне.", "run_process" to args("argv" to listOf("python3", "checks.py"), "background" to true, "timeout_ms" to 30_000))
                7 -> {
                    if (pacing) delay(700)
                    val process = results.asReversed().firstNotNullOfOrNull { (it["data"] as? JsonObject)?.get("processId")?.jsonPrimitive?.contentOrNull } ?: "missing"
                    calls("Читаю вывод процесса.", "read_process" to args("process_id" to process))
                }
                8 -> calls(when { exitCode == 0 -> "Проверки прошли (код 0)."; exitCode != null -> "Проверки упали с кодом $exitCode — в демо оставлю как есть."; else -> "Процесс ещё не завершился (${processState ?: "состояние неизвестно"}) — результат не выдумываю." } +
                    " Перед уборкой читаю старые файлы — без чтения инструмент не даст их трогать.",
                    "read_file" to args("path" to "helpers_old.py"), "read_file" to args("path" to "scratch.txt"))
                9 -> calls("Переименовываю модуль и удаляю черновик (удаление можно отменить из ленты).",
                    "move_file" to args("path" to "helpers_old.py", "snapshot_id" to (snapshot("helpers_old.py") ?: "missing"), "destination" to "helpers.py"),
                    "delete_file" to args("path" to "scratch.txt", "snapshot_id" to (snapshot("scratch.txt") ?: "missing")))
                10 -> calls("Остался вопрос, который решаете вы.",
                    "ask_user" to args("question" to "Добавить в README раздел о том, как запускать проверки?", "options" to listOf("Да, добавь", "Нет, не нужно")))
                11 -> if (answer?.startsWith("Да") == true) calls("Добавляю раздел в README.", "read_file" to args("path" to "README.md"))
                    else finish(answer, exitCode)
                12 -> if (answer?.startsWith("Да") == true && snapshot("README.md") != null) calls("Дописываю раздел.",
                    "replace_text" to args("path" to "README.md", "snapshot_id" to snapshot("README.md")!!, "old_text" to "A tiny project for the Chiller tool demo.", "new_text" to "A tiny project for the Chiller tool demo.\n\n## Checks\n\n```sh\npython3 checks.py\n```"))
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
            val report = if (greeting) "Крайние случаи в `greeting.py`:\n\n- строка 2: имя из одних пробелов уже даёт `Hello` после правки;\n- `None` вызовет `AttributeError` на `.strip()` — не обработан;\n- имя не обрезается по краям: `greet(' Ada ')` → `Hello,  Ada `.\n\nПроверено чтением файла, без запуска."
                else "`config.py` нигде не импортируется: поиск `config` нашёл только сам файл и README.\n\n- Изменение `TIMEOUT_SECONDS` сейчас ни на что не влияет.\n- Риск: появится код, который будет ждать 45 с вместо 30.\n\nПроверено поиском по проекту."
            if (pacing) report.chunked(24).forEach { delay(10) }
            emit(ModelEvent.Completed(AgentMessage("assistant", report), "stop"))
        }

        private suspend fun kotlinx.coroutines.flow.FlowCollector<ModelEvent>.finish(answer: String?, exitCode: Int?) {
            val text = buildString {
                appendLine("Готово. Что было сделано в демо-проекте:")
                appendLine()
                appendLine("- `greeting.py`: пустое имя теперь даёт `Hello` (через `replace_text`; первая попытка показала ошибку `NO_MATCH`).")
                appendLine("- `config.py`: `TIMEOUT_SECONDS = 45` (через `edit_file` по номеру строки).")
                appendLine("- `stats.py`: новый модуль с `mean`.")
                appendLine("- `helpers_old.py` → `helpers.py`, `scratch.txt` удалён.")
                appendLine("- Два помощника параллельно проверили крайние случаи приветствия и использование настроек; их отчёты — в Studio → Агенты.")
                if (answer?.startsWith("Да") == true) appendLine("- `README.md`: добавлен раздел о проверках.")
                appendLine()
                appendLine(when (exitCode) { 0 -> "Проверка запускалась процессом и завершилась с кодом 0:"; null -> "Проверка запускалась, но код завершения не получен:"; else -> "Проверка запускалась и завершилась с кодом $exitCode:" })
                appendLine()
                appendLine("```sh")
                appendLine("python3 checks.py")
                appendLine("```")
                appendLine()
                append("Любую правку можно отменить в ленте или во вкладке «Изменения» в Studio. Это был сценарий, а не модель: метрики токенов здесь пустые.")
            }
            text.chunked(16).forEach { emit(ModelEvent.TextDelta(it)); if (pacing) delay(14) }
            emit(ModelEvent.Completed(AgentMessage("assistant", text), "stop"))
        }
    }
}
