package dev.chiller.app.di

import dev.chiller.contracts.AgentMessage
import dev.chiller.contracts.ExecutionMode
import dev.chiller.sessions.JsonlEventStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

class DesktopControllerTest {
    @TempDir lateinit var directory: Path

    @Test fun `message delete and edit project history without replaying effects`() = runBlocking {
        val controller = DesktopController(directory, initialCredential = null)
        try {
            controller.initialize()
            val session = controller.state.value.sessionId
            assertTrue(controller.send("first", ExecutionMode.PLAN).accepted)
            assertTrue(controller.send("second", ExecutionMode.PLAN).accepted)
            assertEquals(4, controller.state.value.run.messages.size)
            assertTrue(controller.deleteMessage(3))
            assertEquals(listOf("first"), controller.state.value.run.messages.filter { it.role == "user" }.map { it.content })
            val historyBeforeEdit = JsonlEventStore(directory.resolve("sessions")).read(session)
            val messagesBeforeEdit = historyBeforeEdit.count { it.type == "message" }
            val runsBeforeEdit = historyBeforeEdit.count { it.type == "run.started" }
            assertTrue(controller.editMessage(0, "edited", ExecutionMode.PLAN).accepted)
            assertEquals(listOf("edited"), controller.state.value.run.messages.filter { it.role == "user" }.map { it.content })
            val historyAfterEdit = JsonlEventStore(directory.resolve("sessions")).read(session)
            assertEquals(messagesBeforeEdit, historyAfterEdit.count { it.type == "message" })
            assertEquals(runsBeforeEdit, historyAfterEdit.count { it.type == "run.started" })
            assertEquals("history.replace_turn", historyAfterEdit.last().type)
            controller.selectSession(session)
            assertEquals(listOf("edited"), controller.state.value.run.messages.filter { it.role == "user" }.map { it.content })
        } finally { controller.close() }
    }

    @Test fun `attachments belong to a session and an attachment-only message is accepted`() = runBlocking {
        val controller = DesktopController(directory, initialCredential = null)
        try {
            controller.initialize()
            val attachment = assertNotNull(controller.importTextAttachment("данные".repeat(1000)))
            assertTrue(controller.send("", ExecutionMode.PLAN, attachments = listOf(attachment)).accepted)
            assertEquals(attachment, controller.state.value.run.messages.first().attachments.single())
            controller.newSession()
            assertFalse(controller.send("read attachment", ExecutionMode.PLAN, attachments = listOf(attachment)).accepted)
        } finally { controller.close() }
    }

    @Test fun `late import remains bound to its original session after switching chats`() = runBlocking {
        val controller = DesktopController(directory, initialCredential = null)
        try {
            controller.initialize()
            val owner = controller.state.value.sessionId
            controller.send("persist owner", ExecutionMode.PLAN)
            controller.newSession()
            val other = controller.state.value.sessionId
            val imported = assertNotNull(controller.importTextAttachmentForSession(owner, "synthetic dropped content"))
            assertEquals(other, controller.state.value.sessionId)
            assertFalse(controller.send("wrong chat", ExecutionMode.PLAN, attachments = listOf(imported)).accepted)
            controller.selectSession(owner)
            assertTrue(controller.send("right chat", ExecutionMode.PLAN, attachments = listOf(imported)).accepted)
            assertNull(controller.importTextAttachmentForSession("missing-session", "refuse orphan"))
        } finally { controller.close() }
    }

    @Test fun `session deletion requires preview refuses selected and preserves project files`() = runBlocking {
        val root = directory.toRealPath()
        val project = java.nio.file.Files.createDirectories(root.resolve("project"))
        java.nio.file.Files.writeString(project.resolve("keep.txt"), "user data")
        val store = JsonlEventStore(root.resolve("sessions"))
        store.append("old", "message", kotlinx.serialization.json.buildJsonObject { put("role", kotlinx.serialization.json.JsonPrimitive("user")); put("content", kotlinx.serialization.json.JsonPrimitive("old")) })
        val controller = DesktopController(root, initialCredential = null)
        try {
            controller.initialize()
            assertFalse(controller.deleteSession("old"))
            assertNotNull(controller.previewSessionDeletion("old"))
            controller.selectSession("old")
            assertFalse(controller.deleteSession("old"))
            assertNull(controller.previewSessionDeletion("old"))
            controller.newSession()
            assertNotNull(controller.previewSessionDeletion("old"))
            assertTrue(controller.deleteSession("old"))
            assertFalse(java.nio.file.Files.exists(root.resolve("sessions/old")))
            assertEquals("user data", java.nio.file.Files.readString(project.resolve("keep.txt")))
        } finally { controller.close() }
    }

