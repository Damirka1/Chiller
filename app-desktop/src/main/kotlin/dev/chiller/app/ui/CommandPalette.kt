package dev.chiller.app.ui

import dev.chiller.app.i18n.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.chiller.design.LocalChillerPalette
import dev.chiller.design.LocalChillerReducedMotion

/** One action of the palette: [group] says where it lives in the app, [shortcut] how to reach it without the palette. */
data class PaletteCommand(val title: String, val group: String, val icon: ImageVector = Icons.Outlined.ChevronRight, val shortcut: String? = null, val run: () -> Unit)

/** Every word of the query must start a word of the title or the group, in any order: «нов диал», «studio», «тем». */
fun matchCommands(commands: List<PaletteCommand>, query: String): List<PaletteCommand> {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return commands
    return commands.filter { command ->
        val haystack = (command.title + " " + command.group).lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        words.all { word -> haystack.any { it.startsWith(word) } }
    }.sortedBy { if (it.title.lowercase().startsWith(words.first())) 0 else 1 }
}

/** The app's actions from the system menu and the window itself, searchable by words; the menu entries come from [menu]. */
fun menuCommands(menu: List<AppMenuSection>): List<PaletteCommand> = menu.flatMap { section ->
    section.groups.flatten().filter { it.enabled }.map { item -> PaletteCommand(item.title.removeSuffix("…"), section.title, shortcut = item.keyLabel?.let(::shortcut), run = item.onClick) }
}

/** Ctrl+Shift+P: every action of the app by typing a part of its name. */
@Composable
internal fun CommandPalette(commands: List<PaletteCommand>, onDismiss: () -> Unit) {
    val c = LocalChillerPalette.current
    var query by remember { mutableStateOf("") }
    var highlight by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val matches = remember(query, commands) { matchCommands(commands, query) }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) { highlight = 0 }
    LaunchedEffect(highlight) { if (matches.isNotEmpty()) list.animateScrollToItem(highlight.coerceIn(0, matches.lastIndex)) }
    fun run(index: Int) { matches.getOrNull(index)?.let { command -> onDismiss(); command.run() } }
    val reduced = LocalChillerReducedMotion.current
    var shown by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) { shown = true }
    val appear by animateFloatAsState(if (shown) 1f else 0f, if (reduced) snap() else tween(140), label = "palette")
    val shape = RoundedCornerShape(14.dp)
    val topOffset = with(LocalDensity.current) { 72.dp.roundToPx() }
    Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, topOffset), onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.padding(16.dp).width(520.dp).graphicsLayer { alpha = appear; scaleX = .98f + .02f * appear; scaleY = .98f + .02f * appear }
                .shadow(28.dp, shape, ambientColor = Color.Black.copy(alpha = .12f), spotColor = Color.Black.copy(alpha = .22f))
                .clip(shape).background(c.surface).border(1.dp, c.border, shape)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> { if (matches.isNotEmpty()) highlight = (highlight + 1) % matches.size; true }
                        Key.DirectionUp -> { if (matches.isNotEmpty()) highlight = (highlight - 1 + matches.size) % matches.size; true }
                        Key.Enter, Key.NumPadEnter -> { run(highlight); true }
                        Key.Escape -> { onDismiss(); true }
                        else -> false
                    }
                },
        ) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Bolt, null, Modifier.size(16.dp), tint = c.faint)
                Spacer(Modifier.width(10.dp))
                BasicTextField(query, { query = it }, singleLine = true,
                    textStyle = TextStyle(color = c.text, fontSize = 14.sp, lineHeight = 20.sp), cursorBrush = SolidColor(c.text),
                    modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = I18n.current.screens.commandSearchA11y },
                    decorationBox = { inner -> Box { if (query.isEmpty()) Text(strings.screens.commandPlaceholder, color = c.faint, fontSize = 14.sp, lineHeight = 20.sp); inner() } })
                Text("Esc", color = c.faint, fontSize = 11.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
            if (matches.isEmpty()) Text(strings.screens.noCommands(query.trim()), color = c.faint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
            LazyColumn(Modifier.heightIn(max = 400.dp).padding(vertical = 6.dp), state = list) {
                itemsIndexed(matches, key = { _, command -> command.group + "/" + command.title }) { index, command ->
                    SwitcherRow(index == highlight, { run(index) }, { highlight = index }) {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Icon(command.icon, null, Modifier.size(15.dp), tint = c.muted) }
                        Spacer(Modifier.width(12.dp))
                        Text(command.title, color = c.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(command.group, color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp))
                        command.shortcut?.let { Text(it, color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp)) }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
            Text(strings.screens.paletteHint, color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}
