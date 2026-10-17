package dev.chiller.app.di

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialStoreTest {
    private class FakeKeychain : KeychainNative {
        val values = mutableMapOf<String, ByteArray>()
        var failWith: RuntimeException? = null
        override fun find(account: ByteArray): ByteArray? {
            failWith?.let { throw it }
            return values[account.decodeToString()]?.copyOf()
        }
        override fun put(account: ByteArray, value: ByteArray) {
            failWith?.let { throw it }
            values[account.decodeToString()] = value.copyOf()
        }
        override fun remove(account: ByteArray) {
            failWith?.let { throw it }
            values.remove(account.decodeToString())?.fill(0)
        }
    }

    @Test fun `save load overwrite and delete use the origin account`() {
        val fake = FakeKeychain()
        val store = MacKeychainCredentialStore(fake)
        assertTrue(store.supported)
        assertNull(store.load("https://api.example"))
        store.save("https://api.example", "synthetic-key")
        assertEquals("synthetic-key", store.load("https://api.example"))
        store.save("https://api.example", "replacement")
        assertEquals("replacement", store.load("https://api.example"))
        store.delete("https://api.example")
        assertNull(store.load("https://api.example"))
        store.delete("https://api.example")
    }

    @Test fun `unsupported platform gives clear unavailable status`() {
        val store = MacKeychainCredentialStore(null)
        assertFalse(store.supported)
        assertFailsWith<CredentialStoreException> { store.load("origin") }
    }

    @Test fun `input validation and native errors do not expose credential data`() {
        val fake = FakeKeychain()
        val store = MacKeychainCredentialStore(fake)
        assertFailsWith<IllegalArgumentException> { store.save(" ", "synthetic-key") }
        assertFailsWith<IllegalArgumentException> { store.save("origin", "") }
        fake.failWith = CredentialStoreException("Keychain read failed (status -1)")
        val error = assertFailsWith<CredentialStoreException> { store.save("origin", "synthetic-key") }
        assertFalse(error.message.orEmpty().contains("synthetic-key"))
    }

    @Test fun `malformed stored bytes are rejected and cleared from returned buffer`() {
        val fake = FakeKeychain()
        fake.values["origin"] = byteArrayOf(0xC3.toByte(), 0x28)
        val store = MacKeychainCredentialStore(fake)
        assertFailsWith<CredentialStoreException> { store.load("origin") }
    }
}
