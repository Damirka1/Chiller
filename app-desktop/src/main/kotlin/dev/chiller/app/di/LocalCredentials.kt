package dev.chiller.app.di

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

/** Resolves a DeepSeek key without logging or executing dotenv contents. */
object LocalCredentials {
    private const val MAX_ENV_BYTES = 64 * 1024L

    fun load(
        environment: Map<String, String> = System.getenv(),
        envFile: Path = Path.of(".env"),
    ): String? {
        environment["DEEPSEEK_API_KEY"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        environment["API_TOKEN"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        val path = (if (envFile.isAbsolute) envFile else Path.of("").toAbsolutePath().resolve(envFile)).normalize()
        val contents = readBoundedUtf8(path) ?: return null
        val values = parse(contents)
        return values["API_TOKEN"]?.takeIf { it.isNotEmpty() }
            ?: values["DEEPSEEK_API_KEY"]?.takeIf { it.isNotEmpty() }
    }

    private fun readBoundedUtf8(path: Path): String? {
        return try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_ENV_BYTES) return null
            val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_ENV_BYTES.toInt() + 1) }
            if (bytes.size > MAX_ENV_BYTES) return null
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun parse(contents: String): Map<String, String> {
        val parsed = linkedMapOf<String, String>()
        for (rawLine in contents.lineSequence()) {
            var line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            if (line.startsWith("export ")) line = line.removePrefix("export ").trimStart()
            val equals = line.indexOf('=')
            if (equals <= 0) continue
            val name = line.substring(0, equals).trim()
            if (name != "API_TOKEN" && name != "DEEPSEEK_API_KEY") continue
            val rawValue = line.substring(equals + 1).trim()
            val value = parseValue(rawValue) ?: continue
            parsed.putIfAbsent(name, value.trim())
        }
        return parsed
    }

    private fun parseValue(raw: String): String? {
        if (raw.isEmpty()) return ""
        val quote = raw.first()
        if (quote == '\'' || quote == '"') {
            val close = raw.indexOf(quote, startIndex = 1)
            if (close < 0) return null
            val trailing = raw.substring(close + 1).trim()
            if (trailing.isNotEmpty() && !trailing.startsWith('#')) return null
            return raw.substring(1, close)
        }
        if (raw.startsWith('#')) return ""
        val comment = raw.indexOf(" #")
        return (if (comment >= 0) raw.substring(0, comment) else raw).trimEnd()
    }
}
