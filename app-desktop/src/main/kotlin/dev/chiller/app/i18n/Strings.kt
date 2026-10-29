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
    val composer: ComposerStrings
    val conversation: ConversationStrings
    val run: RunStrings
    val turn: TurnStrings
    val studio: StudioStrings
    val dialogs: DialogStrings
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
    val saveUnavailable: String
    val providers: String
    val searchByName: String
    val addProvider: String
    val cloud: String
    val local: String
    val myConnections: String
    val noKeyNeeded: String
    val keyFromEnv: String
    val keySaved: String
    val keySet: String
    val keyNeeded: String
    val noProvidersFound: String
    val ownProvider: String
    val ownAddress: String
    val ownProviderNote: String
    val anyServerNote: String
    val name: String
    val apiAddress: String
    val localNoKey: String
    val keyFromEnvHint: String
    fun keySavedHint(store: String): String
    val keySetHint: String
    val pasteKey: String
    val apiKey: String
    fun keyStoredNote(store: String): String
    val keySessionNote: String
    fun modelLine(catalog: String): String
    val refreshList: String
    val modelNoList: String
    val modelIdHint: String
    val modelAfterApply: String
    fun whereKey(link: String): String
    val modelReasoning: String
    val modelReasoningHint: String
    val providerProfile: String
    val keepProfile: String
    val auto: String
    val full: String
    val compact: String
    val apply: String
    val cancelCheck: String
    val checkConnection: String
    val deleteKey: String
    val deleteProvider: String
    val notApplied: String
    val applyNote: String
    val verifiedInChiller: String
    val budget: String
    val budgetNote: String
    val limitSpending: String
    fun budgetOnFor(model: String, address: String): String
    val off: String
    val budgetLimit: String
    val priceInput: String
    val priceOutput: String
    fun priceImage(model: String): String
    val inputCeiling: String
    val ceilingNote: String
    fun budgetBound(saved: String, current: String): String
    fun saveFor(model: String): String
    val currentModel: String
    val saved: String
    val proxy: String
    val proxyNote: String
    val proxySystem: String
    val proxyManual: String
    val proxyOff: String
    val proxySystemHint: String
    val proxyManualHint: String
    val proxyOffHint: String
    val proxyAddress: String
    val proxyBypass: String
    val save: String
    val proxySaved: String
    val runLimits: String
    val runLimitsNote: String
    val taskTime: String
    val taskTimeNote: String
    fun minutes(n: Int): String
    val noLimit: String
    val modelSteps: String
    val modelStepsNote: String
    val sharedBudget: String
    val sharedBudgetNote: String
    val outputTokensNote: String
    val replyLength: String
    val modelMaximum: String
    val modelMaximumNote: String
    fun noLimitWarning(backstop: Int): String
    val capabilities: String
    val helpers: String
    val helpersHint: String
    val projectInstructions: String
    val projectInstructionsHint: String
    val processCommands: String
    val processCommandsHint: String
    val webSearch: String
    val webSearchHint: String
    val windowSnapshots: String
    val windowSnapshotsHint: String
    val captureUnsupported: String
    val captureEnableFirst: String
    val captureWindow: String
    fun captureSelected(app: String, title: String): String
    val captureNone: String
    val captureEmpty: String
    val autoVerify: String
    val autoVerifyNote: String
    val verifyAfterEdits: String
    val verifyAfterEditsHint: String
    val fixAttempts: String
    val openProjectForCheck: String
    fun checkCommandFor(project: String): String
    val checkCommandExample: String
    fun useSuggestion(command: String): String
    val processesOffWarning: String
    val modelProfile: String
    val modelProfileNote: String
    fun profileFor(model: String, profile: String): String
    val theModel: String
    fun autoByIdAndName(name: String): String
    val autoById: String
    fun tools(list: String): String
    val images: String
    val imagesOn: String
    val imagesOff: String
    val on: String
    val offShort: String
    val imageProvider: String
    val imageProviderNote: String
    val sameAsChat: String
    val imageModel: String
    val imageToolOff: String
    val imageNoModel: String
    fun imageDrawsWith(model: String, edits: Boolean): String
    val temperature: String
    fun recommended(value: String): String
    fun profileValue(value: String): String
    val byDefault: String
    val requestExtras: String
    val requestExtrasNote: String
    val saveFields: String
    val whichProfile: String
    val whichProfileNote: String
    val modelComparison: String
    val recentProjects: String
    val recentProjectsNote: String
    val emptyYet: String
    val removeShort: String
    val appData: String
    val appDataNote: String
    val dataFolder: String
    val requestTraces: String
    val demoProjects: String
    val none: String
    fun demoFolders(n: Int, path: String): String
    val reallyDelete: String
    val delete: String
    val open: String
    val keyNewDialog: String
    val keyOpenProject: String
    val keySearch: String
    val keyJump: String
    val keyAllCommands: String
    val keyNewInProject: String
    val keySend: String
    val keyStop: String
    val keySettings: String
    val keyEsc: String
    val keyModelMenu: String
    val keySidebar: String
    val keyRowMenu: String
    val keySections: String
    val keyTab: String
    val aboutVersion: String
    val aboutBuild: String
    val aboutCommit: String
    val aboutRuntime: String
    val aboutSystem: String
    val aboutData: String
    val preRelease: String
    val tagline: String
    val copied: String
    val copyDetails: String
    val githubRepo: String
    val diagnostics: String
    val diagnosticsNote: String
    val checking: String
    val checkEnvironment: String
    fun permissionsLine(processes: Boolean, web: Boolean): String
    fun screenshotsLine(supported: Boolean): String
    val whatWasChecked: String
    val whatWasCheckedNote: String
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

