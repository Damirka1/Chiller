package dev.chiller.app.di

import java.util.UUID

/** Explicit native smoke; never discovers or touches configured provider origins. */
fun main() {
    check(MacKeychainCredentialStore.isMacOs()) { "Native Keychain smoke requires macOS" }
    val store = MacKeychainCredentialStore()
    val origin = "https://chiller-smoke-${UUID.randomUUID()}.invalid:443"
    val first = UUID.randomUUID().toString()
    val second = UUID.randomUUID().toString()
    try {
        check(store.load(origin) == null) { "Disposable account unexpectedly exists" }
        store.save(origin, first)
        check(MacKeychainCredentialStore().load(origin) == first) { "Credential did not survive store recreation" }
        store.save(origin, second)
        check(store.load(origin) == second) { "Credential update failed" }
        store.delete(origin)
        check(store.load(origin) == null) { "Credential deletion failed" }
        println("Keychain smoke passed: create, reopen, update, delete (synthetic disposable credential).")
    } finally {
        store.delete(origin)
    }
}
