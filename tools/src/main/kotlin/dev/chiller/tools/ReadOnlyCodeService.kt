package dev.chiller.tools

import dev.chiller.contracts.ToolException
import dev.chiller.contracts.ToolResult
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.channels.Channels
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlin.io.path.isRegularFile

/** Read-only, bounded repository inspection. This is lexical navigation, not an LSP. */
interface ReadOnlyCodeService {
    suspend fun gitStatus(): ToolResult
    suspend fun gitDiff(staged: Boolean, path: String?): ToolResult
    suspend fun gitLog(limit: Int, revision: String?): ToolResult
    suspend fun gitShow(revision: String, path: String): ToolResult
    suspend fun findSymbols(query: String, path: String?, limit: Int): ToolResult
}

class LocalReadOnlyCodeService(workspaceRoot: String) : ReadOnlyCodeService {
    private val root = Path.of(workspaceRoot).toAbsolutePath().normalize().toRealPath()

    override suspend fun gitStatus() = git(listOf("status", "--short", "--branch", "--untracked-files=normal", "--ignore-submodules=all"))

    override suspend fun gitDiff(staged: Boolean, path: String?): ToolResult {
        val args = mutableListOf("diff", "--no-ext-diff", "--no-textconv", "--no-color")
        if (staged) args += "--cached"
        if (path != null) { args += "--"; args += checkedPath(path) }
        return git(args)
    }

    override suspend fun gitLog(limit: Int, revision: String?): ToolResult {
        if (limit !in 1..30) throw ToolException("INVALID_ARGUMENTS", "limit must be between 1 and 30")
        val args = mutableListOf("log", "--no-color", "--format=%h%x09%s", "-n", limit.toString())
        revision?.let { args += checkedRevision(it) }
        args += "--"
        return git(args)
    }

    override suspend fun gitShow(revision: String, path: String): ToolResult {
        val spec = "${checkedRevision(revision)}:${checkedPath(path)}"
        return git(listOf("show", "--no-ext-diff", "--no-textconv", "--no-color", spec))
    }

