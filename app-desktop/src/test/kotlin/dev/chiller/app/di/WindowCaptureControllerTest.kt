package dev.chiller.app.di

import dev.chiller.contracts.ToolException
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowCaptureControllerTest {
    @Test fun exposesOnlySelectedWindowAndReturnsOwnedPng() = runBlocking {
        val root = Files.createTempDirectory("chiller-window-capture")
        try {
            val native = FakeNative(listOf(window(41, "Editor", "Code"), window(42, "Browser", "Safari")))
            val capturer = FakeCapturer()
            val controller = WindowCaptureController(root, native, capturer) { "session-a" }

            assertEquals(2, controller.listAvailable().size)
            assertEquals("41", controller.select("41").id)
            assertEquals(listOf("41"), controller.targets().map { it.id })
            val file = controller.capture()

            assertEquals(41L, capturer.lastWindowId)
            assertEquals("image/png", file.mimeType)
            assertEquals(Files.size(Path.of(file.path)), file.sizeBytes)
            assertTrue(file.sizeBytes > 8)
            assertEquals(2, javax.imageio.ImageIO.read(Path.of(file.path).toFile()).width)
            assertTrue(Path.of(file.path).startsWith(root.toRealPath()))
            assertEquals("session-a", Path.of(file.path).parent.fileName.toString())
            assertTrue(Files.exists(Path.of(file.path)))
            assertEquals("Code.png", file.name)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun requiresPermissionAndNeverFallsBackWhenSelectionChanges() = runBlocking {
        val root = Files.createTempDirectory("chiller-window-capture")
        try {
            val native = FakeNative(listOf(window(41, "Editor", "Code")), permission = false)
            val controller = WindowCaptureController(root, native, FakeCapturer())
            val denied = assertFailsWith<ToolException> { controller.listAvailable() }
            assertEquals("CAPTURE_PERMISSION_REQUIRED", denied.code)
            native.permission = true
            controller.select("41")
            native.records = listOf(window(99, "Different", "Other"))
            val changed = assertFailsWith<ToolException> { controller.capture() }
            assertEquals("CAPTURE_TARGET_CHANGED", changed.code)
            assertFalse(Files.list(root).use { it.findAny().isPresent })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun rejectsInvalidTargetAndInvalidPngOutput() = runBlocking {
        val root = Files.createTempDirectory("chiller-window-capture")
        try {
            val native = FakeNative(listOf(window(41, "Editor", "Code")))
            val controller = WindowCaptureController(root, native, FakeCapturer(validPng = false))
            assertFailsWith<ToolException> { controller.select("41; /usr/bin/open") }
            controller.select("41")
            val failed = assertFailsWith<ToolException> { controller.capture() }
            assertEquals("CAPTURE_FAILED", failed.code)
            assertFalse(Files.list(root).use { it.findAny().isPresent })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun unsupportedPlatformHasNoCapability() {
        val root = Files.createTempDirectory("chiller-window-capture")
        try {
            val controller = WindowCaptureController(root, UnsupportedWindowCaptureNative, FakeCapturer())
            assertFalse(controller.supported)
            assertTrue(controller.selected() == null)
            assertEquals("CAPTURE_UNSUPPORTED", assertFailsWith<ToolException> { controller.listAvailable() }.code)
        } finally { root.toFile().deleteRecursively() }
    }

    private fun window(id: Long, title: String, application: String) = WindowRecord(id, 12, title, application)

    private class FakeNative(var records: List<WindowRecord>, var permission: Boolean = true) : WindowCaptureNative {
        override val supported = true
        override fun hasPermission() = permission
        override fun windows() = records
    }

    private class FakeCapturer(private val validPng: Boolean = true) : WindowImageCapturer {
        var lastWindowId: Long? = null
        override suspend fun capture(windowId: Long, destination: Path) {
            lastWindowId = windowId
            val bytes = if (validPng) java.io.ByteArrayOutputStream().also { out -> javax.imageio.ImageIO.write(java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", out) }.toByteArray() else "not a png".toByteArray()
            Files.write(destination, bytes)
        }
    }
}
