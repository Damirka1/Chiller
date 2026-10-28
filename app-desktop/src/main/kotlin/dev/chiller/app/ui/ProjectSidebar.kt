package dev.chiller.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.chiller.contracts.SessionInfo
import dev.chiller.design.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** Project list state and actions shared by the sidebar, the composer's folder menu and the project switcher. */
data class ProjectsModel(
    val pinned: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
    /** The shown dialog keeps its folder; another folder opens a new dialog. */
    val folderLocked: Boolean = false,
    /** The shown dialog's folder is gone; it can be pointed at the new location. */
    val workspaceMissing: Boolean = false,
    /** Folders listed as projects only while open, such as the demo. */
    val excluded: (String) -> Boolean = { false },
    val onNewIn: (String?) -> Unit = {},
    val onPin: (String, Boolean) -> Unit = { _, _ -> },
    val onRemove: (String) -> Unit = {},
    val onReveal: (String) -> Unit = {},
    val onRelocate: (String) -> Unit = {},
    /** Dialogs whose run is in progress, shown or in the background, and those waiting for an answer. */
    val running: Set<String> = emptySet(),
    val waiting: Set<String> = emptySet(),
    /** Dialogs with a result the user has not seen yet. */
    val unread: Set<String> = emptySet(),
    val onStop: (String) -> Unit = {},
)

private const val PROJECT_PREVIEW = 5
private const val LOOSE_PREVIEW = 4
private const val LOOSE_KEY = "\u0000loose"

/**
 * The sidebar's projects and dialogs without a folder. The current project and the one holding the selected dialog
 * start expanded; the user's own expand/collapse wins after that.
 */
internal fun LazyListScope.projectTreeItems(
    tree: ProjectTree, current: String?, selected: String?, reduced: Boolean,
    expanded: SnapshotStateMap<String, Boolean>, showAll: SnapshotStateMap<String, Boolean>,
    model: ProjectsModel, onChoose: () -> Unit,
    sessionRow: @Composable (SessionInfo, nested: Boolean) -> Unit,
) {
    val enabled = true
    @Composable fun LazyItemScope.Animated(content: @Composable () -> Unit) =
        Box(if (reduced) Modifier else Modifier.animateItem(fadeInSpec = tween(180), placementSpec = spring(stiffness = 600f, dampingRatio = .9f), fadeOutSpec = tween(110))) { content() }

    item(key = "projects-header") { SectionHeader("Проекты", Icons.Outlined.CreateNewFolder, "Открыть проект…", onChoose, enabled, first = true) }
    if (tree.projects.isEmpty()) item(key = "projects-empty") {
        NestedAction("Открыть папку проекта", Icons.Outlined.FolderOpen, enabled, indent = 10.dp, trailing = shortcut("O"), onClick = onChoose)
    }
    tree.projects.forEach { group ->
        val open = expanded[group.path] ?: (group.path == current || group.sessions.any { it.id == selected })
        item(key = "project:${group.path}") {
            val activity = when {
                group.sessions.any { it.id in model.waiting } -> ProjectActivity.WAITING
                group.sessions.any { it.id in model.running } -> ProjectActivity.RUNNING
                group.sessions.any { it.id in model.unread } -> ProjectActivity.UNREAD
                else -> ProjectActivity.IDLE
            }
            Animated { ProjectRow(group, group.path == current, open, activity, enabled, reduced, model) { expanded[group.path] = !open } }
        }
        if (open) {
            val all = showAll[group.path] == true
            if (group.sessions.isEmpty()) item(key = "project-empty:${group.path}") {
                Animated { NestedAction("Новый диалог", Icons.Outlined.Add, enabled) { model.onNewIn(group.path) } }
            }
            items(if (all) group.sessions else group.sessions.take(PROJECT_PREVIEW), key = { it.id }) { session -> Animated { sessionRow(session, true) } }
            if (group.sessions.size > PROJECT_PREVIEW) item(key = "project-more:${group.path}") {
                Animated { NestedAction(if (all) "Свернуть" else "Ещё ${group.sessions.size - PROJECT_PREVIEW}", if (all) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, true) { showAll[group.path] = !all } }
            }
        }
    }
    item(key = "loose-header") { SectionHeader("Без папки", Icons.Outlined.Add, "Новый диалог без папки", { model.onNewIn(null) }, enabled) }
    if (tree.loose.isEmpty()) item(key = "loose-empty") {
        Text("Вопросы, тексты и поиск в вебе — без доступа к файлам", color = LocalChillerPalette.current.faint, fontSize = 11.sp, lineHeight = 16.sp,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 6.dp))
    }
    val allLoose = showAll[LOOSE_KEY] == true
    items(if (allLoose) tree.loose else tree.loose.take(LOOSE_PREVIEW), key = { it.id }) { session -> Animated { sessionRow(session, false) } }
    if (tree.loose.size > LOOSE_PREVIEW) item(key = "loose-more") {
        Animated { NestedAction(if (allLoose) "Свернуть" else "Ещё ${tree.loose.size - LOOSE_PREVIEW}", if (allLoose) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, true, indent = 10.dp) { showAll[LOOSE_KEY] = !allLoose } }
    }
}

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, enabled: Boolean, first: Boolean = false) {
    val c = LocalChillerPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = if (first) 4.dp else 14.dp, bottom = 2.dp).height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = c.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        ChillerIconButton(icon, label, onClick, Modifier.size(24.dp), enabled = enabled)
    }
}

