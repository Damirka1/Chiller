package dev.chiller.app.di

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomProviderTest {
    // Helpers read the global language; these checks are written against the Russian texts.
    @kotlin.test.BeforeTest fun russian() { dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.RuStrings }
    @kotlin.test.AfterTest fun english() { dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.EnStrings }

    @TempDir lateinit var directory: Path

    @Test fun `invalid providers are refused with a reason`() {
        val good = CustomProvider("a", "Qarmet", "https://gateway.example.com/ai/api/v1/", "QARMET_API_KEY", "quasar", thinking = true, profile = "full")
        assertNull(good.normalized().problem())
        assertEquals("https://gateway.example.com/ai/api/v1", good.normalized().baseUrl)
        assertNotNull(good.copy(name = " ").problem())
        assertNull(good.copy(baseUrl = "http://gateway.example.com/v1").problem(), "remote HTTP is allowed")
        assertNull(good.copy(baseUrl = "http://localhost:8000/v1").problem(), "loopback HTTP is fine")
        assertNotNull(good.copy(baseUrl = "https://user:pw@gateway.example.com/v1").problem())
        assertNotNull(good.copy(keyVariable = "QARMET-KEY").problem())
        assertNotNull(good.copy(profile = "huge").problem())
    }

    @Test fun `a saved provider keeps its named key on its own origin and survives a restart`() = runBlocking {
        val keys = mapOf("QARMET_API_KEY" to "secret-from-env")
        suspend fun open() = DesktopController(directory, initialCredential = null, catalogClient = OfflineCatalog, keyLookup = keys::get).also { it.initialize() }
        var controller = open()
        try {
            val provider = CustomProvider("q1", "Qarmet", "https://gateway.example.com/ai/api/v1", "QARMET_API_KEY", "quasar", thinking = true, profile = "full")
            assertNull(controller.saveCustomProvider(provider))
            assertEquals("env", controller.keySources()["https://gateway.example.com:443"])
            assertTrue(controller.keySources().keys.none { it.contains("deepseek") }, "the key is bound to the provider's origin only")
            assertEquals("Название: от 1 до 60 символов", controller.saveCustomProvider(provider.copy(id = "q2", name = "")))
            controller.close()
            controller = open()
            assertEquals(listOf(provider), controller.state.value.settings.customProviders)
            assertEquals("env", controller.keySources()["https://gateway.example.com:443"], "the key is read again at start")
            controller.deleteCustomProvider("q1")
            assertTrue(controller.state.value.settings.customProviders.isEmpty())
        } finally { controller.close() }
    }

    @Test fun `a key typed in the app is not replaced by the variable`() = runBlocking {
        val controller = DesktopController(directory, initialCredential = null, catalogClient = OfflineCatalog, keyLookup = { "from-env" }).also { it.initialize() }
        try {
            controller.configure("https://gateway.example.com/v1", "quasar", true, "typed-key")
            controller.saveCustomProvider(CustomProvider("q", "Qarmet", "https://gateway.example.com/v1", "QARMET_API_KEY"))
            assertEquals("ui", controller.keySources()["https://gateway.example.com:443"])
        } finally { controller.close() }
    }

    @Test fun `custom presets are found by origin after the built-in ones`() {
        val custom = listOf(CustomProvider("q", "Qarmet", "https://gateway.example.com/ai/api/v1", "QARMET_API_KEY"))
        assertEquals("custom:q", ProviderPresets.byOrigin("https://gateway.example.com/other", custom)?.id)
        assertEquals("deepseek", ProviderPresets.byOrigin("https://api.deepseek.com", custom)?.id)
    }
}
