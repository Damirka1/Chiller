package dev.chiller.app.di

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsCredentialStoreTest {
    // Helpers read the global language; these checks are written against the Russian texts.
    @kotlin.test.BeforeTest fun russian() { dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.RuStrings }
    @kotlin.test.AfterTest fun english() { dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.EnStrings }

    private class FakeCredentials : WinCredNative {
        val values = mutableMapOf<String, ByteArray>()
        var failWith: RuntimeException? = null
        override fun read(target: String): ByteArray? { failWith?.let { throw it }; return values[target]?.copyOf() }
        override fun write(target: String, value: ByteArray) { failWith?.let { throw it }; values[target] = value.copyOf() }
        override fun remove(target: String) { failWith?.let { throw it }; values.remove(target) }
    }

    @Test fun `keys are stored per origin under a readable Chiller target`() {
        val fake = FakeCredentials()
        val store = WindowsCredentialStore(fake)
        assertTrue(store.supported)
        assertNull(store.load("https://gateway.example.com:443"))
        store.save("https://gateway.example.com:443", "synthetic-key")
        assertEquals(setOf("Chiller/https://gateway.example.com:443"), fake.values.keys)
        assertEquals("synthetic-key", store.load("https://gateway.example.com:443"))
        store.save("https://gateway.example.com:443", "replacement")
        assertEquals("replacement", store.load("https://gateway.example.com:443"))
        assertNull(store.load("https://api.deepseek.com:443"), "another origin never sees it")
        store.delete("https://gateway.example.com:443")
        assertNull(store.load("https://gateway.example.com:443"))
        store.delete("https://gateway.example.com:443")
    }

    @Test fun `bad input, foreign bytes and native errors stay safe`() {
        val fake = FakeCredentials()
        val store = WindowsCredentialStore(fake)
        assertFailsWith<IllegalArgumentException> { store.save(" ", "synthetic-key") }
        assertFailsWith<IllegalArgumentException> { store.save("origin", "") }
        assertFailsWith<IllegalArgumentException> { store.save("origin", "x".repeat(2561)) }
        fake.values["Chiller/origin"] = byteArrayOf(0xC3.toByte(), 0x28)
        assertFailsWith<CredentialStoreException> { store.load("origin") }
        fake.failWith = CredentialStoreException("Credential Manager save failed (error 5)")
        val error = assertFailsWith<CredentialStoreException> { store.save("origin", "synthetic-key") }
        assertFalse(error.message.orEmpty().contains("synthetic-key"))
    }

    @Test fun `unavailable off Windows`() {
        val store = WindowsCredentialStore(null)
        assertFalse(store.supported)
        assertFailsWith<CredentialStoreException> { store.load("origin") }
        assertEquals("Диспетчер учётных данных Windows", store.name)
    }
}
