package dev.chiller.app.ui

import dev.chiller.contracts.Activity
import dev.chiller.contracts.AgentMessage
import dev.chiller.contracts.RunState
import dev.chiller.contracts.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnResultTest {
    private fun call(id: String, name: String, args: String) = AgentMessage("assistant", toolCalls = listOf(ToolCall(id, name, args)))
    private fun result(id: String, data: String) = AgentMessage("tool", """{"ok":true,"data":$data}""", toolCallId = id)
    private fun run(id: String, argv: String, state: String, exit: Int?) = listOf(
        call(id, "run_process", """{"argv":$argv}"""),
        result(id, """{"processId":"p$id","output":"","cursor":0,"state":"$state"${exit?.let { ",\"exitCode\":$it" } ?: ""}}"""),
    )
    private fun edit(id: String, path: String, ok: Boolean, diff: String? = null) = Activity(name = "replace_text", target = path,
        status = if (ok) "completed" else "failed", output = if (ok) """{"ok":true,"data":{"id":"c$id"}}""" else "{}", diff = diff, toolCallId = id)

    @Test fun `files, commands and shown results come from tool calls`() {
        val messages = listOf(AgentMessage("user", "fix it")) +
            call("e1", "replace_text", "{}") + call("e2", "replace_text", "{}") + run("r1", """["python3","-m","pytest"]""", "exited", 0) +
            AgentMessage("assistant", "Готово: тесты прошли.")
        val activities = listOf(
            edit("e1", "app.py", true, "--- a\n+++ b\n-old\n+new\n+more\n"),
            edit("e2", "app.py", true, "--- a\n+++ b\n+x\n"),
            Activity(name = "show_file", target = "plot.png", status = "completed", toolCallId = "e2"),
        )
        val r = turnResult(RunState(status = "completed", messages = messages, activities = activities), undone = setOf("ce1"))
        assertEquals(listOf(ChangedFile("app.py", "edited", 3, 1, undone = false)), r.files)
        assertEquals(listOf(CommandRun("python3 -m pytest", "exited", 0, isCheck = true)), r.commands)
        assertEquals(listOf("plot.png"), r.presented)
        assertTrue(r.issues.isEmpty(), r.issues.toString())
    }

    @Test fun `a failed check is an issue unless it later passes, a failing grep is not`() {
        val failedThenFixed = listOf(AgentMessage("user", "go")) + run("r1", """["pytest"]""", "exited", 1) + run("r2", """["pytest"]""", "exited", 0) +
            run("r3", """["grep","-r","TODO","."]""", "exited", 1) + AgentMessage("assistant", "ok")
        assertTrue(turnResult(RunState(status = "completed", messages = failedThenFixed)).issues.isEmpty())
        val stillFailing = listOf(AgentMessage("user", "go")) + run("r1", """["./gradlew","check"]""", "exited", 0) + run("r2", """["./gradlew","check"]""", "exited", 1)
        assertEquals(listOf("«./gradlew check» завершилась с кодом 1 и после этого не прошла"), turnResult(RunState(status = "completed", messages = stillFailing)).issues)
    }

    @Test fun `unread background checks and unapplied edits are issues`() {
        val messages = listOf(AgentMessage("user", "go")) + run("r1", """["npm","test"]""", "running", null) + call("e1", "replace_text", "{}") + call("e2", "replace_text", "{}") + call("e3", "replace_text", "{}")
        val activities = listOf(edit("e1", "a.py", false), edit("e2", "a.py", true), edit("e3", "b.py", false))
        val issues = turnResult(RunState(status = "completed", messages = messages, activities = activities)).issues
        assertEquals(listOf("Результат «npm test» не прочитан: неизвестно, прошла ли проверка", "Правка «b.py» не применилась"), issues)
    }

    @Test fun `a claimed check without any check command is flagged, a stated non-check is not`() {
        fun issues(answer: String) = turnResult(RunState(status = "completed", messages = listOf(AgentMessage("user", "go"), AgentMessage("assistant", answer)))).issues
        assertEquals(1, issues("Исправил функцию, все тесты проходят.").size)
        assertEquals(1, issues("Fixed. All tests passed.").size)
        assertTrue(issues("Исправил функцию. Тесты не запускал: команды отключены.").isEmpty())
        assertTrue(issues("Renamed the variable.").isEmpty())
        assertTrue(claimsVerification("Проверил: всё работает.") && !claimsVerification("Без запуска проверок."))
    }

    @Test fun `only the latest turn counts and a plain answer is empty`() {
        val messages = run("old", """["pytest"]""", "exited", 1) + listOf(AgentMessage("user", "what is this?"), AgentMessage("assistant", "A parser."))
        assertTrue(turnResult(RunState(status = "completed", messages = messages)).isEmpty)
        assertTrue(isCheckCommand(listOf("python3", "checks.py")) && !isCheckCommand(listOf("ls", "-la")))
    }
}
