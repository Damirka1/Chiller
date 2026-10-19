package dev.chiller.app.di

import dev.chiller.contracts.ProviderModel
import kotlin.test.Test
import dev.chiller.app.ui.withUnshownOutputs
import kotlin.test.assertEquals

class ModelCatalogStateTest {
    @Test fun imageOnlyModelsAreNotOfferedForChat() {
        val catalog = ModelCatalogState("ready", listOf(
            ProviderModel("flux-2-klein", chat = false, imageGeneration = true),
            ProviderModel("quasar", chat = true),
            ProviderModel("plain"),
        ))
        assertEquals(listOf("quasar", "plain"), catalog.chatModels.map { it.id })
    }

    @Test fun imageModelIsPickedFromTheCatalogUnlessTurnedOffOrNamed() {
        val catalog = ModelCatalogState("ready", listOf(ProviderModel("quasar", chat = true), ProviderModel("flux-2-klein", chat = false, imageGeneration = true, imageEditing = true)))
        assertEquals("flux-2-klein", imageModelFor(AgentOptions(), catalog)?.id)
        assertEquals(null, imageModelFor(AgentOptions(imageModel = "off"), catalog))
        assertEquals(null, imageModelFor(AgentOptions(), ModelCatalogState("ready", listOf(ProviderModel("quasar")))))
        assertEquals("dall-e-3", imageModelFor(AgentOptions(imageModel = "dall-e-3"), catalog)?.id, "a typed id works without a listing")
    }

    @Test fun processMadeChartsThatWereNotShownAreFlagged() {
        val result = dev.chiller.app.ui.TurnResult(emptyList(), emptyList(), listOf("shown.png"), emptyList())
        val outside = OutsideChanges(0, added = listOf("weather_graph.png", "shown.png", "build/out.class"))
        assertEquals(listOf("«weather_graph.png» создан, но не показан"), result.withUnshownOutputs(outside).issues)
        assertEquals(result, result.withUnshownOutputs(null))
    }
}