    @Test fun `endpoint can be applied without knowing a model and blank model cannot send`() = runBlocking {
        val controller = DesktopController(directory, initialCredential = null)
        try {
            controller.initialize()
            controller.configure("https://example.test/v1", "", false, "synthetic")
            assertEquals("https://example.test/v1", controller.state.value.settings.baseUrl)
            assertEquals("", controller.state.value.settings.model)
            assertFalse(controller.send("hello", ExecutionMode.PLAN).accepted)
            assertTrue(controller.state.value.run.messages.isEmpty())
        } finally { controller.close() }
    }

    @Test fun `keychain saved key restores and deletes without writing secret to settings`() = runBlocking {
        val entries = mutableMapOf<String, String>()
        val store = object : CredentialStore {
            override val supported = true
            override fun load(origin: String) = entries[origin]
            override fun save(origin: String, key: String) { entries[origin] = key }
            override fun delete(origin: String) { entries.remove(origin) }
        }
        val first = DesktopController(directory, initialCredential = null, credentialStore = store)
        try {
            first.initialize()
            first.configure("https://example.test/v1", "fixture", false, "fixture-secret")
            assertTrue(first.saveCredential())
            assertFalse(java.nio.file.Files.readString(directory.resolve("settings.json")).contains("fixture-secret"))
        } finally { first.close() }
        val second = DesktopController(directory, initialCredential = null, credentialStore = store)
        try {
            second.initialize()
            assertTrue(second.state.value.apiKeyConfigured)
            assertEquals("keychain", second.keySources()[originOf("https://example.test")])
            assertTrue(second.deleteCredential())
            assertFalse(second.state.value.apiKeyConfigured)
            assertTrue(entries.isEmpty())
        } finally { second.close() }
    }

    @Test fun `history without a workspace can be viewed without creating an engine`() = runBlocking {
        val store = JsonlEventStore(directory.resolve("sessions"))
        val message = AgentMessage("user", "Offline fixture")
        store.append("offline-fixture", "message", Json.encodeToJsonElement(AgentMessage.serializer(), message).jsonObject)
        val controller = DesktopController(directory, initialCredential = null)
        try {
            controller.initialize()
            controller.selectSession("offline-fixture")
            assertEquals(listOf(message), controller.state.value.run.messages)
            assertEquals("offline-fixture", controller.state.value.sessionId)
            assertNull(controller.state.value.settings.workspace)
            assertNull(controller.state.value.run.error)
        } finally { controller.close() }
    }

    @Test fun `rewinding the demo turn restores every file it changed`() = runBlocking {
        val controller = DesktopController(directory.resolve("rewind"), initialCredential = null)
        try {
            controller.initialize()
            controller.startDemo()
            val root = java.nio.file.Path.of(controller.state.value.settings.workspace!!)
            val original = java.nio.file.Files.list(root).use { files -> files.toList() }.associate { it.fileName.toString() to java.nio.file.Files.readString(it) }
            kotlinx.coroutines.withTimeout(90_000) {
                while (controller.state.value.run.status !in setOf("completed", "failed")) {
                    if (controller.state.value.run.question != null) controller.answer("Да, добавь")
                    kotlinx.coroutines.delay(50)
                }
            }
            val run = controller.state.value.run
            val userIndex = run.messages.indexOfFirst { it.role == "user" }
            val ids = dev.chiller.app.ui.turnChangeIds(run.messages, run.activities, userIndex)
            assertTrue(ids.size >= 6, "demo makes at least six file changes, got ${ids.size}")
            val result = controller.rewindTurn(ids)
            assertEquals(ids.size, result.undone, result.failed.joinToString())
            val after = java.nio.file.Files.list(root).use { files -> files.toList() }.filter { java.nio.file.Files.isRegularFile(it) }.associate { it.fileName.toString() to java.nio.file.Files.readString(it) }
            assertEquals(original, after, "the project is back to its state before the turn")
        } finally { controller.close() }
    }

