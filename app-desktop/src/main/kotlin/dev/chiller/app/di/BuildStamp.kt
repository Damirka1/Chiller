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
