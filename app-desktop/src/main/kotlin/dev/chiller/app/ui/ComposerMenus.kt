package dev.chiller.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.chiller.app.di.ModelCatalogState
import dev.chiller.design.LocalChillerPalette
import dev.chiller.design.LocalChillerReducedMotion

private val PopoverShape = RoundedCornerShape(12.dp)

/**
 * A floating menu anchored to the control that hosts it: opens above the anchor (below only when
 * there is no room), aligned to its left edge and clamped to the window, so it never covers the
 * control that opened it. Must be called inside the anchor's own layout node.
 */
@Composable
internal fun ChillerPopover(onDismiss: () -> Unit, width: Dp, below: Boolean = false, alignEnd: Boolean = false, focusable: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalChillerPalette.current
    val (gap, margin) = with(LocalDensity.current) { 8.dp.roundToPx() to 12.dp.roundToPx() }
    val reduced = LocalChillerReducedMotion.current
    val appear = remember { MutableTransitionState(reduced).apply { targetState = true } }
    Popup(popupPositionProvider = remember(gap, margin, below, alignEnd) { AnchoredAbovePositionProvider(gap, margin, below, alignEnd) }, onDismissRequest = onDismiss, properties = PopupProperties(focusable = focusable)) {
        AnimatedVisibility(appear, enter = fadeIn(tween(120)) + scaleIn(tween(140), initialScale = .96f, transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, if (below) 0f else 1f))) {
            Column(
                Modifier.padding(12.dp).width(width)
                    .shadow(18.dp, PopoverShape, ambientColor = Color.Black.copy(alpha = .10f), spotColor = Color.Black.copy(alpha = .16f))
                    .clip(PopoverShape).background(c.surface).border(1.dp, c.border, PopoverShape),
                content = content,
            )
        }
    }
}

/**
 * The popover content carries a transparent [marginPx] border so its shadow is not clipped by the popup
 * window; positions compensate so the visible card, not the margin, sits [gapPx] from the anchor.
 */
internal class AnchoredAbovePositionProvider(
    private val gapPx: Int, private val marginPx: Int, private val preferBelow: Boolean = false, private val alignEnd: Boolean = false,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val above = anchorBounds.top - gapPx - popupContentSize.height + marginPx
        val below = anchorBounds.bottom + gapPx - marginPx
        val fitsAbove = above + marginPx >= 0
        val fitsBelow = below + popupContentSize.height - marginPx <= windowSize.height
        val y = when { preferBelow -> if (fitsBelow || !fitsAbove) below else above; else -> if (fitsAbove || !fitsBelow) above else below }
        val x = if (alignEnd) anchorBounds.right - popupContentSize.width + marginPx else anchorBounds.left - marginPx
        return IntOffset(
            x.coerceIn(-marginPx, (windowSize.width - popupContentSize.width + marginPx).coerceAtLeast(-marginPx)),
            y.coerceIn(-marginPx, (windowSize.height - popupContentSize.height + marginPx).coerceAtLeast(-marginPx)),
        )
    }
}

@Composable
internal fun MenuRow(
    title: String, onClick: () -> Unit, subtitle: String? = null, icon: ImageVector? = null,
    selected: Boolean = false, highlighted: Boolean = false, tag: String? = null, enabled: Boolean = true, shortcut: String? = null,
) {
    val c = LocalChillerPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(8.dp))
            .background(if ((hovered || highlighted) && enabled) c.hover else Color.Transparent)
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = if (subtitle == null) 7.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, Modifier.padding(end = 10.dp).size(16.dp), tint = if (enabled) c.muted else c.faint.copy(alpha = .5f)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) c.text else c.faint, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = c.faint, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        tag?.let {
            Text(it, color = c.muted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(5.dp)).background(c.selected.copy(alpha = .6f)).padding(horizontal = 6.dp, vertical = 1.dp))
        }
        shortcut?.let { Text(it, color = c.faint, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, modifier = Modifier.padding(start = 12.dp)) }
        if (shortcut == null || selected) Box(Modifier.padding(start = 8.dp).size(16.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = c.accent)
        }
    }
}

@Composable
internal fun MenuDivider() {
    val c = LocalChillerPalette.current
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(c.border))
}

/**
 * Model selector: search, the known models with declared limits, catalog status and refresh,
 * and a link to connection settings. Arrow keys move the highlight, Enter picks, Escape closes.
 */
