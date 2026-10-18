package dev.chiller.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ShortcutsTest {
    @Test fun `shortcut labels follow the platform`() {
        assertEquals("⌘ N", shortcut("N", mac = true))
        assertEquals("Ctrl+N", shortcut("N", mac = false))
        assertEquals("Ctrl+Enter", shortcut("↵", mac = false))
        assertEquals("⌘ ↵", shortcut("↵", mac = true))
    }
}
