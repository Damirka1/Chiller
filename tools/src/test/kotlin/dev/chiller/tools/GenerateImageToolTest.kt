package dev.chiller.tools

import dev.chiller.contracts.*
import dev.chiller.runtime.LocalProcessService
import dev.chiller.workspace.LocalWorkspace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class GenerateImageToolTest {
    @Test fun withoutAnImageModelTheToolIsNotOffered() = fixture(images = null) { tools, _, _ ->
        assertTrue(tools.specs.none { it.name == "generate_image" })
    }

    @Test fun imageIsSavedAsANewUndoableFileAndPresented() = fixture { tools, root, service ->
        val result = tools.execute(call("""{"prompt":"a red cube","path":"art/cube.png"}"""), EDIT)
        assertTrue(result.ok, result.error?.message)
        assertTrue(PNG.contentEquals(Files.readAllBytes(root.resolve("art/cube.png"))))
        val data = result.data!!.jsonObject
        assertTrue(data.getValue("id").jsonPrimitive.content.isNotBlank(), "a change id makes the image undoable")
        assertEquals("art/cube.png", data.getValue("file").jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals(1024, data.getValue("width").jsonPrimitive.int)
        assertEquals("1024x1024", service.calls.single().size, "default size")
        assertEquals(false, data.getValue("edited").jsonPrimitive.boolean)
    }

    @Test fun localChecksRunBeforeTheSlowRequest() = fixture { tools, root, service ->
        Files.writeString(root.resolve("taken.png"), "x")
        Files.writeString(root.resolve("secret.env"), "API_KEY=1")
        assertEquals("ALREADY_EXISTS", tools.execute(call("""{"prompt":"x","path":"taken.png"}"""), EDIT).error?.code)
        assertEquals("INVALID_ARGUMENTS", tools.execute(call("""{"prompt":"x","path":"notes.txt"}"""), EDIT).error?.code)
        assertEquals("INVALID_ARGUMENTS", tools.execute(call("""{"prompt":"x","path":"a.png","size":"huge"}"""), EDIT).error?.code)
        assertEquals("NOT_AN_IMAGE", tools.execute(call("""{"prompt":"x","path":"a.png","reference_image":"secret.env"}"""), EDIT).error?.code)
        assertEquals("PERMISSION_DENIED", tools.execute(call("""{"prompt":"x","path":"a.png"}"""), ToolContext("s", "r", ExecutionMode.PLAN)).error?.code)
        assertTrue(service.calls.isEmpty(), "nothing reached the image model")
    }

    @Test fun referenceFromTheProjectOrAnAttachmentIsEdited() = fixture { tools, root, service ->
        Files.write(root.resolve("logo.png"), PNG)
        assertTrue(tools.execute(call("""{"prompt":"make it blue","path":"logo-blue.png","reference_image":"logo.png"}"""), EDIT).ok)
        assertTrue(tools.execute(call("""{"prompt":"crop it","path":"photo-crop.png","reference_image":"$ATTACHMENT"}"""), EDIT).ok)
        assertEquals(listOf("image/png", "image/jpeg"), service.calls.map { it.reference?.mimeType })
    }

    @Test fun extensionFollowsTheReturnedTypeAndTurnsAreCapped() = fixture { tools, root, service ->
        val renamed = tools.execute(call("""{"prompt":"x","path":"photo.jpg"}"""), EDIT)
        assertTrue(renamed.ok, renamed.error?.message)
        assertTrue(Files.exists(root.resolve("photo.png")) && !Files.exists(root.resolve("photo.jpg")))
        assertTrue("image/png" in renamed.data!!.jsonObject.getValue("note").jsonPrimitive.content)
        repeat(3) { assertTrue(tools.execute(call("""{"prompt":"x","path":"n$it.png"}"""), EDIT).ok) }
        assertEquals("LIMIT_REACHED", tools.execute(call("""{"prompt":"x","path":"n9.png"}"""), EDIT).error?.code)
        assertTrue(tools.execute(call("""{"prompt":"x","path":"next.png"}"""), ToolContext("s", "r2", ExecutionMode.WORKSPACE_EDIT)).ok, "a new turn has its own quota")
        assertEquals(5, service.calls.size)
    }

    private class Call(val prompt: String, val size: String, val reference: ImageData?)
    private class FakeImages : ImageService {
        val calls = mutableListOf<Call>()
        override val model = "flux-2-klein"
        override val supportsEditing = true
        override suspend fun generate(prompt: String, size: String, reference: ImageData?): ImageData {
            calls += Call(prompt, size, reference); return ImageData(PNG, "image/png")
        }
    }

    private fun fixture(images: FakeImages? = FakeImages(), block: suspend (BuiltinTools, Path, FakeImages) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("chiller-images")
        val artifacts = object : ArtifactStore {
            override suspend fun put(sessionId: String, content: String) = ArtifactRef("unused", sessionId, content.length.toLong())
            override suspend fun read(sessionId: String, artifactId: String, offset: Int, limit: Int) = ""
        }
        val attachments = AttachmentImages { _, id -> if (id == ATTACHMENT) ImageData(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0), "image/jpeg") else null }
        val tools = BuiltinTools(LocalWorkspace(root), LocalProcessService(root), artifacts, object : InteractionBroker { override suspend fun ask(question: UserQuestion) = "" },
            images = images, attachmentImages = attachments)
        try { block(tools, root, images ?: FakeImages()) } finally { root.toFile().deleteRecursively() }
    }

    private fun call(arguments: String) = ToolCall("c", "generate_image", arguments)

    private companion object {
        val EDIT = ToolContext("s", "r", ExecutionMode.WORKSPACE_EDIT)
        const val ATTACHMENT = "0b6f1c2e-6a1d-4f0e-9a51-3f2c8d7e4b10"
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52, 0, 0, 0x04, 0, 0, 0, 0x04, 0x00)
    }
}
