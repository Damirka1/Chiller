package dev.chiller.app.di

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import dev.chiller.contracts.CaptureTarget
import dev.chiller.contracts.FilePresentation
import dev.chiller.contracts.ScreenCaptureService
import dev.chiller.contracts.ToolException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Captures only a window explicitly selected by the user in the app's window picker.
 * The model can see that selected target through [targets], but cannot choose another window.
 */
class WindowCaptureController private constructor(
    outputDirectory: Path,
    private val sessionIdProvider: () -> String,
    private val native: WindowCaptureNative,
    private val capturer: WindowImageCapturer,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : ScreenCaptureService {
    constructor(outputDirectory: Path, sessionIdProvider: () -> String = { "captures" }) : this(outputDirectory, sessionIdProvider, defaultNative(), MacScreencaptureCommand, Unit)
    internal constructor(outputDirectory: Path, native: WindowCaptureNative, capturer: WindowImageCapturer, sessionIdProvider: () -> String = { "test" }) : this(outputDirectory, sessionIdProvider, native, capturer, Unit)
    private val outputRoot = outputDirectory.toAbsolutePath().normalize()
    @Volatile private var selection: WindowRecord? = null

    override val supported: Boolean get() = native.supported

    /** Lists bounded, on-screen, layer-zero windows for the UI picker only. */
    fun listAvailable(): List<CaptureTarget> {
        ensureSupported()
        ensurePermission(request = true)
        return runCatching { native.windows().asSequence()
            .filter { it.onScreen && it.layer == 0 && it.id > 0 && it.ownerPid > 0 }
            .distinctBy { it.id }
            .take(MAX_WINDOWS)
            .map(::presentationTarget)
            .toList()
        }.getOrElse { throw ToolException("CAPTURE_UNAVAILABLE", "Window list could not be read") }
    }

    /** Stores a user-picker choice after resolving it against a fresh native window list. */
    fun select(windowId: String): CaptureTarget {
        val parsed = windowId.toLongOrNull()?.takeIf { it > 0 }
            ?: throw ToolException("INVALID_CAPTURE_TARGET", "Choose a window from the available-window list")
        val record = currentWindows().firstOrNull { it.id == parsed }
            ?: throw ToolException("CAPTURE_TARGET_CHANGED", "That window is no longer available", "Refresh the window list and choose again")
        selection = record
        return presentationTarget(record)
    }

    fun selected(): CaptureTarget? = selection?.let { record ->
        currentWindows().firstOrNull { it.sameIdentity(record) }?.let(::presentationTarget)
    }

    /** Exposes at most the user's current selection to model-facing callers. */
    override suspend fun targets(): List<CaptureTarget> {
        ensureSupported()
        ensurePermission()
        val chosen = selection ?: return emptyList()
        val stillPresent = currentWindows().firstOrNull { it.sameIdentity(chosen) }
            ?: run { selection = null; throw ToolException("CAPTURE_TARGET_CHANGED", "The selected window changed or closed", "Choose a window again") }
        return listOf(presentationTarget(stillPresent))
    }

    override suspend fun capture(): FilePresentation {
        ensureSupported()
        ensurePermission()
        val chosen = selection ?: throw ToolException("CAPTURE_TARGET_REQUIRED", "Choose a window in the app before asking for a capture")
        val current = currentWindows().firstOrNull { it.sameIdentity(chosen) }
            ?: run { selection = null; throw ToolException("CAPTURE_TARGET_CHANGED", "The selected window changed or closed", "Choose a window again") }
        // Re-check directly before starting the fixed executable; never fall back to the full display.
        ensurePermission()
        val root = try {
            withContext(Dispatchers.IO) {
                Files.createDirectories(outputRoot)
                if (Files.isSymbolicLink(outputRoot)) throw IllegalStateException("Capture root must be app-owned")
                outputRoot.toRealPath()
            }
        }
        catch (_: Exception) { throw ToolException("CAPTURE_STORAGE_UNAVAILABLE", "Capture storage is unavailable") }
        val session = sessionIdProvider().takeIf { SESSION_ID.matches(it) }
            ?: throw ToolException("CAPTURE_STORAGE_UNAVAILABLE", "The active session has an invalid storage identifier")
        val sessionRoot = root.resolve(session).normalize()
        if (!sessionRoot.startsWith(root)) throw ToolException("CAPTURE_STORAGE_UNAVAILABLE", "Capture storage is unavailable")
        val destination = sessionRoot.resolve("window-${UUID.randomUUID()}.png")
        try {
            withContext(Dispatchers.IO) {
                Files.createDirectories(sessionRoot)
                if (Files.isSymbolicLink(sessionRoot)) throw ToolException("CAPTURE_STORAGE_UNAVAILABLE", "Session capture storage must be app-owned")
            }
            withTimeout(CAPTURE_TIMEOUT_MILLIS) { capturer.capture(current.id, destination) }
            val real = withContext(Dispatchers.IO) {
                if (Files.isSymbolicLink(destination) || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS))
                    throw ToolException("CAPTURE_FAILED", "Window capture did not produce a regular image")
                val size = Files.size(destination)
                if (size !in PNG_SIGNATURE.size.toLong()..MAX_PNG_BYTES) throw ToolException("CAPTURE_SIZE_LIMIT", "Window capture is empty or exceeds the image size limit")
                val signature = Files.newInputStream(destination).use { it.readNBytes(PNG_SIGNATURE.size) }
                if (!signature.contentEquals(PNG_SIGNATURE)) throw ToolException("CAPTURE_FAILED", "Window capture did not produce a PNG image")
                javax.imageio.ImageIO.createImageInputStream(destination.toFile()).use { input ->
                    if (input == null) throw ToolException("CAPTURE_FAILED", "Invalid captured PNG")
                    val readers = javax.imageio.ImageIO.getImageReaders(input)
                    if (!readers.hasNext()) throw ToolException("CAPTURE_FAILED", "Invalid captured PNG")
                    val reader = readers.next()
                    try {
                        reader.input = input
                        if (reader.formatName.lowercase() != "png" || reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0)
                            throw ToolException("CAPTURE_FAILED", "Invalid captured PNG dimensions")
                    } finally { reader.dispose() }
                }
                destination.toRealPath()
            }
            return FilePresentation(real.toString(), "${safeName(current.application)}.png", "image/png", Files.size(real))
        } catch (_: TimeoutCancellationException) {
            withContext(Dispatchers.IO + NonCancellable) { Files.deleteIfExists(destination); runCatching { Files.delete(sessionRoot) } }
            throw ToolException("CAPTURE_TIMEOUT", "Window capture exceeded its time limit")
        } catch (cancelled: CancellationException) {
            withContext(Dispatchers.IO + NonCancellable) { Files.deleteIfExists(destination); runCatching { Files.delete(sessionRoot) } }
            throw cancelled
        } catch (failure: ToolException) {
            withContext(Dispatchers.IO + NonCancellable) { Files.deleteIfExists(destination); runCatching { Files.delete(sessionRoot) } }
            throw failure
        } catch (_: Exception) {
            withContext(Dispatchers.IO + NonCancellable) { Files.deleteIfExists(destination); runCatching { Files.delete(sessionRoot) } }
            throw ToolException("CAPTURE_FAILED", "Window capture failed; confirm the selected window is still visible")
        }
    }

    private fun currentWindows(): List<WindowRecord> {
        ensureSupported()
        ensurePermission()
        return try { native.windows().asSequence().filter { it.onScreen && it.layer == 0 }.take(MAX_WINDOWS).toList() }
        catch (_: Exception) { throw ToolException("CAPTURE_UNAVAILABLE", "Window list could not be read") }
    }

    private fun ensureSupported() {
        if (!supported) throw ToolException("CAPTURE_UNSUPPORTED", "Selected-window capture is available only on supported macOS systems")
    }

    private fun ensurePermission(request: Boolean = false) {
        val granted = try {
            native.hasPermission() || (request && native.requestPermission())
        } catch (_: Exception) { false }
        if (!granted) throw ToolException("CAPTURE_PERMISSION_REQUIRED", "macOS screen recording permission is required", "Allow Chiller in System Settings → Privacy & Security → Screen & System Audio Recording, restart Chiller if required, then refresh the window list")
    }

    private fun presentationTarget(record: WindowRecord) = CaptureTarget(
        record.id.toString(), record.title.ifBlank { record.application }.take(MAX_TITLE_CHARS), record.application.take(MAX_APP_NAME_CHARS),
    )
    private fun WindowRecord.sameIdentity(other: WindowRecord) = id == other.id && ownerPid == other.ownerPid && title == other.title && application == other.application && onScreen && layer == 0

    private fun safeName(value: String): String = value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(48).ifBlank { "window" }

    companion object {
        private const val MAX_WINDOWS = 100
        private const val MAX_PNG_BYTES = 16L * 1024 * 1024
        private const val CAPTURE_TIMEOUT_MILLIS = 10_500L
        private const val MAX_TITLE_CHARS = 300
        private const val MAX_APP_NAME_CHARS = 100
        private val SESSION_ID = Regex("[A-Za-z0-9_-]{1,80}")
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

        private fun defaultNative(): WindowCaptureNative {
            if (!System.getProperty("os.name").lowercase(Locale.ROOT).contains("mac")) return UnsupportedWindowCaptureNative
            return runCatching { MacWindowCaptureNative() }.getOrDefault(UnsupportedWindowCaptureNative)
        }
    }
}

