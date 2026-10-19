package dev.chiller.agent

import dev.chiller.contracts.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentCoordinatorTest {
    @Test fun terminalRunsAlwaysReleaseOwnedProcesses() = runTest {
        for (fail in listOf(false, true)) {
            val provider = ScriptedProvider { flow {
                if (fail) emit(ModelEvent.Failed("TEST", "failure"))
                else emit(ModelEvent.Completed(AgentMessage("assistant", "done"), "stop"))
            } }
            val fixture = fixture(provider, dispatcher = StandardTestDispatcher(testScheduler))
            fixture.coordinator.send("inspect", ExecutionMode.WORKSPACE_EDIT)
            advanceUntilIdle()
            assertEquals(1, fixture.processes.stoppedRuns.size)
            assertEquals(if (fail) "failed" else "completed", fixture.coordinator.state.value.status)
        }
    }

    @Test fun incompleteModelStreamNeverExecutesToolCalls() = runTest {
        val tools = RecordingTools()
        val provider = ScriptedProvider { _ -> flow {
            emit(ModelEvent.TextDelta("partial response"))
            // The transport closes without Completed; even a tool-shaped response cannot execute.
        } }
        val fixture = fixture(provider, tools, dispatcher = StandardTestDispatcher(testScheduler))
        fixture.coordinator.send("inspect", ExecutionMode.WORKSPACE_EDIT)
        advanceUntilIdle()
        assertEquals(0, tools.executed.size)
        assertEquals("failed", fixture.coordinator.state.value.status)
    }

    @Test fun truncatedToolCallIsNotExecutedAndTheModelIsToldToWriteInParts() = runTest {
        val tools = RecordingTools()
        val requests = mutableListOf<ModelRequest>()
        val provider = ScriptedProvider { request -> flow {
            requests += request
            if (requests.size == 1) emit(ModelEvent.Failed("INCOMPLETE_TOOL_CALL", "Provider truncated a tool call; it was not executed"))
            else emit(ModelEvent.Completed(AgentMessage("assistant", "done in parts"), "stop"))
        } }
        val events = MemoryEvents()
        val fixture = fixture(provider, tools, eventStore = events, dispatcher = StandardTestDispatcher(testScheduler))
        fixture.coordinator.send("big file", ExecutionMode.WORKSPACE_EDIT)
        advanceUntilIdle()
        assertEquals("completed", fixture.coordinator.state.value.status)
        assertEquals(0, tools.executed.size, "nothing truncated is executed")
        val notice = requests[1].messages.last()
        assertTrue(notice.role == "user" && "cut off" in notice.content.orEmpty() && "in parts" in notice.content.orEmpty())
        assertTrue(fixture.coordinator.state.value.messages.none { "[Chiller]" in it.content.orEmpty() }, "the note is not part of the conversation")
        assertEquals(1, events.records.count { it.type == "run.truncated" })
    }

    @Test fun finalAnswerWithAnOpenPlanGetsARemindedRoundFirst() = runTest {
        val requests = mutableListOf<ModelRequest>()
        fun plan(status: String) = ToolCall("p${requests.size}", "update_plan", """{"steps":[{"step":"Find weather","status":"done"},{"step":"Draw chart","status":"$status"}]}""")
        val provider = ScriptedProvider { request -> flow {
            requests += request
            when (requests.size) {
                1 -> emit(ModelEvent.Completed(AgentMessage("assistant", null, listOf(plan("pending"))), "tool_calls"))
                2 -> emit(ModelEvent.Completed(AgentMessage("assistant", "All done!"), "stop"))
                3 -> emit(ModelEvent.Completed(AgentMessage("assistant", null, listOf(plan("skipped"))), "tool_calls"))
                else -> emit(ModelEvent.Completed(AgentMessage("assistant", "Chart skipped: no data."), "stop"))
            }
        } }
        val events = MemoryEvents()
        val fixture = fixture(provider, PlanTools(), eventStore = events, dispatcher = StandardTestDispatcher(testScheduler))
        fixture.coordinator.send("weather and chart", ExecutionMode.WORKSPACE_EDIT)
        advanceUntilIdle()
        assertEquals("completed", fixture.coordinator.state.value.status)
        assertEquals(4, requests.size)
        assertTrue("- Draw chart" in requests[2].messages.last().content.orEmpty(), "the reminder names the open step")
        assertTrue(requests[3].messages.none { "[Chiller]" in it.content.orEmpty() }, "the reminder is not kept in the conversation")
        assertEquals(1, events.records.count { it.type == "run.plan_open" })
    }

    @Test fun planRemindersAreBoundedAndTheAnswerThenStands() = runTest {
        var calls = 0
        val provider = ScriptedProvider { _ -> flow {
            calls++
            if (calls == 1) emit(ModelEvent.Completed(AgentMessage("assistant", null, listOf(ToolCall("p", "update_plan", """{"steps":[{"step":"A","status":"in_progress"}]}"""))), "tool_calls"))
            else emit(ModelEvent.Completed(AgentMessage("assistant", "Done."), "stop"))
        } }
        val fixture = fixture(provider, PlanTools(), dispatcher = StandardTestDispatcher(testScheduler))
        fixture.coordinator.send("task", ExecutionMode.WORKSPACE_EDIT)
        advanceUntilIdle()
        assertEquals("completed", fixture.coordinator.state.value.status)
        assertEquals(4, calls, "one plan round, then the answer, two reminders and the answer that stands")
    }

    @Test fun capabilityLineFollowsTheEnabledTools() {
        val line = capabilityLine(setOf("generate_image", "web_search", "run_process", "show_file"))
        assertTrue("generate and edit images" in line && "search the web" in line && "run programs" in line, line)
        assertTrue("helpers" !in line)
        assertEquals("", capabilityLine(emptySet()))
    }

    private class PlanTools : ToolExecutor {
        override val specs = listOf(ToolSpec("update_plan", "plan", JsonObject(emptyMap()), ToolEffect.READ))
        override suspend fun execute(call: ToolCall, context: ToolContext) = ToolResult(true, buildJsonObject { put("ok", true) })
    }

    @Test fun providerRetryWaitIsShownUntilTheModelAnswers() = runTest {
        val resend = CompletableDeferred<Unit>()
        val provider = ScriptedProvider { _ -> flow {
            emit(ModelEvent.Retrying("RATE_LIMITED", 60_000, 2, 3))
            resend.await()
            emit(ModelEvent.TextDelta("ok"))
            emit(ModelEvent.Completed(AgentMessage("assistant", "ok"), "stop"))
        } }
        val fixture = fixture(provider, dispatcher = StandardTestDispatcher(testScheduler))
        fixture.coordinator.send("hi", ExecutionMode.PLAN)
        runCurrent()
        assertEquals("Провайдер ограничил частоту запросов. Повтор через 60 с (попытка 2/3).", fixture.coordinator.state.value.recoveryNotice)
        resend.complete(Unit)
        advanceUntilIdle()
        assertEquals("completed", fixture.coordinator.state.value.status)
        assertEquals(null, fixture.coordinator.state.value.recoveryNotice)
    }

    @Test fun textCutByTheLimitIsKeptAndContinued() = runTest {
        val requests = mutableListOf<ModelRequest>()
        val provider = ScriptedProvider { request -> flow {
            requests += request
            if (requests.size == 1) emit(ModelEvent.Completed(AgentMessage("assistant", "first half"), "length"))
            else emit(ModelEvent.Completed(AgentMessage("assistant", "second half"), "stop"))
        } }
        val fixture = fixture(provider, dispatcher = StandardTestDispatcher(testScheduler))
        fixture.coordinator.send("long answer", ExecutionMode.PLAN)
        advanceUntilIdle()
        assertEquals("completed", fixture.coordinator.state.value.status)
        assertEquals(listOf("first half", "second half"), fixture.coordinator.state.value.messages.filter { it.role == "assistant" }.map { it.content })
        assertTrue("Continue exactly where it stopped" in requests[1].messages.last().content.orEmpty())
    }

    @Test fun truncationsAreLimitedPerStreakAndTheNoteNamesTheLimit() = runTest {
        val requests = mutableListOf<ModelRequest>()
        // Cut-off, progress, cut-off, progress, cut-off, progress, then done: three cut-offs in total, never two in a row.
        val provider = ScriptedProvider { request -> flow {
            requests += request
            when (requests.size) {
                1, 3, 5 -> emit(ModelEvent.Failed("INCOMPLETE_TOOL_CALL", "Provider truncated a tool call; it was not executed"))
                7 -> emit(ModelEvent.Completed(AgentMessage("assistant", "done"), "stop"))
                else -> emit(ModelEvent.Completed(AgentMessage("assistant", toolCalls = listOf(ToolCall(newId(), "read", "{}"))), "tool_calls"))
            }
        } }
        val fixture = fixture(provider, dispatcher = StandardTestDispatcher(testScheduler), maxOutputTokens = 2048)
        fixture.coordinator.send("write in parts", ExecutionMode.WORKSPACE_EDIT)
        advanceUntilIdle()
        assertEquals("completed", fixture.coordinator.state.value.status, fixture.coordinator.state.value.error)
        val note = requests[1].messages.last().content.orEmpty()
        assertTrue("about 2048 tokens" in note && "3072 characters" in note && "edit_file" in note, note)
    }

    @Test fun repeatedTruncationStopsInsteadOfLooping() = runTest {
        val provider = ScriptedProvider { _ -> flow { emit(ModelEvent.Failed("INCOMPLETE_TOOL_CALL", "Provider truncated a tool call; it was not executed")) } }
        val fixture = fixture(provider, dispatcher = StandardTestDispatcher(testScheduler))
        fixture.coordinator.send("huge", ExecutionMode.WORKSPACE_EDIT)
        advanceUntilIdle()
        assertEquals("failed", fixture.coordinator.state.value.status)
        assertEquals(3, provider.calls, "two recoveries, then the third truncation fails the run")
    }

    @Test fun modelMaximumAsksForHalfTheContextWindow() = runTest {
        for ((window, expected) in listOf(1_048_576 to 524_288, null to 16_384)) {
            val requests = mutableListOf<ModelRequest>()
            val provider = ScriptedProvider { request -> flow { requests += request; emit(ModelEvent.Completed(AgentMessage("assistant", "ok"), "stop")) } }
            val fixture = fixture(provider, dispatcher = StandardTestDispatcher(testScheduler), contextWindow = window, maxOutputTokens = Int.MAX_VALUE)
            fixture.coordinator.send("big", ExecutionMode.PLAN)
            advanceUntilIdle()
            assertEquals("completed", fixture.coordinator.state.value.status)
            assertEquals(expected, requests.single().maxOutputTokens)
        }
    }

    @Test fun planModeSendsOnlyReadAndInteractionToolSpecs() = runTest {
        val requests = mutableListOf<ModelRequest>()
        val provider = ScriptedProvider { request -> flow {
            requests += request
            emit(ModelEvent.Completed(AgentMessage("assistant", "planned"), "stop"))
        } }
        val fixture = fixture(provider, dispatcher = StandardTestDispatcher(testScheduler))
        fixture.coordinator.send("plan", ExecutionMode.PLAN)
        advanceUntilIdle()
        val names = requests.single().tools.map { it.name }.toSet()
        assertEquals(setOf("read", "ask"), names)
        assertEquals("completed", fixture.coordinator.state.value.status)
    }

    @Test fun externalToolCatalogRequiresEachExplicitPermissionEvenInEditMode() = runTest {
        val requests = mutableListOf<ModelRequest>()
        val provider = ScriptedProvider { request -> flow {
            requests += request
            emit(ModelEvent.Completed(AgentMessage("assistant", "done"), "stop"))
        } }
        val tools = object : ToolExecutor {
            override val specs = listOf(
                ToolSpec("web", "web", JsonObject(emptyMap()), ToolEffect.NETWORK),
                ToolSpec("capture", "capture", JsonObject(emptyMap()), ToolEffect.SCREEN_CAPTURE),
            )
            override suspend fun execute(call: ToolCall, context: ToolContext) = error("No execution expected")
        }
        val fixture = fixture(provider, tools, dispatcher = StandardTestDispatcher(testScheduler))
        fixture.coordinator.send("default", ExecutionMode.WORKSPACE_EDIT)
        advanceUntilIdle()
        assertTrue(requests.last().tools.isEmpty())
        fixture.coordinator.send("network", ExecutionMode.PLAN, allowNetwork = true)
        advanceUntilIdle()
        assertEquals(listOf("web"), requests.last().tools.map { it.name })
        fixture.coordinator.send("capture", ExecutionMode.PLAN, allowScreenshots = true)
        advanceUntilIdle()
        assertEquals(listOf("capture"), requests.last().tools.map { it.name })
    }

    @Test fun projectInstructionsAreAStableSystemMessageAndRoundsAreTraced() = runTest {
        val requests = mutableListOf<ModelRequest>()
        var round = 0
        val provider = ScriptedProvider { request -> flow {
            requests += request
            if (round++ == 0) {
                emit(ModelEvent.Usage(TokenUsage(promptTokens = 100, completionTokens = 5)))
                emit(ModelEvent.Completed(AgentMessage("assistant", null, toolCalls = listOf(ToolCall("c1", "read", "{}"))), "tool_calls"))
            } else emit(ModelEvent.Completed(AgentMessage("assistant", "done"), "stop"))
        } }
        val traced = mutableListOf<JsonObject>()
        val fixture = fixture(provider, dispatcher = StandardTestDispatcher(testScheduler), instructions = "Run make test.", traces = { traced += it })
        fixture.coordinator.send("go", ExecutionMode.WORKSPACE_EDIT)
        advanceUntilIdle()
        assertEquals("completed", fixture.coordinator.state.value.status)
        requests.forEach { request ->
            assertEquals(listOf("system", "system", "user"), request.messages.take(3).map { it.role })
            assertTrue(request.messages[1].content!!.contains("Run make test."))
        }
        assertEquals(listOf(1, 2), traced.map { it.getValue("round").jsonPrimitive.int })
        assertEquals("tool_calls", traced[0].getValue("finishReason").jsonPrimitive.content)
        assertEquals(100, traced[0].getValue("usage").jsonObject.getValue("promptTokens").jsonPrimitive.int)
        assertTrue("toolSchemas" in traced[0] && "toolSchemas" !in traced[1])
        assertEquals("done", traced[1].getValue("response").jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test fun legacyMessageJsonWithoutOriginStillDecodes() {
        val legacy = Json.decodeFromString<AgentMessage>("""{"role":"assistant","content":"old reply"}""")
        assertEquals("old reply", legacy.content)
        assertEquals(null, legacy.origin)
    }

    @Test fun acceptedCallbackRunsOnceAfterUserMessageIsDurablyAppendedAndBeforeProvider() = runTest {
        val events = MemoryEvents()
        val provider = ScriptedProvider { _ -> flow {
            emit(ModelEvent.Completed(AgentMessage("assistant", "done"), "stop"))
        } }
        val fixture = fixture(provider, eventStore = events, dispatcher = StandardTestDispatcher(testScheduler))
        var accepted = 0
        val job = fixture.coordinator.send("hello", ExecutionMode.PLAN) {
            accepted++
            assertEquals(1, events.userMessageAppends)
            assertEquals(0, provider.calls)
        }
        advanceUntilIdle()
        job.join()
        assertEquals(1, accepted)
        assertEquals(1, events.userMessageAppends)
    }

    @Test fun failedUserMessageAppendDoesNotAcceptOrCallProvider() = runTest {
        val events = MemoryEvents().apply { failUserMessageAppend = true }
        val provider = ScriptedProvider { error("provider must not be called") }
        val fixture = fixture(provider, eventStore = events, dispatcher = StandardTestDispatcher(testScheduler))
        var accepted = false
        val job = fixture.coordinator.send("hello", ExecutionMode.PLAN) { accepted = true }
        advanceUntilIdle()
        job.join()
        assertEquals(false, accepted)
        assertEquals(0, events.userMessageAppends)
        assertEquals(0, provider.calls)
    }

    @Test fun restoredAssistantKeepsOriginalModelAfterCoordinatorChangesModel() = runTest {
        val events = MemoryEvents()
        val first = fixture(ScriptedProvider { _ -> flow {
            emit(ModelEvent.Completed(AgentMessage("assistant", "answer from A"), "stop"))
        } }, eventStore = events, dispatcher = StandardTestDispatcher(testScheduler), model = "model-A")
        first.coordinator.send("question", ExecutionMode.PLAN)
        advanceUntilIdle()
        assertEquals(null, first.coordinator.state.value.activeModelId)

        val second = fixture(ScriptedProvider { error("restore must not call provider") }, eventStore = events,
            dispatcher = StandardTestDispatcher(testScheduler), model = "model-B")
        second.coordinator.restore()
        val restored = second.coordinator.state.value.messages.single { it.role == "assistant" }
        assertEquals(MessageOrigin(MessageOriginKind.MODEL, "model-A"), restored.origin)
        assertEquals(null, second.coordinator.state.value.activeModelId)
    }

    @Test fun restorePreservesFailedStatusAndErrorWithoutReplayingProvider() = runTest {
        val events = MemoryEvents().apply {
            append("session", "run.started", buildJsonObject { put("runId", "run-failed") })
            append("session", "run.failed", buildJsonObject { put("runId", "run-failed"); put("message", "provider timed out") })
        }
        val provider = ScriptedProvider { error("restored run must not replay") }
        val fixture = fixture(provider, eventStore = events, dispatcher = StandardTestDispatcher(testScheduler))

        fixture.coordinator.restore()

        assertEquals("failed", fixture.coordinator.state.value.status)
        assertEquals("provider timed out", fixture.coordinator.state.value.error)
        assertEquals(null, fixture.coordinator.state.value.activeModelId)
        assertEquals(0, provider.calls)
    }

    @Test fun restorePreservesCancelledStatusWithoutReplayingProvider() = runTest {
        val events = MemoryEvents().apply {
            append("session", "run.started", buildJsonObject { put("runId", "run-cancelled") })
            append("session", "run.cancelled", buildJsonObject { put("runId", "run-cancelled") })
        }
        val provider = ScriptedProvider { error("restored run must not replay") }
        val fixture = fixture(provider, eventStore = events, dispatcher = StandardTestDispatcher(testScheduler))

        fixture.coordinator.restore()

        assertEquals("cancelled", fixture.coordinator.state.value.status)
        assertEquals(null, fixture.coordinator.state.value.error)
        assertEquals(null, fixture.coordinator.state.value.activeModelId)
        assertEquals(0, provider.calls)
    }

    @Test fun restoreLoadsMetricsFromLatestCompletedRunSummary() = runTest {
        val events = MemoryEvents().apply {
            append("session", "run.started", buildJsonObject { put("runId", "run-1") })
            append("session", "run.completed", buildJsonObject { put("runId", "run-1") })
            append("session", "run.summary", buildJsonObject {
                put("runId", "run-1"); put("status", "completed"); put("rounds", 3); put("toolCalls", 2)
                put("failedToolCalls", 1); put("durationMs", 876); put("promptTokens", 120L); put("completionTokens", 40L)
            })
        }
        val fixture = fixture(ScriptedProvider { error("restore must not call provider") }, eventStore = events)

        fixture.coordinator.restore()

        val restored = fixture.coordinator.state.value
        assertEquals("completed", restored.status)
        assertEquals(3, restored.modelRounds)
        assertEquals(2, restored.toolCalls)
        assertEquals(1, restored.failedToolCalls)
        assertEquals(876L, restored.runDurationMs)
        assertEquals(120L, restored.usage.promptTokens)
        assertEquals(40L, restored.usage.completionTokens)
    }

    @Test fun historyReplacementInvalidatesPriorRunSummaryAndMetrics() = runTest {
        val events = MemoryEvents().apply {
            append("session", "message", buildJsonObject { put("role", "user"); put("content", "old request") })
            append("session", "run.started", buildJsonObject { put("runId", "run-old") })
            append("session", "run.completed", buildJsonObject { put("runId", "run-old") })
            append("session", "run.summary", buildJsonObject {
                put("runId", "run-old"); put("status", "completed"); put("rounds", 4); put("toolCalls", 3)
                put("failedToolCalls", 0); put("durationMs", 1200)
            })
            append("session", "history.replace_turn", buildJsonObject {
                put("index", 0); put("expectedCount", 1)
                put("message", Json.encodeToJsonElement(AgentMessage("user", "edited request")))
            })
        }
        val fixture = fixture(ScriptedProvider { error("restore must not call provider") }, eventStore = events)

        fixture.coordinator.restore()

        val restored = fixture.coordinator.state.value
        assertEquals("idle", restored.status)
        assertEquals("edited request", restored.messages.single().content)
        assertEquals(null, restored.runDurationMs)
        assertEquals(0, restored.modelRounds)
        assertEquals(0, restored.toolCalls)
    }

    @Test fun planModeDoesNotExecuteAWriteCallReturnedByTheProvider() = runTest {
        val tools = RecordingTools()
        val forbidden = ToolCall("write-in-plan", "write", "{}")
        val provider = ScriptedProvider { _ -> flow {
            emit(ModelEvent.Completed(AgentMessage("assistant", toolCalls = listOf(forbidden)), "tool_calls"))
        } }
        val fixture = fixture(provider, tools, dispatcher = StandardTestDispatcher(testScheduler))
        fixture.coordinator.send("plan", ExecutionMode.PLAN)
        advanceUntilIdle()
        assertTrue(tools.executed.isEmpty())
    }

    @Test fun toolResultIsPairedBeforeTheNextModelRound() = runTest {
        val call = ToolCall("call-1", "read", "{\"path\":\"src/Main.kt\"}")
        val requests = mutableListOf<ModelRequest>()
        val provider = ScriptedProvider { request ->
            requests += request
            flow {
                if (requests.size == 1) emit(ModelEvent.Completed(AgentMessage("assistant", toolCalls = listOf(call)), "tool_calls"))
                else emit(ModelEvent.Completed(AgentMessage("assistant", "done"), "stop"))
            }
        }
        val tools = RecordingTools()
        val events = MemoryEvents()
        val fixture = fixture(provider, tools, events, StandardTestDispatcher(testScheduler))
        fixture.coordinator.send("read it", ExecutionMode.WORKSPACE_EDIT)
        advanceUntilIdle()

        assertEquals(1, tools.executed.size)
        val transcript = requests[1].messages
        val callIndex = transcript.indexOfFirst { it.toolCalls.any { call -> call.id == "call-1" } }
        assertTrue(callIndex >= 0)
        assertEquals("assistant", transcript[callIndex].role)
        assertEquals("tool", transcript[callIndex + 1].role)
        assertEquals("call-1", transcript[callIndex + 1].toolCallId)
        assertEquals("completed", fixture.coordinator.state.value.status)
        assertEquals("call-1", fixture.coordinator.state.value.activities.single().toolCallId)

        val restored = fixture(ScriptedProvider { error("restore must not call provider") }, eventStore = events,
            dispatcher = StandardTestDispatcher(testScheduler))
        restored.coordinator.restore()
        assertEquals("call-1", restored.coordinator.state.value.activities.single().toolCallId)
    }

    @Test fun interruptedToolHistoryRejectsContinuationWithoutCallingProvider() = runTest {
        val oldCall = ToolCall("call-interrupted", "write", "{}")
        val events = MemoryEvents().apply {
            append("session", "message", Json.encodeToJsonElement(AgentMessage("assistant", toolCalls = listOf(oldCall))).jsonObject)
            append("session", "tool.prepared", buildJsonObject { put("callId", oldCall.id); put("name", oldCall.name); put("arguments", buildJsonObject { put("path", "src/main.kt") }) })
            append("session", "run.failed", buildJsonObject { put("runId", "run-old"); put("message", "old failure") })
        }
        val provider = ScriptedProvider { error("provider must not receive an incomplete tool group") }
        val tools = RecordingTools()
        val fixture = fixture(provider, tools, events, StandardTestDispatcher(testScheduler))
        fixture.coordinator.restore()
        assertEquals("interrupted", fixture.coordinator.state.value.status)
        assertTrue(fixture.coordinator.state.value.error.orEmpty().contains("outcome is unknown"))
        var accepted = false
        fixture.coordinator.send("continue", ExecutionMode.WORKSPACE_EDIT) { accepted = true }
        advanceUntilIdle()
        assertEquals(false, accepted)
        assertEquals(0, provider.calls)
        assertTrue(tools.executed.isEmpty())
        assertEquals("failed", fixture.coordinator.state.value.status)
    }

    @Test fun restoreExposesInterruptedQuestionPromptWithoutResumingIt() = runTest {
        val questionCall = ToolCall("question-call", "ask_user", "{\"question\":\"Which target should I use?\",\"options\":[\"A\",\"B\"]}")
        val events = MemoryEvents().apply {
            append("session", "run.started", buildJsonObject { put("runId", "run-old") })
            append("session", "message", Json.encodeToJsonElement(AgentMessage("assistant", toolCalls = listOf(questionCall))).jsonObject)
            append("session", "tool.prepared", buildJsonObject { put("callId", questionCall.id); put("name", questionCall.name); put("arguments", Json.parseToJsonElement(questionCall.arguments)) })
        }
        val provider = ScriptedProvider { error("restored question must not resume the provider") }
        val tools = RecordingTools()
        val fixture = fixture(provider, tools, events, StandardTestDispatcher(testScheduler))

        fixture.coordinator.restore()

        val restored = fixture.coordinator.state.value
        assertEquals("interrupted", restored.status)
        assertEquals(questionCall.id, restored.question?.id)
        assertEquals("Which target should I use?", restored.question?.question)
        assertEquals(listOf("A", "B"), restored.question?.options)
        assertTrue(restored.error.orEmpty().contains("cannot resume automatically"))
        assertTrue(restored.error.orEmpty().contains("start a new chat"))
        fixture.coordinator.send("continue", ExecutionMode.WORKSPACE_EDIT)
        advanceUntilIdle()
        assertEquals(0, provider.calls)
        assertTrue(tools.executed.isEmpty())
    }

    @Test fun repeatedErrorsSaveEveryBatchResultAndAllowManualContinuation() = runTest {
        var rounds = 0
        var effects = 0
        val tools = object : ToolExecutor {
            override val specs = listOf(ToolSpec("write", "write", JsonObject(emptyMap()), ToolEffect.FILE_WRITE))
            override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
                effects++
                return if (call.id.startsWith("bad")) ToolResult.failure("INVALID_PATH", "bad path") else ToolResult(true)
            }
        }
        val provider = ScriptedProvider { flow {
            val message = if (rounds++ == 0) AgentMessage("assistant", toolCalls = listOf("bad1", "bad2", "bad3", "good").map { ToolCall(it, "write", "{}") })
                else AgentMessage("assistant", "continued")
            emit(ModelEvent.Completed(message, if (message.toolCalls.isEmpty()) "stop" else "tool_calls"))
        } }
        val f = fixture(provider, tools, dispatcher = StandardTestDispatcher(testScheduler))
        f.coordinator.send("work", ExecutionMode.WORKSPACE_EDIT)
        advanceUntilIdle()
        assertEquals("failed", f.coordinator.state.value.status)
        assertEquals(4, f.coordinator.state.value.messages.count { it.role == "tool" })
        assertTrue(f.coordinator.state.value.activities.none { it.status in setOf("running", "interrupted") })
        f.coordinator.send("use the existing directory", ExecutionMode.WORKSPACE_EDIT)
        advanceUntilIdle()
        assertEquals("completed", f.coordinator.state.value.status)
        assertEquals(4, effects)
    }

    @Test fun failuresAreJudgedPerCallNotPerErrorCode() = runTest {
        val tools = object : ToolExecutor {
            override val specs = listOf(ToolSpec("read", "read", JsonObject(emptyMap()), ToolEffect.READ))
            override suspend fun execute(call: ToolCall, context: ToolContext) = ToolResult.failure("NOT_FOUND", "missing")
        }
        fun run(paths: List<String>): String = run {
            var round = 0
            val provider = ScriptedProvider { flow {
                val path = paths.getOrNull(round++)
                if (path == null) emit(ModelEvent.Completed(AgentMessage("assistant", "done"), "stop"))
                else emit(ModelEvent.Completed(AgentMessage("assistant", null, listOf(ToolCall("c$round", "read", """{"path":"$path"}"""))), "tool_calls"))
            } }
            val f = fixture(provider, tools, dispatcher = StandardTestDispatcher(testScheduler))
            f.coordinator.send("read", ExecutionMode.PLAN)
            advanceUntilIdle()
            f.coordinator.state.value.status
        }
        assertEquals("completed", run(listOf("a.txt", "b.txt", "c.txt", "d.txt")), "different files failing the same way are not a loop")
        assertEquals("failed", run(listOf("a.txt", "b.txt", "a.txt", "b.txt", "a.txt")), "alternating failures still repeat a call")
    }

    @Test fun theSameResultAgainGetsAReminderThenEndsTheTurn() = runTest {
        val requests = mutableListOf<ModelRequest>()
        val provider = ScriptedProvider { request -> flow {
            requests += request
            emit(ModelEvent.Completed(AgentMessage("assistant", null, listOf(ToolCall("c${requests.size}", "read", """{"path":"."}"""))), "tool_calls"))
        } }
        val events = MemoryEvents()
        val f = fixture(provider, RecordingTools(), eventStore = events, dispatcher = StandardTestDispatcher(testScheduler))
        f.coordinator.send("list", ExecutionMode.PLAN)
        advanceUntilIdle()
        assertEquals("failed", f.coordinator.state.value.status)
        assertEquals(5, requests.size, "stopped at the fifth identical result")
        assertTrue("[Chiller] You have called read with the same arguments 3 times" in requests[3].messages.last().content.orEmpty())
        assertEquals(1, events.records.count { it.type == "run.no_progress" })
    }

    @Test fun identifiersThatChangeOnEveryCallDoNotHideARepeat() {
        val first = ToolResult(true, buildJsonObject { put("snapshot_id", "s1"); put("content", "1 | x") })
        val again = ToolResult(true, buildJsonObject { put("snapshot_id", "s2"); put("content", "1 | x") })
        val changed = ToolResult(true, buildJsonObject { put("snapshot_id", "s3"); put("content", "1 | y") })
        assertEquals(resultFingerprint(first), resultFingerprint(again))
        assertTrue(resultFingerprint(first) != resultFingerprint(changed))
        assertEquals(canonicalJson("""{"b":1,"a":2}"""), canonicalJson("""{"a":2,"b":1}"""))
    }

    @Test fun stoppingDuringAToolSettlesActivityWithoutInventingAResult() = runTest {
        val entered = CompletableDeferred<Unit>()
        val tools = RecordingTools().apply { onExecute = { entered.complete(Unit); awaitCancellation() } }
        val provider = ScriptedProvider { flow {
            emit(ModelEvent.Completed(AgentMessage("assistant", toolCalls = listOf(ToolCall("pending", "write", "{}"))), "tool_calls"))
        } }
        val events = MemoryEvents()
        val f = fixture(provider, tools, events, StandardTestDispatcher(testScheduler))
        f.coordinator.send("write", ExecutionMode.WORKSPACE_EDIT)
        runCurrent()
        entered.await()
        f.coordinator.stop()
        assertEquals("cancelled", f.coordinator.state.value.status)
        val activity = f.coordinator.state.value.activities.single()
        assertEquals("interrupted", activity.status)
        assertTrue(activity.output.contains("OUTCOME_UNKNOWN"))
        assertTrue(f.coordinator.state.value.messages.none { it.role == "tool" })
        assertEquals("interrupted", events.records.last { it.type == "activity" }.payload["status"]!!.jsonPrimitive.content)
        f.coordinator.restore()
        assertEquals("interrupted", f.coordinator.state.value.status)
        assertEquals("interrupted", f.coordinator.state.value.activities.single().status)
        val priorCalls = provider.calls
        f.coordinator.recoverInterrupted()
        assertEquals(priorCalls, provider.calls)
        assertTrue(tools.executed.isEmpty())
        val unknown = f.coordinator.state.value.messages.single { it.role == "tool" }
        assertEquals("pending", unknown.toolCallId)
        assertTrue(unknown.content!!.contains("OUTCOME_UNKNOWN"))
        f.coordinator.restore()
        assertEquals("idle", f.coordinator.state.value.status)
    }

    @Test fun stoppingDuringStreamCancelsRoundBeforeAnyToolExecution() = runTest {
        val started = CompletableDeferred<Unit>()
        val tools = RecordingTools()
        val provider = ScriptedProvider { _ -> flow {
            started.complete(Unit)
            emit(ModelEvent.TextDelta("working"))
            awaitCancellation()
        } }
        val fixture = fixture(provider, tools, dispatcher = StandardTestDispatcher(testScheduler))
        fixture.coordinator.send("work", ExecutionMode.WORKSPACE_EDIT)
        runCurrent()
        assertTrue(started.isCompleted)
        fixture.coordinator.stop()

        assertEquals(0, tools.executed.size)
        assertEquals(1, fixture.processes.stoppedRuns.size)
        assertEquals("cancelled", fixture.coordinator.state.value.status)
    }

    @Test fun consecutiveReadCallsStartTogetherAndKeepCallOrder() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val tools = RecordingTools().apply {
            onExecute = { call ->
                started += call.id
                if (started.size == 2) gate.complete(Unit)
                gate.await()
            }
        }
        val reads = listOf(
            ToolCall("read-a", "read", "{\"path\":\"a.kt\"}"),
            ToolCall("read-b", "read", "{\"path\":\"b.kt\"}"),
        )
        val provider = ScriptedProvider { request ->
            val round = request.messages.count { it.role == "tool" }
            flow {
                if (round == 0) emit(ModelEvent.Completed(AgentMessage("assistant", toolCalls = reads), "tool_calls"))
                else emit(ModelEvent.Completed(AgentMessage("assistant", "done"), "stop"))
            }
        }
        val fixture = fixture(provider, tools, dispatcher = StandardTestDispatcher(testScheduler))
        fixture.coordinator.send("read both", ExecutionMode.WORKSPACE_EDIT)
        advanceUntilIdle()

        assertEquals(setOf("read-a", "read-b"), started.toSet(), "both reads must be in flight at the same time")
        val state = fixture.coordinator.state.value
        assertEquals("completed", state.status)
        assertEquals(listOf("read-a", "read-b"), state.messages.filter { it.role == "tool" }.map { it.toolCallId })
    }

    @Test fun lifecycleToolSplitsReadBatchesAndWaitsBeforeLaterReads() = runTest {
        val waitEntered = CompletableDeferred<Unit>()
        val releaseWait = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val calls = listOf(
            ToolCall("read-before", "read", "{}"),
            ToolCall("wait", "wait_agents", "{}"),
            ToolCall("read-after", "read", "{}"),
        )
        val provider = ScriptedProvider { request -> flow {
            if (request.messages.none { it.role == "tool" }) emit(ModelEvent.Completed(AgentMessage("assistant", toolCalls = calls), "tool_calls"))
            else emit(ModelEvent.Completed(AgentMessage("assistant", "done"), "stop"))
        } }
        val lifecycleTools = object : ToolExecutor {
            override val specs = listOf("read", "wait_agents").map {
                ToolSpec(it, it, JsonObject(emptyMap()), ToolEffect.READ)
            }
            override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
                started += call.id
                if (call.name == "wait_agents") {
                    waitEntered.complete(Unit)
                    releaseWait.await()
                }
                return ToolResult(true, buildJsonObject { put("ok", true) })
            }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val coordinator = AgentCoordinator(provider, lifecycleTools, MemoryEvents(), RecordingProcesses(), "session", "model", scope = scope)
        coordinator.send("read around helpers", ExecutionMode.PLAN)
        runCurrent()
        waitEntered.await()

        assertEquals(listOf("read-before", "wait"), started, "the later read must wait for lifecycle completion")
        releaseWait.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("read-before", "wait", "read-after"), started)
        assertEquals("completed", coordinator.state.value.status)
    }

    @Test fun providerUsageIsSumsAcrossRoundsAndSummaryIsJournaled() = runTest {
        val call = ToolCall("call-1", "read", "{}")
        var round = 0
        val provider = ScriptedProvider { _ -> flow {
            emit(ModelEvent.Usage(TokenUsage(promptTokens = 100, completionTokens = 20, cachedPromptTokens = 60)))
            if (round++ == 0) emit(ModelEvent.Completed(AgentMessage("assistant", toolCalls = listOf(call)), "tool_calls"))
            else emit(ModelEvent.Completed(AgentMessage("assistant", "done"), "stop"))
        } }
        val events = MemoryEvents()
        val fixture = fixture(provider, RecordingTools(), events, StandardTestDispatcher(testScheduler))
        fixture.coordinator.send("count", ExecutionMode.WORKSPACE_EDIT)
        advanceUntilIdle()

        val usage = fixture.coordinator.state.value.usage
        assertEquals(200L, usage.promptTokens)
        assertEquals(40L, usage.completionTokens)
        assertEquals(120L, usage.cachedPromptTokens)
        assertEquals(1, fixture.coordinator.state.value.toolCalls)
        val summary = events.records.last { it.type == "run.summary" }.payload
        assertEquals("completed", summary["status"]?.jsonPrimitive?.content)
        assertEquals(2, summary["rounds"]?.jsonPrimitive?.int)
        assertEquals(1, summary["toolCalls"]?.jsonPrimitive?.int)
        assertEquals(200L, summary["promptTokens"]?.jsonPrimitive?.long)
    }

    @Test fun missingUsageInRecoveredRoundIsMarkedAndRestored() = runTest {
        var round = 0
        val provider = ScriptedProvider { _ -> flow {
            if (round++ == 0) emit(ModelEvent.Failed("INCOMPLETE_TOOL_CALL", "cut"))
            else {
                emit(ModelEvent.Usage(TokenUsage(promptTokens = 100, completionTokens = 20)))
                emit(ModelEvent.Completed(AgentMessage("assistant", "done"), "stop"))
            }
        } }
        val events = MemoryEvents()
        val f = fixture(provider, RecordingTools(), events, StandardTestDispatcher(testScheduler))
        f.coordinator.send("count", ExecutionMode.WORKSPACE_EDIT)
        advanceUntilIdle()
        assertTrue(f.coordinator.state.value.usageIncomplete)
        assertEquals(20L, f.coordinator.state.value.usage.completionTokens)
        assertEquals(true, events.records.last { it.type == "run.summary" }.payload["usageIncomplete"]?.jsonPrimitive?.boolean)
        f.coordinator.restore()
        assertTrue(f.coordinator.state.value.usageIncomplete)
    }

    @Test fun taskDeadlineIsNotReportedAsUserCancellation() = runTest {
        val provider = ScriptedProvider { _ -> flow {
            kotlinx.coroutines.delay(601_000)
            emit(ModelEvent.Completed(AgentMessage("assistant", "late"), "stop"))
        } }
        val events = MemoryEvents()
        val f = fixture(provider, RecordingTools(), events, StandardTestDispatcher(testScheduler))
        f.coordinator.send("work", ExecutionMode.WORKSPACE_EDIT)
        advanceUntilIdle()
        assertEquals("failed", f.coordinator.state.value.status)
        assertTrue(f.coordinator.state.value.error.orEmpty().contains("10 минут"))
        assertTrue(events.records.none { it.type == "run.cancelled" })
        assertTrue(events.records.any { it.type == "run.failed" })
    }

    @Test fun roundLimitIsConfigurableAndReported() = runTest {
        val provider = ScriptedProvider { _ -> flow {
            emit(ModelEvent.Completed(AgentMessage("assistant", toolCalls = listOf(ToolCall(newId(), "read", "{}"))), "tool_calls"))
        } }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val coordinator = AgentCoordinator(provider, RecordingTools(), MemoryEvents(), RecordingProcesses(), "session", "model", maxRounds = 2, scope = scope)
        coordinator.send("loop", ExecutionMode.WORKSPACE_EDIT)
        advanceUntilIdle()

        assertEquals("failed", coordinator.state.value.status)
        assertTrue(coordinator.state.value.error.orEmpty().contains("лимит 2"))
        assertEquals(2, provider.calls)
    }

    @Test fun cancelledDeniedProcessRetainsLandingPlanAfterRestore() = runTest {
        val store = MemoryEvents()
        val json = Json { encodeDefaults = true }
        val history = listOf(
            AgentMessage("user", "Build a landing page in HTML and Tailwind"),
            AgentMessage("assistant", "Skywise plan: landing/index.html, pricing and FAQ"),
            AgentMessage("user", "Do it"),
            AgentMessage("assistant", toolCalls = listOf(ToolCall("denied", "process", "{}"))),
            AgentMessage("tool", "{\"ok\":false,\"error\":{\"code\":\"PERMISSION_DENIED\"}}", toolCallId = "denied"),
        )
        history.forEach { store.append("session", "message", json.encodeToJsonElement(AgentMessage.serializer(), it).jsonObject) }
        store.append("session", "run.cancelled", buildJsonObject { put("runId", "old") })
        val requests = mutableListOf<ModelRequest>()
        val provider = ScriptedProvider { request -> flow {
            requests += request
            emit(ModelEvent.Usage(TokenUsage(promptTokens = 1234)))
            emit(ModelEvent.Completed(AgentMessage("assistant", "Continuing Skywise"), "stop"))
        } }
        val fixture = fixture(provider, eventStore = store, dispatcher = StandardTestDispatcher(testScheduler))
        fixture.coordinator.restore()
        fixture.coordinator.send("Permission enabled, try again", ExecutionMode.WORKSPACE_EDIT, allowProcesses = true)
        advanceUntilIdle()
        assertTrue(requests.single().messages.any { it.content?.contains("Skywise plan") == true })
        assertTrue(requests.single().messages.any { it.content?.contains("PERMISSION_DENIED") == true })
        assertEquals(0, fixture.coordinator.state.value.droppedContextMessages)
        val restored = fixture(provider, eventStore = store, dispatcher = StandardTestDispatcher(testScheduler))
        restored.coordinator.restore()
        assertEquals(1234L, restored.coordinator.state.value.contextReportedTokens)
        assertEquals(fixture.coordinator.state.value.contextEstimatedTokens, restored.coordinator.state.value.contextEstimatedTokens)
    }

    @Test fun longHistoryGetsDurableExtractiveMemoryAndConfiguredWindow() = runTest {
        val store = MemoryEvents()
        val json = Json { encodeDefaults = true }
        listOf(
            AgentMessage("user", "Build Skywise landing with pricing"),
            AgentMessage("assistant", "Plan: Tailwind, pricing, FAQ. " + "x".repeat(140_000)),
        ).forEach { store.append("session", "message", json.encodeToJsonElement(AgentMessage.serializer(), it).jsonObject) }
        store.append("session", "run.completed", buildJsonObject { put("runId", "old") })
        var request: ModelRequest? = null
        val provider = ScriptedProvider { r -> flow { request = r; emit(ModelEvent.Completed(AgentMessage("assistant", "ok"), "stop")) } }
        val f = fixture(provider, eventStore = store, dispatcher = StandardTestDispatcher(testScheduler))
        f.coordinator.restore()
        f.coordinator.send("Proceed", ExecutionMode.PLAN)
        advanceUntilIdle()
        assertEquals("completed", f.coordinator.state.value.status)
        assertEquals(2, f.coordinator.state.value.droppedContextMessages)
        assertTrue(request!!.messages.any { it.role == "system" && it.content.orEmpty().contains("Skywise") })
        assertTrue(store.records.any { it.type == "context.summary" && it.payload["method"]?.jsonPrimitive?.content == "extractive" })
        assertEquals(32768, f.coordinator.state.value.contextWindowTokens)
        val wide = fixture(provider, eventStore = store, dispatcher = StandardTestDispatcher(testScheduler), contextWindow = 131072)
        wide.coordinator.restore()
        wide.coordinator.send("Continue with full history", ExecutionMode.PLAN)
        advanceUntilIdle()
        assertEquals("completed", wide.coordinator.state.value.status)
        assertEquals(131072, wide.coordinator.state.value.contextWindowTokens)
        assertEquals(0, wide.coordinator.state.value.droppedContextMessages)
        assertTrue(request!!.messages.any { it.content.orEmpty().length > 100_000 })

    }

    private fun fixture(
        provider: ScriptedProvider,
        tools: ToolExecutor = RecordingTools(),
        eventStore: MemoryEvents = MemoryEvents(),
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = StandardTestDispatcher(),
        model: String = "model",
        instructions: String? = null,
        traces: RunTraceSink? = null,
        contextWindow: Int? = null,
        maxOutputTokens: Int = 4096,
    ): Fixture {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val processes = RecordingProcesses()
        return Fixture(AgentCoordinator(provider, tools, eventStore, processes, "session", model, projectInstructions = instructions, traces = traces, scope = scope, contextWindowTokens = contextWindow, maxOutputTokens = maxOutputTokens), processes)
    }

    private data class Fixture(val coordinator: AgentCoordinator, val processes: RecordingProcesses)

    private class ScriptedProvider(private val script: (ModelRequest) -> Flow<ModelEvent>) : LlmProvider {
        var calls = 0
        override suspend fun probe() = CapabilityReport("test", true, true)
        override fun stream(request: ModelRequest): Flow<ModelEvent> {
            calls++
            return script(request)
        }
    }

    private class RecordingTools : ToolExecutor {
        override val specs = listOf(
            ToolSpec("read", "read", JsonObject(emptyMap()), ToolEffect.READ),
            ToolSpec("ask", "ask", JsonObject(emptyMap()), ToolEffect.USER_INTERACTION),
            ToolSpec("write", "write", JsonObject(emptyMap()), ToolEffect.FILE_WRITE),
            ToolSpec("process", "process", JsonObject(emptyMap()), ToolEffect.PROCESS),
        )
        val executed = mutableListOf<ToolCall>()
        var onExecute: suspend (ToolCall) -> Unit = {}
        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            onExecute(call)
            synchronized(executed) { executed += call }
            return ToolResult(true, buildJsonObject { put("ok", true) })
        }
    }

    private class MemoryEvents : EventStore {
        val records = mutableListOf<StoredEvent>()
        var failUserMessageAppend = false
        var userMessageAppends = 0
        override suspend fun append(sessionId: String, type: String, payload: JsonObject): StoredEvent {
            if (failUserMessageAppend && type == "message" && payload["role"]?.jsonPrimitive?.content == "user") {
                error("synthetic user message persistence failure")
            }
            val event = StoredEvent(sessionId = sessionId, seq = records.size.toLong() + 1, type = type, payload = payload)
            records += event
            if (type == "message" && payload["role"]?.jsonPrimitive?.content == "user") userMessageAppends++
            return event
        }
        override suspend fun read(sessionId: String) = records.filter { it.sessionId == sessionId }
        override suspend fun list() = emptyList<SessionInfo>()
    }

    private class RecordingProcesses : ProcessService {
        val stoppedRuns = mutableListOf<String>()
        override suspend fun start(request: StartProcessRequest, runId: String) = ProcessOutput("p", "", 0, "running")
        override suspend fun read(processId: String, cursor: Long) = ProcessOutput(processId, "", cursor, "running")
        override suspend fun stop(processId: String) = ProcessOutput(processId, "", 0, "stopped")
        override suspend fun stopRun(runId: String) { stoppedRuns += runId }
        override suspend fun close() = Unit
    }
}
