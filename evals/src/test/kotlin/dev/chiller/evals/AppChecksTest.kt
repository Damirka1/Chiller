package dev.chiller.evals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppChecksTest {
    @Test fun `whole scripts pass, with templates, regular expressions and comments holding brackets`() {
        val code = """
            const re = /[}{(]+/g; // a comment with } and (
            /* a block comment ] */
            const label = `total: ${'$'}{items.map(i => `<b>${'$'}{i.name}</b>`).join(', ')} {not code}`;
            function f(a, b) { return a / b / 2 + (a ? '}' : "{"); }
            if (x) { y = [1, 2, { z: 3 }]; }
        """.trimIndent()
        assertNull(jsProblem(code))
    }

    @Test fun `cut off or broken scripts are named with the line`() {
        assertEquals("'{' from line 1 is never closed", jsProblem("function f() {\n  if (a) {\n    go();\n  }\n"))
        assertEquals("unterminated string from line 1", jsProblem("const s = 'abc;\nnext();"))
        assertEquals("'(' from line 1 closed by '}' at line 1", jsProblem("call(a, { b: 1 }}"))
        assertEquals("unexpected ')' at line 2", jsProblem("x = 1;\n)"))
        assertEquals("unterminated template from line 1", jsProblem("const t = `a ${'$'}{b"))
    }

    @Test fun `a page cut off by the reply limit is not whole`() {
        val page = "<!DOCTYPE html>\n<html>\n<body>\n<script>\nfunction go() {\n  draw();\n}\n</script>\n</body>\n</html>\n"
        assertNull(htmlProblem(page))
        assertEquals("does not end with </html>, the page looks cut off", htmlProblem(page.substring(0, page.indexOf("draw();"))))
        assertEquals("script 1: '{' from line 2 is never closed", htmlProblem(page.replace("}\n</script>", "</script>")))
        assertNull(htmlProblem(page.replace("<script>", "<script src=\"https://cdn.tailwindcss.com\"></script>\n<script>")))
    }
}
