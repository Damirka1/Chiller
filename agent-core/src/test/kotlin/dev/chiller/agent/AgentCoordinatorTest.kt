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
        val fixture = fixture(provider, tools, dispatcher = StandardTestDispatcher(testScheduler))
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
    }

    @Test fun interruptedToolHistoryRejectsContinuationWithoutCallingProvider() = runTest {
        val oldCall = ToolCall("call-interrupted", "write", "{}")
        val events = MemoryEvents().apply {
            append("session", "message", Json.encodeToJsonElement(AgentMessage("assistant", toolCalls = listOf(oldCall))).jsonObject)
            append("session", "tool.prepared", buildJsonObject { put("callId", oldCall.id); put("name", oldCall.name); put("arguments", buildJsonObject { put("path", "src/main.kt") }) })
        }
        val provider = ScriptedProvider { error("provider must not receive an incomplete tool group") }
        val tools = RecordingTools()
        val fixture = fixture(provider, tools, events, StandardTestDispatcher(testScheduler))
        fixture.coordinator.restore()
        assertEquals("interrupted", fixture.coordinator.state.value.status)
        assertTrue(fixture.coordinator.state.value.error.orEmpty().contains("outcome is unknown"))
        fixture.coordinator.send("continue", ExecutionMode.WORKSPACE_EDIT)
        advanceUntilIdle()
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

    private fun fixture(
        provider: ScriptedProvider,
        tools: RecordingTools = RecordingTools(),
        eventStore: MemoryEvents = MemoryEvents(),
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = StandardTestDispatcher(),
    ): Fixture {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val processes = RecordingProcesses()
        return Fixture(AgentCoordinator(provider, tools, eventStore, processes, "session", "model", scope = scope), processes)
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
        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            executed += call
            return ToolResult(true, buildJsonObject { put("ok", true) })
        }
    }

    private class MemoryEvents : EventStore {
        private val records = mutableListOf<StoredEvent>()
        override suspend fun append(sessionId: String, type: String, payload: JsonObject): StoredEvent {
            val event = StoredEvent(sessionId = sessionId, seq = records.size.toLong() + 1, type = type, payload = payload)
            records += event
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
