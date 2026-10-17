package dev.chiller.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownTest {
    @Test fun parsesBlocks() {
        val blocks = parseMarkdownBlocks("## Итог\nГотово. Сделано:\n\n- `a.py`: fix\n  wrapped tail\n  - nested\n1. first\n> note\n---\nend")
        assertEquals(listOf(
            MdBlock.Heading(2, "Итог"), MdBlock.Paragraph("Готово. Сделано:"),
            MdBlock.ListItem("•", 0, "`a.py`: fix wrapped tail"), MdBlock.ListItem("•", 1, "nested"),
            MdBlock.ListItem("1.", 0, "first"), MdBlock.Quote("note"), MdBlock.Rule, MdBlock.Paragraph("end"),
        ), blocks)
    }

    @Test fun inlineSpansDropMarkersAndKeepText() {
        val text = inlineMarkdownPlain("Use `replace_text`, **bold**, *it*, snake_case_name and [docs](https://x.y).")
        assertEquals("Use  replace_text , bold, it, snake_case_name and docs.", text)
    }
}
