package dev.chiller.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.chiller.app.di.UiPreferences
import dev.chiller.app.di.AgentOptions
import dev.chiller.app.di.visionEnabled
import dev.chiller.app.di.entry
import dev.chiller.app.di.SendReceipt
import dev.chiller.app.di.ConnectionState
import dev.chiller.app.di.ModelCatalogState
import dev.chiller.contracts.*
import dev.chiller.design.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*
import java.awt.Image as AwtImage
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.ImageReader

// The sidebar: projects and dialogs, search over titles and messages, and each dialog row.

@Composable
internal fun Sidebar(
    sessions: List<SessionInfo>, tree: ProjectTree, projects: ProjectsModel, selected: String?, workspace: String?, connected: Boolean, enabled: Boolean,
    onNew: () -> Unit, onSelect: (String) -> Unit, onChoose: () -> Unit, onSettings: () -> Unit, onTheme: () -> Unit, onRename: (SessionInfo) -> Unit, onDelete: (SessionInfo) -> Unit, onExport: (String, String) -> Unit, connection: ConnectionState, width: Float,
    running: Boolean, searchFocus: FocusRequester, titleBar: TitleBarSpec?,
    settingsOpen: Boolean = false, settingsSection: SettingsSection = SettingsSection.CONNECTION,
    onSettingsSection: (SettingsSection) -> Unit = {}, onCloseSettings: () -> Unit = {}, version: String? = null,
    /** A folder dropped on the sidebar opens as a project. */
    onOpenPath: (String) -> Unit = {},
    onSearchMessages: suspend (String) -> List<dev.chiller.sessions.SessionHit> = { emptyList() },
    onOpenHit: (dev.chiller.sessions.SessionHit, String) -> Unit = { _, _ -> },
    /** Saved expand/collapse of projects; changes are handed back to be saved. */
    expansion: Map<String, Boolean> = emptyMap(), onExpansion: (Map<String, Boolean>) -> Unit = {},
) {
    val c = LocalChillerPalette.current
    var query by remember { mutableStateOf("") }
    val shown = filterSessions(sessions, query)
    // Kept outside the animated content so the dialog list keeps its scroll position across a settings visit.
    val listState = rememberLazyListState()
    val reduced = LocalChillerReducedMotion.current
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    // Saved choices arrive with the preferences; the user's clicks in this session win over them.
    LaunchedEffect(expansion) { expansion.forEach { (path, open) -> if (path !in expanded) expanded[path] = open } }
    val saved by rememberUpdatedState(expansion)
    val save by rememberUpdatedState(onExpansion)
    LaunchedEffect(Unit) {
        snapshotFlow { expanded.toMap() }.collect { current -> if (current.isNotEmpty() && current != saved) save(current) }
    }
    val showAll = remember { mutableStateMapOf<String, Boolean>() }
    var dropping by remember { mutableStateOf(false) }
    val dropTarget = remember(onOpenPath) { folderDropTarget(onOpenPath) { dropping = it } }
    Box(Modifier.width(width.dp).fillMaxHeight().dragAndDropTarget(shouldStartDragAndDrop = { !settingsOpen }, target = dropTarget)) {
    Column(Modifier.fillMaxSize().background(c.sidebar).padding(horizontal = 12.dp)) {
        // Room for the floating traffic lights; the strip moves the window like a title bar.
        val trafficLights = titleBar != null && titleBar.trafficLightsWidth > 0.dp
        if (trafficLights) titleBar!!.dragArea(Modifier.fillMaxWidth().height(titleBar.height))
        Row(Modifier.fillMaxWidth().height(if (trafficLights) 44.dp else if (titleBar != null) titleBar.height else 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChillerMark(Modifier.size(23.dp), c.accent); Spacer(Modifier.width(9.dp))
            Text("Chiller", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.4f).sp)
            Spacer(Modifier.weight(1f))
            // Without a system menu bar the app menu lives by the logo, away from the sidebar toggle in the toolbar.
            titleBar?.menu?.let { AppMenuButton(it) }
        }
        // While settings are open the chats cannot be used, so the sidebar turns into the settings navigation.
        // Opening slides it in from the right, closing slides the dialogs back from the left.
        AnimatedContent(
            settingsOpen, Modifier.weight(1f).fillMaxWidth(), label = "sidebar",
            transitionSpec = {
                if (reduced) EnterTransition.None togetherWith ExitTransition.None
                else {
                    val direction = if (targetState) 1 else -1
                    (slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { direction * it / 3 } + fadeIn(tween(200, 40))) togetherWith
                        (slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { -direction * it / 3 } + fadeOut(tween(120)))
                }
            },
        ) { inSettings ->
        if (inSettings) Column(Modifier.fillMaxSize()) {
            SettingsNav(settingsSection, onSettingsSection, onCloseSettings, Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp), connectionDot = connectionSummary(connection, connected).second)
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border.copy(alpha = .75f)))
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 3.dp).heightIn(min = 37.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull("Chiller", version).joinToString(" "), color = c.faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp))
                ChillerIconButton(Icons.Outlined.Contrast, "Сменить тему", onTheme)
            }
        }
        else Column(Modifier.fillMaxSize()) {
        // Dialogs stay reachable while one runs: it goes on in the background.
        ChillerControl("Новый диалог", onNew, Modifier.fillMaxWidth(), icon = Icons.Outlined.Add, trailing = shortcut("N"))
        SidebarSearch(query, { query = it }, searchFocus)
        Spacer(Modifier.height(6.dp))
        val searching = query.isNotBlank()
        // Messages are searched a moment after typing stops, in the app's own journals only.
        val hits by produceState(emptyList<dev.chiller.sessions.SessionHit>(), query) {
            value = if (query.trim().length < 2) emptyList() else { kotlinx.coroutines.delay(250); runCatching { onSearchMessages(query) }.getOrDefault(emptyList()) }
        }
        if (searching && shown.isEmpty() && hits.isEmpty()) Text("Ничего не найдено по «${query.trim()}»", color = c.faint, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
        val roots = remember(tree) { tree.projects.flatMap { group -> group.sessions.map { it.id to group.path } }.toMap() }
        val row: @Composable (SessionInfo, Boolean, String?) -> Unit = { session, nested, project ->
            val runs = session.id in projects.running || (running && session.id == selected)
            SessionRow(session, session.id == selected, runs, true, { onSelect(session.id) }, { onRename(session) }, { onDelete(session) }, !running && !runs && session.id != selected, { onExport(session.id, it) },
                nested = nested, project = project, waiting = session.id in projects.waiting && session.id != selected,
                unread = session.id in projects.unread && session.id != selected, isolated = session.id in projects.origins,
                onStop = if (runs && session.id != selected) ({ projects.onStop(session.id) }) else null)
        }
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        // ↑ ↓ walk the projects and dialogs; Enter opens, → and ← expand and collapse a project.
        LazyColumn(Modifier.weight(1f).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false
            else when (event.key) {
                Key.DirectionDown -> focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                Key.DirectionUp -> focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)
                else -> false
            }
        }, state = listState, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (searching) {
                if (shown.isNotEmpty()) item(key = "search-header") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text("Найдено", color = c.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("${shown.size} из ${sessions.size}", color = c.faint, fontSize = 11.sp)
                    }
                }
                items(shown, key = { it.id }) { session -> row(session, false, roots[session.id]?.let(::folderName) ?: "без папки") }
                if (hits.isNotEmpty()) item(key = "hits-header") {
                    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 4.dp)) {
                        Text("В сообщениях", color = c.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("${hits.size}", color = c.faint, fontSize = 11.sp)
                    }
                }
                items(hits, key = { "hit:" + it.sessionId }) { hit ->
                    SearchHitRow(hit, sessions.firstOrNull { it.id == hit.sessionId }?.title ?: "Диалог", roots[hit.sessionId]?.let(::folderName), hit.sessionId == selected) { onOpenHit(hit, query.trim()) }
                }
            } else projectTreeItems(tree, workspace, selected, reduced, expanded, showAll, projects, onChoose) { session, nested -> row(session, nested, null) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border.copy(alpha = .75f)))
        val (line, dot) = connectionSummary(connection, connected)
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            HoverRow(onSettings, Modifier.weight(1f), enabled = enabled) {
                Icon(Icons.Outlined.Settings, null, Modifier.size(16.dp), tint = c.muted)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("Настройки", color = c.text, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
                        Spacer(Modifier.width(6.dp))
                        Text(line, color = c.muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            ChillerIconButton(Icons.Outlined.Contrast, "Сменить тему", onTheme)
        }
        }
        }
    }
    if (dropping) Box(
        Modifier.matchParentSize().padding(8.dp).clip(RoundedCornerShape(12.dp)).background(c.sidebar.copy(alpha = .92f))
            .border(2.dp, c.accent.copy(alpha = .6f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(24.dp), tint = c.accent)
            Spacer(Modifier.height(8.dp))
            Text("Отпустите, чтобы открыть проект", color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
    }
}

@Composable
internal fun SidebarSearch(query: String, onQuery: (String) -> Unit, focus: FocusRequester) {
    val c = LocalChillerPalette.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.padding(top = 6.dp).fillMaxWidth().height(32.dp).clip(RoundedCornerShape(7.dp))
            .background(if (focused) c.surface else c.hover.copy(alpha = .55f))
            .border(1.dp, if (focused) c.border else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(7.dp)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, Modifier.size(14.dp), tint = c.faint)
        Spacer(Modifier.width(8.dp))
        BasicTextField(query, onQuery, singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = 13.sp, lineHeight = 19.sp, letterSpacing = 0.sp), cursorBrush = SolidColor(c.text),
            modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent {
                    when {
                        it.type != KeyEventType.KeyDown -> false
                        it.key == Key.Escape && query.isNotEmpty() -> { onQuery(""); true }
                        it.key == Key.DirectionDown -> { ChillerFocusVisibility.keyboard = true; focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }
                        else -> false
                    }
                }
                .semantics { contentDescription = "Поиск диалогов" },
            decorationBox = { inner -> Box { if (query.isEmpty()) Text("Поиск", color = c.faint, fontSize = 13.sp, lineHeight = 19.sp); inner() } },
        )
        if (query.isNotEmpty()) Icon(Icons.Outlined.Close, "Очистить поиск", Modifier.size(14.dp).clip(CircleShape).clickable { onQuery("") }, tint = c.faint)
        else Text(shortcut("K"), color = c.faint, fontSize = 11.sp)
    }
}

