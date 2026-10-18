package dev.chiller.app.di

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ModelComparisonTest {
    @TempDir lateinit var temp: Path

    /**
     * Loopback chat endpoint: the first request answers at once, later ones wait for [release]. Releasing right
     * after cancel keeps the test independent of how fast the HTTP client aborts a pending read.
     */
    private val release = java.util.concurrent.CountDownLatch(1)

    private fun server(): HttpServer {
        val calls = AtomicInteger()
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.requestBody.readBytes()
                if (calls.getAndIncrement() > 0) release.await(60, java.util.concurrent.TimeUnit.SECONDS)
                val body = "data: {\"choices\":[{\"delta\":{\"content\":\"5\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
            start()
        }
    }

    @Test fun `stopping keeps finished attempts and quick restarts get their own record`() = runBlocking<Unit> {
        val server = server()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val comparison = ModelComparison(temp, scope)
            val endpoint = "http://127.0.0.1:${server.address.port}/v1"
            val sides = listOf(ComparisonSide("local-model", "compact"), ComparisonSide("other-model", "full"))
            comparison.start(endpoint, { null }, sides, setOf("count-todos", "fix-bug"), 1, JsonObject(emptyMap()))
            withTimeout(60_000) { while (comparison.state.value.results.values.sumOf { it.size } < 1) delay(20) }
            comparison.cancel()
            release.countDown()
            withTimeout(60_000) { while (comparison.state.value.running) delay(20) }
            val record = comparison.state.value.history.single()
            assertFalse(record.complete)
            assertEquals(1, record.reports.flatMap { it.results }.size, "the finished attempt of the stopped side is kept")

            comparison.start(endpoint, { null }, sides.take(1), setOf("count-todos"), 1, JsonObject(emptyMap()))
            comparison.cancel()
            withTimeout(60_000) { while (comparison.state.value.running) delay(20) }
            val ids = Files.list(temp.resolve("comparisons")).use { files -> files.toList() }.filter { it.toString().endsWith(".json") }
            assertEquals(2, ids.size, "a second start in the same second does not overwrite the first")
            assertNotEquals(ids[0].fileName, ids[1].fileName)
            assertTrue(comparison.state.value.history.any { it.reports.isNotEmpty() })
        } finally { scope.cancel(); server.stop(0) }
    }
}