    override suspend fun findSymbols(query: String, path: String?, limit: Int): ToolResult = withContext(Dispatchers.IO) {
        if (query.isBlank() || query.length > 120) throw ToolException("INVALID_ARGUMENTS", "query must contain 1–120 characters")
        if (limit !in 1..100) throw ToolException("INVALID_ARGUMENTS", "limit must be between 1 and 100")
        val checked = path?.let(::checkedPath)?.takeIf { it.isNotEmpty() }
        val prefix = checked?.let { if (it.endsWith('/')) it else "$it/" }
        val listing = runRgFileList(checked)
        if (listing.truncated) throw ToolException("OUTPUT_LIMIT", "Repository file list exceeded its scan limit")
        val names = listing.text.split('\u0000').filter(String::isNotEmpty)
        var scanTruncated = names.size > 5000
        val files = names.asSequence().take(5000).mapNotNull { name ->
            val rel = runCatching { checkedPath(name) }.getOrNull() ?: return@mapNotNull null
            if (prefix != null && !rel.startsWith(prefix) && rel != prefix.removeSuffix("/")) return@mapNotNull null
            root.resolve(rel).takeIf { it.isRegularFile() && supported(it) && !Files.isSymbolicLink(it) }
        }.toList()
        var scannedBytes = 0L
        val matches = buildJsonArray {
            var count = 0
            for (file in files) {
                if (count >= limit || count >= 500) { scanTruncated = true; break }
                if (!supported(file)) continue
                val size = Files.size(file)
                if (size > MAX_FILE_BYTES || scannedBytes + minOf(size, MAX_FILE_BYTES) > MAX_TOTAL_SCAN_BYTES) { scanTruncated = true; break }
                val relative = root.relativize(file).toString().replace('\\', '/')
                val bytes = Files.newByteChannel(file, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { Channels.newInputStream(it).readNBytes((MAX_FILE_BYTES + 1).toInt()) }
                if (bytes.size > MAX_FILE_BYTES) { scanTruncated = true; break }
                scannedBytes += bytes.size
                if (scannedBytes > MAX_TOTAL_SCAN_BYTES) { scanTruncated = true; break }
                bytes.toString(Charsets.UTF_8).lineSequence().forEachIndexed { index, line ->
                    if (count < limit && isDeclaration(line) && line.contains(query, ignoreCase = true)) {
                        add(buildJsonObject { put("path", relative); put("line", index + 1); put("text", line.take(400)) })
                        count++
                    }
                }
            }
        }
        ToolResult(true, buildJsonObject { put("matches", matches); put("lexical", true) }, truncated = scanTruncated || matches.size >= limit)
    }

    private suspend fun git(args: List<String>): ToolResult = withContext(Dispatchers.IO) {
        ensureGitRoot()
        val result = runGitCommand(args)
        ToolResult(true, buildJsonObject { put("output", result.text) }, truncated = result.truncated)
    }

    private suspend fun ensureGitRoot() {
        val top = runGitCommand(listOf("rev-parse", "--show-toplevel")).text.trim().let { runCatching { Path.of(it).toRealPath() }.getOrNull() }
        if (top != root) throw ToolException("GIT_ROOT_MISMATCH", "Git repository root must be the workspace root")
    }

    private data class CommandOutput(val text: String, val truncated: Boolean)

    private suspend fun runRgFileList(path: String?): CommandOutput = withContext(Dispatchers.IO) {
        val command = buildList {
            add("rg"); add("--no-config"); add("--no-require-git"); add("--files"); add("--null"); add("--hidden"); add("--path-separator"); add("/"); add("--sort"); add("path"); add("--glob"); add("!.git/**"); add("--glob"); add("!**/.git/**")
            add("--glob"); add("!**/build/**"); add("--glob"); add("!**/node_modules/**"); add("--glob"); add("!**/.gradle/**")
            val localIgnore = root.resolve(".chillerignore")
            if (Files.isRegularFile(localIgnore) && !Files.isSymbolicLink(localIgnore)) { add("--ignore-file"); add(localIgnore.toString()) }
            add("--"); add(path ?: ".")
        }
        val process = try { ProcessBuilder(command).directory(root.toFile()).apply {
            environment().keys.filter { it.startsWith("RG_") || it == "RIPGREP_CONFIG_PATH" }.toList().forEach(environment()::remove)
            redirectErrorStream(false)
        }.start() } catch (_: Exception) { throw ToolException("RG_UNAVAILABLE", "ripgrep is required for bounded symbol file discovery") }
        process.outputStream.close()
        val stdout = ByteArrayOutputStream(OUTPUT_CAP + 1)
        val stderr = ByteArrayOutputStream(4096)
        fun drain(stream: java.io.InputStream, target: ByteArrayOutputStream, cap: Int) = Thread {
            stream.use { input -> val buffer = ByteArray(4096); while (true) { val n = input.read(buffer); if (n < 0) break; val remaining = cap + 1 - target.size(); if (remaining > 0) target.write(buffer, 0, minOf(n, remaining)) } }
        }.apply { isDaemon = true; start() }
        val outThread = drain(process.inputStream, stdout, OUTPUT_CAP)
        val errThread = drain(process.errorStream, stderr, 4096)
        try {
            if (!runInterruptible { process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) }) throw ToolException("TIMEOUT", "Symbol file discovery exceeded its time limit")
            outThread.join(1000); errThread.join(1000)
            if (process.exitValue() > 1) throw ToolException("SEARCH_FAILED", stderr.toByteArray().toString(Charsets.UTF_8).take(1000))
            val captured = stdout.toByteArray()
            CommandOutput(captured.copyOf(minOf(captured.size, OUTPUT_CAP)).toString(Charsets.UTF_8), captured.size > OUTPUT_CAP)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }; runCatching { process.errorStream.close() }
            outThread.interrupt(); errThread.interrupt(); outThread.join(500); errThread.join(500)
        }
    }

    private suspend fun runGitCommand(args: List<String>): CommandOutput = withContext(Dispatchers.IO) {
        val nullDevice = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "NUL" else "/dev/null"
        val command = listOf("git", "--no-optional-locks", "--no-pager", "-c", "core.pager=cat", "-c", "diff.external=", "-c", "core.attributesFile=$nullDevice", "-c", "core.fsmonitor=false") + args
        val process = try { ProcessBuilder(command).directory(root.toFile()).apply {
            environment().keys.filter { it.startsWith("GIT_") }.toList().forEach(environment()::remove)
            environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            environment()["GIT_CONFIG_GLOBAL"] = nullDevice
            environment()["GIT_TERMINAL_PROMPT"] = "0"
            environment()["GIT_PAGER"] = "cat"
            environment()["GIT_OPTIONAL_LOCKS"] = "0"
            redirectErrorStream(true)
        }.start() } catch (_: Exception) { throw ToolException("GIT_UNAVAILABLE", "Could not start Git") }
        val output = ByteArrayOutputStream(OUTPUT_CAP + 1)
        val reader = Thread { process.inputStream.use { input -> val buffer = ByteArray(4096); while (true) { val n = input.read(buffer); if (n < 0) break; val remaining = OUTPUT_CAP + 1 - output.size(); if (remaining > 0) output.write(buffer, 0, minOf(n, remaining)) } } }.apply { isDaemon = true; start() }
        try {
            process.outputStream.close()
            if (!runInterruptible { process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) })
                throw ToolException("TIMEOUT", "Git inspection exceeded its time limit")
            reader.join(1000)
            val captured = output.toByteArray()
            val text = captured.copyOf(minOf(captured.size, OUTPUT_CAP)).toString(Charsets.UTF_8)
            if (process.exitValue() != 0) throw ToolException("GIT_FAILED", text.take(1000).ifBlank { "Git inspection failed" })
            CommandOutput(text, captured.size > OUTPUT_CAP)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            reader.interrupt()
            reader.join(500)
        }
    }

    private fun checkedRevision(value: String): String {
        if (value.length !in 1..128 || !value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]*")) || value.startsWith('-') || value.contains(".."))
            throw ToolException("INVALID_ARGUMENTS", "revision must be a simple commit or branch name")
        return value
    }

    private fun checkedPath(value: String): String {
        if (value.isBlank() || value.length > 400 || value.startsWith('/') || value.contains('\\') || value.contains(':'))
            throw ToolException("INVALID_ARGUMENTS", "path must be relative to the workspace")
        val rel = Path.of(value).normalize()
        if (rel.isAbsolute || rel.startsWith("..")) throw ToolException("INVALID_ARGUMENTS", "path escapes the workspace")
        val candidate = root.resolve(rel).normalize(); requireInside(candidate)
        var p: Path? = candidate
        while (p != null && p.startsWith(root)) { if (Files.isSymbolicLink(p)) throw ToolException("INVALID_ARGUMENTS", "symbolic-link paths are not allowed"); p = p.parent }
        return rel.toString().replace('\\', '/')
    }

    private fun requireInside(path: Path) { if (!path.startsWith(root)) throw ToolException("INVALID_ARGUMENTS", "path escapes the workspace") }
    private fun supported(path: Path) = path.fileName.toString().substringAfterLast('.', "").lowercase() in setOf("kt", "java", "kts", "py", "js", "jsx", "ts", "tsx", "go", "rs", "cs", "swift")
    private fun isDeclaration(line: String) = DECLARATION.containsMatchIn(line) || KOTLIN_FUNCTION.containsMatchIn(line)

    companion object {
        private const val OUTPUT_CAP = 48 * 1024
        private const val MAX_FILE_BYTES = 512 * 1024L
        private const val MAX_TOTAL_SCAN_BYTES = 8 * 1024 * 1024L
        private const val TIMEOUT_SECONDS = 4L
        private val DECLARATION = Regex("^\\s*(?:(?:public|private|protected|internal|static|final|async|export|abstract|open|override|data|sealed|class|interface|enum|object|fun|def|fn|func|function|type|struct|actor)\\s+)+[A-Za-z_$][A-Za-z0-9_$]*")
        private val KOTLIN_FUNCTION = Regex("^\\s*(?:(?:public|private|protected|internal|tailrec|inline|suspend|operator|infix|external|override)\\s+)*fun\\s+(?:<[^>]+>\\s+)?(?:[A-Za-z_$][A-Za-z0-9_$.<>?, ]*\\.)?[A-Za-z_$][A-Za-z0-9_$]*(?:<[^>]+>)?\\s*\\(")
    }
}

internal object UnsupportedReadOnlyCodeService : ReadOnlyCodeService {
    private fun unavailable(): Nothing = throw ToolException("UNSUPPORTED", "Read-only code navigation is not connected")
    override suspend fun gitStatus(): ToolResult = unavailable()
    override suspend fun gitDiff(staged: Boolean, path: String?): ToolResult = unavailable()
    override suspend fun gitLog(limit: Int, revision: String?): ToolResult = unavailable()
    override suspend fun gitShow(revision: String, path: String): ToolResult = unavailable()
    override suspend fun findSymbols(query: String, path: String?, limit: Int): ToolResult = unavailable()
}
