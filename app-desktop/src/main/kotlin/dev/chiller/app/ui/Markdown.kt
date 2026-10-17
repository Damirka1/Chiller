package dev.chiller.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chiller.design.ChillerPalette
import dev.chiller.design.LocalChillerPalette

/**
 * Markdown blocks outside fenced code: paragraphs, ATX headings, bullet and numbered lists with
 * nesting, and quotes. Rendered as data — no HTML, no images, links are shown, not opened.
 */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class ListItem(val marker: String, val depth: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>, val alignments: List<TableAlignment>) : MdBlock
    data object Rule : MdBlock
}

enum class TableAlignment { LEFT, CENTER, RIGHT }

private val heading = Regex("^(#{1,4})\\s+(.*)$")
private val bullet = Regex("^(\\s*)([-*+])\\s+(.*)$")
private val numbered = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")

fun parseMarkdownBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = mutableListOf<String>()
    fun flush() { if (paragraph.isNotEmpty()) { blocks += MdBlock.Paragraph(paragraph.joinToString(" ") { it.trim() }); paragraph.clear() } }
    val lines = text.lines()
    var index = 0
    while (index < lines.size) {
        val raw = lines[index]
        val line = raw.trimEnd()
        val table = if (index + 1 < lines.size) parseTableAt(lines, index) else null
        if (table != null) {
            flush()
            blocks += table.first
            index = table.second
            continue
        }
        val h = heading.matchEntire(line.trimStart())
        val b = bullet.matchEntire(line)
        val n = numbered.matchEntire(line)
        when {
            line.isBlank() -> flush()
            line.trim().matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> { flush(); blocks += MdBlock.Rule }
            h != null -> { flush(); blocks += MdBlock.Heading(h.groupValues[1].length, h.groupValues[2]) }
            b != null -> { flush(); blocks += MdBlock.ListItem("•", b.groupValues[1].length / 2, b.groupValues[3]) }
            n != null -> { flush(); blocks += MdBlock.ListItem("${n.groupValues[2]}.", n.groupValues[1].length / 2, n.groupValues[3]) }
            line.trimStart().startsWith(">") -> { flush(); blocks += MdBlock.Quote(line.trimStart().removePrefix(">").trim()) }
            // A wrapped continuation line belongs to the list item above it.
            paragraph.isEmpty() && blocks.lastOrNull() is MdBlock.ListItem && raw.startsWith("  ") -> {
                val item = blocks.removeAt(blocks.lastIndex) as MdBlock.ListItem
                blocks += item.copy(text = item.text + " " + line.trim())
            }
            else -> paragraph += line
        }
        index++
    }
    flush()
    return blocks
}

/** Returns cells while honoring escaped pipes and pipes protected by matching backtick spans. */
private fun tableCells(line: String): List<String> {
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var ticks = 0
    var i = 0
    var beginsWithDelimiter = false
    var sawNonWhitespace = false
    var endsWithDelimiter = false
    while (i < line.length) {
        val ch = line[i]
        if (ch == '`') {
            var end = i + 1
            while (end < line.length && line[end] == '`') end++
            val run = end - i
            current.append(line.substring(i, end))
            sawNonWhitespace = true
            endsWithDelimiter = false
            ticks = if (ticks == 0) run else if (ticks == run) 0 else ticks
            i = end
            continue
        }
        if (ch == '|' && ticks == 0) {
            if (!sawNonWhitespace) beginsWithDelimiter = true
            cells += current.toString()
            current.setLength(0)
            endsWithDelimiter = true
        } else if (ch == '\\' && i + 1 < line.length && line[i + 1] == '|') {
            current.append('|')
            sawNonWhitespace = true
            endsWithDelimiter = false
            i++
        } else {
            current.append(ch)
            if (!ch.isWhitespace()) {
                sawNonWhitespace = true
                endsWithDelimiter = false
            }
        }
        i++
    }
    cells += current.toString()
    if (beginsWithDelimiter && cells.isNotEmpty()) cells.removeAt(0)
    if (endsWithDelimiter && cells.isNotEmpty()) cells.removeAt(cells.lastIndex)
    return cells.map(String::trim)
}

private val tableSeparator = Regex("^:?-{3,}:?$")
private fun parseTableAt(lines: List<String>, start: Int): Pair<MdBlock.Table, Int>? {
    val headers = tableCells(lines[start])
    if (headers.size < 2) return null
    val separators = tableCells(lines[start + 1])
    if (separators.size != headers.size || separators.any { !tableSeparator.matches(it) }) return null
    val alignments = separators.map { cell -> when {
        cell.startsWith(':') && cell.endsWith(':') -> TableAlignment.CENTER
        cell.endsWith(':') -> TableAlignment.RIGHT
        else -> TableAlignment.LEFT
    } }
    val rows = mutableListOf<List<String>>()
    var i = start + 2
    while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
        val cells = tableCells(lines[i])
        // A malformed row invalidates the candidate, leaving all lines to normal Markdown parsing.
        if (cells.size != headers.size) return null
        rows += cells
        i++
    }
    return MdBlock.Table(headers, rows, alignments) to i
}

