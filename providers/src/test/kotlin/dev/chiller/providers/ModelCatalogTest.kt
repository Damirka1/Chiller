package dev.chiller.providers

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.chiller.contracts.ModelCatalogResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCatalogTest {
    @Test fun successPreservesOrderDeduplicatesAndIgnoresUnknownFields() = runBlocking {
        Fixture { exchange, _ ->
            sendJson(exchange, """
                {"object":"list","data":[
                  {"id":" zeta ","name":"Zeta","context_window":8192,"max_output_tokens":1024,"unknown":{"a":1}},
                  {"id":"alpha"},
                  {"id":"zeta","name":"Duplicate"},
                  {"id":"beta","name":"  Beta  ","owned_by":"test"}
                ]}
            """.trimIndent())
        }.use { fixture ->
            val result = fixture.discover()
            assertTrue(result.ok, "Discovery failed: ${result.errorCode} ${result.message}")
            assertEquals(listOf("zeta", "alpha", "beta"), result.models.map { it.id })
            val zeta = result.models.first()
            assertEquals("Zeta", zeta.displayName)
            assertEquals(8192, zeta.contextWindow)
            assertEquals(1024, zeta.maxOutputTokens)
            assertNull(result.models[1].displayName)
            assertNull(result.models[1].contextWindow)
            assertEquals("Beta", result.models[2].displayName)
        }
    }

    @Test fun gatewayAndVllmMetadataGiveWindowVisionAndReasoning() = runBlocking {
        Fixture { exchange, _ ->
            sendJson(exchange, """
                {"object":"list","data":[
                  {"id":"quasar","name":"Gemma 4 26B A4B","capabilities":["chat_completions","vision","tools","reasoning"],
                   "input_modalities":["text","image"],"output_modalities":["text"],"context_length":131072,"max_model_len":131072},
                  {"id":"gemma-4-26b-a4b-it","object":"model","owned_by":"vllm","max_model_len":32768},
                  {"id":"coder","capabilities":["chat_completions","vision"],"input_modalities":["text"]},
                  {"id":"seer","capabilities":["Vision"]}
                ]}
            """.trimIndent())
        }.use { fixture ->
            val (quasar, vllm, coder, seer) = fixture.discover().models
            assertEquals(131072, quasar.contextWindow)
            assertEquals("Gemma 4 26B A4B", quasar.displayName)
            assertEquals(true, quasar.vision)
            assertEquals(true, quasar.reasoning)
            assertNull(quasar.maxOutputTokens, "an absent output limit stays unknown")
            assertEquals(32768, vllm.contextWindow)
            assertNull(vllm.vision); assertNull(vllm.reasoning)
            assertEquals(false, coder.vision, "input modalities outrank capabilities")
            assertEquals(false, coder.reasoning)
            assertEquals(true, seer.vision)
        }
    }

    @Test fun baseWithoutVersionRequestsModelsAndVersionedBaseRequestsV1Models() = runBlocking {
        Fixture { exchange, _ -> sendJson(exchange, """{"data":[]}""") }.use { fixture ->
            fixture.discover()
            fixture.discover(fixture.baseUrl + "/v1")
            fixture.discover(fixture.baseUrl + "/v1/")
            assertEquals(listOf("/models", "/v1/models", "/v1/models"), fixture.log.paths)
        }
    }

    @Test fun authorizationHeaderIsSentOnlyForNonBlankKeys() = runBlocking {
        Fixture { exchange, _ -> sendJson(exchange, """{"data":[]}""") }.use { fixture ->
            fixture.discover(key = "test-secret")
            fixture.discover(key = null)
            fixture.discover(key = "   ")
            assertEquals(listOf("Bearer test-secret", null, null), fixture.log.authorizations)
        }
    }

    @Test fun unauthorizedIsAuthenticationFailureWithoutRetry() = runBlocking {
        Fixture { exchange, _ -> sendJson(exchange, "secret response body", status = 401) }.use { fixture ->
            val result = fixture.discover()
            assertEquals("AUTHENTICATION_FAILED", result.errorCode)
            assertFalse(result.ok)
            assertEquals(1, fixture.log.count.get())
            val message = result.message.orEmpty()
            assertTrue("secret response body" !in message)
            assertTrue("test-secret" !in message)
        }
    }

    @Test fun missingModelsEndpointIsUnsupportedWithoutThrowing() = runBlocking {
        Fixture { exchange, _ -> sendJson(exchange, """{"error":"not found"}""", status = 404) }.use { fixture ->
            val result = fixture.discover()
            assertEquals("UNSUPPORTED", result.errorCode)
            assertEquals("This endpoint does not list models", result.message)
        }
    }

    @Test fun malformedBodiesAreRejected() = runBlocking {
        for (body in listOf("not json", "[]", "{}", """{"data":{}}""")) {
            Fixture { exchange, _ -> sendJson(exchange, body) }.use { fixture ->
                assertEquals("MALFORMED_RESPONSE", fixture.discover().errorCode, "Body: $body")
            }
        }
    }

    @Test fun oversizedBodyIsRejectedBeforeParsing() = runBlocking {
        val body = """{"data":[""" + "0".repeat(1_048_576) + "]}"
        Fixture { exchange, _ -> sendJson(exchange, body) }.use { fixture ->
            val result = fixture.discover()
            assertEquals("RESPONSE_TOO_LARGE", result.errorCode)
        }
    }

    @Test fun emptyDataArrayIsSuccessfulWithNoModels() = runBlocking {
        Fixture { exchange, _ -> sendJson(exchange, """{"data":[]}""") }.use { fixture ->
            val result = fixture.discover()
            assertTrue(result.ok, "Discovery failed: ${result.errorCode} ${result.message}")
            assertTrue(result.models.isEmpty())
        }
    }

    @Test fun optionalMetadataIsMappedAndInvalidValuesIgnored() = runBlocking {
        Fixture { exchange, _ ->
            sendJson(exchange, """
                {"data":[
                  {"id":"a","context_window":16384,"max_output_tokens":2048},
                  {"id":"b","context_length":32768},
                  {"id":"c","context_window":0,"context_length":-1,"max_output_tokens":"1024"},
                  {"id":"d","context_window":"nope","max_output_tokens":12.5},
                  {"id":"e","name":"   "}
                ]}
            """.trimIndent())
        }.use { fixture ->
            val result = fixture.discover()
            assertTrue(result.ok, "Discovery failed: ${result.errorCode} ${result.message}")
            val byId = result.models.associateBy { it.id }
            assertEquals(16384, byId.getValue("a").contextWindow)
            assertEquals(2048, byId.getValue("a").maxOutputTokens)
            assertEquals(32768, byId.getValue("b").contextWindow)
            assertNull(byId.getValue("b").maxOutputTokens)
            assertNull(byId.getValue("c").contextWindow)
            assertNull(byId.getValue("c").maxOutputTokens)
            assertNull(byId.getValue("d").contextWindow)
            assertNull(byId.getValue("d").maxOutputTokens)
            assertNull(byId.getValue("e").displayName)
        }
    }

    @Test fun redirectIsReportedWithoutFollowingIt() = runBlocking {
        Fixture { exchange, _ ->
            exchange.responseHeaders.add(HttpHeaders.Location, "/models")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }.use { fixture ->
            val result = fixture.discover()
            assertEquals("PROVIDER_HTTP_ERROR", result.errorCode)
            assertTrue("302" in result.message.orEmpty())
            assertEquals(1, fixture.log.count.get())
        }
    }

    @Test fun providerErrorsAreSanitized() = runBlocking {
        for ((status, code) in listOf(429 to "RATE_LIMITED", 500 to "PROVIDER_HTTP_ERROR")) {
            Fixture { exchange, _ -> sendJson(exchange, "secret response body", status = status) }.use { fixture ->
                val result = fixture.discover()
                assertEquals(code, result.errorCode)
                assertTrue(status.toString() in result.message.orEmpty())
                assertTrue("secret response body" !in result.message.orEmpty())
                assertEquals(1, fixture.log.count.get())
            }
        }
    }

    @Test fun invalidBaseUrlsAreRejectedBeforeAnyRequest() = runBlocking {
        val invalid = listOf(
            "",
            "   ",
            "not a url",
            "ftp://example.invalid",
            "example.invalid",
            "http://example.invalid",
            "http://user:pass@localhost",
            "http://localhost?x=1",
            "http://localhost#frag",
        )
        Fixture { exchange, _ -> sendJson(exchange, """{"data":[]}""") }.use { fixture ->
            for (base in invalid) {
                assertEquals("INVALID_BASE_URL", fixture.discover(base).errorCode, "Base: $base")
            }
            assertEquals(0, fixture.log.count.get())
        }
    }

    private class Log {
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val authorizations: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        val count = AtomicInteger(0)
    }

    private class Fixture(handler: (HttpExchange, Log) -> Unit) : AutoCloseable {
        val log = Log()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                log.paths += exchange.requestURI.path
                log.authorizations += exchange.requestHeaders.getFirst(HttpHeaders.Authorization)
                log.count.incrementAndGet()
                handler(exchange, log)
            }
            start()
        }
        private val client = HttpClient(CIO) { followRedirects = false }
        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        suspend fun discover(base: String = baseUrl, key: String? = "test-secret"): ModelCatalogResult =
            KtorModelCatalogClient(client).discover(base, key)

        override fun close() {
            client.close()
            server.stop(0)
        }
    }

    private companion object {
        fun sendJson(exchange: HttpExchange, body: String, status: Int = 200) {
            val bytes = body.toByteArray()
            exchange.responseHeaders.add(HttpHeaders.ContentType, "application/json")
            if (bytes.isEmpty()) exchange.sendResponseHeaders(status, -1)
            else exchange.sendResponseHeaders(status, bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
            exchange.close()
        }
    }
}
