package dev.chiller.app.ui

/** True on macOS; shortcut accelerators use ⌘ there and Ctrl elsewhere. */
internal val isMacOs: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

/** How a shortcut is written on this platform: "⌘ N" on macOS, "Ctrl+N" on Windows and Linux. */
internal fun shortcut(key: String, mac: Boolean = isMacOs): String =
    if (mac) "⌘ $key" else "Ctrl+" + when (key) { "↵" -> "Enter"; else -> key }
