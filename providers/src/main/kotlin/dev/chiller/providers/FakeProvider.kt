package dev.chiller.providers

import dev.chiller.contracts.CapabilityReport
import dev.chiller.contracts.LlmProvider
import dev.chiller.contracts.ModelEvent
import dev.chiller.contracts.ModelRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicInteger

/** Offline deterministic provider. Each stream call consumes exactly one scripted response. */
class FakeProvider(
    private val responses: List<List<ModelEvent>>,
    private val profileId: String = "fake",
) : LlmProvider {
    private val nextResponse = AtomicInteger(0)

    override suspend fun probe() = CapabilityReport(profileId, tools = true, reasoning = true)

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        val index = nextResponse.getAndIncrement()
        val response = responses.getOrNull(index)
        if (response == null) {
            emit(ModelEvent.Failed("FAKE_SCRIPT_EXHAUSTED", "No scripted fake response remains"))
            return@flow
        }
        response.forEach { emit(it) }
    }
}