@Composable
internal fun SessionRow(session: SessionInfo, selected: Boolean, running: Boolean, enabled: Boolean, onSelect: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, canDelete: Boolean, onExport: (String) -> Unit,
    nested: Boolean = false, project: String? = null, waiting: Boolean = false, onStop: (() -> Unit)? = null, unread: Boolean = false, isolated: Boolean = false) {
    val c = LocalChillerPalette.current
    var menu by remember(session.id) { mutableStateOf(false) }
    var closedAt by remember { mutableStateOf(0L) }
    var rowFocused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(7.dp))
            .background(when { selected -> c.selected; (hovered || (rowFocused && ChillerFocusVisibility.keyboard)) && enabled -> c.hover; else -> androidx.compose.ui.graphics.Color.Transparent })
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
            .onFocusChanged { rowFocused = it.isFocused }
            // The row is one stop for the arrows; its menu opens with the context-menu key or Shift+F10.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && enabled && (event.key == Key.Menu || (event.isShiftPressed && event.key == Key.F10))) { menu = true; true } else false
            }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onSelect)
            .padding(start = if (nested) 24.dp else 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Inside a project the title lines up with the project name; the icon is left for the running pulse.
        Box(Modifier.size(15.dp), contentAlignment = Alignment.Center) {
            if (waiting) Box(Modifier.size(8.dp).clip(CircleShape).background(c.focus)) else if (running) Pulse(c.accent)
            else if (unread) Box(Modifier.size(7.dp).clip(CircleShape).background(c.accent))
            else if (isolated) Icon(Icons.Outlined.AccountTree, "Отдельная копия", Modifier.size(12.dp), tint = c.faint) else if (!nested) Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(14.dp), tint = if (selected) c.muted else c.faint)
        }
        Spacer(Modifier.width(9.dp))
        Text(session.title.ifBlank { "Новый диалог" }, color = if (selected || !nested || unread) c.text else c.text.copy(alpha = .86f), fontSize = 13.sp, lineHeight = 19.sp,
            fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (project != null && !hovered && !menu) Text(project, color = c.faint, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 70.dp).padding(start = 6.dp))
        Box(Modifier.width(44.dp), contentAlignment = Alignment.CenterEnd) {
            if (hovered || menu || selected || rowFocused) {
                Box {
                    ChillerIconButton(Icons.Outlined.MoreHoriz, "Действия диалога", { if (System.currentTimeMillis() - closedAt > 200) menu = !menu },
                        Modifier.size(26.dp).focusProperties { canFocus = false }, enabled = enabled)
                    if (menu) ChillerPopover({ menu = false; closedAt = System.currentTimeMillis() }, 200.dp, below = true, alignEnd = true) {
                        Spacer(Modifier.height(5.dp))
                        onStop?.let { stop -> MenuRow("Остановить", { menu = false; stop() }, subtitle = "Задача идёт в фоне", icon = Icons.Outlined.Stop); MenuDivider() }
                        MenuRow("Переименовать", { menu = false; onRename() }, icon = Icons.Outlined.Edit)
                        MenuRow("Экспорт Markdown", { menu = false; onExport("markdown") }, icon = Icons.Outlined.Description)
                        MenuRow("Экспорт JSON", { menu = false; onExport("json") }, icon = Icons.Outlined.DataObject)
                        if (canDelete) {
                            MenuDivider()
                            MenuRow("Удалить диалог…", { menu = false; onDelete() }, icon = Icons.Outlined.DeleteOutline)
                        }
                        Spacer(Modifier.height(5.dp))
                    }
                }
            } else Text(if (waiting) "ждёт ответа" else if (running) "идёт" else relativeAge(session.updatedAt), color = if (waiting) c.focus else if (running) c.accent else c.faint, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

/** A dialog found by its messages: its title, the matching words highlighted in a snippet, and how often it matched. */
@Composable
internal fun SearchHitRow(hit: dev.chiller.sessions.SessionHit, title: String, project: String?, selected: Boolean, onOpen: () -> Unit) {
    val c = LocalChillerPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val snippet = remember(hit, c) {
        androidx.compose.ui.text.buildAnnotatedString {
            val end = (hit.matchStart + hit.matchLength).coerceAtMost(hit.snippet.length)
            append(hit.snippet.substring(0, hit.matchStart.coerceIn(0, end)))
            pushStyle(androidx.compose.ui.text.SpanStyle(color = c.text, fontWeight = FontWeight.SemiBold, background = c.accent.copy(alpha = .16f)))
            append(hit.snippet.substring(hit.matchStart.coerceIn(0, end), end)); pop()
            append(hit.snippet.substring(end))
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(if (selected) c.selected else if (hovered) c.hover else androidx.compose.ui.graphics.Color.Transparent)
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = "Найдено в «$title»" }
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title.ifBlank { "Новый диалог" }, color = c.text, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (hit.matches > 1) Text("×${hit.matches}", color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
        }
        Text(snippet, color = c.muted, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(if (hit.role == "user") "вы" else "модель", project).joinToString(" · "), color = c.faint, fontSize = 10.sp, lineHeight = 14.sp)
    }
}
