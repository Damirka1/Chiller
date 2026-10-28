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

// The conversation: messages, streamed text, code blocks, questions and the run's progress line.

@Composable
internal fun Conversation(state: RunState, reduced: Boolean, onAnswer: (String) -> Unit, onUndo: (String) -> Unit, canUndo: Boolean, jumpTo: String? = null, onRestoreDraft: (String) -> Unit, onPreviewFile: suspend (String) -> ByteArray?, onRevealFile: (String) -> Unit, onEdit: (Int, String) -> Unit, onDelete: (Int, String) -> Unit, onRecoverInterrupted: () -> Unit, modifier: Modifier, onRewind: (List<String>) -> Unit = {}, onContinue: () -> Unit = {}, undoneChanges: Set<String> = emptySet(), outsideChanges: dev.chiller.app.di.OutsideChanges? = null) {
    val c = LocalChillerPalette.current
    // Begin at the tail even for restored long conversations; LazyColumn clamps to the final item.
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = Int.MAX_VALUE)
    val scope = rememberCoroutineScope()
    // Following is a user decision: only the user's own scroll turns it off (scrolling up) or on (reaching the end).
    var follow by remember { mutableStateOf(true) }
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.collect { inProgress ->
            if (autoScrolling) return@collect
            if (inProgress) follow = false else follow = !scroll.canScrollForward
        }
    }
    // Any growth below the viewport (new item, streamed text, expanded card) pulls the view to the end while following.
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.canScrollForward to scroll.layoutInfo.totalItemsCount }.collect { (canScroll, count) ->
            if (follow && canScroll && count > 0 && !scroll.isScrollInProgress) {
                autoScrolling = true
                try { scroll.scrollToItem(count - 1) } finally { autoScrolling = false }
            }
        }
    }
    // Sending a message always returns to the end.
    LaunchedEffect(state.messages.size) { if (state.messages.lastOrNull()?.role == "user") follow = true }
    val projection = projectConversation(state.messages, state.activities)
    // Opened from a message search: show the first message with the text instead of the end, once.
    var jumped by remember { mutableStateOf(false) }
    LaunchedEffect(jumpTo, projection.entries.size) {
        val text = jumpTo?.takeIf { it.isNotBlank() && !jumped } ?: return@LaunchedEffect
        val position = projection.entries.indexOfFirst { it.message.content.orEmpty().contains(text, ignoreCase = true) }
        if (position < 0) return@LaunchedEffect
        jumped = true; follow = false; autoScrolling = true
        try { scroll.scrollToItem(position) } finally { autoScrolling = false }
    }
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        LazyColumn(state = scroll, modifier = Modifier.widthIn(max = 816.dp).fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(if (LocalChillerCompact.current) 14.dp else 18.dp)) {
            // LazyColumn fades items in only when they are added after the first layout, and slides neighbours into place.
            // No fade-out: a removed status line ("Модель пишет…") fading while the list auto-scrolls to the new
            // summary could stay drawn over it.
            @Suppress("UNUSED_PARAMETER")
            fun LazyItemScope.entryModifier(key: String): Modifier =
                if (reduced) Modifier else Modifier.animateItem(fadeInSpec = tween(220), placementSpec = spring(stiffness = 500f, dampingRatio = .9f), fadeOutSpec = null)
            projection.entries.forEachIndexed { position, entry ->
                val key = "message-${entry.index}"
                item(key = key) {
                    val previous = projection.entries.getOrNull(position - 1)?.message?.role
                    Column(entryModifier(key).widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!entry.message.content.isNullOrBlank() || entry.message.attachments.isNotEmpty()) Message(entry.message, showHeader = !(entry.message.role == "assistant" && previous == "assistant"),
                            onPreviewFile = onPreviewFile,
                            onEdit = if (canUndo) ({ _ -> onEdit(entry.index, entry.message.content.orEmpty()) }) else null,
                            onDelete = if (canUndo) ({ _ -> onDelete(entry.index, entry.message.content.orEmpty()) }) else null,
                            rewindCount = if (entry.message.role == "user" && canUndo) turnChangeIds(state.messages, state.activities, entry.index, undoneChanges).size else 0,
                            onRewind = if (canUndo) ({ onRewind(turnChangeIds(state.messages, state.activities, entry.index, undoneChanges)) }) else null)
                        ToolGroup(entry.activities, reduced, onUndo, canUndo, onPreviewFile, onRevealFile)
                    }
                }
            }
            // The streamed reply takes the key of the message it becomes, so completion does not flash.
            if (state.streamingText.isNotBlank()) item(key = "message-${state.messages.size}") {
                Box(entryModifier("message-${state.messages.size}")) {
                    Message(AgentMessage("assistant", state.streamingText, origin = state.activeModelId?.let { MessageOrigin(MessageOriginKind.MODEL, it) }),
                        showHeader = state.messages.lastOrNull()?.role != "assistant", streaming = true)
                }
            }
            if (projection.unlinked.isNotEmpty()) item(key = "unlinked-activities") {
                Column(entryModifier("unlinked-activities"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Действия сессии", color = c.muted, fontSize = 12.sp)
                    projection.unlinked.forEach { ToolRow(it, reduced, onUndo, canUndo, onPreviewFile = onPreviewFile, onRevealFile = onRevealFile) }
                }
            }
            if (state.reasoning.isNotBlank()) item(key = "reasoning") {
                var open by remember { mutableStateOf(false) }
                Column(entryModifier("reasoning")) {
                    ChillerControl(if (open) "Скрыть рассуждения" else "Рассуждения модели", { open = !open }, icon = Icons.Outlined.Psychology)
                    AnimatedVisibility(open, enter = if (reduced) EnterTransition.None else expandVertically(tween(180)) + fadeIn(tween(160)), exit = if (reduced) ExitTransition.None else shrinkVertically(tween(150)) + fadeOut(tween(100))) {
                        Text(state.reasoning, color = c.muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(start = 34.dp, top = 6.dp, end = 10.dp))
                    }
                }
            }
            // The model's checklist for this turn, kept under the latest messages and updated live.
            currentTurnPlan(state.messages)?.takeUnless {
                state.status in setOf("completed", "failed", "cancelled", "interrupted") && !turnResult(state, undoneChanges).isEmpty
            }?.let { steps ->
                item(key = "turn-plan") { PlanChecklist(steps, running = state.status in setOf("preparing", "streaming", "executing"), modifier = entryModifier("turn-plan")) }
            }
            if (state.status in setOf("preparing", "streaming", "executing")) item(key = "run-status") {
                val label = runProgressLabel(state).orEmpty()
                Row(entryModifier("run-status").padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val waiting = state.question != null || state.approval != null
                    if (!waiting) Pulse(c.accent) else Icon(if (state.approval != null) Icons.Outlined.FactCheck else Icons.Outlined.Chat, null, Modifier.size(15.dp), tint = c.accent)
                    Spacer(Modifier.width(10.dp))
                    if (!waiting) ShimmerText(label, reduced) else Text(if (state.approval != null) "Ждёт подтверждения правки" else label, color = c.accent, fontSize = 12.sp)
                }
            }
            // A turn that did something ends with a card of facts; a plain answer keeps the short status line.
            val finished = state.status in setOf("completed", "failed", "cancelled", "interrupted")
            val result = if (finished) turnResult(state, undoneChanges) else null
            val outside = outsideChanges?.takeIf { it.count > 0 && it.userIndex == state.messages.indexOfLast { m -> m.role == "user" } }
            if (result != null && (!result.isEmpty || outside != null)) item(key = "turn-result") { TurnResultCard(state, result.withUnshownOutputs(outside), entryModifier("turn-result"), outside = outside) }
            else {
                if (state.status in setOf("completed", "cancelled", "interrupted")) item(key = "run-outcome") {
                    Text(runProgressLabel(state).orEmpty(), color = c.muted, fontSize = 12.sp, modifier = entryModifier("run-outcome").padding(start = 6.dp))
                }
                if (state.status in setOf("completed", "failed", "cancelled") && state.modelRounds > 0) item(key = "run-summary") { Box(entryModifier("run-summary")) { RunSummaryLine(state) } }
            }
            state.question?.let { q -> item(key = "question-${q.id}") { Box(entryModifier("question-${q.id}")) { Question(q, onAnswer, state.status != "interrupted") } } }
            state.approval?.let { request -> item(key = "approval-${request.id}") {
                val approval = LocalEditApproval.current
                Box(entryModifier("approval-${request.id}")) { ApprovalCard(request, approval.onDecide) }
            } }
            state.error?.let { error -> item(key = "error") { Column(entryModifier("error")) { Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = c.negative)
                Text(runErrorDescription(error), color = c.negative, fontSize = 12.sp, lineHeight = 20.sp)
            }
                if (isStepLimitError(error)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChillerControl("Продолжить", onContinue, icon = Icons.Outlined.PlayArrow, style = ControlStyle.OUTLINE)
                    Text("Шагов не хватило: агент продолжит ту же задачу. Лимит меняется в Настройки → Агент.", color = c.faint, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.align(Alignment.CenterVertically))
                }
                else state.messages.lastOrNull { it.role == "user" }?.content?.let { text -> ChillerControl("Вернуть текст в поле", { onRestoreDraft(text) }) }
            } } }
            if (!canUndo && (state.status == "interrupted" || state.activities.any { it.status == "interrupted" })) item(key = "recover-interrupted") {
                Column(entryModifier("recover-interrupted").padding(start = 34.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Неизвестные действия не повторяются; агент сначала проверит состояние.", color = c.muted, fontSize = 11.sp, lineHeight = 16.sp)
                    ChillerControl("Проверить и продолжить", onRecoverInterrupted, icon = Icons.Outlined.Refresh, style = ControlStyle.OUTLINE)
                }
            }
            item(key = "tail") { Spacer(Modifier.height(2.dp)) }
        }
        AnimatedVisibility(!follow && scroll.canScrollForward, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            enter = if (reduced) EnterTransition.None else fadeIn(tween(150)) + slideInVertically(tween(180)) { it / 2 },
            exit = if (reduced) ExitTransition.None else fadeOut(tween(120)) + slideOutVertically(tween(150)) { it / 2 }) {
            Box(Modifier.shadow(8.dp, CircleShape).clip(CircleShape).background(c.surface).border(1.dp, c.border, CircleShape)) {
                ChillerIconButton(Icons.Outlined.ArrowDownward, "К последнему сообщению", {
                    follow = true
                    scope.launch { autoScrolling = true; try { scroll.animateScrollToItem((scroll.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } finally { autoScrolling = false } }
                })
            }
        }
    }
}

