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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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

    @Test fun outputLimitsAreReadFromProviderErrors() {
        assertEquals(393216, parseOutputLimit("""{"error":{"message":"Invalid max_tokens value, the valid range of max_tokens is [1, 393216] (request_id: x)"}}"""))
        assertEquals(16384, parseOutputLimit("""{"error":{"message":"max_tokens is too large: 1000000. This model supports at most 16384 completion tokens, whereas you provided 1000000."}}"""))
        assertEquals(32768 - 1200 - 64, parseRequestOutputLimit("""{"message":"This model's maximum context length is 32768 tokens. However, you requested 1001200 tokens (1200 in the messages, 1000000 in the completion)."}"""))
        assertEquals(null, parseOutputLimit("""{"error":{"message":"Unknown parameter: foo"}}"""))
    }

    @Test fun requestCapacityDoesNotPoisonModelLimit() = runBlocking {
        OutputLimits.clear()
        val asked = mutableListOf<Int>()
        val server = testServer { exchange ->
            asked += Json.parseToJsonElement(exchange.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject.getValue("max_tokens").jsonPrimitive.content.toInt()
            if (asked.size == 1) {
                val error = """{"error":{"message":"This model's maximum context length is 32768 tokens. However, you requested 35000 tokens (30000 in the messages, 5000 in the completion)."}}""".toByteArray()
                exchange.sendResponseHeaders(400, error.size.toLong()); exchange.responseBody.write(error); exchange.close()
            } else sendSse(exchange, sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"))
        }
        val client = HttpClient(CIO)
        try {
            val profile = ChatCompletionsProfile("local", "http://127.0.0.1:${server.address.port}", "model", requiresApiKey = false)
            val provider = ChatCompletionsProvider(client, profile, emptyList()) { null }
            repeat(2) { assertIs<ModelEvent.Completed>(provider.stream(ModelRequest("model", listOf(AgentMessage("user", "hello")), maxOutputTokens = 5000)).toList().last()) }
            assertEquals(listOf(5000, 2704, 5000), asked)
            assertNull(OutputLimits.known(profile.baseUrl, "model"))
        } finally { client.close(); server.stop(0); OutputLimits.clear() }
    }

    @Test fun tooLargeMaxTokensIsClampedToTheProviderLimitAndRemembered() = runBlocking {
        OutputLimits.clear()
        val bodies = mutableListOf<String>()
        val server = testServer { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            bodies += body
            val asked = Json.parseToJsonElement(body).jsonObject.getValue("max_tokens").jsonPrimitive.content.toInt()
            if (asked > 8192) {
                val error = """{"error":{"message":"Invalid max_tokens value, the valid range of max_tokens is [1, 8192]"}}""".toByteArray()
                exchange.sendResponseHeaders(400, error.size.toLong()); exchange.responseBody.write(error); exchange.close()
            } else sendSse(exchange, sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"))
        }
        val client = HttpClient(CIO)
        try {
            val profile = ChatCompletionsProfile("local", "http://127.0.0.1:${server.address.port}", "big-model", requiresApiKey = false)
            val provider = ChatCompletionsProvider(client, profile, emptyList()) { null }
            val first = provider.stream(ModelRequest("big-model", listOf(AgentMessage("user", "hi")), maxOutputTokens = 1_000_000)).toList()
            assertIs<ModelEvent.Completed>(first.last())
            assertEquals(listOf("1000000", "8192"), bodies.map { Json.parseToJsonElement(it).jsonObject.getValue("max_tokens").jsonPrimitive.content })
            assertEquals(8192, OutputLimits.known(profile.baseUrl, "big-model"))
            provider.stream(ModelRequest("big-model", listOf(AgentMessage("user", "again")), maxOutputTokens = 1_000_000)).toList()
            assertEquals("8192", Json.parseToJsonElement(bodies.last()).jsonObject.getValue("max_tokens").jsonPrimitive.content, "the learned limit is used straight away")
            assertEquals(3, bodies.size)
        } finally { client.close(); server.stop(0); OutputLimits.clear() }
    }

    @Test fun otherBadRequestsShowTheProviderMessage() = runBlocking {
        val server = testServer { exchange ->
            exchange.requestBody.readBytes()
            val error = """{"error":{"message":"Unsupported parameter: 'foo'"}}""".toByteArray()
            exchange.sendResponseHeaders(400, error.size.toLong()); exchange.responseBody.write(error); exchange.close()
        }
        val client = HttpClient(CIO)
        try {
            val profile = ChatCompletionsProfile("local", "http://127.0.0.1:${server.address.port}", "m", requiresApiKey = false)
            val failed = ChatCompletionsProvider(client, profile, emptyList()) { null }.stream(ModelRequest("m", listOf(AgentMessage("user", "hi")))).toList().last()
            assertIs<ModelEvent.Failed>(failed)
            assertEquals("Provider request failed (HTTP 400): Unsupported parameter: 'foo'", failed.message)
        } finally { client.close(); server.stop(0) }
    }

    @Test fun visionSendsRecentImagesAsDataUrlsAndOnlyOnce() = runBlocking {
        var body = ""
        val server = testServer { exchange ->
            body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            sendSse(exchange, sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"))
        }
        val client = HttpClient(CIO)
        try {
            val loaded = mutableListOf<String>()
            val profile = ChatCompletionsProfile("local", "http://127.0.0.1:${server.address.port}", "gemma-4", requiresApiKey = false,
                imageInput = ImageInput { id -> loaded += id; ModelImage("image/jpeg", "QUJD") })
            val img = { id: String -> dev.chiller.contracts.ComposerAttachment(id, "$id.png", "image/png", 10) }
            val history = listOf(
                AgentMessage("user", "old", attachments = listOf(img("old"))), AgentMessage("assistant", "seen"),
                AgentMessage("user", "middle"), AgentMessage("assistant", "ok"),
                AgentMessage("user", "look at this", attachments = listOf(img("new"))),
            )
            ChatCompletionsProvider(client, profile, emptyList()) { null }.stream(ModelRequest("gemma-4", history)).toList()
            val messages = Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
            val last = messages.last().jsonObject.getValue("content").jsonArray
            assertEquals("text", last[0].jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals("data:image/jpeg;base64,QUJD", last[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
            assertTrue("not attached again" in messages.first().jsonObject.getValue("content").jsonPrimitive.content, "old images stay text references")
            assertEquals(listOf("new"), loaded)
            assertTrue(VisionModels.likely("gemma-4-26b-a4b") && VisionModels.likely("deepseek-flash") && !VisionModels.likely("qwen2.5-coder-7b") && !VisionModels.likely("gemma-3-1b-it"))
        } finally { client.close(); server.stop(0) }
    }

    @Test fun attachmentReferencesAreSentAsTextWithoutPretendingToBeVision() = runBlocking {
        var request = ""
        val server = testServer { exchange ->
            request = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            sendSse(exchange, sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"))
        }
        invoke(server, listOf(AgentMessage("user", "inspect", attachments = listOf(
            dev.chiller.contracts.ComposerAttachment("synthetic-artifact", "chart.png", "image/png", 42),
        ))))
        assertTrue("synthetic-artifact" in request)
        assertTrue("chart.png" in request)
        assertTrue("not sent as vision input" in request)
        assertTrue("image_url" !in request)
    }

    @Test fun requestExtrasAreSentButCannotReplaceProtocolFields() = runBlocking {
        var body = ""
        val server = testServer { exchange ->
            body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            sendSse(exchange, sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"))
        }
        val client = HttpClient(CIO)
        try {
            val extras = buildJsonObject { put("temperature", 0.2); put("parallel_tool_calls", false); put("model", "hijack"); put("stream", false) }
            val profile = ChatCompletionsProfile("local", "http://127.0.0.1:${server.address.port}", "gemma", requiresApiKey = false).withExtras(extras)
            val provider = ChatCompletionsProvider(client, profile, emptyList()) { null }
            assertIs<ModelEvent.Completed>(provider.stream(ModelRequest("gemma", listOf(AgentMessage("user", "x")))).toList().last())
            val sent = Json.parseToJsonElement(body).jsonObject
            assertEquals("0.2", sent.getValue("temperature").jsonPrimitive.content)
            assertEquals("false", sent.getValue("parallel_tool_calls").jsonPrimitive.content)
            assertEquals("gemma", sent.getValue("model").jsonPrimitive.content)
            assertEquals("true", sent.getValue("stream").jsonPrimitive.content)
        } finally { client.close(); server.stop(0) }
    }

    @Test fun vllmReasoningIsShownAndReplayedAndThinkingGoesThroughTheChatTemplate() = runBlocking {
        val bodies = mutableListOf<String>()
        val server = testServer { exchange ->
            bodies += exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            sendSse(exchange, sse("""{"choices":[{"delta":{"reasoning":"weigh it"},"finish_reason":null}]}""",
                """{"choices":[{"delta":{"content":"391"},"finish_reason":"stop"}]}""", "[DONE]"))
        }
        val client = HttpClient(CIO)
        try {
            val profile = ChatCompletionsProfile("generic", "http://127.0.0.1:${server.address.port}", "quasar", requiresApiKey = false)
                .withChatTemplateThinking()
            val provider = ChatCompletionsProvider(client, profile, emptyList()) { null }
            val events = provider.stream(ModelRequest("quasar", listOf(AgentMessage("user", "17*23")), thinking = true)).toList()
            assertEquals(listOf("weigh it"), events.filterIsInstance<ModelEvent.ReasoningDelta>().map { it.text })
            val message = assertIs<ModelEvent.Completed>(events.last()).message
            assertEquals("weigh it", message.replay["reasoning_content"]?.jsonPrimitive?.content, "vLLM's \"reasoning\" is kept for the template")
            assertEquals("true", Json.parseToJsonElement(bodies[0]).jsonObject.getValue("chat_template_kwargs").jsonObject.getValue("enable_thinking").jsonPrimitive.content)
            // The template keeps it between tool calls of one turn and drops it for earlier turns, so it is always sent.
            provider.stream(ModelRequest("quasar", listOf(AgentMessage("user", "17*23"), message, AgentMessage("user", "x")), thinking = false)).toList()
            val second = Json.parseToJsonElement(bodies[1]).jsonObject
            assertEquals("false", second.getValue("chat_template_kwargs").jsonObject.getValue("enable_thinking").jsonPrimitive.content)
            assertEquals("weigh it", second.getValue("messages").jsonArray[1].jsonObject.getValue("reasoning_content").jsonPrimitive.content)
            // The user's own chat_template_kwargs wins over the switch.
            val custom = ChatCompletionsProvider(client, profile.withExtras(buildJsonObject { put("chat_template_kwargs", buildJsonObject { put("custom", 1) }) }), emptyList()) { null }
            custom.stream(ModelRequest("quasar", listOf(AgentMessage("user", "x")), thinking = true)).toList()
            assertEquals("""{"custom":1}""", Json.parseToJsonElement(bodies[2]).jsonObject.getValue("chat_template_kwargs").toString())
        } finally { client.close(); server.stop(0) }
    }

    @Test fun errorEventInTheStreamIsReportedWithTheProvidersText() = runBlocking {
        val server = testServer { exchange ->
            sendSse(exchange, sse("""{"choices":[{"delta":{"content":"par"},"finish_reason":null}]}""",
                """{"error":{"message":"This model's maximum context length is 131072 tokens","type":"BadRequestError","param":null,"code":400}}"""))
        }
        val events = invoke(server)
        val failure = assertIs<ModelEvent.Failed>(events.last(), "Stream events: $events")
        assertEquals("PROVIDER_STREAM_ERROR", failure.code)
        assertTrue("maximum context length is 131072" in failure.message, failure.message)
    }

    @Test fun keylessProfileSendsNoAuthorizationAndKeyedProfileStillRequiresKey() = runBlocking {
        var authorization: String? = "unset"
        val server = testServer { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            sendSse(exchange, sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"))
        }
        val client = HttpClient(CIO)
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val keyless = ChatCompletionsProvider(client, ChatCompletionsProfile("local", base, "gemma", requiresApiKey = false), emptyList()) { null }
            assertIs<ModelEvent.Completed>(keyless.stream(ModelRequest("gemma", listOf(AgentMessage("user", "x")))).toList().last())
            assertNull(authorization)
            val keyed = ChatCompletionsProvider(client, ChatCompletionsProfile("remote", base, "m"), emptyList()) { null }
            assertEquals("MISSING_CREDENTIAL", (keyed.stream(ModelRequest("m", listOf(AgentMessage("user", "x")))).toList().last() as ModelEvent.Failed).code)
        } finally { client.close(); server.stop(0) }
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
        // A turn from another model (before a mid-dialog switch) goes back without its protocol reasoning.
        val second = invoke(server, listOf(
            AgentMessage("assistant", "earlier", replay = buildJsonObject { put("reasoning_content", "r0-other-model") },
                origin = MessageOrigin(MessageOriginKind.MODEL, "private-local-model-id")),
            AgentMessage("assistant", "answer", replay = firstMessage.replay, origin = MessageOrigin(MessageOriginKind.MODEL, "test")),
        ), thinking = true, stopServer = false)
        assertIs<ModelEvent.Completed>(second.last())
        server.stop(0)
        val secondRequest = Json.parseToJsonElement(seen.last()).jsonObject
        val sentMessage = secondRequest["messages"]!!.toString()
        assertTrue("reasoning_content" in sentMessage)
        assertTrue("r1" in sentMessage)
        assertTrue("r0-other-model" !in sentMessage)
        assertTrue("origin" !in sentMessage)
        assertTrue("private-local-model-id" !in sentMessage)
    }

    @Test fun truncatedToolRetainsProviderUsage() = runBlocking {
        val server = testServer { exchange ->
            sendSse(exchange, sse(
                toolChunk(0, "call-cut", "create_file", "{", "length"),
                """{"choices":[],"usage":{"prompt_tokens":50,"completion_tokens":100}}""",
                "[DONE]",
            ))
        }
        val events = invoke(server)
        assertIs<ModelEvent.Failed>(events.last())
        assertEquals("INCOMPLETE_TOOL_CALL", (events.last() as ModelEvent.Failed).code)
        val usage = events.filterIsInstance<ModelEvent.Usage>().single().usage
        assertEquals(100L, usage.completionTokens)
        assertEquals(50L, usage.promptTokens)
        assertTrue(events.none { it is ModelEvent.Completed })
    }

    @Test fun usageIsEmittedOnceBeforeCompleted() = runBlocking {
        val server = testServer { exchange ->
            sendSse(exchange, sse(
                """{"choices":[{"delta":{"content":"hi"},"finish_reason":null}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":11,"completion_tokens":7}}""",
                "[DONE]",
            ))
        }
        val events = invoke(server)
        val usageIndex = events.indexOfFirst { it is ModelEvent.Usage }
        assertTrue(usageIndex >= 0, "Stream events: $events")
        assertEquals(usageIndex + 1, events.indexOfFirst { it is ModelEvent.Completed })
        val usage = assertIs<ModelEvent.Usage>(events[usageIndex]).usage
        assertEquals(11L, usage.promptTokens)
        assertEquals(7L, usage.completionTokens)
        assertNull(usage.cachedPromptTokens)
        assertEquals(1, events.count { it is ModelEvent.Usage })
    }

    @Test fun deepSeekCacheHitTokensAreMapped() = runBlocking {
        val server = testServer { exchange ->
            sendSse(exchange, sse(
                """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":5,"prompt_cache_hit_tokens":12}}""",
                "[DONE]",
            ))
        }
        val usage = assertIs<ModelEvent.Usage>(invoke(server).first { it is ModelEvent.Usage }, "Usage was not emitted").usage
        assertEquals(20L, usage.promptTokens)
        assertEquals(12L, usage.cachedPromptTokens)
    }

    @Test fun openAiCachedTokenDetailsAreMapped() = runBlocking {
        val server = testServer { exchange ->
            sendSse(exchange, sse(
                """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":3,"prompt_tokens_details":{"cached_tokens":4}}}""",
                "[DONE]",
            ))
        }
        val usage = assertIs<ModelEvent.Usage>(invoke(server).first { it is ModelEvent.Usage }, "Usage was not emitted").usage
        assertEquals(4L, usage.cachedPromptTokens)
    }

    @Test fun absentUsageEmitsNoUsageEvent() = runBlocking {
        val server = testServer { exchange ->
            sendSse(exchange, sse(
                """{"choices":[{"delta":{"content":"hi"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ))
        }
        val events = invoke(server)
        assertIs<ModelEvent.Completed>(events.last())
        assertTrue(events.none { it is ModelEvent.Usage })
    }

    @Test fun rateLimitIsRetriedWhileNothingWasStreamed() = runBlocking {
        val (server, requests) = sequenceServer(listOf(429, 200)) { exchange, _ ->
            sendSse(exchange, sse(
                """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ))
        }
        val events = invoke(server, retryDelaysMs = emptyList())
        assertIs<ModelEvent.Completed>(events.last(), "Stream events: $events")
        assertEquals(listOf("ok"), events.filterIsInstance<ModelEvent.TextDelta>().map { it.text })
        assertEquals(2, requests.get())
    }

    @Test fun retryAfterIsReadAsSecondsOrHttpDate() {
        assertEquals(60_000, retryAfterMs("60"))
        assertEquals(0, retryAfterMs(" 0 "))
        assertNull(retryAfterMs("-1"))
        assertNull(retryAfterMs("soon"))
        val now = java.time.Instant.parse("2026-10-19T04:40:00Z")
        assertEquals(56_000, retryAfterMs("Mon, 19 Oct 2026 04:40:56 GMT", now))
        assertEquals(0, retryAfterMs("Mon, 19 Oct 2026 04:39:00 GMT", now))
    }

    @Test fun gatewayQuotaIsWaitedOutAndAnnounced() = runBlocking {
        val (server, requests) = sequenceServer(listOf(429, 200), mapOf("Retry-After" to "60")) { exchange, _ ->
            sendSse(exchange, sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"))
        }
        val waits = mutableListOf<Long>()
        val events = invoke(server, sleep = { waits += it })
        assertIs<ModelEvent.Completed>(events.last(), "Stream events: $events")
        assertEquals(listOf(60_000L), waits)
        assertEquals(ModelEvent.Retrying("RATE_LIMITED", 60_000, 2, 3), events.filterIsInstance<ModelEvent.Retrying>().single())
        assertEquals(2, requests.get())
    }

    @Test fun retryAfterBeyondTheCapEndsTheTurnWithTheServersFigure() = runBlocking {
        val (server, requests) = sequenceServer(listOf(429), mapOf("Retry-After" to "600")) { _, _ -> }
        val waits = mutableListOf<Long>()
        val events = invoke(server, sleep = { waits += it })
        val failure = assertIs<ModelEvent.Failed>(events.last(), "Stream events: $events")
        assertEquals("RATE_LIMITED", failure.code)
        assertTrue("600 s" in failure.message, failure.message)
        assertEquals(1, requests.get())
        assertTrue(waits.isEmpty())
    }

    @Test fun shortBackoffsAreNotAnnounced() = runBlocking {
        val (server, _) = sequenceServer(listOf(500, 200)) { exchange, _ ->
            sendSse(exchange, sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"))
        }
        val events = invoke(server, retryDelaysMs = listOf(500))
        assertIs<ModelEvent.Completed>(events.last())
        assertTrue(events.none { it is ModelEvent.Retrying })
    }

    @Test fun anExhaustedCostBudgetStopsBeforeTheRequestIsSent() = runBlocking {
        val (server, requests) = sequenceServer(listOf(500)) { _, _ -> }
        // Room for exactly one attempt: the retry after the server error must reserve again and be refused.
        val budget = object : dev.chiller.contracts.RequestBudget {
            var reserved = 0
            override fun beginTask() = Unit
            override fun reserveChat(maxOutputTokens: Int): dev.chiller.contracts.RequestReservation {
                if (reserved++ >= 1) throw dev.chiller.contracts.ToolException("COST_BUDGET_EXHAUSTED", "no money left")
                return object : dev.chiller.contracts.RequestReservation { override fun finish(usage: dev.chiller.contracts.TokenUsage?) = Unit }
            }
            override fun reserveImage() = error("unused")
            override fun snapshot() = dev.chiller.contracts.CostBudgetSnapshot("1", "1", "0", reserved, 0)
        }
        val client = HttpClient(CIO)
        try {
            val provider = ChatCompletionsProvider(client, ChatCompletionsProfile("test", "http://127.0.0.1:${server.address.port}", "m"), emptyList(), requestBudget = budget) { "k" }
            val events = provider.stream(ModelRequest("m", listOf(AgentMessage("user", "x")), maxOutputTokens = 4096)).toList()
            assertEquals("COST_BUDGET_EXHAUSTED", assertIs<ModelEvent.Failed>(events.last(), "Stream events: $events").code)
            assertEquals(1, requests.get(), "the first attempt fit, its retry did not: retries are paid too")
        } finally { client.close(); server.stop(0) }
    }

    @Test fun serverErrorsRetryThreeTimesThenFail() = runBlocking {
        val (server, requests) = sequenceServer(listOf(500, 500, 500)) { _, _ -> }
        val events = invoke(server)
        val failure = assertIs<ModelEvent.Failed>(events.last(), "Stream events: $events")
        assertEquals("PROVIDER_HTTP_ERROR", failure.code)
        assertEquals(3, requests.get())
    }

    @Test fun authenticationFailureIsNotRetried() = runBlocking {
        val (server, requests) = sequenceServer(listOf(401)) { _, _ -> }
        val events = invoke(server)
        assertEquals("AUTHENTICATION_FAILED", assertIs<ModelEvent.Failed>(events.last()).code)
        assertEquals(1, requests.get())
    }

    @Test fun failureAfterFirstDeltaIsNotRetried() = runBlocking {
        val (server, requests) = sequenceServer(listOf(200)) { exchange, _ ->
            sendSse(exchange, sse("""{"choices":[{"delta":{"content":"partial"},"finish_reason":null}]}"""))
        }
        val events = invoke(server)
        assertEquals(listOf("partial"), events.filterIsInstance<ModelEvent.TextDelta>().map { it.text })
        assertEquals("INCOMPLETE_STREAM", assertIs<ModelEvent.Failed>(events.last(), "Stream events: $events").code)
        assertEquals(1, requests.get())
    }

    @Test fun emptyStreamIsRetriedWhileNothingWasStreamed() = runBlocking {
        val (server, requests) = sequenceServer(listOf(200, 200)) { exchange, attempt ->
            if (attempt == 0) { exchange.sendResponseHeaders(200, -1); exchange.close() }
            else sendSse(exchange, sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"))
        }
        val events = invoke(server)
        assertIs<ModelEvent.Completed>(events.last(), "Stream events: $events")
        assertEquals(2, requests.get())
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

    private suspend fun invoke(
        server: HttpServer,
        messages: List<AgentMessage> = listOf(AgentMessage("user", "x")),
        thinking: Boolean = false,
        retryDelaysMs: List<Long> = emptyList(),
        stopServer: Boolean = true,
        sleep: suspend (Long) -> Unit = {},
    ): List<ModelEvent> {
        val client = HttpClient(CIO)
        try {
            val provider = ChatCompletionsProvider(
                client,
                ChatCompletionsProfile("test", "http://127.0.0.1:${server.address.port}", "test", reasoningField = "reasoning_content"),
                retryDelaysMs,
                sleep,
            ) { "test-secret" }
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

    private fun sse(vararg chunks: String): String =
        chunks.joinToString("\n\ndata: ", prefix = "data: ", postfix = "\n\n")

    /** Serves one status per request, repeating the last status, and counts requests. */
    private fun sequenceServer(
        statuses: List<Int>,
        errorHeaders: Map<String, String> = emptyMap(),
        handler: (com.sun.net.httpserver.HttpExchange, Int) -> Unit,
    ): Pair<HttpServer, AtomicInteger> {
        val requests = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val attempt = requests.getAndIncrement()
            val status = statuses.getOrElse(attempt) { statuses.last() }
            if (status == 200) handler(exchange, attempt)
            else { errorHeaders.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }; exchange.sendResponseHeaders(status, -1); exchange.close() }
        }
        server.start()
        return server to requests
    }
}
