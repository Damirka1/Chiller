package dev.chiller.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsParserTest {
    @Test fun parsesSupportedLocationsAndLeavesUnmatchedOutputUnclaimed() {
        val raw = """src/Main.kt:12:5: error: unresolved reference
e: src/Other.kt: (3, 8): type mismatch
  File "scripts/check.py", line 27, in verify
Tests passed
"""
        val found = DiagnosticsParser.parse(raw)
        assertEquals(3, found.size)
        assertEquals("src/Main.kt", found[0].file)
        assertEquals(12, found[0].line)
        assertEquals(5, found[0].column)
        assertEquals("error", found[0].severity)
        assertEquals(3, found[1].line)
        assertEquals("scripts/check.py", found[2].file)
        assertEquals(27, found[2].line)
        assertTrue(DiagnosticsParser.parse("Tests passed\nBUILD SUCCESSFUL").isEmpty())
    }

    @Test fun parsesRealKotlinCompilerAndJavaWithoutColumnFormats() {
        val found = DiagnosticsParser.parse("""[stderr] e: file:///tmp/src/Foo.kt:77:21 Unresolved reference: missing
[stderr] src/Main.java:5: error: cannot find symbol
""")
        assertEquals(2, found.size)
        assertEquals("/tmp/src/Foo.kt", found[0].file)
        assertEquals(77, found[0].line)
        assertEquals(21, found[0].column)
        assertEquals(5, found[1].line)
        assertEquals(null, found[1].column)
    }
}