/** Status text with a soft light sweep, so a waiting run reads as alive. Static with reduced motion. */
@Composable
internal fun ShimmerText(text: String, reduced: Boolean) {
    val c = LocalChillerPalette.current
    if (reduced) { Text(text, color = c.muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); return }
    val x by rememberInfiniteTransition(label = "shimmer").animateFloat(-200f, 600f, infiniteRepeatable(tween(1600, easing = androidx.compose.animation.core.LinearEasing)), label = "x")
    val brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(c.muted, c.text, c.muted), start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x + 160f, 0f))
    Text(text, style = TextStyle(brush = brush, fontSize = 12.sp, lineHeight = 18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Hint(text: String, content: @Composable () -> Unit) {
    val c = LocalChillerPalette.current
    TooltipArea(tooltip = { Text(text, color = c.text, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.sidebar).border(1.dp, c.border, RoundedCornerShape(6.dp)).padding(8.dp)) }, content = content)
}

@Composable
internal fun Message(message: AgentMessage, showHeader: Boolean = true, streaming: Boolean = false, onEdit: ((String) -> Unit)? = null, onDelete: ((String) -> Unit)? = null, onPreviewFile: suspend (String) -> ByteArray? = { null },
    rewindCount: Int = 0, onRewind: (() -> Unit)? = null) {
    val content = message.content.orEmpty()
    val c = LocalChillerPalette.current
    if (message.role == "user") {
        // The user's own messages sit on the right as quiet bubbles; the assistant writes on the page.
        val clipboard = LocalClipboardManager.current
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        var hasFocus by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(start = 64.dp).hoverable(interaction).onFocusChanged { hasFocus = it.hasFocus }.focusable(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
            androidx.compose.animation.AnimatedVisibility(hovered || hasFocus, enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
                Row(Modifier.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Rewind undoes every file change of this turn; the first click asks, the second does it.
                    if (onRewind != null && rewindCount > 0) {
                        var confirm by remember(rewindCount) { mutableStateOf(false) }
                        LaunchedEffect(confirm) { if (confirm) { kotlinx.coroutines.delay(4000); confirm = false } }
                        if (confirm) ChillerControl("Откатить $rewindCount ${plural(rewindCount, "правку", "правки", "правок")}?", { confirm = false; onRewind() }, icon = Icons.Outlined.History, style = ControlStyle.OUTLINE)
                        else ChillerIconButton(Icons.Outlined.History, "Откатить изменения файлов этого хода", { confirm = true }, Modifier.size(26.dp))
                    }
                    onEdit?.let { ChillerIconButton(Icons.Outlined.Edit, "Изменить сообщение", { it(content) }, Modifier.size(26.dp)) }
                    onDelete?.let { ChillerIconButton(Icons.Outlined.DeleteOutline, "Удалить ход диалога", { it(content) }, Modifier.size(26.dp)) }
                    ChillerIconButton(Icons.Outlined.ContentCopy, "Копировать", { clipboard.setText(AnnotatedString(content)) }, Modifier.size(26.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
              if (content.isNotBlank()) SelectionContainer {
                Text(content.trim(), color = c.text, fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.sp,
                    modifier = Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp))
                        .background(c.selected.copy(alpha = .75f)).padding(horizontal = 15.dp, vertical = 10.dp))
              }
              message.attachments.forEach { item -> FilePresentationCard(FilePresentation("attachment:${item.artifactId}", item.name, item.mimeType, item.sizeBytes), onPreviewFile, {}, showReveal = false) }
            }
        }
        return
    }
    val clipboard = LocalClipboardManager.current
    var copied by remember(content) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1600); copied = false } }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var hasFocus by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().hoverable(interaction).onFocusChanged { hasFocus = it.hasFocus }.focusable(), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        Box(Modifier.width(21.dp)) { if (showHeader) ChillerMark(Modifier.padding(top = 1.dp).size(21.dp), c.accent) }
        Column(Modifier.weight(1f)) {
            if (showHeader) Row(Modifier.fillMaxWidth().height(24.dp).padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Hint(message.origin?.modelId ?: authorLabel(message)) {
                    Text(authorLabel(message), color = c.muted, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp))
                }
            }
            Box(Modifier.fillMaxWidth()) {
                SelectionContainer(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val segments = content.split("```")
                        segments.forEachIndexed { index, text ->
                            val last = index == segments.lastIndex
                            if (index % 2 == 1) CodeBlock(text.substringAfter('\n', text).trimEnd(), text.substringBefore('\n', "").take(24))
                            else if (text.isNotBlank() || (streaming && last)) {
                                if (streaming && last) StreamingText(text.trim()) else MarkdownText(text.trim())
                            }
                        }
                    }
                }
                // The actions float over the answer's top-right corner on hover: the text keeps its full width and
                // nothing moves when they appear.
                if (!streaming) androidx.compose.animation.AnimatedVisibility(hovered || hasFocus || copied, Modifier.align(Alignment.TopEnd).offset(y = if (showHeader) (-28).dp else (-14).dp),
                    enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
                    val pill = RoundedCornerShape(8.dp)
                    Row(Modifier.shadow(6.dp, pill, ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = .08f), spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = .12f))
                        .clip(pill).background(c.surface).border(1.dp, c.border, pill).padding(2.dp)) {
                        ChillerIconButton(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, if (copied) "Скопировано" else "Копировать ответ", { clipboard.setText(AnnotatedString(content)); copied = true }, Modifier.size(26.dp))
                        if (onEdit != null) ChillerIconButton(Icons.Outlined.Edit, "Изменить сообщение", { onEdit.invoke(content) }, Modifier.size(26.dp))
                        if (onDelete != null) ChillerIconButton(Icons.Outlined.DeleteOutline, "Удалить ход диалога", { onDelete.invoke(content) }, Modifier.size(26.dp))
                    }
                }
            }
        }
    }
}

