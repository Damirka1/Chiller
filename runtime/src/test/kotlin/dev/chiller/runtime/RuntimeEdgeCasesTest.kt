package dev.chiller.runtime

import dev.chiller.contracts.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeEdgeCasesTest {
    @Test
    fun stopRunTerminatesBackgroundProcessesOwnedByRun() = runBlocking {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "Requires /bin/sh")
        val root = Files.createTempDirectory("chiller-background-stop-")
        val pidFile = root.resolve("child.pid")
        val service = LocalProcessService(root)
        try {
            val started = service.start(
                StartProcessRequest(
                    listOf("/bin/sh", "-c", "echo \$\$ > '${pidFile.fileName}'; exec sleep 30"),
                    timeoutMs = 30_000,
                    background = true,
                ),
                "owned-run",
            )
            withTimeout(3_000) { while (!Files.exists(pidFile)) delay(10) }
            val pid = Files.readString(pidFile).trim().toLong()

            service.stopRun("owned-run")

            withTimeout(2_000) {
                while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(10)
            }
            assertEquals("stopped", service.read(started.processId).state)
        } finally {
            service.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun completedProcessRecordsAreBoundedAndEvictedIdsAreClear() = runBlocking {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "Requires /bin/sh")
        val root = Files.createTempDirectory("chiller-process-retention-")
        val service = LocalProcessService(root)
        try {
            val ids = (1..70).map {
                service.start(StartProcessRequest(listOf("/bin/sh", "-c", "printf 'done-$it'")), "retention-run").processId
            }
            val oldest = ids.first()
            withTimeout(3_000) {
                while (true) {
                    val failure = runCatching { service.read(oldest) }.exceptionOrNull()
                    if (failure is ToolException && failure.code == "PROCESS_EXPIRED") break
                    delay(10)
                }
            }
            val latest = service.read(ids.last())
            assertEquals("exited", latest.state)
            assertTrue(latest.output.contains("done-70"))
        } finally {
            service.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrentStartsCannotExceedEightActiveProcesses() = runBlocking {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "Requires /bin/sh")
        val root = Files.createTempDirectory("chiller-process-admission-")
        val service = LocalProcessService(root)
        try {
            val outcomes = (1..12).map { index ->
                async(Dispatchers.IO) {
                    runCatching {
                        service.start(
                            StartProcessRequest(listOf("/bin/sh", "-c", "exec sleep 30"), timeoutMs = 60_000, background = true),
                            "admission-$index",
                        )
                    }
                }
            }.awaitAll()
            assertEquals(8, outcomes.count { it.isSuccess })
            assertEquals(4, outcomes.mapNotNull { it.exceptionOrNull() as? ToolException }.count { it.code == "PROCESS_LIMIT" })
            service.stopRun("admission-1")
            outcomes.forEachIndexed { index, outcome ->
                if (index > 0) outcome.getOrNull()?.let { service.stop(it.processId) }
            }
        } finally {
            service.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun closeTerminatesBackgroundProcesses() = runBlocking {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "Requires /bin/sh")
        val root = Files.createTempDirectory("chiller-background-close-")
        val pidFile = root.resolve("child.pid")
        val service = LocalProcessService(root)
        val started = service.start(
            StartProcessRequest(
                listOf("/bin/sh", "-c", "echo \$\$ > '${pidFile.fileName}'; exec sleep 30"),
                timeoutMs = 30_000,
                background = true,
            ),
            "close-run",
        )
        try {
            withTimeout(3_000) { while (!Files.exists(pidFile)) delay(10) }
            val pid = Files.readString(pidFile).trim().toLong()

            service.close()

            withTimeout(2_000) {
                while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(10)
            }
            assertEquals("stopped", service.read(started.processId).state)
            val closedError = try {
                service.start(StartProcessRequest(listOf("/bin/sh", "-c", "exit 0")), "after-close")
                null
            } catch (failure: ToolException) { failure }
            assertEquals("PROCESS_SERVICE_CLOSED", closedError?.code)
        } finally {
            service.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun callerCancellationTerminatesForegroundProcess() = runBlocking {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "Requires /bin/sh")
        val root = Files.createTempDirectory("chiller-cancel-")
        val pidFile = root.resolve("child.pid")
        val service = LocalProcessService(root)
        try {
            val start = async(Dispatchers.IO) {
                service.start(
                    StartProcessRequest(
                        listOf("/bin/sh", "-c", "echo \$\$ > '${pidFile.fileName}'; exec sleep 30"),
                        timeoutMs = 30_000,
                    ),
                    "cancel-run",
                )
            }
            withTimeout(3_000) {
                while (!Files.exists(pidFile)) delay(10)
            }
            val pid = Files.readString(pidFile).trim().toLong()
            start.cancelAndJoin()
            withTimeout(2_000) {
                while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(10)
            }
            assertTrue(start.isCancelled)
        } finally {
            service.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun outputFloodIsCappedAndMarkedTruncated() = runBlocking {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "Requires /bin/sh")
        val root = Files.createTempDirectory("chiller-output-")
        val service = LocalProcessService(root)
        try {
            val result = withTimeout(10_000) {
                service.start(
                    StartProcessRequest(
                        listOf("/bin/sh", "-c", "while :; do printf 'abcdefghij'; done"),
                        timeoutMs = 10_000,
                    ),
                    "output-run",
                )
            }
            assertEquals("output_limit", result.state)
            assertTrue(result.truncated)

            var cursor = 0L
            var outputLength = 0
            var pages = 0
            while (true) {
                val page = service.read(result.processId, cursor)
                outputLength += page.output.length
                if (page.cursor == cursor) break
                cursor = page.cursor
                pages++
                assertTrue(pages < 100, "Output paging should remain bounded")
            }
            assertTrue(outputLength <= 1_048_576, "Captured output exceeded the configured 1 MiB cap: $outputLength")
        } finally {
            service.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsAbsoluteEscapingMissingAndFileWorkingDirectories() = runBlocking {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "Requires /bin/sh")
        val root = Files.createTempDirectory("chiller-cwd-")
        val outside = root.resolveSibling("${root.fileName}-outside")
        Files.createDirectory(outside)
        Files.writeString(root.resolve("not-a-directory"), "synthetic")
        val service = LocalProcessService(root)
        try {
            val cwdValues = listOf(
                root.toAbsolutePath().toString(),
                "../${outside.fileName}",
                "missing-directory",
                "not-a-directory",
            )
            for (cwd in cwdValues) {
                val failure = try {
                    service.start(StartProcessRequest(listOf("/bin/sh", "-c", "exit 0"), cwd = cwd), "cwd-run")
                    throw AssertionError("Expected cwd=$cwd to be rejected")
                } catch (failure: Exception) { failure }
                assertTrue(failure is ToolException, "Expected a structured cwd error for cwd=$cwd, got $failure")
                assertEquals(
                    if (cwd == "missing-directory") "INVALID_CWD" else "PATH_OUTSIDE_WORKSPACE",
                    (failure as ToolException).code,
                    "cwd=$cwd",
                )
            }
        } finally {
            service.close()
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }
}
