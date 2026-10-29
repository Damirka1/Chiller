package dev.chiller.app.i18n

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The interface languages. English is the default; the choice is a UI preference and applies at once.
 * [nativeName] is how the language names itself in the picker, the same in every language.
 */
enum class Language(val id: String, val nativeName: String) {
    EN("en", "English"), RU("ru", "Русский");

    companion object {
        fun fromId(id: String?): Language = entries.firstOrNull { it.id == id } ?: EN
    }
}

/**
 * Every text the app shows, grouped by screen. Each language implements all of it, so a missing translation is a
 * compile error rather than a stray string at runtime. Texts with numbers are functions: each language applies its
 * own plural rules.
 */
interface Strings {
    val language: Language
    val settings: SettingsStrings
}

interface SettingsStrings {
    val title: String
    val backToDialog: String
    val connection: String
    val agent: String
    val mcp: String
    val appearance: String
    val data: String
    val keys: String
    val about: String
    val connectionDescription: String
    val agentDescription: String
    val mcpDescription: String
    val appearanceDescription: String
    val dataDescription: String
    val keysDescription: String
    val aboutDescription: String

    val theme: String
    val themeSystem: String
    val themeLight: String
    val themeDark: String
    val interfaceTitle: String
    val language: String
    val density: String
    val densityNormal: String
    val densityCompact: String
    val defaultLayout: String
    val reducedMotion: String
    val reducedMotionHint: String
    val notifications: String
    val notificationsHint: String
    val systemNotifications: String
    val systemNotificationsHint: String
}

fun stringsFor(language: Language): Strings = when (language) {
    Language.EN -> EnStrings
    Language.RU -> RuStrings
}

/** The texts of the language chosen in the appearance settings. */
val LocalStrings = staticCompositionLocalOf<Strings> { EnStrings }

/**
 * The same texts for code outside composition (the controller's errors, notifications). The app sets it whenever the
 * language changes; composables read [LocalStrings] instead, so they recompose on a switch.
 */
object I18n {
    @Volatile var current: Strings = EnStrings
}

/** Russian plural forms: 1 файл, 2 файла, 5 файлов, 21 файл. */
internal fun ruPlural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100; val mod10 = n % 10
    return when { mod100 in 11..14 -> many; mod10 == 1 -> one; mod10 in 2..4 -> few; else -> many }
}

/** English plural forms: 1 file, 2 files. */
internal fun enPlural(n: Int, one: String, other: String): String = if (n == 1) one else other
