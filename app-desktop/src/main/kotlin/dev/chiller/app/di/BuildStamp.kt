package dev.chiller.app.di

import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Build identity taken from the classpath artifact timestamp. It is shown in the window title
 * so a stale app bundle is obvious instead of being mistaken for a UI bug.
 */
object BuildStamp {
    val value: String by lazy {
        runCatching {
            val path = Path.of(BuildStamp::class.java.protectionDomain.codeSource.location.toURI())
            val instant = Files.getLastModifiedTime(path).toInstant().atZone(ZoneId.systemDefault())
            DateTimeFormatter.ofPattern("dd.MM HH:mm").format(instant)
        }.getOrDefault("unknown")
    }
}

/** Version and commit generated at build time (see app-desktop/build.gradle.kts). */
object BuildInfo {
    private val props by lazy {
        java.util.Properties().apply { runCatching { BuildInfo::class.java.getResourceAsStream("/chiller-version.properties")?.use { load(it) } } }
    }
    val version: String get() = props.getProperty("version") ?: "dev"
    /** The full version including a pre-release suffix, e.g. 1.2.0-rc.2. */
    val displayVersion: String get() = props.getProperty("displayVersion")?.takeIf { it.isNotBlank() } ?: version
    val prerelease: Boolean get() = isPrerelease(displayVersion)
    val commit: String get() = props.getProperty("commit") ?: "local"
    /** "#57 · 2026-10-18" for a CI build; a local build shows when its classes were built. */
    val build: String get() = buildLabel(props.getProperty("buildNumber").orEmpty(), props.getProperty("commitDate").orEmpty(), BuildStamp.value)
    val runtime: String get() = "${System.getProperty("java.vendor", "")} ${System.getProperty("java.version", "")}".trim()
    val os: String get() = "${System.getProperty("os.name", "")} ${System.getProperty("os.version", "")} (${System.getProperty("os.arch", "")})"
}


internal fun isPrerelease(version: String): Boolean = '-' in version

internal fun buildLabel(number: String, commitDate: String, localStamp: String): String =
    if (number.isNotBlank()) listOf("#$number", commitDate).filter { it.isNotBlank() }.joinToString(" · ")
    else "локальная · $localStamp"
