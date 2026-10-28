package dev.chiller.app.di

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiPreferencesTest {
    @Test
    fun projectExpansionIsKeptAndBounded() = runBlocking {
        val store = UiPreferencesStore(Files.createTempDirectory("ui-preferences-projects"))
        val many = (1..250).associate { "/p/$it" to (it % 2 == 0) } + ("" to true)
        store.save(UiPreferences(projectExpansion = many))
        val loaded = store.load().projectExpansion
        assertEquals(200, loaded.size)
        assertEquals(true, loaded["/p/250"])
        assertFalse("" in loaded)
    }

    @Test
    fun missingFileAndMissingFieldsUseDefaults() = runBlocking {
        val directory = Files.createTempDirectory("ui-preferences-defaults")
        val store = UiPreferencesStore(directory)

        assertEquals(UiPreferences(), store.load())
        Files.writeString(directory.resolve("ui-preferences.json"), """{"theme":"dark","future":true}""")
        assertEquals(UiPreferences(theme = "dark"), store.load())
    }

    @Test
    fun corruptFileFallsBackAndUnknownValuesAreSanitized() = runBlocking {
        val directory = Files.createTempDirectory("ui-preferences-corrupt")
        val file = directory.resolve("ui-preferences.json")
        val store = UiPreferencesStore(directory)

        Files.writeString(file, "not json")
        assertEquals(UiPreferences(), store.load())
        Files.writeString(file, """{"theme":"sepia","density":"huge","layout":"other","inspectorWidth":999.0}""")
        assertEquals(UiPreferences(inspectorWidth = 480f), store.load())
    }

    @Test
    fun saveClampsWidthRejectsNonFiniteValuesAndReplacesAtomically() = runBlocking {
        val directory = Files.createTempDirectory("ui-preferences-save")
        val store = UiPreferencesStore(directory)

        store.save(UiPreferences(theme = "light", density = "compact", layout = "studio", inspectorWidth = 100f, reducedMotion = true))
        assertEquals(UiPreferences("light", "compact", "studio", 280f, true), store.load())
        store.save(UiPreferences(inspectorWidth = Float.NaN))
        assertEquals(348f, store.load().inspectorWidth)
        store.save(UiPreferences(inspectorWidth = Float.POSITIVE_INFINITY))
        assertEquals(348f, store.load().inspectorWidth)
        assertFalse(Files.list(directory).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".tmp") } })
        assertTrue(Files.exists(directory.resolve("ui-preferences.json")))
    }
}
