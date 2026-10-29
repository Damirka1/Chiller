package dev.chiller.app.di

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ConnectionCheckTest {
    // Helpers read the global language; these checks are written against the Russian texts.
    @kotlin.test.BeforeTest fun russian() { dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.RuStrings }
    @kotlin.test.AfterTest fun english() { dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.EnStrings }

    @Test
    fun sendsOneMinimalDeepSeekProbeAndReturnsSuccess() = runBlocking {
        val calls = AtomicInteger()
        val authorization = AtomicReference<String>()
        val payload = AtomicReference<String>()
        val fixture = fixture { exchange ->
            calls.incrementAndGet()
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            payload.set(exchange.requestBody.readBytes().toString(UTF_8))
            respond(exchange, 200, successBody)
        }
        KtorConnectionChecker(deepSeekHosts = setOf("127.0.0.1")).use { checker ->
            val result = checker.check(fixture.url, "deepseek-flash", "synthetic-key", thinking = true)
            assertTrue(result.success)
            assertEquals("Подключение и доступ к модели подтверждены", result.message)
        }
        val body = Json.parseToJsonElement(payload.get()).jsonObject
        assertEquals(1, calls.get())
        assertEquals("Bearer synthetic-key", authorization.get())
        assertEquals("deepseek-flash", body.getValue("model").jsonPrimitive.content)
        assertEquals("false", body.getValue("stream").jsonPrimitive.content)
        assertEquals("128", body.getValue("max_tokens").jsonPrimitive.content)
        assertEquals("Answer OK", body.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("disabled", body.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
        assertFalse("tools" in body)
        fixture.close()
    }

    @Test
    fun classifiesHttpFailuresWithoutReturningBodyOrSecret() = runBlocking {
        val responseCode = AtomicInteger(401)
        val fixture = fixture { exchange -> respond(exchange, responseCode.get(), "raw body synthetic-key") }
        KtorConnectionChecker().use { checker ->
            val expected = listOf(
                401 to "Провайдер отклонил API-ключ (HTTP 401)",
                403 to "Провайдер отклонил API-ключ (HTTP 403)",
                429 to "Провайдер ограничил частоту запросов (HTTP 429)",
                404 to "Модель или адрес провайдера не найдены (HTTP 404)",
                503 to "Ошибка сервера провайдера (HTTP 503)",
            )
            expected.forEach { (status, message) ->
                responseCode.set(status)
                val result = checker.check(fixture.url, "generic-model", "synthetic-key", thinking = false)
                assertFalse(result.success)
                assertEquals(message, result.message)
                assertFalse("raw body" in result.message || "synthetic-key" in result.message)
            }
        }
        fixture.close()
    }

    @Test
    fun genericProviderOmitsThinkingAndRedirectIsNotFollowed() = runBlocking {
        val calls = AtomicInteger()
        val payload = AtomicReference<String>()
        val fixture = fixture { exchange ->
            calls.incrementAndGet()
            payload.set(exchange.requestBody.readBytes().toString(UTF_8))
            exchange.responseHeaders.add("Location", "/elsewhere")
            respond(exchange, 302, "redirect")
        }
        KtorConnectionChecker().use { checker ->
            val result = checker.check(fixture.url, "deepseek-flash", "synthetic-key", thinking = true)
            assertFalse(result.success)
            assertEquals("Провайдер отклонил запрос (HTTP 302)", result.message)
        }
        assertEquals(1, calls.get())
        assertFalse("thinking" in Json.parseToJsonElement(payload.get()).jsonObject)
        fixture.close()
    }

    @Test
    fun rejectsUnsafeEndpointBeforeSendingAndPropagatesCancellation() = runBlocking {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val fixture = fixture { exchange ->
            calls.incrementAndGet()
            entered.complete(Unit)
            Thread.sleep(1_000)
            runCatching { respond(exchange, 200, "done") }
        }
        KtorConnectionChecker().use { checker ->
            listOf("http://user:pass@localhost", "http://localhost?x=1", "ftp://localhost").forEach { url ->
                assertFalse(checker.check(url, "model", "synthetic-key", false).success)
            }
            val request = async { checker.check(fixture.url, "model", "synthetic-key", false) }
            withTimeout(2_000) { entered.await() }
            request.cancel()
            try {
                request.await()
                fail("Cancellation should propagate")
            } catch (_: CancellationException) {
                // Expected: cancellation is not turned into an ordinary network failure.
            }
        }
        assertEquals(1, calls.get())
        fixture.close()
    }

    @Test
    fun finiteTimeoutReturnsSanitizedNetworkMessage() = runBlocking {
        val fixture = fixture { exchange ->
            Thread.sleep(500)
            runCatching { respond(exchange, 200, "late") }
        }
        KtorConnectionChecker(timeoutMillis = 100).use { checker ->
            val result = withTimeout(2_000) { checker.check(fixture.url, "model", "synthetic-key", false) }
            assertFalse(result.success)
            assertEquals("Сетевая ошибка или истекло время ожидания", result.message)
        }
        fixture.close()
    }

    @Test
    fun successfulStatusRequiresAChatCompletionMessageAndBoundsTheBody() = runBlocking {
        val response = AtomicReference("{}")
        val fixture = fixture { exchange -> respond(exchange, 200, response.get()) }
        KtorConnectionChecker().use { checker ->
            val malformed = checker.check(fixture.url, "generic-model", "synthetic-key", false)
            assertFalse(malformed.success)
            assertEquals("Ответ провайдера не соответствует формату Chat Completions", malformed.message)

            response.set("{".padEnd(70 * 1024, ' '))
            val oversized = checker.check(fixture.url, "generic-model", "synthetic-key", false)
            assertFalse(oversized.success)
            assertEquals("Ответ провайдера пустой, повреждён или превышает 64 КиБ", oversized.message)

            response.set(successBody)
            assertTrue(checker.check(fixture.url, "deepseek-flash", "synthetic-key", false).success)
        }
        fixture.close()
    }

    private data class Fixture(val url: String, val server: HttpServer) : AutoCloseable {
        override fun close() = server.stop(0)
    }

    private fun fixture(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): Fixture {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions", handler)
        server.start()
        return Fixture("http://127.0.0.1:${server.address.port}", server)
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray(UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private companion object {
        const val successBody = """{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""
    }
}
