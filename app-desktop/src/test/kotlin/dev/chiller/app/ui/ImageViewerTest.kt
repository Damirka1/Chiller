package dev.chiller.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageViewerTest {
    @Test fun `fit preserves all chart content in wide and portrait viewports`() {
        assertEquals(.5f, imageFitScale(IntSize(1500, 825), IntSize(750, 600)))
        assertEquals(.25f, imageFitScale(IntSize(1500, 825), IntSize(375, 600)))
        assertEquals(.5f, imageFitScale(IntSize(600, 1200), IntSize(800, 600)))
    }

    @Test fun `pan cannot lose the image outside the viewport`() {
        val image = IntSize(1500, 800)
        val viewport = IntSize(750, 600)
        assertEquals(Offset.Zero, clampImagePan(Offset(100f, 100f), image, viewport, .5f))
        assertEquals(Offset(375f, -100f), clampImagePan(Offset(9000f, -9000f), image, viewport, 1f))
    }
}
