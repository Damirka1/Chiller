package dev.chiller.evals

/*
 * Structure checks for the app tasks' pages. Kept apart from the task list so that using them does not initialise
 * the tasks (and, through them, the whole EVAL_TASKS list) first.
 */

private val SCRIPT = Regex("""<script\b([^>]*)>([\s\S]*?)</script\s*>""", RegexOption.IGNORE_CASE)
private val STYLE = Regex("""<style\b[^>]*>([\s\S]*?)</style\s*>""", RegexOption.IGNORE_CASE)

/** What makes a page incomplete or broken (cut off, an unclosed script or bracket), or null. */
internal fun htmlProblem(html: String): String? {
    val text = html.trim()
    val lower = text.lowercase()
    if (!lower.startsWith("<!doctype html")) return "does not start with <!DOCTYPE html>"
    if (!lower.endsWith("</html>")) return "does not end with </html>, the page looks cut off"
    if ("</body>" !in lower) return "has no </body>"
    val opened = Regex("""<script\b""", RegexOption.IGNORE_CASE).findAll(text).count()
    val closed = Regex("""</script\s*>""", RegexOption.IGNORE_CASE).findAll(text).count()
    if (opened != closed) return "has $opened <script> tags but $closed </script>"
    SCRIPT.findAll(text).forEachIndexed { index, match ->
        val attributes = match.groupValues[1].lowercase()
        if ("src=" in attributes || "json" in attributes || "text/template" in attributes) return@forEachIndexed
        jsProblem(match.groupValues[2])?.let { return "script ${index + 1}: $it" }
    }
    STYLE.findAll(text).forEach { match ->
        val css = match.groupValues[1].replace(Regex("""/\*[\s\S]*?\*/"""), "")
        if (css.count { it == '{' } != css.count { it == '}' }) return "unbalanced braces in <style>"
    }
    return null
}

/**
 * Unbalanced brackets or an unterminated string, template, comment or regular expression in JavaScript, or null.
 * A lexer, not a parser: it is there to catch code cut off or broken by an edit, not to judge the program.
 */
internal fun jsProblem(code: String): String? {
    val stack = ArrayDeque<Pair<Char, Int>>()
    var line = 1
    var i = 0
    // The last significant character and word decide whether a slash starts a regular expression or divides.
    var last: Char? = null
    var lastWord = ""
    fun regexAllowed(): Boolean { val before = last; return before == null || before in "(,=:[!&|?{};+-*%<>~^" || lastWord in REGEX_KEYWORDS }
    /** Scans template text from [from]; returns the index after its closing backtick or after an opening `${`. */
    fun template(from: Int): Int? {
        var j = from
        while (j < code.length) {
            when (code[j]) {
                '\\' -> j += 2
                '`' -> return j + 1
                '\n' -> { line++; j++ }
                '$' -> if (code.getOrNull(j + 1) == '{') { stack.addLast('$' to line); return j + 2 } else j++
                else -> j++
            }
        }
        return null
    }
    while (i < code.length) {
        val c = code[i]
        when {
            c == '\n' -> { line++; i++ }
            c.isWhitespace() -> i++
            c == '/' && code.getOrNull(i + 1) == '/' -> while (i < code.length && code[i] != '\n') i++
            c == '/' && code.getOrNull(i + 1) == '*' -> {
                val end = code.indexOf("*/", i + 2)
                if (end < 0) return "unterminated comment from line $line"
                line += code.substring(i, end).count { it == '\n' }
                i = end + 2
            }
            c == '\'' || c == '"' -> {
                val start = line
                i++
                while (true) {
                    if (i >= code.length || code[i] == '\n') return "unterminated string from line $start"
                    if (code[i] == '\\') i += 2 else if (code[i] == c) { i++; break } else i++
                }
                last = c; lastWord = ""
            }
            c == '`' -> {
                val start = line
                i = template(i + 1) ?: return "unterminated template from line $start"
                last = '`'; lastWord = ""
            }
            c == '/' && regexAllowed() -> {
                val start = line
                var inClass = false
                i++
                while (true) {
                    if (i >= code.length || code[i] == '\n') return "unterminated regular expression from line $start"
                    when {
                        code[i] == '\\' -> i++
                        code[i] == '[' -> inClass = true
                        code[i] == ']' -> inClass = false
                        code[i] == '/' && !inClass -> break
                    }
                    i++
                }
                i++
                while (i < code.length && code[i].isLetter()) i++
                last = 'r'; lastWord = ""
            }
            c.isLetterOrDigit() || c == '_' || c == '$' -> {
                val start = i
                while (i < code.length && (code[i].isLetterOrDigit() || code[i] == '_' || code[i] == '$')) i++
                last = 'a'; lastWord = code.substring(start, i)
            }
            c in "([{" -> { stack.addLast(c to line); last = c; lastWord = ""; i++ }
            c in ")]}" -> {
                val open = stack.removeLastOrNull() ?: return "unexpected '$c' at line $line"
                if (open.first == '$' && c == '}') {
                    val start = open.second
                    i = template(i + 1) ?: return "unterminated template from line $start"
                    last = '`'; lastWord = ""
                    continue
                }
                if (PAIRS[open.first] != c) return "'${open.first}' from line ${open.second} closed by '$c' at line $line"
                last = c; lastWord = ""; i++
            }
            else -> { last = c; lastWord = ""; i++ }
        }
    }
    return stack.lastOrNull()?.let { (bracket, at) -> if (bracket == '$') "unterminated template from line $at" else "'$bracket' from line $at is never closed" }
}
private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}')
private val REGEX_KEYWORDS = setOf("return", "typeof", "case", "in", "of", "delete", "void", "throw", "new", "else", "do", "yield", "await")
