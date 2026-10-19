package dev.chiller.app.di

import dev.chiller.contracts.ProviderModel
import kotlin.test.Test
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
}
