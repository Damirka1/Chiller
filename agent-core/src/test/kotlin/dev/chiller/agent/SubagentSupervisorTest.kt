package dev.chiller.agent

import dev.chiller.contracts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import kotlin.test.*

class SubagentSupervisorTest {
    private class Events : EventStore {
        val list = mutableListOf<StoredEvent>()
        var failWhen: (JsonObject) -> Boolean = { false }
        var beforeAppend: suspend (JsonObject) -> Unit = {}
        override suspend fun append(sessionId: String, type: String, payload: JsonObject): StoredEvent {
            beforeAppend(payload)
            if (failWhen(payload)) error("journal unavailable")
            return StoredEvent(sessionId = sessionId, seq = list.size.toLong(), type = type, payload = payload).also { synchronized(list) { list += it } }
        }
        override suspend fun read(sessionId: String) = synchronized(list) { list.toList() }
        override suspend fun list() = emptyList<SessionInfo>()
    }

    /** Read tools over an in-memory tree; records every path it was asked for. */
    private class Files : ToolExecutor {
        val calls = mutableListOf<String>()
        override val specs = listOf("list_files", "search_text", "read_file", "edit_file", "web_search").map {
            ToolSpec(it, it, buildJsonObject { put("type", "object") }, when (it) { "edit_file" -> ToolEffect.FILE_WRITE; "web_search" -> ToolEffect.NETWORK; else -> ToolEffect.READ })
        }
        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            synchronized(calls) { calls += "${call.name}:${Json.parseToJsonElement(call.arguments).jsonObject["path"]?.jsonPrimitive?.content}" }
            return ToolResult(true, buildJsonObject { put("content", "1 | fun main() = Unit") })
        }
    }

    /** Child script: the goal decides what the child does; "slow" children wait for a message or cancel. */
    private class Script : LlmProvider {
        override suspend fun probe() = CapabilityReport("test", true, false)
        override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
            val goal = request.messages.first { it.role == "user" }.content.orEmpty()
            val tools = request.messages.count { it.role == "tool" }
            val inbox = request.messages.filter { it.role == "user" && it.content!!.startsWith("Message from the lead agent") }
            when {
                "outside" in goal && tools == 0 -> emit(call("read_file", """{"path":"src2/secret.kt"}"""))
                "dotdot" in goal && tools == 0 -> emit(call("read_file", """{"path":"src/../etc"}"""))
                "write" in goal && tools == 0 -> emit(call("edit_file", """{"path":"src/a.kt"}"""))
                "web" in goal && tools == 0 -> emit(call("web_search", """{"query":"weather Temirtau"}"""))
                "slow" in goal && inbox.isEmpty() -> { delay(200); emit(call("search_text", """{"query":"x","path":"src"}""")) }
                tools == 0 -> emit(call("read_file", """{"path":"src/App.kt"}"""))
                else -> { emit(ModelEvent.Usage(TokenUsage(promptTokens = 100, completionTokens = 10))); emit(ModelEvent.Completed(AgentMessage("assistant", "Report for: ${goal.lineSequence().first()} · inbox=${inbox.size}"), "stop")) }
            }
        }
        private var n = 0
        private fun call(name: String, args: String) = ModelEvent.Completed(AgentMessage("assistant", null, listOf(ToolCall("c${n++}", name, args))), "tool_calls")
    }

    private fun supervisor(scope: CoroutineScope, events: Events = Events(), files: Files = Files()) =
        SubagentSupervisor(Script(), files, events, "s", "m", scope, childTimeoutMs = 5_000)
    private val ctx = ToolContext("s", "run-1", ExecutionMode.PLAN)

    @Test fun `two children read in parallel and report`() = runBlocking {
        val files = Files()
        val sup = supervisor(this, files = files)
        sup.begin("run-1")
        val a = sup.spawn(ctx, SubagentRequest("a", "Explain src", allowedPaths = listOf("src")))
        val b = sup.spawn(ctx, SubagentRequest("b", "Explain app", allowedPaths = listOf(".")))
        withTimeout(5_000) { var cursor = 0L; while (sup.state.value.any { !it.status.isTerminal }) cursor = sup.wait(ctx, cursor, 1_000).cursor }
        val done = sup.list(ctx).agents
        assertEquals(setOf(SubagentStatus.COMPLETED), done.map { it.status }.toSet())
        assertTrue(sup.result(ctx, a.id).result!!.startsWith("Report for: Task: Explain src"))
        assertEquals(listOf("src/App.kt"), sup.result(ctx, b.id).readPaths)
        assertEquals(2, files.calls.size)
        assertEquals(100L, sup.result(ctx, a.id).usage.promptTokens, "a child's provider usage is recorded on its snapshot")
        sup.finish("run-1")
    }

    @Test fun `scope guard blocks neighbours, dotdot and writes`() = runBlocking {
        val files = Files()
        val sup = supervisor(this, files = files)
        sup.begin("run-1")
        listOf("outside", "dotdot", "write").forEach { sup.spawn(ctx, SubagentRequest(it, "$it check", allowedPaths = listOf("src"))) }
        withTimeout(5_000) { var cursor = 0L; while (sup.state.value.any { !it.status.isTerminal }) cursor = sup.wait(ctx, cursor, 1_000).cursor }
        assertTrue(files.calls.isEmpty(), "no tool reached the workspace: ${files.calls}")
        assertTrue(sup.list(ctx).agents.all { it.failedToolCalls == 1 && it.status == SubagentStatus.COMPLETED })
        assertTrue(SubagentSupervisor.inScope("src/a.kt", "src") && !SubagentSupervisor.inScope("src2/a.kt", "src"))
        assertFailsWith<ToolException> { SubagentSupervisor.normalizeScope("/etc") }
        sup.finish("run-1")
    }

    @Test fun `helpers search the web only when the lead's run may`() = runBlocking {
        for (network in listOf(true, false)) {
            val files = Files()
            val sup = supervisor(this, files = files)
            val run = ctx.copy(allowNetwork = network)
            sup.begin("run-1")
            val child = sup.spawn(run, SubagentRequest("w", "web lookup", allowedPaths = listOf("src")))
            withTimeout(5_000) { var cursor = 0L; while (sup.state.value.any { !it.status.isTerminal }) cursor = sup.wait(run, cursor, 1_000).cursor }
            assertEquals(if (network) listOf("web_search:null") else emptyList(), files.calls, "network=$network")
            assertEquals(if (network) 0 else 1, sup.result(run, child.id).failedToolCalls)
            sup.finish("run-1")
        }
    }

    @Test fun `wait returns when a helper finishes, not on its progress`() = runBlocking {
        val sup = supervisor(this)
        sup.begin("run-1")
        sup.spawn(ctx, SubagentRequest("s", "slow scan", allowedPaths = listOf("src")))
        val page = withTimeout(5_000) { sup.wait(ctx, 0, 5_000) }
        assertTrue(page.agents.single().status.isTerminal, "one wait covers the helper's rounds: ${page.agents.single().status}")
        sup.finish("run-1")
    }

    @Test fun `dedup, conflict, limit and stale run`() = runBlocking {
        val sup = supervisor(this)
        sup.begin("run-1")
        val first = sup.spawn(ctx, SubagentRequest("a", "Explain src"))
        assertEquals(first.id, sup.spawn(ctx, SubagentRequest("a", "Explain src")).id)
        assertEquals("CONFLICT", assertFailsWith<ToolException> { sup.spawn(ctx, SubagentRequest("a", "Something else")) }.code)
        sup.spawn(ctx, SubagentRequest("b", "x")); sup.spawn(ctx, SubagentRequest("c", "y"))
        assertEquals("LIMIT_REACHED", assertFailsWith<ToolException> { sup.spawn(ctx, SubagentRequest("d", "z")) }.code)
        assertEquals("STALE_RUN", assertFailsWith<ToolException> { sup.list(ctx.copy(runId = "other")) }.code)
        sup.finish("run-1")
        assertEquals("STALE_RUN", assertFailsWith<ToolException> { sup.spawn(ctx, SubagentRequest("e", "late")) }.code)
    }

    @Test fun `messages arrive before the next round and cancel wins`() = runBlocking {
        val sup = supervisor(this)
        sup.begin("run-1")
        val slow = sup.spawn(ctx, SubagentRequest("slow", "slow task", allowedPaths = listOf("src")))
        withTimeout(2_000) { while (sup.state.value.first().status != SubagentStatus.RUNNING) delay(5) }
        val receipt = sup.send(ctx, slow.id, "m1", "Focus on errors")
        assertEquals(receipt, sup.send(ctx, slow.id, "m1", "Focus on errors"))
        assertEquals("CONFLICT", assertFailsWith<ToolException> { sup.send(ctx, slow.id, "m1", "other") }.code)
        withTimeout(5_000) { var cursor = 0L; while (!sup.result(ctx, slow.id).status.isTerminal) cursor = sup.wait(ctx, cursor, 1_000).cursor }
        val done = sup.result(ctx, slow.id)
        assertEquals(SubagentStatus.COMPLETED, done.status)
        assertTrue(done.result!!.endsWith("inbox=1"), done.result)
        assertEquals(1, done.processedMessages)

        val another = sup.spawn(ctx, SubagentRequest("slow-2", "slow again", allowedPaths = listOf("src")))
        val cancelled = sup.cancel(ctx, another.id)
        assertEquals(SubagentStatus.CANCELLED, cancelled.status)
        delay(400)
        assertEquals(SubagentStatus.CANCELLED, sup.result(ctx, another.id).status, "a late completion cannot overwrite cancel")
        sup.finish("run-1")
    }

    @Test fun `finish stops orphans and restore marks them interrupted`() = runBlocking {
        val events = Events()
        val sup = supervisor(this, events)
        sup.begin("run-1")
        sup.spawn(ctx, SubagentRequest("slow", "slow task", allowedPaths = listOf("src")))
        withTimeout(2_000) { while (sup.state.value.first().status != SubagentStatus.RUNNING) delay(5) }
        sup.finish("run-1")
        assertEquals(SubagentStatus.CANCELLED, sup.state.value.single().status)

        // A journal that ends with a running child, as after a crash.
        val crashed = Events()
        val running = SubagentSnapshot("x", "run-0", "t", "goal", "m", SubagentStatus.RUNNING, revision = 7)
        crashed.append("s", "subagent.state", Json.encodeToJsonElement(SubagentSnapshot.serializer(), running).jsonObject)
        val restored = supervisor(this, crashed)
        restored.restore(crashed.read("s"))
        assertEquals(SubagentStatus.INTERRUPTED, restored.state.value.single().status)
    }

    @Test fun `failed durable append does not publish or leave a queued child`() = runBlocking {
        val events = Events().apply { failWhen = { true } }
        val sup = supervisor(this, events)
        sup.begin("run-1")
        assertFailsWith<IllegalStateException> { sup.spawn(ctx, SubagentRequest("a", "Explain src")) }
        assertTrue(sup.state.value.isEmpty(), "an unjournaled child must not become visible")
        events.failWhen = { false }
        assertEquals(SubagentStatus.QUEUED, sup.spawn(ctx, SubagentRequest("a", "Explain src")).status)
        sup.finish("run-1")
    }

    @Test fun `failed message journal append rolls back mailbox acceptance`() = runBlocking {
        val events = Events()
        val sup = supervisor(this, events)
        sup.begin("run-1")
        val child = sup.spawn(ctx, SubagentRequest("slow", "slow task", allowedPaths = listOf("src"), maxRounds = 3))
        withTimeout(2_000) { while (sup.state.value.first().status != SubagentStatus.RUNNING) delay(5) }
        events.failWhen = { it["acceptedMessages"]?.jsonPrimitive?.intOrNull == 1 }
        assertFailsWith<IllegalStateException> { sup.send(ctx, child.id, "m1", "Focus on errors") }
        assertEquals(0, sup.result(ctx, child.id).acceptedMessages)
        events.failWhen = { false }
        val receipt = sup.send(ctx, child.id, "m1", "Focus on errors")
        withTimeout(5_000) { var cursor = 0L; while (!sup.result(ctx, child.id).status.isTerminal) cursor = sup.wait(ctx, cursor, 1_000).cursor }
        assertEquals(1L, receipt.sequence)
        assertEquals(1, sup.result(ctx, child.id).processedMessages)
        sup.finish("run-1")
    }

    @Test fun `message waiting at final round boundary is rejected as too late`() = runBlocking {
        val boundaryEntered = CompletableDeferred<Unit>()
        val releaseBoundary = CompletableDeferred<Unit>()
        val events = Events().apply {
            beforeAppend = { payload ->
                if (payload["rounds"]?.jsonPrimitive?.intOrNull == 2) {
                    boundaryEntered.complete(Unit)
                    releaseBoundary.await()
                }
            }
        }
        val provider = object : LlmProvider {
            private var requests = 0
            override suspend fun probe() = CapabilityReport("test", true, false)
            override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
                if (requests++ == 0) emit(ModelEvent.Completed(AgentMessage("assistant", toolCalls = listOf(ToolCall("first", "read_file", """{"path":"src/a.kt"}"""))), "tool_calls"))
                else emit(ModelEvent.Completed(AgentMessage("assistant", "final"), "stop"))
            }
        }
        val sup = SubagentSupervisor(provider, Files(), events, "s", "m", this, childTimeoutMs = 5_000)
        sup.begin("run-1")
        val child = sup.spawn(ctx, SubagentRequest("final", "final round", allowedPaths = listOf("src"), maxRounds = 2))
        withTimeout(2_000) { boundaryEntered.await() }

        val sending = async { runCatching { sup.send(ctx, child.id, "late", "include one more detail") } }
        yield() // Ensure the send is queued behind the in-progress durable round transition.
        assertEquals(false, sending.isCompleted)
        releaseBoundary.complete(Unit)
        val result = sending.await()
        assertTrue(result.isFailure)
        assertTrue((result.exceptionOrNull() as ToolException).code in setOf("TOO_LATE", "AGENT_FINISHED"))
        withTimeout(2_000) {
            while (!sup.result(ctx, child.id).status.isTerminal) delay(1)
        }
        assertEquals(0, sup.result(ctx, child.id).acceptedMessages)
        assertEquals(0, sup.result(ctx, child.id).processedMessages)
        sup.finish("run-1")
    }
}