/** A quiet row inside the tree: «Новый диалог», «Ещё 7». */
@Composable
private fun NestedAction(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, indent: Dp = 24.dp, trailing: String? = null, onClick: () -> Unit) {
    val c = LocalChillerPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(7.dp)).background(if (hovered && enabled) c.hover else Color.Transparent)
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = indent, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(15.dp), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(13.dp), tint = c.faint) }
        Spacer(Modifier.width(9.dp))
        Text(title, color = if (hovered && enabled) c.muted else c.faint, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, color = c.faint, fontSize = 10.sp) }
    }
}

private enum class ProjectActivity { IDLE, RUNNING, WAITING, UNREAD }

@Composable
private fun ProjectRow(group: ProjectGroup, current: Boolean, open: Boolean, activity: ProjectActivity, enabled: Boolean, reduced: Boolean, model: ProjectsModel, onToggle: () -> Unit) {
    val c = LocalChillerPalette.current
    val name = folderName(group.path)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menu by remember(group.path) { mutableStateOf(false) }
    var closedAt by remember { mutableStateOf(0L) }
    val exists by produceState(true, group.path, model.workspaceMissing) { value = withContext(Dispatchers.IO) { Files.isDirectory(Path.of(group.path)) } }
    val branch by produceState<String?>(null, group.path, current) { value = if (current) withContext(Dispatchers.IO) { runCatching { gitBranch(Path.of(group.path)) }.getOrNull() } else null }
    val turn by animateFloatAsState(if (open) 0f else -90f, if (reduced) snap() else tween(160), label = "chevron")
    val actions = hovered || menu
    Hint(homeRelative(group.path)) {
        Row(
            Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(7.dp))
                .background(if (hovered && enabled) c.hover else Color.Transparent)
                .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onToggle)
                .semantics { contentDescription = "Проект $name, ${if (open) "свернуть" else "развернуть"}" }
                .padding(start = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(14.dp).graphicsLayer { rotationZ = turn }, tint = c.faint)
            Spacer(Modifier.width(3.dp))
            ProjectTile(group.path, 20.dp, dimmed = !exists)
            Spacer(Modifier.width(7.dp))
            Text(name, color = if (exists) c.text else c.faint, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (actions) {
                ChillerIconButton(Icons.Outlined.Add, "Новый диалог в $name", { model.onNewIn(group.path) }, Modifier.size(24.dp), enabled = enabled && exists)
                Box {
                    ChillerIconButton(Icons.Outlined.MoreHoriz, "Действия проекта", { if (System.currentTimeMillis() - closedAt > 200) menu = !menu }, Modifier.size(24.dp))
                    if (menu) ChillerPopover({ menu = false; closedAt = System.currentTimeMillis() }, 240.dp, below = true, alignEnd = true) {
                        // Picking an item closes the menu; only an outside click needs the reopen guard.
                        val close = { menu = false }
                        Spacer(Modifier.height(5.dp))
                        MenuRow("Новый диалог", { close(); model.onNewIn(group.path) }, icon = Icons.Outlined.Add, enabled = enabled && exists)
                        MenuRow(if (group.pinned) "Открепить" else "Закрепить наверху", { close(); model.onPin(group.path, !group.pinned) }, icon = Icons.Outlined.PushPin)
                        if (exists) MenuRow("Показать в $fileManagerName", { close(); model.onReveal(group.path) }, icon = Icons.Outlined.FolderOpen)
                        else MenuRow("Указать новое место…", { close(); model.onRelocate(group.path) }, subtitle = "Папку переместили или переименовали", icon = Icons.Outlined.FolderOpen, enabled = enabled)
                        MenuDivider()
                        MenuRow("Убрать из списка", { close(); model.onRemove(group.path) }, subtitle = "Папка и диалоги останутся", icon = Icons.Outlined.VisibilityOff, enabled = !current)
                        Spacer(Modifier.height(5.dp))
                    }
                }
            } else {
                when {
                    // Collapsed, the project still shows that one of its dialogs runs or waits for an answer.
                    activity == ProjectActivity.WAITING -> Box(Modifier.padding(end = 9.dp).size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.focus))
                    activity == ProjectActivity.RUNNING -> Box(Modifier.padding(end = 9.dp)) { Pulse(c.accent) }
                    activity == ProjectActivity.UNREAD && !open -> Box(Modifier.padding(end = 9.dp).size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.accent))
                    !exists -> Icon(Icons.Outlined.WarningAmber, "Папка не найдена", Modifier.padding(end = 7.dp).size(13.dp), tint = c.negative)
                    branch != null -> Text(branch!!, color = c.faint, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 76.dp).padding(end = 7.dp))
                    group.pinned -> Icon(Icons.Outlined.PushPin, "Закреплён", Modifier.padding(end = 8.dp).size(11.dp), tint = c.faint)
                    !open && group.sessions.isNotEmpty() -> Text("${group.sessions.size}", color = c.faint, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(end = 9.dp))
                }
            }
        }
    }
}

