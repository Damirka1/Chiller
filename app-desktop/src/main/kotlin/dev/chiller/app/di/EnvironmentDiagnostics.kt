package dev.chiller.app.di

import dev.chiller.workspace.Ripgrep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.TimeUnit

/** Explicit, local version probes only; no project commands, network or credential reads. */
object EnvironmentDiagnostics {
    suspend fun inspect(): List<Pair<String, String>> = runInterruptible(Dispatchers.IO) {
        listOf("Поиск (ripgrep)" to runCatching { Ripgrep.executable() }.getOrNull(), "Git" to "git", "Python 3" to "python3").map { (label, command) ->
            label to if (command == null) "Встроенный инструмент повреждён — переустановите Chiller" else probe(command)
        }
    }
    private fun probe(command: String): String {
        val process = try { ProcessBuilder(command, "--version").redirectErrorStream(true).start() }
            catch (_: java.io.IOException) { return "Не найден. Установите инструмент, если он нужен проекту." }
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) return "Нет ответа за 3 секунды"
            val text = process.inputStream.readNBytes(4096).toString(Charsets.UTF_8).lineSequence().firstOrNull().orEmpty()
            return if (process.exitValue() == 0) text.take(180).ifBlank { "Доступен" } else "Ошибка запуска (${process.exitValue()})"
        } finally { if (process.isAlive) process.destroyForcibly(); process.inputStream.close() }
    }
}
