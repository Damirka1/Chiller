package dev.chiller.app.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
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
    val common: CommonStrings
    val sidebar: SidebarStrings
    val projects: ProjectStrings
    // groups
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
    // end settings
}

interface CommonStrings {
    val newDialog: String
    val cancel: String
    val noFolder: String
    val noFolderLower: String
    val relocate: String
    val relocateHint: String
    val collapse: String
    fun more(n: Int): String
    fun dialogs(n: Int): String
    fun files(n: Int): String
    fun showInFileManager(): String
    val ageNow: String
    fun ageMinutes(n: Long): String
    fun ageHours(n: Long): String
    val ageYesterday: String
    fun ageDays(n: Long): String
    fun ageDate(day: java.time.LocalDate): String
    // end common
}

interface SidebarStrings {
    val changeTheme: String
    fun nothingFound(query: String): String
    val found: String
    fun foundCount(shown: Int, total: Int): String
    val inMessages: String
    val dialog: String
    val dropToOpen: String
    val searchDialogs: String
    val search: String
    val clearSearch: String
    val separateCopy: String
    val dialogActions: String
    val stop: String
    val runsInBackground: String
    val rename: String
    val exportMarkdown: String
    val exportJson: String
    val deleteDialog: String
    val waiting: String
    val running: String
    fun foundIn(title: String): String
    val you: String
    val model: String
    // end sidebar
}

interface ProjectStrings {
    val projects: String
    val openProject: String
    val openProjectFolder: String
    val newDialogNoFolder: String
    val looseHint: String
    fun projectToggle(name: String, open: Boolean): String
    fun newDialogIn(name: String): String
    val projectActions: String
    val unpin: String
    val pinTop: String
    val removeFromList: String
    val removeHint: String
    val folderMissing: String
    val pinned: String
    fun dialogFolderA11y(name: String): String
    val copySuffixA11y: String
    val copySuffix: String
    val folderLocked: String
    val folderHasFiles: String
    val continueElsewhere: String
    val continueHint: String
    val isolate: String
    val isolateHint: String
    val newDialogHint: String
    val openFolder: String
    val noAccessHint: String
    val jumpSearchA11y: String
    val jumpPlaceholder: String
    val noProjects: String
    fun noProjectsMatching(query: String): String
    val openNow: String
    val jumpHint: String
    val projectFolderMissing: String
    fun separateCopyBranch(branch: String): String
    val copyRunning: String
    val copyChangesLater: String
    val copyNoChanges: String
    fun copyChanged(n: Int): String
    val moving: String
    val moveToProject: String
    val copyActions: String
    val deleteCopyMenu: String
    val deleteCopyHint: String
    fun deleteCopyConfirm(branch: String): String
    fun deleteCopyConfirmChanges(branch: String): String
    val deleteCopy: String
    // end projects
}

// interfaces

fun stringsFor(language: Language): Strings = when (language) {
    Language.EN -> EnStrings
    Language.RU -> RuStrings
}

/** The texts of the language chosen in the appearance settings. */
val LocalStrings = staticCompositionLocalOf<Strings> { EnStrings }

/** The current language's texts in composition, e.g. `strings.sidebar.newDialog`. */
val strings: Strings @Composable @ReadOnlyComposable get() = LocalStrings.current

/**
 * The same texts for code outside composition (the controller's errors, notifications). The app sets it whenever the
 * language changes; composables read [LocalStrings] instead, so they recompose on a switch.
 */
object I18n {
    @Volatile var current: Strings = EnStrings
}

/** The OS, for texts that name its file manager. */
internal val osName: String = System.getProperty("os.name").orEmpty()

/** Russian plural forms: 1 файл, 2 файла, 5 файлов, 21 файл. */
internal fun ruPlural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100; val mod10 = n % 10
    return when { mod100 in 11..14 -> many; mod10 == 1 -> one; mod10 in 2..4 -> few; else -> many }
}

/** English plural forms: 1 file, 2 files. */
internal fun enPlural(n: Int, one: String, other: String): String = if (n == 1) one else other
