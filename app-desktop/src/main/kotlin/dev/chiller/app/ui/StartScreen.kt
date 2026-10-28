package dev.chiller.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chiller.design.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * The empty conversation. Without a folder it offers recent projects and drag-and-drop; with one it
 * shows what Chiller sees in the project and stack-aware starter tasks that fill the composer.
 */
@Composable
internal fun StartScreen(
    workspacePath: String?, recent: List<RecentProject>, modelName: String, connectionLine: String, connectionColor: Color,
    onOpenFolder: () -> Unit, onOpenPath: (String) -> Unit, onStarter: (Starter) -> Unit, modifier: Modifier = Modifier,
    resume: ResumeItem? = null, onResume: (String) -> Unit = {}, connection: ConnectionPrompt? = null,
    onDemo: (() -> Unit)? = null,
    onCloseProject: () -> Unit = {},
) {
    val c = LocalChillerPalette.current
    var dropping by remember { mutableStateOf(false) }
    val dropTarget = remember(onOpenPath) { folderDropTarget(onOpenPath) { dropping = it } }
    Box(
        modifier.dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
            .then(if (dropping) Modifier.padding(12.dp).clip(RoundedCornerShape(16.dp)).border(2.dp, c.accent.copy(alpha = .6f), RoundedCornerShape(16.dp)).background(c.accent.copy(alpha = .04f)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Low windows drop decoration first so the actionable blocks stay above the composer.
        val tight = maxHeight < 560.dp
        val hasHistory = workspacePath == null && (resume != null || recent.isNotEmpty())
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!(tight && hasHistory)) {
                ChillerMark(Modifier.size(if (workspacePath == null) 32.dp else 28.dp), c.accent)
                Spacer(Modifier.height(if (workspacePath == null) 18.dp else 14.dp))
            }
            if (workspacePath == null) {
                Text("Что будем создавать?", fontSize = if (tight) 22.sp else 26.sp, lineHeight = if (tight) 30.sp else 34.sp, letterSpacing = (-.6).sp, fontWeight = FontWeight.Medium, color = c.text, textAlign = TextAlign.Center)
                if (!hasHistory) {
                    Spacer(Modifier.height(8.dp))
                    Text("Откройте папку проекта — или перетащите её в окно", color = c.muted, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    ChillerControl("Открыть проект…", onOpenFolder, icon = Icons.Outlined.FolderOpen, style = ControlStyle.OUTLINE, trailing = shortcut("O"), inlineTrailing = true)
                }
                Spacer(Modifier.height(if (tight) 18.dp else 26.dp))
                resume?.let { item ->
                    SectionLabel("Продолжить")
                    ResumeCard(item) { onResume(item.sessionId) }
                    Spacer(Modifier.height(16.dp))
                }
                if (recent.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Недавние проекты", color = c.muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
                        ChillerControl("Открыть другой…", onOpenFolder, icon = Icons.Outlined.FolderOpen, trailing = shortcut("O"), inlineTrailing = true)
                    }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(12.dp)).padding(4.dp)) {
                        recent.take(if (tight) 3 else 5).forEach { project -> RecentProjectRow(project) { onOpenPath(project.path) } }
                    }
                    Spacer(Modifier.height(16.dp))
                } else if (hasHistory) {
                    ChillerControl("Открыть проект…", onOpenFolder, icon = Icons.Outlined.FolderOpen, style = ControlStyle.OUTLINE, trailing = shortcut("O"), inlineTrailing = true)
                    Spacer(Modifier.height(16.dp))
                }
                connection?.let { ConnectionRow(modelName, connectionLine, connectionColor, it); Spacer(Modifier.height(16.dp)) }
                onDemo?.let {
                    ChillerControl("Посмотреть демо: все инструменты на тестовом проекте", it, icon = Icons.Outlined.PlayCircleOutline)
                    Spacer(Modifier.height(10.dp))
                }
                if (!tight) ShortcutHints()
            } else {
                val name = Path.of(workspacePath).fileName?.toString() ?: workspacePath
                Text("Что сделаем в $name?", fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-.6).sp, fontWeight = FontWeight.Medium, color = c.text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(18.dp))
                val overview by produceState<ProjectOverview?>(null, workspacePath) { value = withContext(Dispatchers.IO) { runCatching { inspectProject(Path.of(workspacePath)) }.getOrNull() } }
                ProjectCard(workspacePath, overview, onOpenFolder, onCloseProject)
                Spacer(Modifier.height(14.dp))
                SectionLabel("Начать с задачи")
                val tasks = overview?.let(::starters) ?: starters(ProjectOverview(name, workspacePath, null, emptyList(), null, 0))
                tasks.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { StarterCard(it, Modifier.weight(1f)) { onStarter(it) } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun folderDropTarget(onOpenPath: (String) -> Unit, onActive: (Boolean) -> Unit) = object : DragAndDropTarget {
    override fun onEntered(event: DragAndDropEvent) = onActive(true)
    override fun onExited(event: DragAndDropEvent) = onActive(false)
    override fun onEnded(event: DragAndDropEvent) = onActive(false)
    override fun onDrop(event: DragAndDropEvent): Boolean {
        onActive(false)
        val files = event.dragData() as? DragData.FilesList ?: return false
        val folder = files.readFiles().asSequence()
            .mapNotNull { runCatching { Path.of(java.net.URI(it)) }.getOrNull() }
            .map { if (Files.isDirectory(it)) it else it.parent }
            .firstOrNull { it != null && Files.isDirectory(it) } ?: return false
        onOpenPath(folder.toString())
        return true
    }
}

@Composable
private fun SectionLabel(text: String) {
    val c = LocalChillerPalette.current
    Text(text, color = c.muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp))
}

@Composable
private fun RecentProjectRow(project: RecentProject, onClick: () -> Unit) {
    val c = LocalChillerPalette.current
    val exists = remember(project.path) { Files.isDirectory(Path.of(project.path)) }
    HoverRow(onClick, enabled = exists) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(if (exists) c.accent.copy(alpha = .10f) else c.hover), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Folder, null, Modifier.size(15.dp), tint = if (exists) c.accent else c.faint)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(Path.of(project.path).fileName?.toString() ?: project.path, color = if (exists) c.text else c.faint, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (exists) homeRelative(project.path) else "Папка не найдена · ${homeRelative(project.path)}", color = c.faint, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (project.dialogs > 0) Text("${project.dialogs} ${plural(project.dialogs, "диалог", "диалога", "диалогов")}" + (project.lastActive?.let { relativeAge(it) }?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
            color = c.faint, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(start = 8.dp))
        if (exists) { Spacer(Modifier.width(10.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(14.dp), tint = c.faint) }
    }
}

/** The most recent dialog, offered on the empty screen. Opening it restores its folder. */
data class ResumeItem(val sessionId: String, val title: String, val updatedAt: String, val project: String?)

@Composable
private fun ResumeCard(item: ResumeItem, onClick: () -> Unit) {
    val c = LocalChillerPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (hovered) c.hover else c.surface).border(1.dp, if (hovered) c.muted.copy(alpha = .35f) else c.border, shape)
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(c.sidebar), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(15.dp), tint = c.muted)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title.ifBlank { "Новый диалог" }, color = c.text, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(relativeAge(item.updatedAt).takeIf { it.isNotEmpty() }, item.project?.let { Path.of(it).fileName?.toString() }).joinToString(" · "),
                color = c.faint, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Открыть", color = c.muted, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(14.dp), tint = c.muted)
    }
}

