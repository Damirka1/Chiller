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
    val commit: String get() = props.getProperty("commit") ?: "local"
    val runtime: String get() = "${System.getProperty("java.vendor", "")} ${System.getProperty("java.version", "")}".trim()
    val os: String get() = "${System.getProperty("os.name", "")} ${System.getProperty("os.version", "")} (${System.getProperty("os.arch", "")})"
}