@Composable
internal fun ModelPickerPopover(
    model: String, models: List<String>, configured: Boolean, running: Boolean, catalog: ModelCatalogState, contextNote: String?,
    onRefresh: () -> Unit, onCancelRefresh: () -> Unit, onClose: () -> Unit, onModel: (String) -> Unit, onSettings: () -> Unit,
) {
    ChillerPopover(onDismiss = onClose, width = 320.dp) {
        ModelPickerContent(model, models, configured, running, catalog, contextNote, onRefresh, onCancelRefresh, onClose, onModel, onSettings)
    }
}

@Composable
internal fun ModelPickerContent(
    model: String, models: List<String>, configured: Boolean, running: Boolean, catalog: ModelCatalogState, contextNote: String?,
    onRefresh: () -> Unit, onCancelRefresh: () -> Unit, onClose: () -> Unit, onModel: (String) -> Unit, onSettings: () -> Unit,
) {
    val c = LocalChillerPalette.current
    var filter by remember { mutableStateOf("") }
    val discovered = catalog.chatModels.map { it.id }
    val known = (listOf(model) + discovered + models).filter { it.isNotBlank() }.distinct().take(40)
    val query = filter.trim()
    val shown = if (query.isEmpty()) known else known.filter { id -> id.contains(query, true) || modelLabel(id).contains(query, true) || catalog.models.firstOrNull { it.id == id }?.displayName?.contains(query, true) == true }
    var highlight by remember { mutableStateOf(-1) }
    LaunchedEffect(query) { highlight = if (query.isEmpty()) -1 else 0 }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val pick: (String) -> Unit = { onModel(it); onClose() }
    Column(
        Modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.Escape -> { onClose(); true }
                Key.DirectionDown -> { if (shown.isNotEmpty()) highlight = (highlight + 1).coerceAtMost(shown.lastIndex); true }
                Key.DirectionUp -> { if (shown.isNotEmpty()) highlight = (highlight - 1).coerceAtLeast(0); true }
                Key.Enter, Key.NumPadEnter -> { shown.getOrNull(highlight)?.let(pick); true }
                else -> false
            }
        }.padding(bottom = 6.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, Modifier.size(15.dp), tint = c.faint)
            Spacer(Modifier.width(9.dp))
            BasicTextField(
                filter, { filter = it }, singleLine = true,
                textStyle = TextStyle(color = c.text, fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.sp), cursorBrush = SolidColor(c.text),
                modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "Поиск модели" },
                decorationBox = { inner -> Box { if (filter.isEmpty()) Text("Найти модель", color = c.faint, fontSize = 13.sp, lineHeight = 20.sp); inner() } },
            )
            if (catalog.status == "loading") SmallIcon(Icons.Outlined.Close, "Отменить загрузку списка", onCancelRefresh)
            else SmallIcon(Icons.Outlined.Refresh, "Обновить список моделей", onRefresh, enabled = !running)
        }
        MenuDivider()
        Text(
            listOfNotNull(catalogLine(catalog, configured), contextNote).joinToString(" · "),
            color = if (catalog.status == "failed") c.negative else c.faint, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            shown.forEachIndexed { index, id ->
                MenuRow(
                    catalog.models.firstOrNull { it.id == id }?.displayName?.takeIf { it.isNotBlank() } ?: modelLabel(id), { pick(id) }, subtitle = modelSubtitle(id, catalog), selected = id == model, highlighted = index == highlight,
                    tag = if (id != model && id !in discovered) "вручную" else null,
                )
            }
            when {
                shown.isEmpty() && known.isNotEmpty() -> EmptyNote("Ничего не найдено по запросу «$query»")
                catalog.status == "ready" && catalog.chatModels.isEmpty() -> EmptyNote("Провайдер не вернул ни одной модели. Задайте ID вручную в настройках.")
            }
        }
        MenuDivider()
        MenuRow("Настройки подключения", onSettings, icon = Icons.Outlined.Tune)
        Text("Модель сменится со следующего сообщения", color = c.faint, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp))
    }
}

@Composable
private fun EmptyNote(text: String) {
    val c = LocalChillerPalette.current
    Text(text, color = c.muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun SmallIcon(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = LocalChillerPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(if (hovered && enabled) c.hover else Color.Transparent)
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(15.dp), tint = if (enabled) c.muted else c.faint.copy(alpha = .45f)) }
}

