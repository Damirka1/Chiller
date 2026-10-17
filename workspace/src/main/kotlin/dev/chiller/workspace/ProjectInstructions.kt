package dev.chiller.workspace

import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Repository guidance for the model: AGENTS.md, CLAUDE.md and .chiller/instructions.md at the workspace
 * root, in that order. Symlinks and non-UTF-8 files are skipped and the total is bounded. The text is
 * guidance from the repository; it never grants permissions.
 */
object ProjectInstructions {
    val FILES = listOf("AGENTS.md", "CLAUDE.md", ".chiller/instructions.md")
    const val MAX_CHARACTERS = 12_000

    data class Loaded(val text: String, val sources: List<String>, val truncated: Boolean)

    fun load(root: Path, maxCharacters: Int = MAX_CHARACTERS): Loaded? {
        val parts = FILES.mapNotNull { name ->
            val file = root.resolve(name)
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) return@mapNotNull null
            if (runCatching { Files.size(file) > 256 * 1024 }.getOrDefault(true)) return@mapNotNull null
            val text = runCatching { decodeStrict(Files.readAllBytes(file)) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            name to text
        }
        if (parts.isEmpty()) return null
        val joined = parts.joinToString("\n\n") { (name, text) -> "## $name\n$text" }
        val truncated = joined.length > maxCharacters
        return Loaded(if (truncated) joined.take(maxCharacters) + "\n[truncated]" else joined, parts.map { it.first }, truncated)
    }

    private fun decodeStrict(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) { throw IllegalArgumentException("not UTF-8", e) }
}