interface ComposerStrings {
    val sendingAttachLater: String
    val attachFailed: String
    val tooManyAttachments: String
    val addFiles: String
    val clipboardFailed: String
    val clipboardUnsupported: String
    fun imageScaled(width: Int, height: Int): String
    val placeholderWithFiles: String
    val placeholder: String
    val addingAttachment: String
    val visionOn: String
    val visionOff: String
    fun preparing(label: String): String
    fun remove(name: String): String
    val conversation: String
    val conversationHint: String
    val chooseFolder: String
    val chooseFolderHint: String
    val plan: String
    val planHint: String
    val changes: String
    val changesHint: String
    val processCommands: String
    val processCommandsHint: String
    val confirmEdits: String
    val confirmEditsHint: String
    val modeShortcutHint: String
    val chooseModel: String
    val savingMessage: String
    val stop: String
    val addingAttachmentA11y: String
    val send: String
    val webSearch: String
    val webSearchHint: String
    val modelSearchA11y: String
    val findModel: String
    val cancelListLoad: String
    val refreshModels: String
    val manual: String
    fun nothingFoundModel(query: String): String
    val noModelsFromProvider: String
    val otherProviders: String
    val connectAndPickFirst: String
    val connectionSettings: String
    val switchNote: String
    val catalogLoading: String
    val catalogFailed: String
    val catalogEmpty: String
    fun catalogCount(n: Int): String
    fun catalogStale(line: String): String
    val catalogNotLoaded: String
    val noApiKey: String
    fun contextWindow(tokens: String): String
    fun outputLimit(tokens: String): String
    val noFolderChosen: String
    val mentionHint: String
    fun contextA11y(estimated: Boolean, tokens: Int, window: Int?): String
    val tokensShort: String
    // end composer
}

interface ConversationStrings {
    val sessionActions: String
    val waitingApproval: String
    val continueRun: String
    val stepsRanOut: String
    val restoreDraft: String
    val unknownNotRepeated: String
    val checkAndContinue: String
    val toLatest: String
    fun rewindConfirm(n: Int): String
    val rewindTurn: String
    val editMessage: String
    val deleteTurn: String
    val copy: String
    val copied: String
    val copyReply: String
    val code: String
    val copyCode: String
    val yourAnswer: String
    val questionAfterInterrupt: String
    // end conversation
}

interface RunStrings {
    fun toolLabel(name: String): String
    val waitingYou: String
    val preparingRequest: String
    val waitingModel: String
    val modelWriting: String
    val runningAction: String
    val replyReady: String
    val taskStopped: String
    val runInterrupted: String
    fun errorsCount(n: Int): String
    val collapse: String
    val expand: String
    val tagError: String
    val tagRunning: String
    val tagInterrupted: String
    val noOutput: String
    val undoEdit: String
    val collapseReasoning: String
    val showReasoning: String
    val thinking: String
    val reasoning: String
    fun sizeBytes(n: Long): String
    fun sizeKb(kb: Double): String
    fun sizeMb(mb: Double): String
    fun rounds(n: Int): String
    fun actions(n: Int, failed: Int): String
    fun tokensFlow(input: String, output: String): String
    val usagePartial: String
    fun cacheShare(percent: Long): String
    fun durationMs(ms: Long): String
    fun durationSeconds(seconds: Double): String
    fun durationMinutes(minutes: Long, seconds: Long): String
    val metricsLater: String
    val usageIncompleteNote: String
    val statRounds: String
    val statActions: String
    val statToolErrors: String
    val statFailedCommands: String
    val statTime: String
    val statInput: String
    val statOutput: String
    val statCache: String
    val statContext: String
    val statHelpers: String
    val statHelperTokens: String
    val statsNote: String
    val requestTrace: String
    fun modelPlanProgress(done: Int, total: Int): String
    fun droppedMessages(n: Int): String
    val lastRoundReasoning: String
    fun statusLabel(status: String): String
    val errorIncompleteToolCall: String
    val errorIncompleteStream: String
    val errorContextOverflow: String
    val errorBudget: String
    val planUnfinished: String
    val planDone: String
    val planClosed: String
    val plan: String
    val collapsePlan: String
    val expandPlan: String
    fun verifyRunning(command: String, attempt: Int, max: Int): String
    fun verifyPassed(command: String, duration: String): String
    fun verifyFailed(code: String, message: String, attempt: Int, max: Int): String
    fun verifyGaveUp(attempts: Int, code: String): String
    val verifyNotStarted: String
    val hideOutput: String
    val output: String
    val hide: String
    fun contextShortened(messages: Int, results: Int): String
    val hideMemory: String
    val showMemory: String
    val memDetailedRequests: String
    val memLatestPlan: String
    val memRecentRequests: String
    val memFiles: String
    val memNotes: String
    val memIntro: String
    val memUnavailable: String
    // end run
}