/** Streamed markdown with a soft blinking caret at the end. */
@Composable
internal fun StreamingText(text: String) {
    val c = LocalChillerPalette.current
    val reduced = LocalChillerReducedMotion.current
    val alpha = if (reduced) 1f else rememberInfiniteTransition(label = "caret").animateFloat(1f, .15f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "a").value
    MarkdownText(text, tail = androidx.compose.ui.text.buildAnnotatedString {
        pushStyle(androidx.compose.ui.text.SpanStyle(color = c.accent.copy(alpha = alpha)))
        append(" ▍")
        pop()
    })
}

@Composable
internal fun CodeBlock(code: String, language: String) {
    val c = LocalChillerPalette.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1600); copied = false } }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, c.border, shape).background(c.sidebar)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(language.ifBlank { "код" }, color = c.faint, fontSize = 11.sp, fontFamily = ChillerFonts.mono)
            Spacer(Modifier.weight(1f))
            if (copied) Text("Скопировано", color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(end = 2.dp))
            ChillerIconButton(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, "Копировать код", { clipboard.setText(AnnotatedString(code)); copied = true }, Modifier.size(26.dp))
        }
        Text(code, color = c.text, fontFamily = ChillerFonts.mono, fontSize = 12.5.sp, lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
    }
}

@Composable
internal fun Question(question: UserQuestion, onAnswer: (String) -> Unit, canAnswer: Boolean) {
    val c = LocalChillerPalette.current
    var answer by remember(question.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).border(1.dp, c.border, RoundedCornerShape(9.dp)).padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(question.question, color = c.text, fontSize = 13.sp, lineHeight = 21.sp)
        if (canAnswer) {
        question.options.forEach { ChillerControl(it, { onAnswer(it) }, icon = Icons.AutoMirrored.Outlined.ArrowForward, style = ControlStyle.OUTLINE) }
        ChillerField(answer, { answer = it }, "Ваш ответ")
        ChillerControl("Продолжить", { if (answer.isNotBlank()) onAnswer(answer) }, style = ControlStyle.PRIMARY, enabled = answer.isNotBlank())
        } else {
            question.options.forEach { Text("• $it", color = c.muted, fontSize = 12.sp) }
            Text("Вопрос сохранён после прерывания. Проверьте историю и начните новый диалог; прежняя операция не будет запущена повторно.", color = c.muted, fontSize = 12.sp)
        }
    }
}
