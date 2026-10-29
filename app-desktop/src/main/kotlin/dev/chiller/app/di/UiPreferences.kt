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
    val windowWidth: Float = 1280f,
    val windowHeight: Float = 860f,
    val windowMaximized: Boolean = false,
    /** Background news also as system notifications while the window is not in focus; needs an icon in the tray. */
    val systemNotifications: Boolean = true,
    /** The composer mode last used in each project: "plan" or "edit". */
    val projectModes: Map<String, String> = emptyMap(),
    /** Projects the user expanded (true) or collapsed (false) in the sidebar, by folder. */
    val projectExpansion: Map<String, Boolean> = emptyMap(),
    /** Interface language: "en" (the default) or "ru"; see [dev.chiller.app.i18n.Language]. */
    val language: String = "en",
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
        windowWidth = value.windowWidth.takeIf { it.isFinite() && it > 0f }?.coerceIn(320f, 16384f) ?: 1280f,
        windowHeight = value.windowHeight.takeIf { it.isFinite() && it > 0f }?.coerceIn(240f, 16384f) ?: 860f,
        projectModes = value.projectModes.filter { (path, mode) -> path.length in 1..1024 && mode in setOf("plan", "edit") }.entries.toList().takeLast(MAX_PROJECTS).associate { it.toPair() },
        projectExpansion = value.projectExpansion.filterKeys { it.length in 1..1024 }.entries.toList().takeLast(MAX_PROJECTS).associate { it.toPair() },
    )

    private companion object {
        val THEMES = setOf("system", "light", "dark")
        val DENSITIES = setOf("normal", "compact")
        val LAYOUTS = setOf("focus", "studio")
        const val MAX_PROJECTS = 200
    }
}
