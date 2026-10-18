package dev.chiller.app.ui

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PastePreparationTest {
    @Test fun `text target is decided without encoding long strings`() {
        assertEquals(TextPasteTarget.FIELD, textPasteTarget("short"))
        assertEquals(TextPasteTarget.ATTACHMENT, textPasteTarget("x".repeat(3_000_000)))
        assertEquals(TextPasteTarget.FIELD, textPasteTarget("a".repeat(5_000)))
        assertEquals(TextPasteTarget.ATTACHMENT, textPasteTarget("я".repeat(3_000)), "Cyrillic is two bytes per char: 6000 bytes")
    }

    @Test fun `a 4K screenshot encodes off the UI thread and fits the limit`() {
        // Noise is the worst case for PNG; a real screenshot compresses far better.
        val image = BufferedImage(3840, 2160, BufferedImage.TYPE_INT_RGB)
        val random = Random(7)
        for (y in 0 until 2160 step 4) for (x in 0 until 3840 step 4) image.setRGB(x, y, random.nextInt())
        val g = image.createGraphics(); g.color = Color(30, 120, 90); g.fillRect(100, 100, 1200, 600); g.dispose()
        val started = System.nanoTime()
        val encoded = encodePastedImage(image)!!
        val ms = (System.nanoTime() - started) / 1_000_000
        println("PASTE 4K: ${encoded.width}x${encoded.height} ${encoded.png.size / 1024} KiB scaled=${encoded.scaled} in $ms ms")
        assertTrue(encoded.png.size <= MAX_PASTED_IMAGE_BYTES)
        assertTrue(encoded.width <= 3840 && encoded.height <= 2160)
    }

    @Test fun `oversized images are scaled to the pixel limit`() {
        val image = BufferedImage(6000, 4000, BufferedImage.TYPE_INT_RGB)
        val encoded = encodePastedImage(image, maxPixels = 4_000_000)!!
        assertTrue(encoded.scaled && encoded.width.toLong() * encoded.height <= 4_000_000)
    }

    @Test fun `other buffer types convert straight to the limited size`() {
        val image = BufferedImage(5000, 4000, BufferedImage.TYPE_3BYTE_BGR)
        val encoded = encodePastedImage(image, maxPixels = 16_000_000)!!
        assertTrue(encoded.scaled && encoded.width.toLong() * encoded.height <= 16_000_000, "${encoded.width}x${encoded.height}")
        assertTrue(encoded.width in 4400..4480, "aspect kept: ${encoded.width}x${encoded.height}")
        val small = encodePastedImage(BufferedImage(40, 30, BufferedImage.TYPE_3BYTE_BGR))!!
        assertTrue(!small.scaled && small.width == 40 && small.height == 30)
    }
}