    @Test fun `new dialog after the demo returns to the chosen project and close leaves it`() = runBlocking {
        val project = java.nio.file.Files.createDirectories(directory.resolve("work/app")).toRealPath()
        val controller = DesktopController(directory.resolve("data"), initialCredential = null)
        try {
            controller.initialize()
            controller.chooseWorkspace(project)
            controller.startDemo()
            val demo = controller.state.value.settings.workspace!!
            assertTrue("demo" in demo && demo != project.toString())
            kotlinx.coroutines.withTimeout(60_000) { while (controller.state.value.run.status !in setOf("completed", "failed")) {
                if (controller.state.value.run.question != null) controller.answer("Нет, не нужно")
                kotlinx.coroutines.delay(50)
            } }
            assertEquals("completed", controller.state.value.run.status)
            controller.newSession()
            assertEquals(project.toString(), controller.state.value.settings.workspace)
            assertFalse(demo in controller.state.value.settings.recentWorkspaces)
            controller.closeWorkspace()
            assertNull(controller.state.value.settings.workspace)
        } finally { controller.close() }
        val restarted = DesktopController(directory.resolve("data"), initialCredential = null)
        try { restarted.initialize(); assertNull(restarted.state.value.settings.workspace) } finally { restarted.close() }
    }

    @Test fun `keys stay with their endpoint and loopback servers need none`() = runBlocking {
        val controller = DesktopController(directory, initialCredential = "deepseek-key", namedKeys = mapOf("OPENAI_API_KEY" to "openai-key"))
        try {
            controller.initialize()
            assertTrue(controller.state.value.apiKeyConfigured)
            controller.configure("https://api.openai.com/v1", "some-model", false, null)
            assertTrue(controller.state.value.apiKeyConfigured, "OpenAI key from the environment applies to its own origin")
            controller.configure("https://api.groq.com/openai/v1", "m", false, null)
            assertFalse(controller.state.value.apiKeyConfigured, "no key is carried to another origin")
            controller.configure("http://localhost:11434/v1", "gemma", false, null)
            assertTrue(controller.state.value.apiKeyConfigured, "loopback works without a key")
            assertEquals("unverified", controller.state.value.connection.status)
            controller.configure("https://api.deepseek.com", "deepseek-flash", false, null)
            assertTrue(controller.state.value.apiKeyConfigured, "returning restores the endpoint's key")
            assertEquals(setOf("https://api.deepseek.com:443", "https://api.openai.com:443"), controller.keyedOrigins())
        } finally { controller.close() }
    }

    @Test fun `offline send acknowledges durable user message and deduplicates request id`() = runBlocking {
        val controller = DesktopController(directory, initialCredential = null)
        try {
            controller.initialize()
            val id = controller.state.value.sessionId
            val first = controller.send("Synthetic offline request", ExecutionMode.PLAN, "client-1")
            val duplicate = controller.send("Synthetic offline request", ExecutionMode.PLAN, "client-1")
            assertTrue(first.accepted)
            assertEquals(first, duplicate)
            val messages = JsonlEventStore(directory.resolve("sessions")).read(id).filter { it.type == "message" }
            assertEquals(2, messages.size)
            assertEquals("Synthetic offline request", messages.first().payload.getValue("content").toString().trim('"'))
        } finally { controller.close() }
    }

    @Test fun `configured send without workspace is rejected and leaves journal unchanged`() = runBlocking {
        val controller = DesktopController(directory, initialCredential = "synthetic-key")
        try {
            controller.initialize()
            val id = controller.state.value.sessionId
            val receipt = controller.send("Synthetic request", ExecutionMode.PLAN, "reject-1")
            assertFalse(receipt.accepted)
            assertEquals(id, receipt.sessionId)
            assertNotNull(receipt.reason)
            assertTrue(JsonlEventStore(directory.resolve("sessions")).read(id).isEmpty())
        } finally { controller.close() }
    }

