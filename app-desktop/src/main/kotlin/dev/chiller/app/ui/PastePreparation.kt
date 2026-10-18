package dev.chiller.app.ui

import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** A paste being prepared in the background; shown as a card right away so typing never waits. */
data class PendingPaste(val id: String, val label: String)

/** Byte limit of an image attachment (matches AttachmentRepository). */
const val MAX_PASTED_IMAGE_BYTES = 4 * 1024 * 1024

/** Where pasted text goes: short text into the field, long text into an attachment. */
enum class TextPasteTarget { FIELD, ATTACHMENT }

/**
 * Decides without encoding megabytes on the UI thread: UTF-8 needs 1–4 bytes per char, so short and long
 * strings are decided from their length; only the narrow middle band is measured exactly.
 */
fun textPasteTarget(text: String, limitBytes: Int = 5_120): TextPasteTarget = when {
    text.length > limitBytes -> TextPasteTarget.ATTACHMENT
    text.length * 4 <= limitBytes -> TextPasteTarget.FIELD
    text.toByteArray(Charsets.UTF_8).size > limitBytes -> TextPasteTarget.ATTACHMENT
    else -> TextPasteTarget.FIELD
}

fun pasteLabelForImage(image: Image): String {
    val w = image.getWidth(null); val h = image.getHeight(null)
    return if (w > 0 && h > 0) "Изображение $w×$h" else "Изображение"
}

fun pasteLabelForText(text: String): String = "Текст · ${formatPresentedSize(text.length.toLong())}"

/** The encoded PNG and whether it had to be scaled down to fit the attachment limit. */
data class EncodedPaste(val png: ByteArray, val width: Int, val height: Int, val scaled: Boolean)

/**
 * Encodes a pasted image as PNG off the UI thread. An image that is already a plain RGB/ARGB buffer is
 * encoded directly (no redraw). If the PNG exceeds [maxBytes] the image is scaled down step by step
 * instead of being rejected; the result says so, so the UI can label it honestly.
 */
fun encodePastedImage(image: Image, maxBytes: Int = MAX_PASTED_IMAGE_BYTES, maxPixels: Long = 16_000_000L): EncodedPaste? {
    val sourceWidth = image.getWidth(null); val sourceHeight = image.getHeight(null)
    if (sourceWidth <= 0 || sourceHeight <= 0) return null
    // Huge sources go straight to the pixel limit while being converted: no full-size intermediate buffer.
    val factor = if (sourceWidth.toLong() * sourceHeight > maxPixels) kotlin.math.sqrt(maxPixels.toDouble() / (sourceWidth.toLong() * sourceHeight)) else 1.0
    var width = (sourceWidth * factor).toInt().coerceAtLeast(1); var height = (sourceHeight * factor).toInt().coerceAtLeast(1)
    var buffered = asRgbBuffer(image, width, height)
    var scaled = factor < 1.0
    repeat(6) {
        val png = ByteArrayOutputStream(width * height / 2).use { out -> if (!ImageIO.write(buffered, "png", out)) return null; out.toByteArray() }
        if (png.size <= maxBytes) return EncodedPaste(png, width, height, scaled)
        buffered = scale(buffered, (width * 0.75).toInt().coerceAtLeast(1), (height * 0.75).toInt().coerceAtLeast(1)); width = buffered.width; height = buffered.height; scaled = true
    }
    return null
}

/** An RGB/ARGB buffer of exactly [width]×[height]; the source is used as is when it already matches. */
private fun asRgbBuffer(image: Image, width: Int, height: Int): BufferedImage {
    if (image is BufferedImage && image.width == width && image.height == height &&
        (image.type == BufferedImage.TYPE_INT_RGB || image.type == BufferedImage.TYPE_INT_ARGB)) return image
    val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = buffered.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(image, 0, 0, width, height, null)
    } finally { g.dispose() }
    return buffered
}

/** At most two pastes are prepared at once; more wait with their cards visible instead of competing for memory. */
internal val pastePreparation = kotlinx.coroutines.sync.Semaphore(2)

private fun scale(source: BufferedImage, width: Int, height: Int): BufferedImage {
    val target = BufferedImage(width.coerceAtLeast(1), height.coerceAtLeast(1), if (source.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
    val g = target.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(source, 0, 0, target.width, target.height, null)
    } finally { g.dispose() }
    return target
}
