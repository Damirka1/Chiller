package dev.chiller.app.di

import dev.chiller.contracts.ModelCatalogClient
import dev.chiller.contracts.ModelCatalogResult

internal object OfflineCatalog : ModelCatalogClient {
    override suspend fun discover(baseUrl: String, apiKey: String?) = ModelCatalogResult(errorCode = "UNSUPPORTED", message = "Offline test catalog")
}
