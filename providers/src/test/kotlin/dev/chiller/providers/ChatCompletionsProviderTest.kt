package dev.chiller.providers

import com.sun.net.httpserver.HttpServer
import dev.chiller.contracts.AgentMessage
import dev.chiller.contracts.ModelEvent
import dev.chiller.contracts.ModelRequest
import dev.chiller.contracts.MessageOrigin
import dev.chiller.contracts.MessageOriginKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatCompletionsProviderTest {
    @Test fun fragmentedSseAssemblesToolArgumentsOnlyAfterDone() = runBlocking {
        val server = testServer { exchange ->
            val body = """
                : ping
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read","arguments":"{\"pa"}}]},"finish_reason":null}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"x\"}"}}]},"finish_reason":null}]}

                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

            """.trimIndent().replace("\n", "\r\n") + "\r\n"
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            body.toByteArray().forEach { byte -> exchange.responseBody.write(byte.toInt()); exchange.responseBody.flush() }
            exchange.close()
        }
        val events = invoke(server, listOf(AgentMessage("user", "read")))
        val completed = assertIs<ModelEvent.Completed>(events.last(), "Stream events: $events")
        assertEquals("tool_calls", completed.finishReason)
        assertEquals("{\"path\":\"x\"}", completed.message.toolCalls.single().arguments)
    }

    @Test fun missingFinishAndDoneFailsInsteadOfCompleting() = runBlocking {
        val server = testServer { exchange ->
            sendSse(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n")
        }
        val events = invoke(server)
        assertIs<ModelEvent.Failed>(events.last())
        assertEquals("INCOMPLETE_STREAM", (events.last() as ModelEvent.Failed).code)
    }

    @Test fun sanitizesAuthenticationAndRateLimitResponses() = runBlocking {
        for ((status, code) in listOf(401 to "AUTHENTICATION_FAILED", 429 to "RATE_LIMITED")) {
            val server = testServer(status) { exchange -> exchange.close() }
            val event = invoke(server).last() as ModelEvent.Failed
            assertEquals(code, event.code)
            assertTrue("secret response body" !in event.message)
        }
    }

    @Test fun parseableToolArgumentsWithLengthFinishAreRejected() = runBlocking {
        val server = testServer { exchange ->
            sendSse(exchange, "data: ${toolChunk(0, "call-1", "read", "{\\\"path\\\":\\\"x\\\"}", "length")}\n\ndata: [DONE]\n\n")
        }
        val events = invoke(server)
        val failure = assertIs<ModelEvent.Failed>(events.last(), "Stream events: $events")
        assertEquals("INCOMPLETE_TOOL_CALL", failure.code)
    }

    @Test fun malformedToolArgumentsNeverProduceCompletedCall() = runBlocking {
        val server = testServer { exchange ->
            sendSse(exchange, "data: ${toolChunk(0, "call-1", "read", "{broken", "tool_calls")}\n\ndata: [DONE]\n\n")
        }
        val events = invoke(server)
        val failure = assertIs<ModelEvent.Failed>(events.last(), "Stream events: $events")
        assertEquals("MALFORMED_TOOL_CALL", failure.code)
    }

    @Test fun interleavedToolIndicesKeepTheirOwnFragmentsAndStableOrder() = runBlocking {
        val server = testServer { exchange ->
            val body = listOf(
                toolChunk(1, "call-b", "tool_b", "{\"b\":"),
                toolChunk(0, "call-a", "tool_a", "{\"a\":"),
                toolChunk(1, null, null, "2}"),
                toolChunk(0, null, null, "1}"),
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}"
            ).joinToString("\n\ndata: ", prefix = "data: ", postfix = "\n\ndata: [DONE]\n\n")
            sendSse(exchange, body)
        }
        val events = invoke(server)
        val completed = assertIs<ModelEvent.Completed>(events.last(), "Stream events: $events")
        assertEquals(listOf("call-a", "call-b"), completed.message.toolCalls.map { it.id })
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), completed.message.toolCalls.map { it.arguments })
    }

    @Test fun oversizedSseLineFailsBeforeParsing() = runBlocking {
        val server = testServer { exchange ->
            val body = "data: " + "x".repeat(1_048_577) + "\n\n"
            sendSse(exchange, body)
        }
        val events = invoke(server)
        val failure = assertIs<ModelEvent.Failed>(events.last(), "Stream events: $events")
        assertEquals("RESPONSE_TOO_LARGE", failure.code)
    }

    @Test fun deepSeekThinkingReplayAndSuccessiveRoundsArePreserved() = runBlocking {
        val seen = mutableListOf<String>()
        val server = testServer { exchange ->
            seen += exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val text = if (seen.size == 1) "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"r1\",\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                else "data: {\"choices\":[{\"delta\":{\"content\":\"next\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            sendSse(exchange, text)
        }
        val first = invoke(server, listOf(AgentMessage("user", "hello")), thinking = true, stopServer = false)
        val firstMessage = (first.last() as ModelEvent.Completed).message
        assertEquals("r1", firstMessage.replay["reasoning_content"]?.jsonPrimitive?.content)
        val second = invoke(server, listOf(AgentMessage("assistant", "answer", replay = firstMessage.replay,
            origin = MessageOrigin(MessageOriginKind.MODEL, "private-local-model-id"))), thinking = true, stopServer = false)
        assertIs<ModelEvent.Completed>(second.last())
        server.stop(0)
        val secondRequest = Json.parseToJsonElement(seen.last()).jsonObject
        val sentMessage = secondRequest["messages"]!!.toString()
        assertTrue("reasoning_content" in sentMessage)
        assertTrue("r1" in sentMessage)
        assertTrue("origin" !in sentMessage)
        assertTrue("private-local-model-id" !in sentMessage)
    }

    private fun toolChunk(index: Int, id: String?, name: String?, arguments: String, finishReason: String? = null): String {
        val tool = buildJsonObject {
            put("index", index)
            id?.let { put("id", it) }
            put("type", "function")
            put("function", buildJsonObject {
                name?.let { put("name", it) }
                put("arguments", arguments)
            })
        }
        val delta = buildJsonObject { put("tool_calls", kotlinx.serialization.json.buildJsonArray { add(tool) }) }
        val choice = buildJsonObject {
            put("delta", delta)
            put("finish_reason", finishReason?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        }
        return buildJsonObject { put("choices", kotlinx.serialization.json.buildJsonArray { add(choice) }) }.toString()
    }

    private suspend fun invoke(server: HttpServer, messages: List<AgentMessage> = listOf(AgentMessage("user", "x")), thinking: Boolean = false, stopServer: Boolean = true): List<ModelEvent> {
        val client = HttpClient(CIO)
        try {
            val provider = ChatCompletionsProvider(client, ChatCompletionsProfile("test", "http://127.0.0.1:${server.address.port}", "test", reasoningField = "reasoning_content"), { "test-secret" })
            return provider.stream(ModelRequest("test", messages, thinking = thinking)).toList()
        } finally { client.close(); if (stopServer) server.stop(0) }
    }

    private fun testServer(status: Int = 200, handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            if (status != 200) { exchange.sendResponseHeaders(status, 0); handler(exchange) }
            else handler(exchange)
        }
        server.start()
        return server
    }

    private fun sendSse(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
        exchange.responseBody.write(body.toByteArray())
        exchange.close()
    }
}