internal fun catalogLine(catalog: ModelCatalogState, configured: Boolean): String = when (catalog.status) {
    "loading" -> "Загружаю список моделей…"
    "failed" -> catalog.message.ifBlank { "Список моделей не получен" }
    "ready" -> {
        val base = if (catalog.chatModels.isEmpty()) "Список пуст — задайте ID вручную" else "Доступно моделей: ${catalog.chatModels.size}"
        val time = catalog.fetchedAt?.let(::formatCheckTime)
        val line = if (time.isNullOrBlank()) base else "$base · $time"
        if (catalog.stale) "устарел · $line" else line
    }
    else -> if (configured) "Список не загружен — обновите" else "Ключ API не задан"
}

/** Secondary line for a row: the exact id when the label is friendly, plus declared limits. */
internal fun modelSubtitle(id: String, catalog: ModelCatalogState): String? {
    val model = catalog.models.firstOrNull { it.id == id }
    val parts = buildList {
        if (modelLabel(id) != id || !model?.displayName.isNullOrBlank()) add(id)
        model?.contextWindow?.let { add("контекст ${formatTokens(it)}") }
        model?.maxOutputTokens?.let { add("вывод ${formatTokens(it)}") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun formatTokens(value: Int): String = if (value >= 1_000) "${(value + 500) / 1_000}k" else value.toString()

/** The folder the next message works in. Clicking it opens the folder chooser. */
@Composable
internal fun ContextChips(workspace: String?, onChoose: () -> Unit, enabled: Boolean, mentioned: List<String> = emptyList(), onRemoveMention: (String) -> Unit = {}) {
    val c = LocalChillerPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).background(if (hovered && enabled) c.hover else c.sidebar)
                .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onChoose)
                .padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (workspace == null) Icons.Outlined.CreateNewFolder else Icons.Outlined.Folder, null, Modifier.size(12.dp), tint = if (workspace == null) c.faint else c.muted)
            Spacer(Modifier.width(5.dp))
            Text(workspace?.substringAfterLast('/') ?: "Папка не выбрана", color = if (workspace == null) c.faint else c.muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        }
        mentioned.take(6).forEach { path ->
            Row(Modifier.clip(RoundedCornerShape(6.dp)).background(c.accent.copy(alpha = .10f)).padding(start = 7.dp, end = 3.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Description, null, Modifier.size(12.dp), tint = c.accent)
                Spacer(Modifier.width(4.dp))
                Text(path.substringAfterLast('/'), color = c.text, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                Box(Modifier.padding(start = 2.dp).size(16.dp).clip(CircleShape).pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button) { onRemoveMention(path) }.semantics { contentDescription = "Убрать $path" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Close, null, Modifier.size(11.dp), tint = c.muted)
                }
            }
        }
        if (mentioned.size > 6) Text("+${mentioned.size - 6}", color = c.faint, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

/** Project files matching the @-mention being typed; does not take focus, so typing continues. */
@Composable
internal fun MentionPopover(matches: List<String>, highlighted: Int, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = LocalChillerPalette.current
    ChillerPopover(onDismiss, 380.dp, focusable = false) {
        Text("Файлы проекта · ↑↓ выбрать · ↵ вставить", color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
        matches.forEachIndexed { index, path ->
            MenuRow(path.substringAfterLast('/'), { onPick(path) }, subtitle = path.substringBeforeLast('/', "").ifEmpty { null }, icon = Icons.Outlined.Description, highlighted = index == highlighted)
        }
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * Last request input: provider-reported when available, otherwise a script-aware estimate.
 * Denominator is the effective planner window including the output reserve.
 */
@Composable
internal fun ContextMeter(tokens: Int, window: Int?, estimated: Boolean = true) {
    val c = LocalChillerPalette.current
    val fraction = window?.takeIf { it > 0 }?.let { (tokens.toFloat() / it).coerceIn(0f, 1f) }
    val color = when { fraction == null -> c.faint; fraction > .9f -> c.negative; fraction > .7f -> c.muted; else -> c.accent }
    Row(Modifier.semantics { contentDescription = "Контекст: ${if (estimated) "примерно" else "вход последнего запроса,"} $tokens токенов" + (window?.let { " из $it" } ?: "") }, verticalAlignment = Alignment.CenterVertically) {
        if (fraction != null) {
            androidx.compose.foundation.Canvas(Modifier.size(14.dp)) {
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawArc(c.border, 0f, 360f, false, style = stroke)
                drawArc(color, -90f, 360f * fraction.coerceAtLeast(.02f), false, style = stroke)
            }
            Spacer(Modifier.width(6.dp))
        }
        Text("${if (estimated) "≈ " else ""}${compactTokens(tokens.toLong())}" + (window?.let { " / ${compactTokens(it.toLong())}" } ?: " ток."), color = c.faint, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1)
    }
}
