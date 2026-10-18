package dev.chiller.app.di

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentOptionsTest {
    @Test fun `zero means no limit and survives sanitizing, other values stay bounded`() {
        val unlimited = AgentOptions(maxRounds = 0, maxOutputTokens = 0, maxRequests = 0, totalOutputTokens = 0).sanitized()
        assertEquals(listOf(0, 0, 0, 0), listOf(unlimited.maxRounds, unlimited.maxOutputTokens, unlimited.maxRequests, unlimited.totalOutputTokens))
        assertEquals(AgentOptions.UNLIMITED_ROUNDS_BACKSTOP, unlimited.effectiveMaxRounds, "no step limit still has a backstop")
        assertEquals(Int.MAX_VALUE, unlimited.effectiveMaxRequests)
        assertEquals(Int.MAX_VALUE, unlimited.effectiveTotalOutputTokens)
        val typo = AgentOptions(maxRounds = 9999, maxOutputTokens = 5, maxRequests = -3, totalOutputTokens = 99_999_999).sanitized()
        assertEquals(listOf(200, 1024, 1, 4_194_304), listOf(typo.maxRounds, typo.maxOutputTokens, typo.maxRequests, typo.totalOutputTokens))
    }

    @Test fun `model maximum lifts the profile cap, a number respects it`() {
        assertEquals(Int.MAX_VALUE, effectiveMaxOutput(AgentOptions(maxOutputTokens = 0), profileCap = 2048))
        assertEquals(2048, effectiveMaxOutput(AgentOptions(maxOutputTokens = 16384), profileCap = 2048))
        assertEquals(32768, effectiveMaxOutput(AgentOptions(maxOutputTokens = 32768), profileCap = null))
    }
}
