package dev.chiller.app.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import dev.chiller.app.di.Notice
import dev.chiller.app.di.SendReceipt
import dev.chiller.app.di.UiPreferences
import dev.chiller.contracts.RunState
import dev.chiller.contracts.SessionInfo
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The project workflow driven like a user would: clicks, hover, keys and popups on the real ChillerApp, with the
 * controller replaced by recorded callbacks. Offscreen, no provider and no network.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectWorkflowUiTest {
    private val root = Files.createTempDirectory("ui-projects")
    private val shop = Files.createDirectories(root.resolve("demo-shop")).toString()
    private val api = Files.createDirectories(root.resolve("backend-api")).toString()
    private val sessions = listOf(
        SessionInfo("s1", "Починить расчёт скидки", "2026-10-17T09:12:00Z", shop),
        SessionInfo("s2", "Тесты для парсера CSV", "2026-10-16T15:05:00Z", api),
        SessionInfo("s3", "Как работает Flow", "2026-10-16T21:10:00Z"),
    )
    private val calls = mutableListOf<String>()

    private val saved = mutableListOf<UiPreferences>()

    private fun ComposeUiTest.app(
        workspace: String? = shop, projects: ProjectsModel = model(), notices: List<Notice> = emptyList(), selected: String = "fresh",
        preferences: UiPreferences = UiPreferences(theme = "light", reducedMotion = true),
    ) = setContent {
        ChillerApp(
            RunState(), sessions, selected, workspace, isDemo = false,
            providerModel = "fake-model", providerBaseUrl = "http://localhost:8000/v1", thinkingEnabled = false, apiKeyConfigured = true,
            onSend = { _, _, id -> SendReceipt(false, selected, id) }, onStop = {}, onNewSession = { calls += "new" }, onSelectSession = { calls += "select:$it" },
            onChooseWorkspace = { calls += "choose" }, onConfigureProvider = { _, _, _, _ -> null }, onAnswerQuestion = {}, onUndo = {},
            onAllowProcessesChange = {}, allowProcesses = false, preferences = preferences, onPreferences = { saved += it },
            recentWorkspaces = listOf(shop, api), onOpenWorkspace = { calls += "open:$it" }, onCloseWorkspace = { calls += "close" },
            projects = projects, notices = notices, onContinueElsewhere = { calls += "continue"; null }, onOpenNotice = { calls += "notice:${it.sessionId}" }, onDismissNotice = { calls += "dismiss:$it" },
        )
    }

    private fun model(folderLocked: Boolean = false, missing: Boolean = false, running: Set<String> = emptySet(), unread: Set<String> = emptySet()) = ProjectsModel(
        folderLocked = folderLocked, workspaceMissing = missing, running = running, unread = unread,
        onNewIn = { calls += "newIn:${it?.let(::folderName)}" }, onPin = { path, pinned -> calls += "pin:${folderName(path)}:$pinned" },
        onRemove = { calls += "remove:${folderName(it)}" }, onRelocate = { calls += "relocate:${folderName(it)}" }, onStop = { calls += "stop:$it" },
    )

    @Test fun projectsExpandAndOfferActionsOnHover() = runComposeUiTest {
        app()
        // The current project starts expanded, another one collapsed; a fresh dialog in it is selected.
        onNodeWithText("Починить расчёт скидки").assertExists()
        onNodeWithText("Тесты для парсера CSV").assertDoesNotExist()
        onNodeWithText("backend-api").performClick()
        onNodeWithText("Тесты для парсера CSV").assertExists().performClick()
        assertEquals("select:s2", calls.last())
        onNodeWithText("backend-api").performMouseInput { moveTo(center) }
        onNodeWithContentDescription("Новый диалог в backend-api").performClick()
        assertEquals("newIn:backend-api", calls.last())
        onNodeWithText("backend-api").performMouseInput { moveTo(center) }
        onNodeWithContentDescription("Действия проекта").performClick()
        onNodeWithText("Закрепить наверху").performClick()
        assertEquals("pin:backend-api:true", calls.last())
        // Dialogs without a folder have their own section and «+».
        onNodeWithText("Как работает Flow").assertExists()
        onNodeWithContentDescription("Новый диалог без папки").performClick()
        assertEquals("newIn:null", calls.last())
    }

    @Test fun projectExpansionComesFromPreferencesAndIsSaved() = runComposeUiTest {
        app(preferences = UiPreferences(theme = "light", reducedMotion = true, projectExpansion = mapOf(api to true, shop to false)))
        onNodeWithText("Тесты для парсера CSV").assertExists()
        onNodeWithText("Починить расчёт скидки").assertDoesNotExist()
        onAllNodesWithText("demo-shop").onFirst().performClick()
        onNodeWithText("Починить расчёт скидки").assertExists()
        waitUntil { saved.lastOrNull()?.projectExpansion == mapOf(api to true, shop to true) }
    }

    @Test fun unreadDialogStandsOutUntilOpened() = runComposeUiTest {
        app(projects = model(unread = setOf("s3")))
        onNodeWithText("Как работает Flow").assertExists()
        onAllNodesWithText("Как работает Flow").assertCountEquals(1)
    }

    @Test fun ctrlPJumpsToAProjectByName() = runComposeUiTest {
        app()
        onRoot().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.P); keyUp(Key.CtrlLeft) }
        onNodeWithContentDescription("Поиск проекта").performTextInput("back")
        onNodeWithContentDescription("Поиск проекта").performKeyInput { pressKey(Key.Enter) }
        assertEquals("newIn:backend-api", calls.last())
        onNodeWithContentDescription("Поиск проекта").assertDoesNotExist()
        // Shift+Enter opens the project's latest dialog instead.
        onRoot().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.P); keyUp(Key.CtrlLeft) }
        onNodeWithContentDescription("Поиск проекта").performTextInput("back")
        onNodeWithContentDescription("Поиск проекта").performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Enter); keyUp(Key.ShiftLeft) }
        assertEquals("select:s2", calls.last())
    }

    @Test fun folderChipSwapsTheFolderOrDropsIt() = runComposeUiTest {
        app()
        onNodeWithContentDescription("Папка диалога: demo-shop").performClick()
        onAllNodesWithText("backend-api").onLast().performClick()
        assertEquals("open:$api", calls.last())
        onNodeWithContentDescription("Папка диалога: demo-shop").performClick()
        onNodeWithText("Без доступа к файлам и командам").performClick()
        assertEquals("close", calls.last())
    }

    @Test fun lockedFolderSaysAnotherFolderStartsANewDialog() = runComposeUiTest {
        app(projects = model(folderLocked = true))
        onNodeWithContentDescription("Папка диалога: demo-shop").performClick()
        onNodeWithText("Папка закреплена за диалогом", substring = true).assertExists()
        onAllNodesWithText("Новый диалог без папки").onLast().performClick()
        assertEquals("newIn:null", calls.last())
        onNodeWithContentDescription("Папка диалога: demo-shop").performClick()
        onNodeWithText("Продолжить в другой папке…").performClick()
        waitUntil { calls.last() == "continue" }
    }

    @Test fun missingFolderOffersTheNewPlace() = runComposeUiTest {
        app(projects = model(missing = true))
        onNodeWithText("Папка проекта не найдена").assertExists()
        onNodeWithText("Указать новое место…").performClick()
        assertEquals("relocate:demo-shop", calls.last())
    }

    @Test fun aGitProjectOffersItsOwnCopyAndTheCopyCanBeAppliedOrRemoved() = runComposeUiTest {
        Files.createDirectories(java.nio.file.Path.of(shop).resolve(".git"))
        app(projects = model().copy(onIsolate = { calls += "isolate" }))
        onNodeWithContentDescription("Папка диалога: demo-shop").performClick()
        onNodeWithText("Работать в отдельной копии").performClick()
        assertEquals("isolate", calls.last())
    }

    @Test fun theCopyBarShowsChangesAppliesAndAsksBeforeRemoving() = runComposeUiTest {
        val copy = dev.chiller.app.di.WorktreeInfo(shop, root.resolve("copy/demo-shop").toString(), "chiller/fresh", "abc")
        app(workspace = copy.path, projects = model().copy(worktree = copy, worktreeChanges = { listOf("src/app.kt", "README.md") },
            onApplyWorktree = { calls += "apply"; "Перенесено в проект: 2 файла" }, onRemoveWorktree = { calls += "remove" }))
        onNodeWithText("Отдельная копия · chiller/fresh").assertExists()
        onNodeWithContentDescription("Папка диалога: demo-shop, отдельная копия").assertExists()
        waitUntil { onAllNodesWithText("Изменено: 2 файла", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Перенести в проект").performClick()
        waitUntil { onAllNodesWithText("Перенесено в проект: 2 файла").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("apply", calls.last())
        onNodeWithContentDescription("Действия с копией").performClick()
        onNodeWithText("Удалить копию…").performClick()
        onNodeWithText("пропадут вместе с ней", substring = true).assertExists()
        onNodeWithText("Удалить копию").performClick()
        assertEquals("remove", calls.last())
    }

    @Test fun noticesOpenTheirDialogAndCanBeClosed() = runComposeUiTest {
        app(notices = listOf(Notice("n1", "s2", "Тесты для парсера CSV", "Задача завершена, проверка пройдена", "positive")))
        onNodeWithText("Задача завершена, проверка пройдена").performClick()
        assertEquals("notice:s2", calls.last())
        onNodeWithContentDescription("Скрыть уведомление").performClick()
        assertEquals("dismiss:n1", calls.last())
    }

    @Test fun aBackgroundRunCanBeStoppedFromItsRow() = runComposeUiTest {
        app(projects = model(running = setOf("s3")))
        onNodeWithText("идёт").assertExists()
        onNodeWithText("Как работает Flow").performMouseInput { moveTo(center) }
        onNodeWithContentDescription("Действия диалога").performClick()
        onNodeWithText("Остановить").performClick()
        assertEquals("stop:s3", calls.last())
    }
}
