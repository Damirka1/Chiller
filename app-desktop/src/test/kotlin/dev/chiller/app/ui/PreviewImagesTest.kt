package dev.chiller.app.ui

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class PreviewImagesTest {
    @AfterTest fun reset() = PreviewImages.clear()

    @Test fun `returning cards reuse the decoded image and old ones leave within the pixel budget`() {
        val big = ImageBitmap(4000, 2000) // 8 MP each; the budget is 24 MP
        PreviewImages.put(PreviewImages.key("a.png", 1), big)
        PreviewImages.put(PreviewImages.key("b.png", 1), ImageBitmap(4000, 2000))
        PreviewImages.put(PreviewImages.key("c.png", 1), ImageBitmap(4000, 2000))
        assertSame(big, PreviewImages.get(PreviewImages.key("a.png", 1)), "same path and size: the decoded image comes back")
        // a.png was just used, so b.png is the least recently used one when d.png needs room.
        PreviewImages.put(PreviewImages.key("d.png", 1), ImageBitmap(4000, 2000))
        assertNull(PreviewImages.get(PreviewImages.key("b.png", 1)))
        assertNotNull(PreviewImages.get(PreviewImages.key("a.png", 1)))
        assertNull(PreviewImages.get(PreviewImages.key("a.png", 2)), "a different size is a different file")
        // One image larger than the whole budget is still kept: the card showing it needs it.
        PreviewImages.put(PreviewImages.key("huge.png", 1), ImageBitmap(6000, 5000))
        assertNotNull(PreviewImages.get(PreviewImages.key("huge.png", 1)))
    }
}
