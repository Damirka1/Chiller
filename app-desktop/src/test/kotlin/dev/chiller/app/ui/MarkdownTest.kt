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

    @Test fun parsesWeatherTableWithAlignmentsAndInlineFormatting() {
        val blocks = parseMarkdownBlocks("""Погода на сегодня:
| Город | Температура | Условия |
|:------|------------:|:-------:|
| Алматы | **18°C** | `ясно` |
| Астана | 12°C | облачно |""")
        assertEquals(MdBlock.Paragraph("Погода на сегодня:"), blocks[0])
        assertEquals(
            MdBlock.Table(
                headers = listOf("Город", "Температура", "Условия"),
                rows = listOf(listOf("Алматы", "**18°C**", "`ясно`"), listOf("Астана", "12°C", "облачно")),
                alignments = listOf(TableAlignment.LEFT, TableAlignment.RIGHT, TableAlignment.CENTER),
            ),
            blocks[1],
        )
        assertEquals("18°C", inlineMarkdownPlain((blocks[1] as MdBlock.Table).rows[0][1]))
    }

    @Test fun tableCellsHonorEscapedPipesAndCodeSpans() {
        val table = parseMarkdownBlocks("""| Name | Example |
| --- | --- |
| A\|B | `x|y` |""").single() as MdBlock.Table
        assertEquals(listOf("A|B", "`x|y`"), table.rows.single())

        val terminalEscapedPipe = parseMarkdownBlocks("""| Name | Example
| --- | --- |
| A | final\|""").single() as MdBlock.Table
        assertEquals(listOf("A", "final|"), terminalEscapedPipe.rows.single())
    }

    @Test fun parsesExactSevenDayTemirtauTable() {
        val table = parseMarkdownBlocks("""| Дата | День (max) | Ночь (min) |
|---|---|---|
| 17.10.2026 | +9.5 °C | +0.4 °C |
| 18.10.2026 | +8.8 °C | +3.3 °C |
| 19.10.2026 | +12.5 °C | +3.6 °C |
| 20.10.2026 | +8.0 °C | +0.7 °C |
| 21.10.2026 | +6.8 °C | +1.1 °C |
| 22.10.2026 | +4.7 °C | +0.3 °C |
| 23.10.2026 | +3.7 °C | −1.2 °C |""").single() as MdBlock.Table
        assertEquals(listOf("Дата", "День (max)", "Ночь (min)"), table.headers)
        assertEquals(7, table.rows.size)
        assertEquals(listOf("17.10.2026", "+9.5 °C", "+0.4 °C"), table.rows.first())
        assertEquals(listOf("23.10.2026", "+3.7 °C", "−1.2 °C"), table.rows.last())
    }

    @Test fun invalidTableCandidateFallsBackToLiteralMarkdown() {
        assertEquals(
            listOf(MdBlock.Paragraph("a | b | c --- | nope | row")),
            parseMarkdownBlocks("a | b | c\n--- | nope | row"),
        )
    }

    @Test fun `tables copy as TSV for text and HTML for spreadsheets`() {
        val table = parseMarkdownBlocks("""| Дата | День (max) | Заметка |
            ||---|---:|---|
            || 17.10.2026 | +9.5 °C | **тепло** <ясно> |
            || 23.10.2026 | +3.7 °C | `t<0` & ветер |""".trimMargin()).single() as MdBlock.Table
        assertEquals("Дата\tДень (max)\tЗаметка\n17.10.2026\t+9.5 °C\tтепло <ясно>\n23.10.2026\t+3.7 °C\tt<0 & ветер", tableAsTsv(table))
        assertEquals("<table><thead><tr><th>Дата</th><th>День (max)</th><th>Заметка</th></tr></thead><tbody>" +
            "<tr><td>17.10.2026</td><td>+9.5 °C</td><td>тепло &lt;ясно&gt;</td></tr>" +
            "<tr><td>23.10.2026</td><td>+3.7 °C</td><td>t&lt;0 &amp; ветер</td></tr></tbody></table>", tableAsHtml(table))
    }

    @Test fun picturesBecomeBlocksOrTheirDescription() {
        assertEquals(listOf(MdBlock.Paragraph("Лови жабу."), MdBlock.Image("Toad", "toad_wednesday.png")),
            parseMarkdownBlocks("Лови жабу.\n\n![Toad](toad_wednesday.png)"))
        assertEquals(listOf(MdBlock.Image("", "img/a.png")), parseMarkdownBlocks("![](img/a.png \"title\")"))
        // Inside a sentence a picture keeps its description; code keeps its thin padding.
        assertEquals("Смотри схема и  код ", inlineMarkdownPlain("Смотри ![схема](https://example.com/a.png) и `код`"))
        assertEquals("ссылка, жирный, курсив", inlineMarkdownPlain("[ссылка](https://x.y), **жирный**, *курсив*"))
    }
}
