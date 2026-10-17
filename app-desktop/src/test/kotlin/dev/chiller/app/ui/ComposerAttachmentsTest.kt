package dev.chiller.app.ui

import dev.chiller.contracts.ComposerAttachment
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComposerAttachmentsTest {
    @Test fun `append merges current attachments without duplicates and enforces the per-chat limit`() {
        val first = ComposerAttachment("a", "one.txt", "text/plain", 1)
        val second = ComposerAttachment("b", "two.txt", "text/plain", 2)
        val third = ComposerAttachment("c", "three.txt", "text/plain", 3)
        assertEquals(listOf(first, second), mergeComposerAttachments(listOf(first), listOf(first, second)))
        assertNull(mergeComposerAttachments(listOf(first, second), listOf(third), limit = 2))
    }

    @Test fun `pending import count tracks concurrent operations and clears after completion`() {
        assertEquals(1, nextPendingImportCount(0, 1))
        assertEquals(2, nextPendingImportCount(1, 1))
        assertEquals(1, nextPendingImportCount(2, -1))
        assertNull(nextPendingImportCount(1, -1))
    }

    @Test fun `transferable data is copied before its native lifetime ends`() {
        val content = "x".repeat(6_000)
        val source = OneShotTextTransferable(content)
        val payload = snapshotComposerTransferPayload(source) as ComposerTransferPayload.Text
        source.expire()

        assertEquals(content, payload.value)
    }

    private class OneShotTextTransferable(private val text: String) : Transferable {
        private var expired = false
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.stringFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            check(!expired) { "native transferable expired" }
            check(isDataFlavorSupported(flavor))
            return text
        }
        fun expire() { expired = true }
    }
}
