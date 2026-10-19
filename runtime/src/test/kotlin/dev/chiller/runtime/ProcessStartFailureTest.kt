package dev.chiller.runtime

import dev.chiller.contracts.StartProcessRequest
import dev.chiller.contracts.ToolException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProcessStartFailureTest {
    @Test fun missingProgramIsNamedOnThisOs() = runBlocking {
        val service = LocalProcessService(Files.createTempDirectory("chiller-process"))
        try {
            val error = assertFailsWith<ToolException> { service.start(StartProcessRequest(listOf("chiller-no-such-program-7f3")), "r1") }
            assertEquals("EXECUTABLE_NOT_FOUND", error.code)
            assertTrue("chiller-no-such-program-7f3" in error.message)
        } finally { service.close() }
    }

    @Test fun windowsAndPosixMessagesMapToActionableCodes() {
        val windows = startFailure("firefox", IOException("Cannot run program \"firefox\": CreateProcess error=2, The system cannot find the file specified"), "Windows 11")
        assertEquals("EXECUTABLE_NOT_FOUND", windows.code)
        assertTrue("cmd" in windows.recovery.orEmpty() && "start" in windows.recovery.orEmpty())
        val posix = startFailure("xdg-open", IOException("Cannot run program \"xdg-open\": error=2, No such file or directory"), "Linux")
        assertEquals("EXECUTABLE_NOT_FOUND", posix.code)
        assertEquals("PERMISSION_DENIED", startFailure("tool", IOException("CreateProcess error=5, Access is denied"), "Windows 11").code)
        assertEquals("PROCESS_START_FAILED", startFailure("tool", IOException("something else"), "Linux").code)
    }
}
