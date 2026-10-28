package dev.chiller.app.di

import dev.chiller.contracts.ExecutionMode
import dev.chiller.sessions.JsonlEventStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real git in temporary repositories; nothing outside the temp folder is touched. */
class GitWorktreesTest {
    @TempDir lateinit var temp: Path

    private fun git(dir: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.email=test@example.com", "-c", "user.name=Test", "-c", "init.defaultBranch=main") + args)
            .directory(dir.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.joinToString(" ")}: $output" }
        return output
    }

    private fun repository(name: String = "shop"): Path {
        val repo = Files.createDirectories(temp.resolve(name)).toRealPath()
        git(repo, "init")
        // Line endings as written: a machine-wide core.autocrlf must not rewrite the fixtures.
        git(repo, "config", "core.autocrlf", "false")
        Files.writeString(repo.resolve("app.txt"), "one\ntwo\nthree\n")
        git(repo, "add", "-A"); git(repo, "commit", "-m", "start")
        return repo
    }

    @Test fun `a copy carries its changes back exactly or not at all`() = runBlocking {
        val repo = repository()
        val worktrees = GitWorktrees(temp.resolve("data/worktrees"))
        assertTrue(worktrees.canIsolate(repo))
        assertFalse(worktrees.canIsolate(Files.createDirectories(repo.resolve("sub"))), "only the repository's top folder")
        val info = worktrees.create(repo, "dialog-1")
        assertEquals("chiller/dialog-1", info.branch)
        val copy = Path.of(info.path)
        assertEquals("one\ntwo\nthree\n", Files.readString(copy.resolve("app.txt")))
        assertIs<WorktreeApply.Nothing>(worktrees.apply(info))

        Files.writeString(copy.resolve("app.txt"), "one\nTWO\nthree\n")
        Files.writeString(copy.resolve("new.txt"), "new\n")
        assertEquals(listOf("app.txt", "new.txt"), worktrees.changedFiles(info).sorted())
        assertEquals("one\ntwo\nthree\n", Files.readString(repo.resolve("app.txt")), "the project is untouched until applied")
        val applied = assertIs<WorktreeApply.Applied>(worktrees.apply(info))
        assertEquals(2, applied.files.size)
        assertEquals("one\nTWO\nthree\n", Files.readString(repo.resolve("app.txt")))
        assertEquals("new\n", Files.readString(repo.resolve("new.txt")))

        worktrees.remove(info)
        assertFalse(Files.exists(copy))
        assertTrue(git(repo, "branch", "--list", info.branch).isNotBlank(), "the branch stays")
    }

    @Test fun `a conflicting change leaves the project as it is`() = runBlocking {
        val repo = repository()
        val worktrees = GitWorktrees(temp.resolve("data/worktrees"))
        val info = worktrees.create(repo, "dialog-2")
        Files.writeString(Path.of(info.path).resolve("app.txt"), "one\nfrom the copy\nthree\n")
        Files.writeString(repo.resolve("app.txt"), "one\nfrom the project\nthree\n")
        assertIs<WorktreeApply.Conflict>(worktrees.apply(info))
        assertEquals("one\nfrom the project\nthree\n", Files.readString(repo.resolve("app.txt")))
        assertTrue(Files.exists(Path.of(info.path)), "the copy stays for a manual merge")
        worktrees.discard(info)
        assertTrue(git(repo, "branch", "--list", info.branch).isBlank(), "an unused copy goes with its branch")
    }

    @Test fun `a dialog runs in its own copy, applies it and returns to the project`() = runBlocking {
        val repo = repository()
        val controller = DesktopController(temp.resolve("data"), initialCredential = null, catalogClient = OfflineCatalog)
        try {
            controller.initialize()
            controller.chooseWorkspace(repo)
            val session = controller.state.value.sessionId
            controller.isolateInWorktree()
            val info = assertNotNull(controller.state.value.worktree)
            assertEquals(info.path, controller.state.value.settings.workspace)
            assertEquals(repo.toString(), controller.state.value.settings.recentWorkspaces.first(), "the project, not the copy, is remembered")
            // Offline: the turn is journaled, and with it the copy and its project.
            assertTrue(controller.send("Поправь app.txt", ExecutionMode.PLAN).accepted)
            val listed = controller.state.value.sessions.single { it.id == session }
            assertEquals(info.path, listed.workspace)
            assertEquals(repo.toString(), listed.origin)
            assertTrue(controller.isAppFolder(info.path))
            // What is chosen in a copy is remembered for its project, not for the app's copy folder.
            controller.allowProcesses(true)
            kotlinx.coroutines.withTimeout(5_000) { while (controller.state.value.settings.projectPrefs[repo.toString()]?.allowProcesses != true) kotlinx.coroutines.delay(10) }
            assertFalse(info.path in controller.state.value.settings.projectPrefs)

            Files.writeString(Path.of(info.path).resolve("app.txt"), "one\nTWO\nthree\n")
            assertEquals(listOf("app.txt"), controller.worktreeChanges())
            assertEquals("Перенесено в проект: 1 файл", controller.applyWorktree())
            assertEquals("one\nTWO\nthree\n", Files.readString(repo.resolve("app.txt")))
            assertTrue(JsonlEventStore(temp.resolve("data/sessions")).read(session).any { it.type == "worktree.applied" })

            controller.removeWorktree()
            assertNull(controller.state.value.worktree)
            assertEquals(repo.toString(), controller.state.value.settings.workspace)
            assertFalse(Files.exists(Path.of(info.path)))
            assertNull(controller.state.value.sessions.single { it.id == session }.origin)
            assertEquals(repo.toString(), controller.state.value.sessions.single { it.id == session }.workspace)
        } finally { controller.close() }
    }

    @Test fun `an empty dialog's copy is dropped when the dialog is left`() = runBlocking {
        val repo = repository()
        val controller = DesktopController(temp.resolve("data"), initialCredential = null, catalogClient = OfflineCatalog)
        try {
            controller.initialize()
            controller.chooseWorkspace(repo)
            controller.isolateInWorktree()
            val info = assertNotNull(controller.state.value.worktree)
            controller.newSessionIn(repo.toString())
            assertNull(controller.state.value.worktree)
            kotlinx.coroutines.withTimeout(30_000) { while (Files.exists(Path.of(info.path))) kotlinx.coroutines.delay(50) }
            kotlinx.coroutines.withTimeout(30_000) { while (git(repo, "branch", "--list", info.branch).isNotBlank()) kotlinx.coroutines.delay(50) }
        } finally { controller.close() }
    }
}
