package dev.chiller.app.di

import dev.chiller.app.i18n.*
import dev.chiller.workspace.Ripgrep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.TimeUnit

/** Explicit, local version probes only; no project commands, network or credential reads. */
object EnvironmentDiagnostics {
    suspend fun inspect(): List<Pair<String, String>> = runInterruptible(Dispatchers.IO) {
        val rg = runCatching { Ripgrep.executable() }.getOrNull()
        // "rg" means no bundled copy is configured (tests, unpackaged runs): the one on PATH is used instead.
        val rgSource = when (rg) { null -> null; "rg" -> I18n.current.di.fromPath; else -> I18n.current.di.bundled }
        listOf(I18n.current.di.search to rg, "Git" to "git", "Python 3" to "python3").map { (label, command) ->
            label to when {
                command == null -> I18n.current.di.bundledBroken
                command == rg && rgSource != null -> "${probe(command)} · $rgSource"
                else -> probe(command)
            }
        }
    }
    private fun probe(command: String): String {
        val process = try { ProcessBuilder(command, "--version").redirectErrorStream(true).start() }
            catch (_: java.io.IOException) { return I18n.current.di.toolMissing }
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) return I18n.current.di.noReply3s
            val text = process.inputStream.readNBytes(4096).toString(Charsets.UTF_8).lineSequence().firstOrNull().orEmpty()
            return if (process.exitValue() == 0) text.take(180).ifBlank { I18n.current.di.available } else I18n.current.di.startError(process.exitValue())
        } finally { if (process.isAlive) process.destroyForcibly(); process.inputStream.close() }
    }
}
