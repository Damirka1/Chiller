package dev.chiller.agent

import dev.chiller.contracts.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class RunBudgetProviderTest {
    private class FakeProvider(
        private val block: suspend (ModelRequest) -> List<ModelEvent>,
    ) : LlmProvider {
        val requests = mutableListOf<ModelRequest>()
        override suspend fun probe() = CapabilityReport("test", true, false)
        override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
            synchronized(requests) { requests += request }
            block(request).forEach { emit(it) }
        }
    }

    private fun request(maxOutput: Int = 8) = ModelRequest("test", emptyList(), maxOutputTokens = maxOutput)
    private fun completed(tokens: Long? = null): List<ModelEvent> = buildList {
        if (tokens != null) add(ModelEvent.Usage(TokenUsage(completionTokens = tokens)))
        add(ModelEvent.Completed(AgentMessage("assistant", "ok"), "stop"))
    }

    @Test fun `parent and children reserve from one shared cap concurrently`() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val bothStarted = CompletableDeferred<Unit>()
        var started = 0
        val delegate = FakeProvider {
            synchronized(this) { started++; if (started == 2) bothStarted.complete(Unit) }
            release.await()
            completed(1)
        }
        val budget = RunBudgetProvider(delegate, maxRequests = 4, maxOutputTokens = 10)
        budget.beginRun()

        // Parent and child streams reserve before either one completes.
        val parent = async { budget.stream(request(8)).toList() }
        val child = async { budget.stream(request(8)).toList() }
        bothStarted.await()
        assertEquals(listOf(8, 2), synchronized(delegate.requests) { delegate.requests.map { it.maxOutputTokens }.sortedDescending() })
        release.complete(Unit)
        parent.await(); child.await()
    }

    @Test fun `measured partial response refunds reserve and clamps next request`() = runBlocking {
        var callIndex = 0
        val delegate = FakeProvider { completed(if (callIndex++ == 0) 6 else 4) }
        // The fake response reports fewer tokens than reserved on the first completed call.
        val budget = RunBudgetProvider(delegate, maxRequests = 5, maxOutputTokens = 10)
        budget.beginRun()
        budget.stream(request(8)).toList()
        budget.stream(request(9)).toList()
        assertEquals(listOf(8, 4), synchronized(delegate.requests) { delegate.requests.map { it.maxOutputTokens } })
    }

    @Test fun `unknown and failed outcomes retain full reservation`() = runBlocking {
        var first = true
        val delegate = FakeProvider {
            if (first) {
                first = false
                listOf(ModelEvent.Usage(TokenUsage(completionTokens = 1)), ModelEvent.Failed("TIMEOUT", "unknown outcome"))
            } else completed(2)
        }
        val budget = RunBudgetProvider(delegate, maxRequests = 4, maxOutputTokens = 10)
        budget.beginRun()
        budget.stream(request(8)).toList()
        budget.stream(request(8)).toList()
        assertEquals(listOf(8, 2), synchronized(delegate.requests) { delegate.requests.map { it.maxOutputTokens } })
    }

    @Test fun `completion without measured usage does not refund reservation`() = runBlocking {
        val delegate = FakeProvider { listOf(ModelEvent.Completed(AgentMessage("assistant", "ok"), "stop")) }
        val budget = RunBudgetProvider(delegate, maxRequests = 3, maxOutputTokens = 10)
        budget.beginRun()
        budget.stream(request(8)).toList()
        budget.stream(request(8)).toList()
        assertEquals(listOf(8, 2), synchronized(delegate.requests) { delegate.requests.map { it.maxOutputTokens } })
    }

    @Test fun `request cap is shared and independent of refunded output`() = runBlocking {
        val delegate = FakeProvider { completed(0) }
        val budget = RunBudgetProvider(delegate, maxRequests = 1, maxOutputTokens = 20)
        budget.beginRun()
        budget.stream(request(4)).toList()

        val failure = runCatching { budget.stream(request(4)).toList() }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(1, synchronized(delegate.requests) { delegate.requests.size })
    }

    @Test fun `beginRun rejects active streams and succeeds after they finish`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val delegate = FakeProvider {
            entered.complete(Unit)
            release.await()
            completed(1)
        }
        val budget = RunBudgetProvider(delegate, maxRequests = 2, maxOutputTokens = 10)
        budget.beginRun()
        val active = async { budget.stream(request()).toList() }
        entered.await()

        assertFailsWith<IllegalStateException> { budget.beginRun() }
        release.complete(Unit)
        active.await()
        budget.beginRun()
        budget.stream(request()).toList()
        assertEquals(2, synchronized(delegate.requests) { delegate.requests.size })
    }
}
