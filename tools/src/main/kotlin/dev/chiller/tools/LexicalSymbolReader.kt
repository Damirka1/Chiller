package dev.chiller.tools

import dev.chiller.contracts.ToolException

/** Conservative extractor for block-bodied Kotlin declarations; this is not a parser. */
internal object LexicalSymbolReader {
    data class Result(val startLine: Int, val endLine: Int, val content: String)

    fun read(source: String, symbol: String, startLine: Int = 1, anchored: Boolean = startLine > 1): Result {
        if (!symbol.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) throw ToolException("INVALID_ARGUMENTS", "symbol must be a plain Kotlin identifier")
        if (startLine < 1) throw ToolException("INVALID_ARGUMENTS", "start_line must be positive")
        val originalLines = source.split('\n')
        val lines = maskCommentsAndStrings(source).split('\n')
        val matches = lines.indices.filter { isKotlinDeclaration(lines[it], symbol) }
        if (matches.isEmpty()) throw ToolException("SYMBOL_NOT_FOUND", "No matching Kotlin class or function declaration was found")
        if (anchored && matches.first() != 0) throw ToolException("SYMBOL_NOT_FOUND", "start_line must point to the declaration's first line")
        if (matches.size > 1) throw ToolException("AMBIGUOUS_SYMBOL", "More than one declaration matches in this file")
        val start = matches.single()
        val end = findBlockEnd(lines, start)
        if (end < 0) throw ToolException("SYMBOL_INCOMPLETE", "Only complete block-bodied Kotlin declarations are supported")
        if (end - start > MAX_LINES) throw ToolException("SYMBOL_TOO_LARGE", "Declaration exceeds the 500-line output limit")
        return Result(startLine + start, startLine + end, originalLines.subList(start, end + 1).joinToString("\n"))
    }

    private fun maskCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var commentDepth = 0
        var quote: Char? = null
        var rawString = false
        var escaped = false
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            if (commentDepth > 0) {
                when {
                    c == '/' && next == '*' -> { commentDepth++; out.append("  "); i += 2; continue }
                    c == '*' && next == '/' -> { commentDepth--; out.append("  "); i += 2; continue }
                    else -> { out.append(if (c == '\n') '\n' else ' '); i++; continue }
                }
            }
            if (quote != null) {
                if (rawString && source.startsWith("\"\"\"", i)) { out.append("   "); i += 3; quote = null; rawString = false; continue }
                if (!rawString && escaped) { out.append(if (c == '\n') '\n' else ' '); escaped = false; i++; continue }
                if (!rawString && c == '\\') { out.append(' '); escaped = true; i++; continue }
                if (c == '$' && isInterpolationStart(next))
                    throw ToolException("UNSUPPORTED_SYNTAX", "Symbol extraction refuses Kotlin string interpolation because its embedded code needs a parser")
                if (!rawString && c == quote) { out.append(' '); i++; quote = null; continue }
                out.append(if (c == '\n') '\n' else ' '); i++; continue
            }
            when {
                c == '/' && next == '/' -> { out.append("  "); i += 2; while (i < source.length && source[i] != '\n') { out.append(' '); i++ }; continue }
                c == '/' && next == '*' -> { commentDepth = 1; out.append("  "); i += 2; continue }
                c == '"' && source.startsWith("\"\"\"", i) -> { quote = '"'; rawString = true; out.append("   "); i += 3; continue }
                c == '"' || c == '\'' -> { quote = c; out.append(' '); i++; continue }
                else -> { out.append(c); i++ }
            }
        }
        if (commentDepth > 0 || quote != null) throw ToolException("SYMBOL_INCOMPLETE", "File ends inside a comment or string; refusing lexical extraction")
        return out.toString()
    }

    private fun isInterpolationStart(next: Char?) = next == '{' || (next != null && (next == '_' || Character.isJavaIdentifierStart(next)))

    private fun isKotlinDeclaration(line: String, name: String): Boolean {
        val escaped = Regex.escape(name)
        val classDecl = Regex("^\\s*(?:(?:public|private|protected|internal|abstract|open|data|sealed|enum|annotation|value)\\s+)*(?:class|interface|object)\\s+$escaped\\b")
        val functionDecl = Regex("^\\s*(?:(?:public|private|protected|internal|tailrec|inline|suspend|operator|infix|external|override)\\s+)*fun\\s+(?:<[^>]+>\\s+)?(?:[A-Za-z_$][A-Za-z0-9_$.<>?, ]*\\.)?$escaped(?:<[^>]+>)?\\s*\\(")
        return classDecl.containsMatchIn(line) || functionDecl.containsMatchIn(line)
    }

    private fun findBlockEnd(lines: List<String>, startLine: Int): Int {
        var parens = 0
        var depth = 0
        var opened = false
        for (lineIndex in startLine until lines.size) {
            val line = lines[lineIndex]
            for (c in line) {
                when (c) {
                    '(' -> parens++
                    ')' -> if (parens > 0) parens--
                    '=' -> if (!opened && parens == 0) return -1 // expression body: do not guess its extent
                    '{' -> {
                        if (!opened) { if (parens != 0) return -1; opened = true }
                        depth++
                    }
                    '}' -> if (opened && --depth == 0) return lineIndex
                }
            }
        }
        return -1
    }

    private const val MAX_LINES = 500
}
