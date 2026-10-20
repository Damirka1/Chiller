package dev.chiller.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelProfilesTest {
    @Test fun `small models are recognised from their id`() {
        listOf("gemma-4-26b-a4b", "google/gemma-4-26B-A4B-it", "qwen2.5-coder-7b-instruct", "llama-3.1-8b", "gemma-4-e4b", "Qwen3-30B-A3B", "phi-4-mini").forEach {
            assertTrue(ModelProfiles.isSmall(it), it)
        }
        listOf("deepseek-flash", "deepseek-v4-pro", "llama-3.1-70b", "gpt-4o-mini", "mistral-small-24b", "gemma-3-27b-it", "Qwen3-235B-A22B").forEach {
            assertFalse(ModelProfiles.isSmall(it), it)
        }
    }

    @Test fun `auto resolves by model and explicit ids win`() {
        assertEquals("compact", ModelProfiles.resolve("auto", "gemma-4-26b-a4b").id)
        assertEquals("full", ModelProfiles.resolve("auto", "deepseek-flash").id)
        assertEquals("compact", ModelProfiles.resolve("compact", "deepseek-flash").id)
        assertEquals("full", ModelProfiles.resolve("unknown", "gemma-4-26b-a4b").id)
        // A gateway alias says nothing about size; the catalog name decides, the id wins when it does say.
        assertEquals("compact", ModelProfiles.resolve("auto", "quasar", "Gemma 4 26B A4B").id)
        assertEquals("compact", ModelProfiles.resolve("auto", "nebula", "Qwen 3.6 35B A3B").id)
        assertEquals("full", ModelProfiles.resolve("auto", "quasar", null).id)
        assertEquals("full", ModelProfiles.resolve("auto", "quasar", "Quasar").id)
        // A branded name keeps working when it also names the model.
        assertEquals("compact", ModelProfiles.resolve("auto", "quasar", "Quasar (Gemma 4 26B A4B)").id)
        assertEquals(ModelProfiles.GEMMA_SAMPLING, ModelProfiles.recommendedSampling("quasar", "Quasar (Gemma 4 26B A4B)"))
        assertEquals("full", ModelProfiles.resolve("auto", "qwen3-235b-a22b", "Tiny 7B").id)
        assertFalse("spawn_agent" in ModelProfiles.COMPACT.tools!!, "compact profile has no helpers")
        assertEquals(2048, ModelProfiles.replyTokens(2048, thinking = false))
        assertEquals(2048 + ModelProfiles.THINKING_HEADROOM_TOKENS, ModelProfiles.replyTokens(2048, thinking = true))
        assertEquals(Int.MAX_VALUE, ModelProfiles.replyTokens(Int.MAX_VALUE, thinking = true), "unlimited stays unlimited")
        assertTrue(ModelProfiles.COMPACT.tools!!.containsAll(setOf("read_artifact", "show_file", "find_symbols")), "compact keeps attachments and results usable")
        assertTrue(ModelProfiles.COMPACT.tools!!.containsAll(setOf("append_file", "delete_file", "move_file", "create_directory")), "compact can write in parts and delete or move with undo")
    }

    @Test fun `gemma gets its recommended sampling instead of the profile temperature`() {
        val gemma = ModelProfiles.requestDefaults(ModelProfiles.COMPACT, "quasar", "Gemma 4 26B A4B")
        assertEquals("""{"temperature":1.0,"top_p":0.95,"top_k":64,"parallel_tool_calls":false}""", gemma.toString())
        assertEquals(ModelProfiles.GEMMA_SAMPLING, ModelProfiles.recommendedSampling("google/gemma-4-26B-A4B-it"))
        // Families without a known recommendation keep the profile's temperature; the full profile leaves it to the server.
        assertEquals("""{"temperature":0.2,"parallel_tool_calls":false}""", ModelProfiles.requestDefaults(ModelProfiles.COMPACT, "nebula", "Qwen 3.6 35B A3B").toString())
        assertEquals("{}", ModelProfiles.requestDefaults(ModelProfiles.FULL, "deepseek-flash").toString())
        assertEquals("""{"temperature":1.0,"top_p":0.95,"top_k":64}""", ModelProfiles.requestDefaults(ModelProfiles.FULL, "gemma-3-27b-it").toString())
    }
}
