package dev.chiller.app.di

import dev.chiller.app.ui.SettingsData
import dev.chiller.app.ui.aboutRows
import dev.chiller.app.ui.aboutText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildInfoTest {
    @Test fun `build label names the CI run or a local build`() {
        assertEquals("#57 · 2026-10-18", buildLabel("57", "2026-10-18", "18.10 16:20"))
        assertEquals("#57", buildLabel("57", "", "x"))
        assertEquals("локальная · 18.10 16:20", buildLabel("", "2026-10-18", "18.10 16:20"))
    }

    @Test fun `pre-release follows the version suffix`() {
        assertTrue(isPrerelease("1.2.0-rc.2"))
        assertFalse(isPrerelease("1.2.0"))
    }

    @Test fun `copied details leave out the data folder`() {
        val data = SettingsData("u", "m", false, false, version = "1.2.0-rc.2", build = "#57", commit = "f37143b0a1b2", dataDir = "/Users/someone/.chiller")
        val text = aboutText(aboutRows(data))
        assertTrue(text.startsWith("Chiller\nВерсия: 1.2.0-rc.2\nСборка: #57\nКоммит: f37143b0a1b2"))
        assertFalse("someone" in text)
    }
}