interface TurnStrings {
    val stoppedStepLimit: String
    val error: String
    val stopped: String
    val interrupted: String
    val doneWithIssues: String
    val turnDone: String
    fun planLine(done: Int, total: Int, skipped: Int): String
    val collapseResult: String
    val expandResult: String
    val modelPlan: String
    val needsAttention: String
    val changed: String
    val undone: String
    val deleted: String
    val created: String
    val modified: String
    fun andMore(n: Int): String
    fun andMoreTruncated(n: Int, truncated: Boolean): String
    val outsideTools: String
    val outsideNote: String
    val commands: String
    val exitZero: String
    val timedOut: String
    fun exitCode(code: String): String
    val resultUnread: String
    val shown: String
    fun issuesCount(n: Int): String
    fun checksPassed(passed: Int, total: Int): String
    val checksNotRun: String
    val checksUnconfirmed: String
    fun shownCount(n: Int): String
    fun outsideCount(n: Int): String
    fun checkTimedOut(command: String): String
    fun checkFailed(command: String, code: String): String
    fun checkUnread(command: String): String
    fun editFailed(path: String): String
    fun planOpen(steps: String): String
    fun quote(text: String): String
    fun createdNotShown(path: String): String
    // end turn
}

interface StudioStrings {
    val tabChanges: String
    val tabAgents: String
    val tabRun: String
    val noChanges: String
    val noChangesHint: String
    val openImage: String
    fun openImageNamed(name: String): String
    val expandDiff: String
    val undo: String
    val noDiff: String
    val closeDiff: String
    val tagNew: String
    val tagDeleted: String
    val tagMoved: String
    val noHelpers: String
    val noHelpersHint: String
    val agentQueued: String
    val agentRunning: String
    val agentDone: String
    val agentFailed: String
    val agentCancelled: String
    val agentInterrupted: String
    val stop: String
    fun agentRound(round: Int, max: Int): String
    fun agentActions(n: Int): String
    fun agentErrors(n: Int): String
    fun agentFilesRead(n: Int): String
    fun agentMessages(processed: Int, total: Int): String
    fun agentTokens(input: String, output: String): String
    val report: String
    val collapse: String
    val more: String
    val previewUnavailable: String
    val loadingImage: String
    val showInFolder: String
    val zoomOut: String
    val zoomIn: String
    val fit: String
    val closeViewer: String
    val viewerHint: String
    fun previewMoreLines(n: Int): String
    val approveReplace: String
    fun approveAllPlaces(n: Int): String
    val approveCreate: String
    val approveAppend: String
    val approveEditLines: String
    fun approveDeleteLines(start: Int?, end: Int?): String
    fun approveInsertBefore(line: Int?): String
    fun approveReplaceLines(start: Int?, end: Int?): String
    fun approveLineEdits(n: Int): String
    val approveDelete: String
    val approveDeleteNote: String
    val approveMove: String
    val approveCreateFolder: String
    val approveImage: String
    fun approveTitle(title: String): String
    val apply: String
    val decline: String
    val applyRest: String
    // end studio
}

interface DialogStrings {
    val dialogTitle: String
    val title: String
    val save: String
    val deleteDialogQuestion: String
    val loadingPreview: String
    val previewUnavailable: String
    val deleteDialog: String
    val editMessage: String
    val editNote: String
    val messageText: String
    val saveAndSend: String
    val deleteTurnQuestion: String
    val deleteTurnNote: String
    val deleteTurn: String
    fun exportTitle(format: String): String
    val closeExport: String
    val exportNote: String
    val saveFile: String
    // end dialogs
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