private val TileHues = floatArrayOf(152f, 205f, 18f, 280f, 42f, 180f, 330f, 228f)

/** A project's monogram: a soft, stable tint per folder name so projects are told apart at a glance. */
@Composable
internal fun ProjectTile(path: String, size: Dp = 18.dp, dimmed: Boolean = false) {
    val c = LocalChillerPalette.current
    val name = folderName(path)
    val dark = c.canvas.luminance() < .5f
    val hue = remember(name) { TileHues[(name.lowercase().hashCode() and Int.MAX_VALUE) % TileHues.size] }
    val ink = if (dimmed) c.faint else Color.hsl(hue, if (dark) .42f else .40f, if (dark) .74f else .38f)
    val initials = remember(name) { projectInitials(name) }
    val fontSize = with(LocalDensity.current) { (size * if (initials.length > 1) .44f else .56f).toSp() }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * .3f)).background(ink.copy(alpha = if (dark) .20f else .15f)), contentAlignment = Alignment.Center) {
        Text(initials, color = ink, fontSize = fontSize, lineHeight = fontSize, fontWeight = FontWeight.SemiBold, letterSpacing = (-.2).sp, maxLines = 1)
    }
}

/** What the composer's folder chip offers; see [FolderChip]. */
data class FolderMenu(
    val workspace: String?, val projects: List<String>, val locked: Boolean, val missing: Boolean,
    val onOpen: (String) -> Unit, val onChoose: () -> Unit, val onDetach: () -> Unit, val onRelocate: (String) -> Unit,
    /** For a dialog with a fixed folder: a new dialog elsewhere that starts from a draft of this one. */
    val onContinueElsewhere: (() -> Unit)? = null,
)

/**
 * The folder of the next message. While the dialog's folder can change the menu swaps it in place; once fixed, a
 * folder picked here starts a new dialog, and the menu says so.
 */
