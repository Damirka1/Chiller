package dev.chiller.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
    data object Rule : MdBlock
}

private val heading = Regex("^(#{1,4})\\s+(.*)$")
private val bullet = Regex("^(\\s*)([-*+])\\s+(.*)$")
private val numbered = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")

fun parseMarkdownBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = mutableListOf<String>()
    fun flush() { if (paragraph.isNotEmpty()) { blocks += MdBlock.Paragraph(paragraph.joinToString(" ") { it.trim() }); paragraph.clear() } }
    for (raw in text.lines()) {
        val line = raw.trimEnd()
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
    }
    flush()
    return blocks
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
                MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(c.border))
            }
        }
    }
}
