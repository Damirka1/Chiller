package dev.chiller.app.di

import dev.chiller.contracts.ProviderModel
import kotlin.test.Test
import dev.chiller.app.ui.withUnshownOutputs
import kotlin.test.assertEquals

class ModelCatalogStateTest {
    // Helpers read the global language; these checks are written against the Russian texts.
    @kotlin.test.BeforeTest fun russian() { dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.RuStrings; dev.chiller.contracts.CoreLanguage.russian = true }
    @kotlin.test.AfterTest fun english() { dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.EnStrings; dev.chiller.contracts.CoreLanguage.russian = false }

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

    @Test fun imagesCanUseAnotherEndpointWithItsCachedCatalog() {
        val qarmet = CachedModelCatalog(listOf(ProviderModel("quasar"), ProviderModel("flux-2-klein", chat = false, imageGeneration = true)), "2026-10-29T00:00:00Z")
        val local = ModelCatalogState("ready", listOf(ProviderModel("gemma")))
        val settings = DesktopSettings(baseUrl = "http://10.0.0.5:8080/v1", model = "gemma",
            modelCatalogs = mapOf("https://gateway.example.com/v1" to qarmet, "http://10.0.0.5:8080/v1" to CachedModelCatalog(local.models, "")),
            customProviders = listOf(CustomProvider("q", "Qarmet", "https://gateway.example.com/v1")))

        assertEquals(null, imageModelFor(settings.agent, imageCatalog(settings, local)), "the chat endpoint cannot draw")
        assertEquals(listOf("https://gateway.example.com/v1" to "Qarmet"), imageProviderChoices(settings))

        val split = settings.copy(agent = AgentOptions(imageBaseUrl = "https://gateway.example.com/v1/").sanitized())
        assertEquals("https://gateway.example.com/v1", imageEndpoint(split))
        assertEquals("flux-2-klein", imageModelFor(split.agent, imageCatalog(split, local))?.id)
        assertEquals("", AgentOptions(imageBaseUrl = "ftp://gateway.example.com").sanitized().imageBaseUrl)
    }

    @Test fun processMadeChartsThatWereNotShownAreFlagged() {
        val result = dev.chiller.app.ui.TurnResult(emptyList(), emptyList(), listOf("shown.png"), emptyList())
        val outside = OutsideChanges(0, added = listOf("weather_graph.png", "shown.png", "build/out.class"))
        assertEquals(listOf("«weather_graph.png» создан, но не показан"), result.withUnshownOutputs(outside).issues)
        assertEquals(result, result.withUnshownOutputs(null))
    }
}