internal data class WindowRecord(
    val id: Long,
    val ownerPid: Int,
    val title: String,
    val application: String,
    val layer: Int = 0,
    val onScreen: Boolean = true,
)

internal interface WindowCaptureNative {
    val supported: Boolean
    fun hasPermission(): Boolean
    fun requestPermission(): Boolean = false
    fun windows(): List<WindowRecord>
}

internal object UnsupportedWindowCaptureNative : WindowCaptureNative {
    override val supported = false
    override fun hasPermission() = false
    override fun windows(): List<WindowRecord> = emptyList()
}

internal fun interface WindowImageCapturer {
    suspend fun capture(windowId: Long, destination: Path)
}

/** Runs only the fixed macOS system executable with an OS window ID and app-owned output path. */
internal object MacScreencaptureCommand : WindowImageCapturer {
    override suspend fun capture(windowId: Long, destination: Path) = withContext(Dispatchers.IO) {
        if (!System.getProperty("os.name").lowercase(Locale.ROOT).contains("mac"))
            throw ToolException("CAPTURE_UNSUPPORTED", "Selected-window capture is available only on macOS")
        val process = ProcessBuilder("/usr/sbin/screencapture", "-x", "-o", "-l", windowId.toString(), destination.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        try {
            val exited = runInterruptible {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CAPTURE_TIMEOUT_SECONDS)
                var complete = false
                while (!complete && System.nanoTime() < deadline) {
                    if (Files.exists(destination) && Files.size(destination) > MAX_PNG_BYTES)
                        throw ToolException("CAPTURE_SIZE_LIMIT", "Window capture exceeds the image size limit")
                    complete = process.waitFor(100, TimeUnit.MILLISECONDS)
                }
                complete
            }
            if (!exited) throw ToolException("CAPTURE_TIMEOUT", "Window capture exceeded its time limit")
            if (process.exitValue() != 0) throw ToolException("CAPTURE_FAILED", "macOS could not capture the selected window")
        } finally {
            if (process.isAlive) {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    private const val CAPTURE_TIMEOUT_SECONDS = 10L
    private const val MAX_PNG_BYTES = 16L * 1024 * 1024
}

/** JNA bindings for the CoreGraphics/CoreFoundation APIs used to enumerate onscreen windows. */
private class MacWindowCaptureNative : WindowCaptureNative {
    private val cg = Native.load("CoreGraphics", CoreGraphicsApi::class.java)
    private val cf = Native.load("CoreFoundation", CoreFoundationApi::class.java)
    override val supported = true

    override fun hasPermission(): Boolean = cg.CGPreflightScreenCaptureAccess().toInt() != 0
    override fun requestPermission(): Boolean = cg.CGRequestScreenCaptureAccess().toInt() != 0

    override fun windows(): List<WindowRecord> {
        val array = cg.CGWindowListCopyWindowInfo(ON_SCREEN_ONLY or EXCLUDE_DESKTOP, 0)
            ?: throw IllegalStateException("No window list")
        try {
            val count = cf.CFArrayGetCount(array).coerceIn(0, MAX_NATIVE_WINDOWS.toLong())
            return (0 until count).mapNotNull { index ->
                val dict = cf.CFArrayGetValueAtIndex(array, index) ?: return@mapNotNull null
                val id = number(dict, "kCGWindowNumber")?.toLong() ?: return@mapNotNull null
                val pid = number(dict, "kCGWindowOwnerPID") ?: return@mapNotNull null
                val layer = number(dict, "kCGWindowLayer") ?: return@mapNotNull null
                val onscreen = boolean(dict, "kCGWindowIsOnscreen")
                WindowRecord(id, pid, string(dict, "kCGWindowName").orEmpty(), string(dict, "kCGWindowOwnerName").orEmpty(), layer, onscreen)
            }
        } finally { cf.CFRelease(array) }
    }

    private fun number(dictionary: Pointer, name: String): Int? = withKey(name) { key ->
        val number = cf.CFDictionaryGetValue(dictionary, key) ?: return@withKey null
        val memory = Memory(Int.SIZE_BYTES.toLong())
        try { if (cf.CFNumberGetValue(number, CF_NUMBER_SINT32, memory).toInt() == 0) null else memory.getInt(0) }
        finally { memory.clear(); memory.close() }
    }

    private fun string(dictionary: Pointer, name: String): String? = withKey(name) { key ->
        val value = cf.CFDictionaryGetValue(dictionary, key) ?: return@withKey null
        val buffer = Memory(MAX_NATIVE_STRING_BYTES.toLong())
        try {
            if (cf.CFStringGetCString(value, buffer, MAX_NATIVE_STRING_BYTES.toLong(), UTF8_ENCODING).toInt() == 0) null
            else buffer.getString(0, "UTF-8").take(MAX_NATIVE_STRING_BYTES - 1)
        } finally { buffer.clear(); buffer.close() }
    }

    private fun boolean(dictionary: Pointer, name: String): Boolean = withKey(name) { key ->
        val value = cf.CFDictionaryGetValue(dictionary, key) ?: return@withKey false
        cf.CFBooleanGetValue(value).toInt() != 0
    }

    private inline fun <T> withKey(name: String, block: (Pointer) -> T): T {
        val key = cf.CFStringCreateWithCString(null, name, UTF8_ENCODING) ?: throw IllegalStateException("Could not create CFString key")
        return try { block(key) } finally { cf.CFRelease(key) }
    }

    private interface CoreGraphicsApi : Library {
        fun CGWindowListCopyWindowInfo(option: Int, relativeToWindow: Int): Pointer?
        fun CGPreflightScreenCaptureAccess(): Byte
        fun CGRequestScreenCaptureAccess(): Byte
    }

    private interface CoreFoundationApi : Library {
        fun CFArrayGetCount(array: Pointer): Long
        fun CFArrayGetValueAtIndex(array: Pointer, index: Long): Pointer?
        fun CFDictionaryGetValue(dictionary: Pointer, key: Pointer): Pointer?
        fun CFStringCreateWithCString(allocator: Pointer?, value: String, encoding: Int): Pointer?
        fun CFStringGetCString(value: Pointer, buffer: Pointer, size: Long, encoding: Int): Byte
        fun CFNumberGetValue(value: Pointer, numberType: Int, output: Pointer): Byte
        fun CFBooleanGetValue(value: Pointer): Byte
        fun CFRelease(value: Pointer)
    }

    companion object {
        private const val ON_SCREEN_ONLY = 1
        private const val EXCLUDE_DESKTOP = 16
        private const val CF_NUMBER_SINT32 = 3
        private const val UTF8_ENCODING = 0x08000100
        private const val MAX_NATIVE_WINDOWS = 512
        private const val MAX_NATIVE_STRING_BYTES = 4096
    }
}
