package dev.chiller.tools

/** Best-effort location extraction only; it does not determine whether a process passed. */
internal object DiagnosticsParser {
    data class Diagnostic(val file: String, val line: Int, val column: Int?, val message: String, val severity: String?)

    private val compiler = Regex("^(.+\\.(?:kt|kts|java)):(\\d+)(?::(\\d+))?:\\s*(?:(error|warning|info):\\s*)?(.*)$", RegexOption.IGNORE_CASE)
    private val kotlin = Regex("^([ew]):\\s*(.+?\\.(?:kt|kts|java)):(?:(\\d+):(\\d+)|\\s*\\((\\d+),\\s*(\\d+)\\))(?::\\s*|\\s+)(.*)$", RegexOption.IGNORE_CASE)
    private val python = Regex("^\\s*File \"(.+\\.py)\", line (\\d+)(?:,.*)?$")

    fun parse(output: String, maxDiagnostics: Int = 100): List<Diagnostic> = output.lineSequence().take(2000).mapNotNull { rawLine ->
        val line = rawLine.removePrefix("[stderr] ").removePrefix("[stdout] ")
        val kotlinMatch = kotlin.matchEntire(line)
        if (kotlinMatch != null) {
            val lineNo = kotlinMatch.groupValues[3].ifBlank { kotlinMatch.groupValues[5] }.toIntOrNull() ?: return@mapNotNull null
            val column = kotlinMatch.groupValues[4].ifBlank { kotlinMatch.groupValues[6] }.toIntOrNull()
            return@mapNotNull Diagnostic(normalizeFile(kotlinMatch.groupValues[2]), lineNo, column, kotlinMatch.groupValues[7].take(1000), if (kotlinMatch.groupValues[1].equals("w", true)) "warning" else "error")
        }
        val compilerMatch = compiler.matchEntire(line)
        if (compilerMatch != null) return@mapNotNull Diagnostic(normalizeFile(compilerMatch.groupValues[1]), compilerMatch.groupValues[2].toIntOrNull() ?: return@mapNotNull null, compilerMatch.groupValues[3].toIntOrNull(), compilerMatch.groupValues[5].take(1000), compilerMatch.groupValues[4].ifBlank { null })
        val pythonMatch = python.matchEntire(line)
        if (pythonMatch != null) return@mapNotNull Diagnostic(pythonMatch.groupValues[1], pythonMatch.groupValues[2].toIntOrNull() ?: return@mapNotNull null, null, "Python traceback location", "error")
        null
    }.take(maxDiagnostics).toList()

    private fun normalizeFile(value: String): String = if (value.startsWith("file:")) runCatching { java.net.URI(value).path ?: value }.getOrDefault(value) else value
}
