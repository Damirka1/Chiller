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
        assertEquals("full", ModelProfiles.resolve("auto", "qwen3-235b-a22b", "Tiny 7B").id)
        assertFalse("spawn_agent" in ModelProfiles.COMPACT.tools!!, "compact profile has no helpers")
        assertTrue(ModelProfiles.COMPACT.tools!!.containsAll(setOf("read_artifact", "show_file", "find_symbols")), "compact keeps attachments and results usable")
    }
}