@Composable
internal fun FolderChip(menu: FolderMenu, enabled: Boolean) {
    val c = LocalChillerPalette.current
    var open by remember { mutableStateOf(false) }
    var closedAt by remember { mutableStateOf(0L) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val workspace = menu.workspace
    Box {
        Row(
            Modifier.widthIn(max = 168.dp).height(28.dp).clip(RoundedCornerShape(7.dp)).background(if ((hovered || open) && enabled) c.hover else c.sidebar)
                .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button) { if (System.currentTimeMillis() - closedAt > 200) open = !open }
                .semantics { contentDescription = "Папка диалога: ${workspace?.let(::folderName) ?: "без папки"}" }
                .padding(start = 6.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (workspace != null) ProjectTile(workspace, 15.dp, dimmed = menu.missing)
            else Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(13.dp), tint = c.faint)
            Spacer(Modifier.width(6.dp))
            Text(workspace?.let(::folderName) ?: "Без папки", color = if (menu.missing) c.negative else if (workspace == null) c.faint else c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (menu.locked && workspace != null) { Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.Lock, null, Modifier.size(10.dp), tint = c.faint) }
        }
        if (open) ChillerPopover({ open = false; closedAt = System.currentTimeMillis() }, 288.dp) {
            val close = { open = false }
            Spacer(Modifier.height(6.dp))
            if (menu.locked) Row(Modifier.padding(start = 16.dp, end = 14.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Lock, null, Modifier.padding(top = 2.dp).size(12.dp), tint = c.faint)
                Spacer(Modifier.width(8.dp))
                Text(if (workspace != null) "Папка закреплена за диалогом: его правки и откаты относятся к ней. Другая папка откроет новый диалог."
                    else "В диалоге уже есть файлы. Другая папка откроет новый диалог.", color = c.faint, fontSize = 11.sp, lineHeight = 15.sp)
            }
            if (menu.locked) menu.onContinueElsewhere?.let { continueElsewhere ->
                MenuRow("Продолжить в другой папке…", { close(); continueElsewhere() }, subtitle = "Новый диалог с кратким пересказом этого", icon = Icons.Outlined.CreateNewFolder)
                MenuDivider()
            }
            if (menu.missing && workspace != null) {
                MenuRow("Указать новое место…", { close(); menu.onRelocate(workspace) }, subtitle = "Папку переместили или переименовали", icon = Icons.Outlined.WarningAmber)
                MenuDivider()
            }
            val others = menu.projects.filter { it != workspace }.take(6)
            if (workspace != null) FolderMenuRow(workspace, selected = true, hint = null) { close() }
            others.forEach { path -> FolderMenuRow(path, selected = false, hint = if (menu.locked) "новый диалог" else null) { close(); menu.onOpen(path) } }
            if (workspace != null || others.isNotEmpty()) MenuDivider()
            MenuRow("Открыть папку…", { close(); menu.onChoose() }, icon = Icons.Outlined.CreateNewFolder, shortcut = shortcut("O"))
            MenuRow(if (menu.locked && workspace != null) "Новый диалог без папки" else "Без папки", { close(); menu.onDetach() }, subtitle = "Без доступа к файлам и командам",
                icon = Icons.AutoMirrored.Outlined.Chat, selected = workspace == null)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun FolderMenuRow(path: String, selected: Boolean, hint: String?, onClick: () -> Unit) {
    val c = LocalChillerPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(8.dp)).background(if (hovered) c.hover else Color.Transparent)
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProjectTile(path, 20.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(folderName(path), color = c.text, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(homeRelative(path), color = c.faint, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        hint?.let { Text(it, color = c.faint, fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp)) }
        if (selected) Icon(Icons.Outlined.Check, null, Modifier.padding(start = 8.dp).size(16.dp), tint = c.accent)
    }
}

/**
 * Ctrl+P: jump to a project by typing part of its name. Enter starts a new dialog there, Shift+Enter opens the
 * project's latest dialog.
 */
@Composable
internal fun ProjectSwitcher(projects: List<ProjectGroup>, current: String?, onPick: (String?) -> Unit, onChoose: () -> Unit, onDismiss: () -> Unit,
    onOpenDialog: (String) -> Unit = {}) {
    val c = LocalChillerPalette.current
    var query by remember { mutableStateOf("") }
    var highlight by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val matches = remember(query, projects) {
        val q = query.trim()
        if (q.isEmpty()) projects else projects.filter { folderName(it.path).contains(q, true) || it.path.contains(q, true) }
            .sortedBy { if (folderName(it.path).startsWith(q, true)) 0 else 1 }
    }
    // Rows: the matches, then «Без папки» and «Открыть папку…».
    val rows = matches.size + 2
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) { highlight = 0 }
    fun pick(index: Int) = when {
        index < matches.size -> onPick(matches[index].path)
        index == matches.size -> onPick(null)
        else -> { onDismiss(); onChoose() }
    }
    val reduced = LocalChillerReducedMotion.current
    val shape = RoundedCornerShape(14.dp)
    val topOffset = with(LocalDensity.current) { 72.dp.roundToPx() }
    Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, topOffset), onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        var shown by remember { mutableStateOf(reduced) }
        LaunchedEffect(Unit) { shown = true }
        val appear by animateFloatAsState(if (shown) 1f else 0f, if (reduced) snap() else tween(140), label = "switcher")
        Column(
            Modifier.padding(16.dp).width(480.dp).graphicsLayer { alpha = appear; scaleX = .98f + .02f * appear; scaleY = .98f + .02f * appear }
                .shadow(28.dp, shape, ambientColor = Color.Black.copy(alpha = .12f), spotColor = Color.Black.copy(alpha = .22f))
                .clip(shape).background(c.surface).border(1.dp, c.border, shape)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> { highlight = (highlight + 1) % rows; true }
                        Key.DirectionUp -> { highlight = (highlight - 1 + rows) % rows; true }
                        Key.Enter, Key.NumPadEnter -> {
                            val latest = matches.getOrNull(highlight)?.sessions?.firstOrNull()
                            if (event.isShiftPressed && latest != null) onOpenDialog(latest.id) else pick(highlight)
                            true
                        }
                        Key.Escape -> { onDismiss(); true }
                        else -> false
                    }
                },
        ) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), tint = c.faint)
                Spacer(Modifier.width(10.dp))
                BasicTextField(query, { query = it }, singleLine = true,
                    textStyle = TextStyle(color = c.text, fontSize = 14.sp, lineHeight = 20.sp), cursorBrush = SolidColor(c.text),
                    modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "Поиск проекта" },
                    decorationBox = { inner -> Box { if (query.isEmpty()) Text("Перейти к проекту…", color = c.faint, fontSize = 14.sp, lineHeight = 20.sp); inner() } })
                Text("Esc", color = c.faint, fontSize = 11.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                if (matches.isEmpty()) Text(if (projects.isEmpty()) "Проектов пока нет" else "Нет проектов с «${query.trim()}»", color = c.faint, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                matches.forEachIndexed { index, group ->
                    SwitcherRow(index == highlight, { pick(index) }, { highlight = index }) {
                        ProjectTile(group.path, 24.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folderName(group.path), color = c.text, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(homeRelative(group.path), color = c.faint, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val meta = listOfNotNull(
                            "открыт".takeIf { group.path == current },
                            group.sessions.size.takeIf { it > 0 }?.let { "$it ${plural(it, "диалог", "диалога", "диалогов")}" },
                            group.lastActive?.let { relativeAge(it) }?.takeIf { it.isNotEmpty() },
                        ).joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp))
                        if (group.pinned) Icon(Icons.Outlined.PushPin, null, Modifier.padding(start = 8.dp).size(12.dp), tint = c.faint)
                    }
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(c.border.copy(alpha = .7f)))
                SwitcherRow(highlight == matches.size, { pick(matches.size) }, { highlight = matches.size }) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(15.dp), tint = c.muted) }
                    Spacer(Modifier.width(12.dp))
                    Text("Новый диалог без папки", color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
                SwitcherRow(highlight == matches.size + 1, { pick(matches.size + 1) }, { highlight = matches.size + 1 }) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(15.dp), tint = c.muted) }
                    Spacer(Modifier.width(12.dp))
                    Text("Открыть папку…", color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(shortcut("O"), color = c.faint, fontSize = 11.sp)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
            Text("↑↓ — выбрать · Enter — новый диалог · Shift+Enter — последний диалог", color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun SwitcherRow(highlighted: Boolean, onClick: () -> Unit, onHover: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val c = LocalChillerPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(9.dp)).background(if (highlighted) c.hover else Color.Transparent)
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

/** Shown above the composer when the dialog's folder is gone: point it at the new place or start over. */
@Composable
internal fun MissingFolderBanner(path: String, enabled: Boolean, onRelocate: () -> Unit, onNewDialog: () -> Unit) {
    val c = LocalChillerPalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.sidebar).border(1.dp, c.border, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.WarningAmber, null, Modifier.size(17.dp), tint = c.negative)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Папка проекта не найдена", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(homeRelative(path), color = c.muted, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ChillerControl("Новый диалог", onNewDialog, enabled = enabled)
        Spacer(Modifier.width(6.dp))
        ChillerControl("Указать новое место…", onRelocate, icon = Icons.Outlined.FolderOpen, style = ControlStyle.PRIMARY, enabled = enabled)
    }
}
