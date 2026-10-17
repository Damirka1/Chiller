package dev.chiller.app.di

import dev.chiller.contracts.AgentMessage
import dev.chiller.sessions.JsonlEventStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopControllerTest {
    @TempDir lateinit var directory: Path

    @Test fun `history without a workspace can be viewed without creating an engine`() = runBlocking {
        val store = JsonlEventStore(directory.resolve("sessions"))
        val message = AgentMessage("user", "Offline fixture")
        store.append("offline-fixture", "message", Json.encodeToJsonElement(AgentMessage.serializer(), message).jsonObject)
        val controller = DesktopController(directory)
        try {
            controller.initialize()
            controller.selectSession("offline-fixture")
            assertEquals(listOf(message), controller.state.value.run.messages)
            assertEquals("offline-fixture", controller.state.value.sessionId)
            assertNull(controller.state.value.settings.workspace)
            assertNull(controller.state.value.run.error)
        } finally { controller.close() }
    }
}
