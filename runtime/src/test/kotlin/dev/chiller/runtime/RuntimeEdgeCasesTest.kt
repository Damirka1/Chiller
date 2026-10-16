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
