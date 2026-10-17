package dev.chiller.app.di

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/** OS-backed storage for provider credentials. Origins are used as Keychain account names. */
interface CredentialStore {
    val supported: Boolean
    fun load(origin: String): String?
    fun save(origin: String, key: String)
    fun delete(origin: String)

    companion object {
        /** Explicit opt-out used by tests and isolated preview runs. */
        val None: CredentialStore = object : CredentialStore {
            override val supported = false
            override fun load(origin: String): String? = null
            override fun save(origin: String, key: String) = Unit
            override fun delete(origin: String) = Unit
        }

        fun system(): CredentialStore = if (MacKeychainCredentialStore.isMacOs()) MacKeychainCredentialStore() else None
    }
}

/** macOS Generic Password item store using Security.framework directly. */
class MacKeychainCredentialStore internal constructor(private val native: KeychainNative?) : CredentialStore {
    constructor() : this(if (isMacOs()) JnaKeychainNative() else null)

    override val supported: Boolean get() = native != null

    override fun load(origin: String): String? {
        val api = requireSupported()
        val account = originBytes(origin)
        try {
            val item = api.find(account) ?: return null
            try {
                val decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                return try { decoder.decode(java.nio.ByteBuffer.wrap(item)).toString() }
                catch (_: Exception) { throw CredentialStoreException("Keychain credential is not valid UTF-8") }
            } finally {
                item.fill(0)
            }
        } finally {
            account.fill(0)
        }
    }

    override fun save(origin: String, key: String) {
        val api = requireSupported()
        val account = originBytes(origin)
        val value = key.toByteArray(StandardCharsets.UTF_8)
        try {
            if (value.isEmpty()) throw IllegalArgumentException("Credential must not be empty")
            if (value.size > MAX_SECRET_BYTES) throw IllegalArgumentException("Credential exceeds the supported size")
            api.put(account, value)
        } finally {
            account.fill(0)
            value.fill(0)
        }
    }

    override fun delete(origin: String) {
        val api = requireSupported()
        val account = originBytes(origin)
        try { api.remove(account) } finally { account.fill(0) }
    }

    private fun requireSupported(): KeychainNative = native ?: throw CredentialStoreException("macOS Keychain is unavailable on this platform")

    private fun originBytes(origin: String): ByteArray {
        require(origin.isNotBlank()) { "Credential origin must not be blank" }
        val bytes = origin.trim().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_ACCOUNT_BYTES) { "Credential origin is too long" }
        return bytes
    }

    companion object {
        private const val MAX_SECRET_BYTES = 1024 * 1024
        private const val MAX_ACCOUNT_BYTES = 1024
        internal fun isMacOs() = System.getProperty("os.name").lowercase(Locale.ROOT).contains("mac")
    }
}

class CredentialStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Small boundary so tests never need to access the user's actual Keychain. */
internal interface KeychainNative {
    fun find(account: ByteArray): ByteArray?
    fun put(account: ByteArray, value: ByteArray)
    fun remove(account: ByteArray)
}

private class JnaKeychainNative : KeychainNative {
    private val security: Security = try { Native.load("Security", Security::class.java) }
    catch (e: Throwable) { throw CredentialStoreException("macOS Keychain could not be initialized", e) }

    override fun find(account: ByteArray): ByteArray? {
        val service = SERVICE.toByteArray(StandardCharsets.UTF_8)
        val length = IntByReference()
        val data = PointerByReference()
        val item = PointerByReference()
        val status = withMemory(service) { s -> withMemory(account) { a ->
            security.SecKeychainFindGenericPassword(null, service.size, s, account.size, a, length, data, item)
        } }
        if (status == ERR_SEC_ITEM_NOT_FOUND) return null
        checkStatus("read", status)
        try {
            if (length.value < 0 || length.value > MAX_SECRET_BYTES) throw CredentialStoreException("Keychain credential exceeds the supported size")
            return data.value?.getByteArray(0, length.value) ?: ByteArray(0)
        } finally {
            data.value?.let { security.SecKeychainItemFreeContent(null, it) }
            release(item.value)
        }
    }

    override fun put(account: ByteArray, value: ByteArray) {
        val service = SERVICE.toByteArray(StandardCharsets.UTF_8)
        val item = PointerByReference()
        val status = withMemory(service) { s -> withMemory(account) { a -> withMemory(value) { v ->
            security.SecKeychainAddGenericPassword(null, service.size, s, account.size, a, value.size, v, item)
        } } }
        if (status == ERR_SEC_DUPLICATE_ITEM) {
            val existing = findItem(account)
            try {
                val updateStatus = withMemory(value) { v -> security.SecKeychainItemModifyAttributesAndData(existing, null, value.size, v) }
                checkStatus("update", updateStatus)
            } finally { release(existing) }
        } else {
            checkStatus("save", status)
            release(item.value)
        }
    }

    override fun remove(account: ByteArray) {
        val item = findItem(account) ?: return
        try { checkStatus("delete", security.SecKeychainItemDelete(item)) } finally { release(item) }
    }

    private fun findItem(account: ByteArray): Pointer? {
        val service = SERVICE.toByteArray(StandardCharsets.UTF_8)
        val item = PointerByReference()
        val status = withMemory(service) { s -> withMemory(account) { a ->
            // The password length/data outputs are optional. Passing null avoids allocating secret data
            // when this lookup only needs the item reference for update/delete.
            security.SecKeychainFindGenericPassword(null, service.size, s, account.size, a, null, null, item)
        } }
        if (status == ERR_SEC_ITEM_NOT_FOUND) return null
        checkStatus("find", status)
        return item.value
    }

    private fun release(pointer: Pointer?) { if (pointer != null) coreFoundation.CFRelease(pointer) }

    private fun checkStatus(operation: String, status: Int) {
        if (status != ERR_SEC_SUCCESS) throw CredentialStoreException("Keychain $operation failed (status $status)")
    }

    private inline fun <T> withMemory(value: ByteArray, block: (Memory) -> T): T {
        val memory = Memory(value.size.toLong())
        try {
            memory.write(0, value, 0, value.size)
            return block(memory)
        } finally {
            // JNA 5.6 exposes no public close/dispose on Memory; clear sensitive bytes now and
            // let JNA's native-memory cleaner release the allocation.
            memory.clear()
        }
    }

    private interface Security : Library {
        fun SecKeychainFindGenericPassword(keychain: Pointer?, serviceLength: Int, service: Pointer, accountLength: Int, account: Pointer,
            passwordLength: IntByReference?, passwordData: PointerByReference?, item: PointerByReference): Int
        fun SecKeychainAddGenericPassword(keychain: Pointer?, serviceLength: Int, service: Pointer, accountLength: Int, account: Pointer,
            passwordLength: Int, passwordData: Pointer, item: PointerByReference): Int
        fun SecKeychainItemModifyAttributesAndData(item: Pointer?, attributes: Pointer?, length: Int, data: Pointer): Int
        fun SecKeychainItemDelete(item: Pointer?): Int
        fun SecKeychainItemFreeContent(attributes: Pointer?, data: Pointer?): Int
    }

    private interface CoreFoundation : Library { fun CFRelease(value: Pointer?) }
    private val coreFoundation: CoreFoundation by lazy { Native.load("CoreFoundation", CoreFoundation::class.java) }

    companion object {
        private const val SERVICE = "dev.chiller.provider-credential"
        private const val MAX_SECRET_BYTES = 1024 * 1024
        private const val ERR_SEC_SUCCESS = 0
        private const val ERR_SEC_ITEM_NOT_FOUND = -25300
        private const val ERR_SEC_DUPLICATE_ITEM = -25299
    }
}