/** Shown while the model is not verified: one line with the state and the action that fixes it. */
class ConnectionPrompt(val actionLabel: String, val onAction: () -> Unit)

@Composable
private fun ConnectionRow(modelName: String, line: String, color: Color, prompt: ConnectionPrompt) {
    val c = LocalChillerPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.sidebar.copy(alpha = .7f)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(10.dp))
        Text(modelName, color = c.text, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1)
        Text(" · $line", color = c.muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        ChillerControl(prompt.actionLabel, prompt.onAction)
    }
}

@Composable
private fun ShortcutHints() {
    val c = LocalChillerPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(shortcut("O") to "проект", shortcut("K") to "поиск", shortcut("N") to "новый диалог", shortcut("↵") to "отправить").forEach { (keys, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(keys, color = c.muted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, c.border, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, color = c.faint, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun ProjectCard(path: String, overview: ProjectOverview?, onChange: () -> Unit, onClose: () -> Unit) {
    val c = LocalChillerPalette.current
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.sidebar.copy(alpha = .6f)).border(1.dp, c.border, shape).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(c.accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(17.dp), tint = c.accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(overview?.name ?: Path.of(path).fileName.toString(), color = c.text, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(homeRelative(path), color = c.faint, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ChillerControl("Сменить", onChange)
            ChillerControl("Закрыть", onClose)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                overview == null -> Fact(Icons.Outlined.HourglassEmpty, "Смотрю проект…")
                else -> {
                    overview.branch?.let { Fact(Icons.Outlined.AccountTree, it) }
                    overview.stack.take(2).forEach { Fact(Icons.Outlined.Code, it) }
                    Fact(Icons.Outlined.Description, overview.files?.let { "$it ${plural(it, "файл", "файла", "файлов")}" } ?: "больше ${overview.limit} файлов")
                }
            }
        }
    }
}

@Composable
private fun Fact(icon: ImageVector, text: String) {
    val c = LocalChillerPalette.current
    Row(Modifier.clip(RoundedCornerShape(6.dp)).background(c.surface).border(1.dp, c.border.copy(alpha = .8f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(12.dp), tint = c.muted)
        Spacer(Modifier.width(5.dp))
        Text(text, color = c.muted, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
    }
}

@Composable
private fun StarterCard(starter: Starter, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalChillerPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.height(IntrinsicSize.Min).clip(shape).background(if (hovered) c.hover else c.surface).border(1.dp, if (hovered) c.muted.copy(alpha = .35f) else c.border, shape)
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(starter.title, color = c.text, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (starter.needsEdits) Icon(Icons.Outlined.Edit, "Нужен режим изменений", Modifier.size(13.dp), tint = c.faint)
        }
        Text(starter.detail, color = c.muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun HoverRow(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val c = LocalChillerPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (hovered && enabled) c.hover else Color.Transparent)
            .hoverable(interaction).pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

internal fun homeRelative(path: String): String {
    val home = System.getProperty("user.home").orEmpty()
    return if (home.isNotBlank() && path.startsWith(home)) "~" + path.removePrefix(home) else path
}

/** Last segment of a folder path with either separator, so Windows roots show «Chiller» rather than the whole path. */
internal fun folderName(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

/** What the system file manager is called, for «Показать в …» actions. */
internal val fileManagerName: String = System.getProperty("os.name").orEmpty().let { os ->
    when { os.startsWith("Mac") -> "Finder"; os.startsWith("Windows") -> "Проводнике"; else -> "файловом менеджере" }
}

internal fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100; val mod10 = n % 10
    return when { mod100 in 11..14 -> many; mod10 == 1 -> one; mod10 in 2..4 -> few; else -> many }
}