private val inline = Regex("`([^`]+)`|\\*\\*([^*]+)\\*\\*|__([^_]+)__|(?<![\\w*])\\*([^*\\s][^*]*)\\*(?!\\w)|(?<!\\w)_([^_\\s][^_]*)_(?!\\w)|\\[([^\\]]+)]\\(([^)\\s]+)\\)")

/** Inline code, bold, italic and links; everything else is literal text. */
fun inlineMarkdown(text: String, c: ChillerPalette): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in inline.findAll(text)) {
        append(text.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> { pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, background = c.hover, color = c.text)); append(" ${g[1]} "); pop() }
            g[2].isNotEmpty() || g[3].isNotEmpty() -> { pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold)); append(g[2].ifEmpty { g[3] }); pop() }
            g[4].isNotEmpty() || g[5].isNotEmpty() -> { pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); append(g[4].ifEmpty { g[5] }); pop() }
            g[6].isNotEmpty() -> { pushStyle(SpanStyle(color = c.accent, textDecoration = TextDecoration.Underline)); append(g[6]); pop() }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

/** The visible text of [inlineMarkdown], for tests and plain-text uses. */
fun inlineMarkdownPlain(text: String): String = inline.replace(text) { m -> val g = m.groupValues
    when { g[1].isNotEmpty() -> "\u2009${g[1]}\u2009"; g[2].isNotEmpty() -> g[2]; g[3].isNotEmpty() -> g[3]; g[4].isNotEmpty() -> g[4]; g[5].isNotEmpty() -> g[5]; else -> g[6] } }

/** Renders markdown prose. [tail] (e.g. a streaming caret) is appended to the last block. */
@Composable
fun MarkdownText(text: String, tail: AnnotatedString? = null) {
    val c = LocalChillerPalette.current
    val blocks = parseMarkdownBlocks(text).ifEmpty { listOf(MdBlock.Paragraph("")) }
    fun withTail(index: Int, value: AnnotatedString) = if (tail != null && index == blocks.lastIndex) value + tail else value
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { index, block ->
            val previous = blocks.getOrNull(index - 1)
            // Lists stay tight; paragraphs and headings get breathing room.
            val gap = when {
                index == 0 -> 0.dp
                block is MdBlock.ListItem && previous is MdBlock.ListItem -> 0.dp
                block is MdBlock.Heading -> 10.dp
                else -> 4.dp
            }
            if (gap > 0.dp) Spacer(Modifier.height(gap))
            when (block) {
                is MdBlock.Paragraph -> Text(withTail(index, inlineMarkdown(block.text, c)), color = c.text, fontSize = 14.sp, lineHeight = 23.sp, letterSpacing = 0.sp)
                is MdBlock.Heading -> Text(withTail(index, inlineMarkdown(block.text, c)), color = c.text, fontWeight = FontWeight.SemiBold,
                    fontSize = when (block.level) { 1 -> 18.sp; 2 -> 16.sp; else -> 14.sp }, lineHeight = when (block.level) { 1 -> 26.sp; 2 -> 24.sp; else -> 22.sp }, letterSpacing = (-.2).sp)
                is MdBlock.ListItem -> Row(Modifier.padding(start = (block.depth * 18).dp)) {
                    Text(block.marker, color = c.muted, fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.widthIn(min = 18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(withTail(index, inlineMarkdown(block.text, c)), color = c.text, fontSize = 14.sp, lineHeight = 23.sp, letterSpacing = 0.sp)
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.border))
                    Spacer(Modifier.width(12.dp))
                    Text(withTail(index, inlineMarkdown(block.text, c)), color = c.muted, fontSize = 14.sp, lineHeight = 23.sp)
                }
                is MdBlock.Table -> MarkdownTable(block, c)
                MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(c.border))
            }
        }
    }
}

@Composable
private fun MarkdownTable(table: MdBlock.Table, c: ChillerPalette) {
    val allRows = listOf(table.headers) + table.rows
    val widths = table.headers.indices.map { column ->
        val longestLine = allRows.maxOf { row -> row[column].lineSequence().maxOfOrNull(String::length) ?: 0 }
        // Approximate 13sp glyph width and clamp pathological cells; long values wrap in-cell.
        (longestLine * 7.2f + 20f).coerceIn(112f, 280f).dp
    }
    val tableWidth = widths.fold(0.dp) { total, width -> total + width }
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        allRows.forEachIndexed { rowIndex, row ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                row.forEachIndexed { column, value ->
                    val header = rowIndex == 0
                    Text(
                        inlineMarkdown(value, c), color = c.text,
                        fontSize = 13.sp, lineHeight = 20.sp,
                        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = when (table.alignments[column]) {
                            TableAlignment.LEFT -> TextAlign.Left
                            TableAlignment.CENTER -> TextAlign.Center
                            TableAlignment.RIGHT -> TextAlign.Right
                        },
                        modifier = Modifier.width(widths[column]).fillMaxHeight().background(if (header) c.hover else c.surface)
                            .border(1.dp, c.border)
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }
            if (rowIndex < table.rows.size) Box(Modifier.width(tableWidth).height(1.dp).background(c.border))
        }
    }
}
