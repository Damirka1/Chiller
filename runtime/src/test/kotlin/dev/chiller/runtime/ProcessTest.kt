package dev.chiller.runtime

import dev.chiller.contracts.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class ProcessTest {
    @Test fun capturesBothPipesAndExit() = runBlocking {
        val service = LocalProcessService(Files.createTempDirectory("chiller-process"))
        try {
            val result = service.start(StartProcessRequest(listOf("/bin/sh", "-c", "echo out; echo err >&2; exit 7")), "r1")
            assertTrue(result.output.contains("out")); assertTrue(result.output.contains("err"))
            assertEquals(7, result.exitCode)
        } finally { service.close() }
    }
    @Test fun timeoutStopsProcess() = runBlocking {
        val service = LocalProcessService(Files.createTempDirectory("chiller-process"))
        try {
            val result = service.start(StartProcessRequest(listOf("/bin/sh", "-c", "sleep 30"), timeoutMs = 150), "r1")
            assertEquals("timed_out", result.state)
        } finally { service.close() }
    }
}