    @Test fun `model selection remembers ids changes session and endpoint change clears credential`() = runBlocking {
        val controller = DesktopController(directory, initialCredential = "synthetic-key")
        try {
            controller.initialize()
            val originalSession = controller.state.value.sessionId
            val selectedSession = controller.selectModel("custom-model-b")
            assertTrue(selectedSession != originalSession)
            assertEquals(listOf("custom-model-b", "deepseek-flash"), controller.configuredModels())
            controller.configure("https://example.test/v1", "other-model", false, null)
            assertFalse(controller.state.value.apiKeyConfigured)
            assertEquals("missing", controller.state.value.connection.status)
        } finally { controller.close() }
    }

    @Test fun `preferences rename and explicit export target persist through controller`() = runBlocking {
        val controller = DesktopController(directory, initialCredential = null)
        try {
            controller.initialize()
            val firstSession = controller.state.value.sessionId
            controller.send("First session fixture", ExecutionMode.PLAN, "offline-first")
            assertTrue(controller.renameSession(firstSession, "  Research notes  "))
            assertEquals("Research notes", controller.state.value.sessions.first { it.id == firstSession }.title)
            assertFalse(controller.renameSession(firstSession, "  "))
            controller.newSession()
            controller.send("Second session fixture", ExecutionMode.PLAN, "offline-second")
            controller.prepareExport("markdown", firstSession)
            assertEquals(firstSession, controller.state.value.exportSessionId)
            assertTrue(controller.state.value.exportContent.orEmpty().contains("First session fixture"))
            val appearance = UiPreferences(theme = "dark", density = "compact", layout = "studio", inspectorWidth = 420f, reducedMotion = true)
            controller.updateUiPreferences(appearance)
            assertEquals(appearance, controller.state.value.uiPreferences)
        } finally { controller.close() }
    }

    @Test fun `connection check is explicit and profile edits invalidate its result`() = runBlocking {
        val gate = CompletableDeferred<ConnectionCheckResult>()
        val called = CompletableDeferred<Unit>()
        val checker = object : ConnectionChecker {
            override suspend fun check(baseUrl: String, model: String, key: String, thinking: Boolean): ConnectionCheckResult {
                called.complete(Unit)
                return gate.await()
            }
        }
        val controller = DesktopController(directory, initialCredential = "synthetic-key", connectionChecker = checker)
        try {
            controller.initialize()
            assertEquals("unverified", controller.state.value.connection.status)
            val job = controller.checkConnection()
            kotlinx.coroutines.withTimeout(5_000) { called.await() }
            assertEquals("checking", controller.state.value.connection.status)
            controller.selectModel("changed-model")
            assertEquals("unverified", controller.state.value.connection.status)
            gate.complete(ConnectionCheckResult(true, "verified"))
            job.join()
            assertEquals("unverified", controller.state.value.connection.status)
        } finally { controller.close() }
    }

    @Test fun `cancelled connection attempt cannot replace a later attempt for the same profile`() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val oldResult = CompletableDeferred<ConnectionCheckResult>()
        val newResult = CompletableDeferred<ConnectionCheckResult>()
        var count = 0
        val checker = object : ConnectionChecker {
            override suspend fun check(baseUrl: String, model: String, key: String, thinking: Boolean): ConnectionCheckResult {
                count++
                return if (count == 1) {
                    oldStarted.complete(Unit)
                    withContext(NonCancellable) { oldResult.await() }
                } else {
                    newStarted.complete(Unit)
                    newResult.await()
                }
            }
        }
        val controller = DesktopController(directory, initialCredential = "synthetic-key", connectionChecker = checker)
        try {
            controller.initialize()
            val oldJob = controller.checkConnection()
            oldStarted.await()
            controller.cancelConnectionCheck()
            assertEquals("unverified", controller.state.value.connection.status)
            val newJob = controller.checkConnection()
            newStarted.await()
            oldResult.complete(ConnectionCheckResult(false, "Authentication failed (HTTP 401)"))
            oldJob.join()
            assertEquals("checking", controller.state.value.connection.status)
            newResult.complete(ConnectionCheckResult(true, "Connection and model access verified"))
            newJob.join()
            assertEquals("verified", controller.state.value.connection.status)
            assertEquals("Подключение и доступ к модели подтверждены", controller.state.value.connection.message)
        } finally { controller.close() }
    }
}
