package dev.chiller.agent

import dev.chiller.contracts.*
import java.math.BigDecimal
import java.math.RoundingMode

/** Conservative USD ledger. Prices and input ceiling must bound the endpoint's actual billing. */
class MonetaryBudget(private val settings: CostBudgetSettings) : RequestBudget {
    private val limit = decimal(settings.limitUsd, "лимит USD", positive = true)
    private val inputRate = decimal(settings.inputUsdPerMillion, "тариф входных токенов")
    private val outputRate = decimal(settings.outputUsdPerMillion, "тариф выходных токенов")
    private val lock = Any()
    private var accounted = BigDecimal.ZERO
    private var reserved = BigDecimal.ZERO
    private var attempts = 0
    private var uncertain = 0
    private var active = 0
    private var exceeded = false

    init {
        if (settings.inputTokenCeiling !in 1024..1_048_576)
            throw ToolException("INVALID_COST_BUDGET", "Предел входных токенов должен быть от 1024 до 1048576")
    }

    override fun beginTask() = synchronized(lock) {
        check(active == 0) { "Предыдущие платные запросы ещё завершаются" }
        accounted = BigDecimal.ZERO
        reserved = BigDecimal.ZERO
        attempts = 0
        uncertain = 0
        exceeded = false
    }

    override fun reserveChat(maxOutputTokens: Int): RequestReservation {
        require(maxOutputTokens > 0)
        return reserve(price(settings.inputTokenCeiling.toLong(), maxOutputTokens.toLong()), chat = true)
    }

    override fun reserveImage(): RequestReservation = reserve(decimal(settings.imageUsdPerRequest, "верхняя цена одного изображения"), chat = false)

    private fun reserve(amount: BigDecimal, chat: Boolean): RequestReservation = synchronized(lock) {
        if (exceeded || accounted + amount > limit)
            throw ToolException("COST_BUDGET_EXHAUSTED", "Денежный бюджет задачи исчерпан: учтено ${usd(accounted)} из ${usd(limit)} USD; следующий запрос требует ${usd(amount)} USD резерва")
        accounted += amount
        reserved += amount
        attempts++
        active++
        object : RequestReservation {
            private var finished = false
            override fun finish(usage: TokenUsage?) = synchronized(lock) {
                if (!finished) {
                    finished = true
                    active--
                    val prompt = usage?.promptTokens?.takeIf { it >= 0 }
                    val completion = usage?.completionTokens?.takeIf { it >= 0 }
                    if (chat && prompt != null && completion != null) {
                        val measured = price(prompt, completion)
                        accounted += measured - amount
                        reserved -= amount
                        if (measured > amount || prompt > settings.inputTokenCeiling) exceeded = true
                    } else {
                        // No trustworthy full usage (including image billing): keep the upper bound.
                        uncertain++
                    }
                }
            }
        }
    }

    override fun snapshot(): CostBudgetSnapshot = synchronized(lock) {
        CostBudgetSnapshot(usd(limit), usd(accounted), usd(reserved), attempts, uncertain, exceeded)
    }

    private fun price(input: Long, output: Long): BigDecimal =
        (inputRate * input.toBigDecimal() + outputRate * output.toBigDecimal()).divide(BigDecimal("1000000"), 12, RoundingMode.CEILING)

    companion object {
        private fun usd(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
        private fun decimal(value: String, label: String, positive: Boolean = false): BigDecimal {
            val parsed = value.takeIf { it.length <= 30 && Regex("\\d+(\\.\\d{1,9})?").matches(it) }?.toBigDecimalOrNull()
            if (parsed == null || parsed < BigDecimal.ZERO || (positive && parsed == BigDecimal.ZERO) || parsed > BigDecimal("1000000000"))
                throw ToolException("INVALID_COST_BUDGET", "Укажите $label: неотрицательное число с точкой${if (positive) ", больше нуля" else ""}")
            return parsed
        }
    }
}

/**
 * Applies [delegate] only to the chat and image models its prices were set for. Any other model is refused with
 * [chatRefusal] / [imageRefusal] instead of being priced with someone else's rates (fail closed).
 */
class BoundBudget(private val delegate: RequestBudget, private val chatRefusal: String?, private val imageRefusal: String?) : RequestBudget {
    override fun beginTask() = delegate.beginTask()
    override fun reserveChat(maxOutputTokens: Int): RequestReservation =
        chatRefusal?.let { throw ToolException("COST_BUDGET_MISMATCH", it) } ?: delegate.reserveChat(maxOutputTokens)
    override fun reserveImage(): RequestReservation =
        imageRefusal?.let { throw ToolException("COST_BUDGET_MISMATCH", it) } ?: delegate.reserveImage()
    override fun snapshot(): CostBudgetSnapshot = delegate.snapshot()
}
