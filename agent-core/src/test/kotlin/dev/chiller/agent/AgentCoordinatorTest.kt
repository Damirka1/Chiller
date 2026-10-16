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
        assertEquals("assistant", transcript[2].role)
        assertEquals("tool", transcript[3].role)
        assertEquals("call-1", transcript[3].toolCallId)
        assertEquals("completed", fixture.coordinator.state.value.status)
    }

    @Test fun interruptedToolHistoryRejectsContinuationWithoutCallingProvider() = runTest {
        val oldCall = ToolCall("call-interrupted", "write", "{}")
        val events = MemoryEvents().apply {
            append("session", "message", Json.encodeToJsonElement(AgentMessage("assistant", toolCalls = listOf(oldCall))).jsonObject)
        }
        val provider = ScriptedProvider { error("provider must not receive an incomplete tool group") }
        val fixture = fixture(provider, eventStore = events, dispatcher = StandardTestDispatcher(testScheduler))
        fixture.coordinator.restore()
        assertEquals("interrupted", fixture.coordinator.state.value.status)
        fixture.coordinator.send("continue", ExecutionMode.WORKSPACE_EDIT)
        advanceUntilIdle()
        assertEquals(0, provider.calls)
        assertEquals("failed", fixture.coordinator.state.value.status)
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
