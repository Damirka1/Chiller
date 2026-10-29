package dev.chiller.app.di

import dev.chiller.app.i18n.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A dialog's own git working copy of a project: [path] is checked out on [branch], made from [base] of [repo]. */
@Serializable
data class WorktreeInfo(val repo: String, val path: String, val branch: String, val base: String)

sealed interface WorktreeApply {
    data class Applied(val files: List<String>) : WorktreeApply
    data object Nothing : WorktreeApply
    /** The project changed in the same places: nothing was applied, the copy and its branch stay. */
    data class Conflict(val message: String) : WorktreeApply
}

class GitException(message: String) : Exception(message)

/**
 * Working copies made with `git worktree` under the app data, so several dialogs can change one project at once
 * without touching each other's files. Every call is argv without a shell, bounded in time and output.
 *
 * Changes go back to the project only as a patch checked with `git apply --check` first: an exact apply or nothing,
 * never a fuzzy one. The project's own working tree is changed only by that final apply.
 */
class GitWorktrees(private val home: Path, private val git: String = "git") {
    /** The folder is the top of a git repository with at least one commit, so a copy can start from its HEAD. */
    suspend fun canIsolate(root: Path): Boolean = runCatching {
        val top = run(root, "rev-parse", "--show-toplevel").trim()
        run(root, "rev-parse", "--verify", "HEAD")
        Path.of(top).toRealPath() == root.toRealPath()
    }.getOrDefault(false)

    suspend fun create(repo: Path, sessionId: String): WorktreeInfo {
        require(sessionId.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
        val root = repo.toRealPath()
        val base = run(root, "rev-parse", "HEAD").trim()
        val branch = "chiller/" + sessionId.take(12)
        val target = home.resolve(sessionId).resolve(root.fileName?.toString() ?: "project")
        withContext(Dispatchers.IO) { Files.createDirectories(target.parent) }
        run(root, "worktree", "add", "-b", branch, target.toString(), base)
        return WorktreeInfo(root.toString(), target.toRealPath().toString(), branch, base)
    }

    /** Files that differ from the copy's starting commit, committed or not, untracked included. */
    suspend fun changedFiles(info: WorktreeInfo): List<String> {
        val path = Path.of(info.path)
        run(path, "add", "-A")
        return run(path, "diff", "--cached", "--name-only", "-z", info.base).split('\u0000').filter { it.isNotEmpty() }
    }

    /** Carries the copy's changes into the project as one exact patch, or reports why it does not apply. */
    suspend fun apply(info: WorktreeInfo): WorktreeApply {
        val files = changedFiles(info)
        if (files.isEmpty()) return WorktreeApply.Nothing
        val patch = runBytes(Path.of(info.path), "diff", "--cached", "--binary", info.base)
        val file = withContext(Dispatchers.IO) { Files.createTempFile("chiller-worktree", ".patch").also { Files.write(it, patch) } }
        try {
            val repo = Path.of(info.repo)
            try { run(repo, "apply", "--check", "--binary", file.toString()) }
            catch (e: GitException) { return WorktreeApply.Conflict(e.message.orEmpty()) }
            run(repo, "apply", "--binary", file.toString())
            return WorktreeApply.Applied(files)
        } finally { withContext(Dispatchers.IO) { Files.deleteIfExists(file) } }
    }

    /** Removes the copy's folder; its branch stays in the repository with whatever was committed there. */
    suspend fun remove(info: WorktreeInfo) {
        run(Path.of(info.repo), "worktree", "remove", "--force", info.path)
        runCatching { withContext(Dispatchers.IO) { Path.of(info.path).parent?.let { Files.deleteIfExists(it) } } }
    }

    /** Removes a copy that was never used, with its branch. */
    suspend fun discard(info: WorktreeInfo) {
        remove(info)
        runCatching { run(Path.of(info.repo), "branch", "-D", info.branch) }
    }

    private suspend fun run(dir: Path, vararg args: String): String = runBytes(dir, *args).toString(Charsets.UTF_8)

    private suspend fun runBytes(dir: Path, vararg args: String): ByteArray = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(listOf(git, "-c", "core.quotepath=false") + args).directory(dir.toFile()).apply {
                environment()["GIT_TERMINAL_PROMPT"] = "0"
                redirectErrorStream(false)
            }.start()
        } catch (_: Exception) { throw GitException(I18n.current.di.gitMissing) }
        process.outputStream.close()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        fun drain(stream: java.io.InputStream, target: ByteArrayOutputStream, cap: Int) = Thread {
            stream.use { input -> val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; val room = cap + 1 - target.size(); if (room > 0) target.write(buffer, 0, minOf(n, room)) } }
        }.apply { isDaemon = true; start() }
        val out = drain(process.inputStream, stdout, MAX_OUTPUT)
        val err = drain(process.errorStream, stderr, 8192)
        try {
            if (!runInterruptible { process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) }) throw GitException(I18n.current.di.gitTimeout(TIMEOUT_SECONDS.toLong()))
            out.join(2000); err.join(2000)
            if (stdout.size() > MAX_OUTPUT) throw GitException(I18n.current.di.gitTooLarge((MAX_OUTPUT / 1024 / 1024).toLong()))
            if (process.exitValue() != 0) throw GitException(stderr.toString(Charsets.UTF_8).trim().take(600).ifEmpty { I18n.current.di.gitExit(process.exitValue()) })
            stdout.toByteArray()
        } finally {
            if (process.isAlive) process.destroyForcibly()
            out.interrupt(); err.interrupt()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60L
        const val MAX_OUTPUT = 32 * 1024 * 1024
    }
}
