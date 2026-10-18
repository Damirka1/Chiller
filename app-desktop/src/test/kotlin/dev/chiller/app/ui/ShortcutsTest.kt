package dev.chiller.app.ui

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShortcutsTest {
    @Test fun `shortcut labels follow the platform`() {
        assertEquals("⌘ N", shortcut("N", mac = true))
        assertEquals("Ctrl+N", shortcut("N", mac = false))
        assertEquals("Ctrl+Enter", shortcut("↵", mac = false))
        assertEquals("⌘ ↵", shortcut("↵", mac = true))
    }

    @Test fun `menu accelerators pick only enabled commands`() {
        var fired = ""
        val menu = listOf(
            AppMenuSection("Файл", listOf(listOf(AppMenuItem("Новый", Key.N, "N") { fired = "new" }), listOf(AppMenuItem("Закрыть") {}))),
            AppMenuSection("Диалог", listOf(listOf(AppMenuItem("Остановить", Key.Period, ".", enabled = false) { fired = "stop" }))),
        )
        menu.commandFor(Key.N)!!.onClick()
        assertEquals("new", fired)
        assertNull(menu.commandFor(Key.Period))
        assertNull(menu.commandFor(Key.Q))
    }
}
