package dev.chiller.tools

import dev.chiller.contracts.ToolException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LexicalSymbolReaderTest {
    @Test fun extractsBlockBodyAndIgnoresBracesInStringsAndComments() {
        val source = """class Box {
    fun <T> Box<T>.receive(value: T) {
        val marker = "}"
        /* { ignored } */
        if (value != null) { println(value) }
    }
}
"""
        val result = LexicalSymbolReader.read(source, "receive")
        assertEquals(2, result.startLine)
        assertEquals(6, result.endLine)
        assertTrue(result.content.contains("if (value != null)"))
        assertEquals("    fun <T> Box<T>.receive(value: T) {\n        val marker = \"}\"\n        /* { ignored } */\n        if (value != null) { println(value) }\n    }", result.content)
    }

    @Test fun refusesAmbiguousAndExpressionBodiedDeclarations() {
        assertFailsWith<ToolException> { LexicalSymbolReader.read("fun same() { }\nfun same() { }", "same") }
        assertFailsWith<ToolException> { LexicalSymbolReader.read("fun calculate() = 1", "calculate") }
    }

    @Test fun ignoresDeclarationsInsideCommentsAndRawStrings() {
        val source = "/* fun ghost() { } */\n" +
            "val text = \"\"\"\nfun ghost() { }\n\"\"\"\n" +
            "fun real() { println(1) }\n"
        val result = LexicalSymbolReader.read(source, "real")
        assertTrue(result.content.contains("println"))
        assertFailsWith<ToolException> { LexicalSymbolReader.read(source, "ghost") }
    }

    @Test fun refusesInterpolationThatNeedsSemanticParsing() {
        val source = "val text = \"value \${something}\"\nfun target() { }\n"
        assertFailsWith<ToolException> { LexicalSymbolReader.read(source, "target") }
    }

    @Test fun anchoredWindowRequiresDeclarationOnItsFirstLineAndReturnsAbsoluteLines() {
        val result = LexicalSymbolReader.read("fun target() { println(1) }\n", "target", startLine = 42, anchored = true)
        assertEquals(42, result.startLine)
        assertEquals(42, result.endLine)
        assertFailsWith<ToolException> { LexicalSymbolReader.read("// preceding line\nfun target() { }", "target", 42, anchored = true) }
    }
}
