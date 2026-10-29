package dev.chiller.app.di

import dev.chiller.contracts.ModelCatalogClient
import dev.chiller.contracts.ModelCatalogResult
import dev.chiller.contracts.ProviderModel
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ModelCatalogControllerTest {
    // Helpers read the global language; these checks are written against the Russian texts.
    @kotlin.test.BeforeTest fun russian() { dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.RuStrings }
    @kotlin.test.AfterTest fun english() { dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.EnStrings }

    @TempDir lateinit var directory: Path

    private suspend fun awaitStatus(controller: DesktopController, status: String) = withTimeout(5_000) {
        while (controller.state.value.catalog.status != status) delay(10)
    }

    @Test fun `startup discovers models once without running a paid connection check`() = runBlocking<Unit> {
        val client = FakeCatalog(ModelCatalogResult(listOf(ProviderModel("deepseek-flash"), ProviderModel("deepseek-chat"))))
        val checker = object : ConnectionChecker {
            override suspend fun check(baseUrl: String, model: String, key: String, thinking: Boolean): ConnectionCheckResult = error("Startup must not run inference")
        }
        val controller = DesktopController(directory, initialCredential = "synthetic-key", catalogClient = client, connectionChecker = checker)
        try {
            controller.initialize()
            awaitStatus(controller, "ready")
            assertEquals(1, client.calls)
            assertEquals("reachable", controller.state.value.connection.status, "a listed catalog shows the server answers; it is not a paid check")
            assertEquals(listOf("deepseek-flash", "deepseek-chat"), controller.configuredModels())
            controller.selectModel("deepseek-chat")
            assertEquals(1, client.calls, "changing only the model reuses the endpoint catalog")
            controller.refreshModels().join()
            assertEquals(2, client.calls, "manual refresh still works")
        } finally { controller.close() }
    }

    @Test fun `failure keeps the previous list and marks it stale`() = runBlocking<Unit> {
        val client = FakeCatalog(ModelCatalogResult(listOf(ProviderModel("deepseek-flash"))))
        val controller = DesktopController(directory, initialCredential = "synthetic-key", catalogClient = client)
        try {
            controller.initialize()
            awaitStatus(controller, "ready")
            client.respond(ModelCatalogResult(errorCode = "UNSUPPORTED", message = "no listing"))
            controller.refreshModels().join()
            val catalog = controller.state.value.catalog
            assertEquals("failed", catalog.status)
            assertTrue(catalog.stale)
            assertEquals(listOf("deepseek-flash"), catalog.models.map { it.id })
            assertEquals("Этот адрес не отдаёт список моделей — укажите ID вручную", catalog.message)
        } finally { controller.close() }
    }

    @Test fun `cached models survive restart and a failed startup request`() = runBlocking<Unit> {
        val first = DesktopController(directory, initialCredential = "synthetic-key", catalogClient = FakeCatalog(ModelCatalogResult(listOf(ProviderModel("from-server", "Server model")))))
        try { first.initialize(); awaitStatus(first, "ready") } finally { first.close() }
        val gate = CompletableDeferred<Unit>()
        val second = DesktopController(directory, initialCredential = "synthetic-key", catalogClient = object : ModelCatalogClient {
            override suspend fun discover(baseUrl: String, apiKey: String?): ModelCatalogResult {
                gate.await()
                return ModelCatalogResult(errorCode = "NETWORK")
            }
        })
        try {
            second.initialize()
            assertEquals(listOf("from-server"), second.state.value.catalog.models.map { it.id })
            assertTrue(second.state.value.catalog.stale)
            gate.complete(Unit)
            awaitStatus(second, "failed")
            assertEquals("Server model", second.state.value.catalog.models.single().displayName)
        } finally { gate.complete(Unit); second.close() }
    }

    @Test fun `late result for the previous endpoint is discarded`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val client = object : ModelCatalogClient {
            override suspend fun discover(baseUrl: String, apiKey: String?): ModelCatalogResult {
                if (baseUrl == "https://api.deepseek.com") {
                    started.complete(Unit)
                    withContext(NonCancellable) { gate.await() }
                    return ModelCatalogResult(listOf(ProviderModel("late-model")))
                }
                return ModelCatalogResult(listOf(ProviderModel("new-model")))
            }
        }
        val controller = DesktopController(directory, initialCredential = "synthetic-key", catalogClient = client)
        try {
            controller.initialize()
            withTimeout(5_000) { started.await() }
            controller.configure("https://gateway.example.test/v1", "other-model", false, "other-key")
            awaitStatus(controller, "ready")
            gate.complete(Unit)
            // Joining a manual refresh drains the active request; the invalidated old result cannot publish.
            controller.refreshModels().join()
            assertEquals(listOf("new-model"), controller.state.value.catalog.models.map { it.id })
        } finally { gate.complete(Unit); controller.close() }
    }

    @Test fun `missing key does not issue an automatic request and adding one starts discovery`() = runBlocking<Unit> {
        val client = FakeCatalog(ModelCatalogResult(listOf(ProviderModel("deepseek-flash"))))
        val controller = DesktopController(directory, initialCredential = null, catalogClient = client)
        try {
            controller.initialize()
            assertEquals("idle", controller.state.value.catalog.status)
            assertEquals(0, client.calls)
            controller.configure("https://api.deepseek.com", "deepseek-flash", false, "synthetic-key")
            awaitStatus(controller, "ready")
            assertEquals(1, client.calls)
            controller.configure("https://gateway.example.test", "other-model", false, null)
            assertEquals("idle", controller.state.value.catalog.status)
            assertTrue(controller.state.value.catalog.models.isEmpty())
            assertFalse(controller.state.value.apiKeyConfigured)
            assertEquals(1, client.calls)
        } finally { controller.close() }
    }

    @Test fun `changing the key reloads models and discards the old credential's cache`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val client = object : ModelCatalogClient {
            override suspend fun discover(baseUrl: String, apiKey: String?): ModelCatalogResult {
                if (apiKey == "new-synthetic-key") {
                    gate.await()
                    return ModelCatalogResult(listOf(ProviderModel("new-account-model")))
                }
                return ModelCatalogResult(listOf(ProviderModel("old-account-model")))
            }
        }
        val controller = DesktopController(directory, initialCredential = "synthetic-key", catalogClient = client)
        try {
            controller.initialize()
            awaitStatus(controller, "ready")
            controller.configure("https://api.deepseek.com/v1", "deepseek-flash", false, null)
            awaitStatus(controller, "ready")
            assertEquals(2, controller.state.value.settings.modelCatalogs.size)
            controller.configure("https://api.deepseek.com", "deepseek-flash", false, "new-synthetic-key")
            assertTrue(controller.state.value.catalog.models.isEmpty())
            assertTrue(controller.state.value.settings.modelCatalogs.isEmpty(), "a key belongs to the origin, so all of its endpoint caches are invalidated")
            gate.complete(Unit)
            awaitStatus(controller, "ready")
            assertEquals("new-account-model", controller.state.value.catalog.models.single().id)
        } finally { gate.complete(Unit); controller.close() }
    }

    @Test fun `loopback catalog automatically loads without a key`() = runBlocking<Unit> {
        val client = FakeCatalog(ModelCatalogResult(listOf(ProviderModel("local-model"))))
        val controller = DesktopController(directory, initialCredential = null, catalogClient = client)
        try {
            controller.initialize()
            controller.configure("http://127.0.0.1:12345/v1", "local-model", false, null)
            awaitStatus(controller, "ready")
            assertEquals(1, client.calls)
            assertEquals(listOf("local-model"), controller.state.value.catalog.models.map { it.id })
        } finally { controller.close() }
    }

    @Test fun `unrelated workspace changes cannot strand a loading catalog`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<ModelCatalogResult>()
        val client = object : ModelCatalogClient {
            override suspend fun discover(baseUrl: String, apiKey: String?): ModelCatalogResult {
                started.complete(Unit)
                return result.await()
            }
        }
        val controller = DesktopController(directory, initialCredential = "synthetic-key", catalogClient = client)
        try {
            controller.initialize()
            withTimeout(5_000) { started.await() }
            controller.chooseWorkspace(java.nio.file.Files.createDirectories(directory.resolve("project")))
            result.complete(ModelCatalogResult(listOf(ProviderModel("loaded"))))
            awaitStatus(controller, "ready")
            assertEquals("loaded", controller.state.value.catalog.models.single().id)
        } finally { result.complete(ModelCatalogResult(errorCode = "NETWORK")); controller.close() }
    }

    @Test fun `persisted v1 endpoint is queried with GET models automatically on restart`() = runBlocking<Unit> {
        val requests = AtomicInteger()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/models") { exchange ->
                assertEquals("GET", exchange.requestMethod)
                assertEquals("/v1/models", exchange.requestURI.path)
                requests.incrementAndGet()
                val response = """{"data":[{"id":"local-flash"},{"id":"local-pro"}]}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        try {
            val first = DesktopController(directory, initialCredential = null)
            try {
                first.initialize()
                first.configure("http://127.0.0.1:${server.address.port}/v1", "local-flash", false, null)
                awaitStatus(first, "ready")
                assertEquals(1, requests.get())
            } finally { first.close() }
            val restarted = DesktopController(directory, initialCredential = null)
            try {
                restarted.initialize()
                awaitStatus(restarted, "ready")
                assertEquals(2, requests.get(), "restart automatically issues exactly one catalog GET")
                assertEquals(listOf("local-flash", "local-pro"), restarted.state.value.catalog.models.map { it.id })
            } finally { restarted.close() }
        } finally { server.stop(0) }
    }

    private class FakeCatalog(next: ModelCatalogResult) : ModelCatalogClient {
        private val count = AtomicInteger()
        @Volatile private var next = next
        val calls: Int get() = count.get()
        override suspend fun discover(baseUrl: String, apiKey: String?): ModelCatalogResult {
            count.incrementAndGet()
            return next
        }
        fun respond(result: ModelCatalogResult) { next = result }
    }
}
