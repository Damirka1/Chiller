package dev.chiller.app.di

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

@Serializable
data class UiPreferences(
    val theme: String = "system",
    val density: String = "normal",
    val layout: String = "focus",
    val inspectorWidth: Float = 348f,
    val reducedMotion: Boolean = false,
)

/** Stores non-secret appearance preferences separately from provider/session data. */
class UiPreferencesStore(private val directory: Path) {
    private val file: Path = directory.resolve("ui-preferences.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun load(): UiPreferences = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext UiPreferences()
        if (runCatching { Files.size(file) > 65_536 }.getOrDefault(true)) return@withContext UiPreferences()
        runCatching {
            sanitize(json.decodeFromString<UiPreferences>(Files.readString(file)))
        }.getOrDefault(UiPreferences())
    }

    suspend fun save(value: UiPreferences) = withContext(Dispatchers.IO) {
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, ".ui-preferences-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(UiPreferences.serializer(), sanitize(value)))
            try {
                Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun sanitize(value: UiPreferences) = value.copy(
        theme = value.theme.takeIf { it in THEMES } ?: "system",
        density = value.density.takeIf { it in DENSITIES } ?: "normal",
        layout = value.layout.takeIf { it in LAYOUTS } ?: "focus",
        inspectorWidth = if (value.inspectorWidth.isFinite()) value.inspectorWidth.coerceIn(280f, 480f) else 348f,
    )

    private companion object {
        val THEMES = setOf("system", "light", "dark")
        val DENSITIES = setOf("normal", "compact")
        val LAYOUTS = setOf("focus", "studio")
    }
}
