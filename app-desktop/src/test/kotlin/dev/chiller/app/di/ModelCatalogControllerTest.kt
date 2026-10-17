package dev.chiller.app.di

import dev.chiller.contracts.ModelCatalogClient
import dev.chiller.contracts.ModelCatalogResult
import dev.chiller.contracts.ProviderModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCatalogControllerTest {
    @TempDir lateinit var directory: Path

    @Test fun `explicit refresh populates the catalog and merges discovered models`() = runBlocking {
        val client = FakeCatalog(ModelCatalogResult(listOf(ProviderModel("deepseek-flash"), ProviderModel("deepseek-chat"))))
        val controller = DesktopController(directory, initialCredential = "synthetic-key", catalogClient = client)
        try {
            controller.initialize()
            controller.configure("https://api.deepseek.com", "deepseek-flash", false, "synthetic-key")

            assertEquals("idle", controller.state.value.catalog.status)
            assertEquals(0, client.calls, "opening the app must not call the provider")

            controller.refreshModels().join()

            assertEquals("ready", controller.state.value.catalog.status)
            assertEquals(listOf("deepseek-flash", "deepseek-chat"), controller.state.value.catalog.models.map { it.id })
            assertEquals(listOf("deepseek-flash", "deepseek-chat"), controller.configuredModels())
            assertFalse(controller.state.value.catalog.stale)
        } finally { controller.close() }
    }

    @Test fun `failure keeps the previous list and marks it stale`() = runBlocking {
        val client = FakeCatalog(ModelCatalogResult(listOf(ProviderModel("deepseek-flash"))))
        val controller = DesktopController(directory, initialCredential = "synthetic-key", catalogClient = client)
        try {
            controller.initialize()
            controller.configure("https://api.deepseek.com", "deepseek-flash", false, "synthetic-key")
            controller.refreshModels().join()

            client.respond(ModelCatalogResult(errorCode = "UNSUPPORTED", message = "no listing"))
            controller.refreshModels().join()

            val catalog = controller.state.value.catalog
            assertEquals("failed", catalog.status)
            assertTrue(catalog.stale)
            assertEquals(listOf("deepseek-flash"), catalog.models.map { it.id })
            assertEquals("Этот адрес не отдаёт список моделей — укажите ID вручную", catalog.message)
        } finally { controller.close() }
    }

    @Test fun `late result for the previous endpoint is discarded`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val client = object : ModelCatalogClient {
            var calls = 0
            override suspend fun discover(baseUrl: String, apiKey: String?): ModelCatalogResult {
                calls++
                gate.await()
                return ModelCatalogResult(listOf(ProviderModel("late-model")))
            }
        }
        val controller = DesktopController(directory, initialCredential = "synthetic-key", catalogClient = client)
        try {
            controller.initialize()
            controller.configure("https://api.deepseek.com", "deepseek-flash", false, "synthetic-key")
            val job = controller.refreshModels()
            controller.configure("https://gateway.example.test", "other-model", false, "other-key")
            gate.complete(Unit)
            job.join()

            assertEquals("idle", controller.state.value.catalog.status)
            assertTrue(controller.state.value.catalog.models.isEmpty())
        } finally { controller.close() }
    }

    @Test fun `endpoint change clears the catalog and the key`() = runBlocking {
        val client = FakeCatalog(ModelCatalogResult(listOf(ProviderModel("deepseek-flash"))))
        val controller = DesktopController(directory, initialCredential = "synthetic-key", catalogClient = client)
        try {
            controller.initialize()
            controller.configure("https://api.deepseek.com", "deepseek-flash", false, "synthetic-key")
            controller.refreshModels().join()
            assertEquals("ready", controller.state.value.catalog.status)

            controller.configure("https://gateway.example.test", "other-model", false, null)

            assertEquals("idle", controller.state.value.catalog.status)
            assertTrue(controller.state.value.catalog.models.isEmpty())
            assertFalse(controller.state.value.apiKeyConfigured)
        } finally { controller.close() }
    }

    private class FakeCatalog(private var next: ModelCatalogResult) : ModelCatalogClient {
        var calls = 0
        override suspend fun discover(baseUrl: String, apiKey: String?): ModelCatalogResult {
            calls++
            return next
        }
        fun respond(result: ModelCatalogResult) { next = result }
    }
}
