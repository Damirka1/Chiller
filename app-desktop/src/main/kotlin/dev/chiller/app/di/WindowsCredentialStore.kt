package dev.chiller.app.di

import dev.chiller.app.i18n.*
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Windows Credential Manager store: one generic credential per origin, named `Chiller/<origin>`, kept for the
 * current user on this machine (not roaming). Windows encrypts it with the user's logon secret; it is visible
 * and removable in "Credential Manager → Windows Credentials".
 */
class WindowsCredentialStore internal constructor(private val native: WinCredNative?) : CredentialStore {
    constructor() : this(if (isWindows()) JnaWinCredNative() else null)

    override val supported: Boolean get() = native != null
    override val name: String get() = I18n.current.di.windowsCredentials

    override fun load(origin: String): String? {
        val api = requireSupported()
        val blob = api.read(target(origin)) ?: return null
        try {
            val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            return try { decoder.decode(java.nio.ByteBuffer.wrap(blob)).toString() }
            catch (_: Exception) { throw CredentialStoreException("Stored credential is not valid UTF-8") }
        } finally { blob.fill(0) }
    }

    override fun save(origin: String, key: String) {
        val api = requireSupported()
        val target = target(origin)
        val value = key.toByteArray(StandardCharsets.UTF_8)
        try {
            if (value.isEmpty()) throw IllegalArgumentException("Credential must not be empty")
            if (value.size > MAX_BLOB_BYTES) throw IllegalArgumentException("Credential exceeds the supported size")
            api.write(target, value)
        } finally { value.fill(0) }
    }

    override fun delete(origin: String) { requireSupported().remove(target(origin)) }

    private fun requireSupported(): WinCredNative = native ?: throw CredentialStoreException("Windows Credential Manager is unavailable on this platform")

    private fun target(origin: String): String {
        require(origin.isNotBlank()) { "Credential origin must not be blank" }
        val target = TARGET_PREFIX + origin.trim()
        require(target.length <= MAX_TARGET_CHARS) { "Credential origin is too long" }
        return target
    }

    companion object {
        internal const val TARGET_PREFIX = "Chiller/"
        /** CRED_MAX_CREDENTIAL_BLOB_SIZE: 5 × 512 bytes. API keys are far smaller. */
        private const val MAX_BLOB_BYTES = 2560
        /** CRED_MAX_GENERIC_TARGET_NAME_LENGTH. */
        private const val MAX_TARGET_CHARS = 32767
        internal fun isWindows() = System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows")
    }
}

/** Small boundary so tests never touch the user's real Credential Manager. */
internal interface WinCredNative {
    fun read(target: String): ByteArray?
    fun write(target: String, value: ByteArray)
    fun remove(target: String)
}

private class JnaWinCredNative : WinCredNative {
    private val advapi: Advapi32 = try { Native.load("Advapi32", Advapi32::class.java) }
    catch (e: Throwable) { throw CredentialStoreException("Windows Credential Manager could not be initialized", e) }

    override fun read(target: String): ByteArray? {
        val out = PointerByReference()
        if (!advapi.CredReadW(WString(target), CRED_TYPE_GENERIC, 0, out)) {
            val error = Native.getLastError()
            if (error == ERROR_NOT_FOUND) return null
            throw CredentialStoreException("Credential Manager read failed (error $error)")
        }
        val pointer = out.value
        try {
            val credential = Credential(pointer)
            val size = credential.CredentialBlobSize
            if (size < 0 || size > 2560) throw CredentialStoreException("Stored credential exceeds the supported size")
            return credential.CredentialBlob?.getByteArray(0, size) ?: ByteArray(0)
        } finally { advapi.CredFree(pointer) }
    }

    override fun write(target: String, value: ByteArray) {
        val blob = Memory(value.size.toLong())
        try {
            blob.write(0, value, 0, value.size)
            val credential = Credential().apply {
                Type = CRED_TYPE_GENERIC
                TargetName = WString(target)
                Comment = WString("Chiller provider API key")
                CredentialBlobSize = value.size
                CredentialBlob = blob
                Persist = CRED_PERSIST_LOCAL_MACHINE
                UserName = WString("Chiller")
            }
            if (!advapi.CredWriteW(credential, 0)) throw CredentialStoreException("Credential Manager save failed (error ${Native.getLastError()})")
        } finally {
            blob.clear()
            blob.close()
        }
    }

    override fun remove(target: String) {
        if (!advapi.CredDeleteW(WString(target), CRED_TYPE_GENERIC, 0)) {
            val error = Native.getLastError()
            if (error != ERROR_NOT_FOUND) throw CredentialStoreException("Credential Manager delete failed (error $error)")
        }
    }

    /** CREDENTIALW; FILETIME LastWritten is two DWORDs. */
    @Structure.FieldOrder("Flags", "Type", "TargetName", "Comment", "LastWrittenLow", "LastWrittenHigh", "CredentialBlobSize",
        "CredentialBlob", "Persist", "AttributeCount", "Attributes", "TargetAlias", "UserName")
    class Credential : Structure {
        @JvmField var Flags: Int = 0
        @JvmField var Type: Int = 0
        @JvmField var TargetName: WString? = null
        @JvmField var Comment: WString? = null
        @JvmField var LastWrittenLow: Int = 0
        @JvmField var LastWrittenHigh: Int = 0
        @JvmField var CredentialBlobSize: Int = 0
        @JvmField var CredentialBlob: Pointer? = null
        @JvmField var Persist: Int = 0
        @JvmField var AttributeCount: Int = 0
        @JvmField var Attributes: Pointer? = null
        @JvmField var TargetAlias: WString? = null
        @JvmField var UserName: WString? = null
        constructor() : super()
        constructor(pointer: Pointer) : super(pointer) { read() }
    }

    @Suppress("FunctionName")
    private interface Advapi32 : Library {
        fun CredReadW(target: WString, type: Int, flags: Int, credential: PointerByReference): Boolean
        fun CredWriteW(credential: Credential, flags: Int): Boolean
        fun CredDeleteW(target: WString, type: Int, flags: Int): Boolean
        fun CredFree(buffer: Pointer)
    }

    companion object {
        private const val CRED_TYPE_GENERIC = 1
        private const val CRED_PERSIST_LOCAL_MACHINE = 2
        private const val ERROR_NOT_FOUND = 1168
    }
}
