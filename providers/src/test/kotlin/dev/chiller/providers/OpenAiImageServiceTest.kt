package dev.chiller.providers

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.chiller.contracts.ImageData
import dev.chiller.contracts.ToolException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAiImageServiceTest {
    @Test fun generationAsksForBase64AndReturnsTheImage() = runBlocking {
        Fixture { exchange, _ -> json(exchange, 200, imageBody(PNG)) }.use { fixture ->
            val image = fixture.service().generate("a red cube", "1024x1024")
            assertEquals("image/png", image.mimeType)
            assertTrue(PNG.contentEquals(image.bytes))
            assertEquals(1024 to 768, pngSize(image.bytes))
            val request = fixture.requests.single()
            assertEquals("/v1/images/generations", request.path)
            assertEquals("Bearer test-key", request.authorization)
            val body = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
            assertEquals("flux-2-klein", body.getValue("model").jsonPrimitive.content)
            assertEquals("1024x1024", body.getValue("size").jsonPrimitive.content)
            assertEquals("b64_json", body.getValue("response_format").jsonPrimitive.content)
        }
    }

    @Test fun editSendsTheReferenceAsMultipart() = runBlocking {
        Fixture { exchange, _ -> json(exchange, 200, imageBody(PNG)) }.use { fixture ->
            fixture.service().generate("make it blue", "1024x1024", ImageData(PNG, "image/png"))
            val request = fixture.requests.single()
            assertEquals("/v1/images/edits", request.path)
            assertTrue(request.contentType.startsWith("multipart/form-data"), request.contentType)
            val text = request.body.toString(Charsets.ISO_8859_1)
            // Ktor writes token-safe parameter values unquoted (name=prompt); both forms are valid (RFC 2183).
            assertTrue(Regex("""name="?prompt"?\r\n""").containsMatchIn(text) && "make it blue" in text, text.take(400))
            assertTrue(Regex("""name="?image"?; filename="?reference\.png"?""").containsMatchIn(text), text.take(600))
            assertTrue(String(PNG, Charsets.ISO_8859_1) in text, "the reference bytes travel unchanged")
        }
    }

    @Test fun editingIsRefusedWhenTheModelCannotEdit() = runBlocking {
        Fixture { exchange, _ -> json(exchange, 200, imageBody(PNG)) }.use { fixture ->
            val error = assertFailsWith<ToolException> { fixture.service(editing = false).generate("x", "1024x1024", ImageData(PNG, "image/png")) }
            assertEquals("UNSUPPORTED", error.code)
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun quotaIsWaitedOutThenTheImageArrives() = runBlocking {
        Fixture { exchange, attempt ->
            if (attempt == 0) { exchange.responseHeaders.add("Retry-After", "7"); json(exchange, 429, """{"error":{"message":"Request rate limit exceeded"}}""") }
            else json(exchange, 200, imageBody(PNG))
        }.use { fixture ->
            val waits = mutableListOf<Long>()
            fixture.service(sleep = { waits += it }).generate("x", "1024x1024")
            assertEquals(listOf(7_000L), waits)
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test fun providerRejectionCarriesItsTextAndIsNotRetried() = runBlocking {
        Fixture { exchange, _ -> json(exchange, 400, """{"error":{"message":"size must be one of 512x512, 1024x1024","type":"invalid_request_error"}}""") }.use { fixture ->
            val error = assertFailsWith<ToolException> { fixture.service().generate("x", "4096x4096") }
            assertEquals("IMAGE_REQUEST_REJECTED", error.code)
            assertTrue("size must be one of" in error.message, error.message)
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test fun urlAnswersAndNonImagesAreRefused() = runBlocking {
        Fixture { exchange, attempt ->
            if (attempt == 0) json(exchange, 200, """{"data":[{"url":"https://elsewhere.example/x.png"}]}""")
            else json(exchange, 200, imageBody("not an image".toByteArray()))
        }.use { fixture ->
            assertEquals("UNSUPPORTED_RESPONSE", assertFailsWith<ToolException> { fixture.service().generate("x", "1024x1024") }.code)
            assertEquals("MALFORMED_RESPONSE", assertFailsWith<ToolException> { fixture.service().generate("x", "1024x1024") }.code)
        }
    }

    @Test fun serverErrorsAreRetriedABoundedNumberOfTimes() = runBlocking {
        Fixture { exchange, _ -> json(exchange, 503, """{"error":{"message":"busy"}}""") }.use { fixture ->
            val error = assertFailsWith<ToolException> { fixture.service().generate("x", "1024x1024") }
            assertEquals("PROVIDER_HTTP_ERROR", error.code)
            assertEquals(3, fixture.requests.size)
        }
    }

    private class Seen(val path: String, val authorization: String?, val contentType: String, val body: ByteArray)

    private class Fixture(private val handler: (HttpExchange, Int) -> Unit) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Seen>()
        private val count = AtomicInteger(0)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/images") { exchange ->
                requests += Seen(exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestHeaders.getFirst("Content-Type").orEmpty(), exchange.requestBody.readBytes())
                handler(exchange, count.getAndIncrement())
            }
            start()
        }
        private val client = HttpClient(CIO) { install(HttpTimeout) }

        fun service(editing: Boolean = true, sleep: suspend (Long) -> Unit = {}) =
            OpenAiImageService(client, "http://127.0.0.1:${server.address.port}/v1", "flux-2-klein", editing, sleep) { "test-key" }

        override fun close() { client.close(); server.stop(0) }
    }

    private companion object {
        /** A PNG signature and IHDR for 1024×768; enough for type and size checks. */
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52, 0, 0, 0x04, 0, 0, 0, 0x03, 0x00)

        fun imageBody(bytes: ByteArray) = """{"created":1,"data":[{"b64_json":"${Base64.getEncoder().encodeToString(bytes)}"}]}"""

        fun json(exchange: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.write(bytes); exchange.close()
        }
    }
}
