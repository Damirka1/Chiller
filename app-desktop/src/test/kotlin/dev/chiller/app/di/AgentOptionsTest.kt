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

    @Test fun `gemma's recommended sampling is the default and the user's settings still win`() {
        val gemma = dev.chiller.contracts.ProviderModel("quasar", displayName = "Gemma 4 26B A4B")
        val compact = dev.chiller.agent.ModelProfiles.COMPACT
        assertEquals("""{"temperature":1.0,"top_p":0.95,"top_k":64,"parallel_tool_calls":false}""", requestExtrasFor(compact, AgentOptions(), "quasar", gemma).toString())
        assertEquals(1.0, defaultTemperature(compact, "quasar", gemma))
        assertEquals(0.2, defaultTemperature(compact, "nebula"))
        // The user's temperature replaces the recommendation; a null extra drops a field a server rejects.
        assertEquals("""{"temperature":0.5,"top_p":0.95,"parallel_tool_calls":false,"min_p":0.05}""",
            requestExtrasFor(compact, AgentOptions(temperature = 0.5, requestExtras = """{"top_k": null, "min_p": 0.05}"""), "quasar", gemma).toString())
    }

    @Test fun `model maximum lifts the profile cap, a number respects it`() {
        assertEquals(Int.MAX_VALUE, effectiveMaxOutput(AgentOptions(maxOutputTokens = 0), profileCap = 2048))
        assertEquals(2048, effectiveMaxOutput(AgentOptions(maxOutputTokens = 16384), profileCap = 2048))
        assertEquals(32768, effectiveMaxOutput(AgentOptions(maxOutputTokens = 32768), profileCap = null))
    }
}
