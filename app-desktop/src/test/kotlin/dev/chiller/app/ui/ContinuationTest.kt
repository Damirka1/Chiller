package dev.chiller.app.ui

import dev.chiller.contracts.AgentMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContinuationTest {
    // Helpers read the global language; these checks are written against the Russian texts.
    @kotlin.test.BeforeTest fun russian() { dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.RuStrings }
    @kotlin.test.AfterTest fun english() { dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.EnStrings }

    @Test fun draftKeepsFirstAndLatestRequestsAndTheLastAnswer() {
        val messages = listOf(
            AgentMessage("user", "Добавь   экспорт\nв CSV"),
            AgentMessage("assistant", "Добавил экспорт."),
            AgentMessage("user", "второй"), AgentMessage("user", "третий"), AgentMessage("user", "четвёртый"), AgentMessage("user", "пятый"),
            AgentMessage("tool", "{}"),
            AgentMessage("assistant", "Готово: экспорт и тесты. " + "x".repeat(1000)),
        )
        val draft = continuationDraft("Экспорт", "D:\\work\\shop", messages)
        assertTrue(draft.startsWith("Продолжаем задачу из диалога «Экспорт» (папка shop). Что было:\n- Запрос: Добавь экспорт в CSV\n"), draft)
        assertEquals(listOf("третий", "четвёртый", "пятый"), Regex("- Затем: (.+)").findAll(draft).map { it.groupValues[1] }.toList(), "only the latest follow-ups")
        val answer = Regex("- Итог: (.+)").find(draft)!!.groupValues[1]
        assertTrue(answer.startsWith("Готово: экспорт и тесты.") && answer.endsWith("…") && answer.length == 800)
        assertTrue(draft.endsWith("\n\nЧто сделать здесь: "))
    }

    @Test fun draftWithoutFolderOrAnswer() {
        assertEquals("Продолжаем задачу из диалога «Вопрос». Что было:\n- Запрос: Привет\n\nЧто сделать здесь: ",
            continuationDraft("Вопрос", null, listOf(AgentMessage("user", "Привет"))))
    }
}
