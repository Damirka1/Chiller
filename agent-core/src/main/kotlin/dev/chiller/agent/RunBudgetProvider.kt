package dev.chiller.agent

import dev.chiller.contracts.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect

/** Shared by parent and helpers. Reserve before starting a stream, including concurrent children.
 * Unknown/failed outcomes keep the full reservation; only a measured response with a known terminal outcome refunds it.
 * This limits logical model requests and output tokens, not monetary cost or transport retries.
 */
class RunBudgetProvider(
    private val delegate: LlmProvider,
    private val maxRequests: Int = 48,
    private val maxOutputTokens: Int = 32768,
) : LlmProvider {
    private val lock = Any()
    private var requests = 0
    private var output = 0L
    private var active = 0
    init { require(maxRequests > 0 && maxOutputTokens > 0) }

    fun beginRun() = synchronized(lock) {
        check(active == 0) { "Предыдущие запросы ещё завершаются" }
        requests = 0; output = 0
    }
    override fun outputTokenLimit(model: String): Int = synchronized(lock) {
        minOf(delegate.outputTokenLimit(model) ?: Int.MAX_VALUE, (maxOutputTokens - output).coerceAtLeast(1).toInt())
    }
    override suspend fun probe() = delegate.probe()
    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        val reserved = synchronized(lock) {
            check(requests < maxRequests && output < maxOutputTokens) {
                "Общий бюджет основного агента и помощников исчерпан ($maxRequests запросов / $maxOutputTokens выходных токенов)"
            }
            val cap = minOf(request.maxOutputTokens.toLong(), maxOutputTokens - output).toInt()
            require(cap > 0)
            requests++; output += cap; active++
            cap
        }
        var completed = false
        var measured: Long? = null
        try {
            delegate.stream(request.copy(maxOutputTokens = reserved)).collect { event ->
                if (event is ModelEvent.Usage) event.usage.completionTokens?.let { measured = (measured ?: 0) + it.coerceAtLeast(0) }
                if (event is ModelEvent.Completed || (event is ModelEvent.Failed && event.code in setOf("INCOMPLETE_TOOL_CALL", "MALFORMED_TOOL_CALL"))) completed = true
                emit(event)
            }
        } finally {
            synchronized(lock) {
                active--
                if (completed && measured != null) output += measured!! - reserved
            }
        }
    }
}
