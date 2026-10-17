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
        envFile: Path? = null,
        start: Path = Path.of("").toAbsolutePath(),
    ): String? {
        environment["DEEPSEEK_API_KEY"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        environment["API_TOKEN"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        val candidates = if (envFile != null) listOf(if (envFile.isAbsolute) envFile else start.resolve(envFile)) else dotenvCandidates(start)
        return candidates.firstNotNullOfOrNull { path ->
            val values = parse(readBoundedUtf8(path.normalize()) ?: return@firstNotNullOfOrNull null)
            values["API_TOKEN"]?.takeIf { it.isNotEmpty() } ?: values["DEEPSEEK_API_KEY"]?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Keys for the named variables (e.g. OPENAI_API_KEY) from the environment first, then from the same
     * `.env` files as [load]. Values are never logged; blank values are ignored.
     */
    fun loadNamed(names: Collection<String>, environment: Map<String, String> = System.getenv(), start: Path = Path.of("").toAbsolutePath()): Map<String, String> {
        val wanted = names.toSet()
        val fromFiles = dotenvCandidates(start).asReversed().fold(mapOf<String, String>()) { acc, path ->
            acc + (readBoundedUtf8(path.normalize())?.let { parse(it, wanted) } ?: emptyMap())
        }
        return wanted.mapNotNull { name ->
            (environment[name]?.trim()?.takeIf { it.isNotEmpty() } ?: fromFiles[name]?.takeIf { it.isNotEmpty() })?.let { name to it }
        }.toMap()
    }

    /**
     * `.env` in the working directory, then in the enclosing project root: the nearest ancestor (at most
     * four levels up) holding `settings.gradle.kts` or `.git`. `gradlew :app-desktop:run` and IDE runs
     * start in a module folder, so the root `.env` would otherwise be missed. Nothing above the root is read.
     */
    internal fun dotenvCandidates(start: Path): List<Path> {
        val origin = start.toAbsolutePath().normalize()
        val root = generateSequence(origin) { it.parent }.take(5)
            .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) || Files.exists(it.resolve(".git")) }
        return listOfNotNull(origin.resolve(".env"), root?.resolve(".env")).distinct()
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

    private fun parse(contents: String, names: Set<String> = setOf("API_TOKEN", "DEEPSEEK_API_KEY")): Map<String, String> {
        val parsed = linkedMapOf<String, String>()
        for (rawLine in contents.lineSequence()) {
            var line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            if (line.startsWith("export ")) line = line.removePrefix("export ").trimStart()
            val equals = line.indexOf('=')
            if (equals <= 0) continue
            val name = line.substring(0, equals).trim()
            if (name !in names) continue
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
