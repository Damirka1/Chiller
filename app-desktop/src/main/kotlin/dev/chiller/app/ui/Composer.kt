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

// The composer: the message field, attachments and mentions, and the toolbar with mode, model and folder.

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Composer(
    draft: String, onDraft: (String) -> Unit, mode: ExecutionMode, onMode: (ExecutionMode) -> Unit,
    model: String, running: Boolean, onSend: () -> Unit, onStop: () -> Unit,
    onSettings: () -> Unit, modifier: Modifier, configured: Boolean, models: List<String>,
    onModel: (String) -> Unit, onComposition: (Boolean) -> Unit, pending: Boolean, contextNote: String?,
    catalog: ModelCatalogState, onRefreshModels: () -> Unit, onCancelCatalogRefresh: () -> Unit,
    allowProcesses: Boolean, onAllow: (Boolean) -> Unit,
    workspace: String?, onChooseWorkspace: () -> Unit, contextTokens: Int?, contextIsEstimate: Boolean, effectiveWindow: Int?, connectionDot: androidx.compose.ui.graphics.Color,
    attachments: List<ComposerAttachment>, attachmentOwnerKey: String,
    importing: Boolean,
    onAppendAttachments: (String, List<ComposerAttachment>) -> Boolean,
    onRemoveAttachment: (String, String) -> Unit,
    onImportState: (String, Boolean) -> Boolean,
    onImportTextAttachment: suspend (String, String) -> ComposerAttachment?, onImportFiles: suspend (String, List<String>) -> List<ComposerAttachment>,
    onImportImageAttachment: suspend (String, ByteArray) -> ComposerAttachment?,
    vision: Boolean = false,
    folderMenu: FolderMenu? = null,
) {
    val c = LocalChillerPalette.current
    var focused by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var modelClosedAt by remember { mutableStateOf(0L) }
    var field by remember { mutableStateOf(TextFieldValue(draft)) }
    var importError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(draft) { if (field.text != draft) { field = TextFieldValue(draft, TextRange(draft.length)); onComposition(false) } }
    val density = LocalDensity.current
    var measuredFieldHeight by remember { mutableStateOf(26.dp) }
    val fieldMaxHeight = 144.dp
    val shape = RoundedCornerShape(16.dp)
    val scope = rememberCoroutineScope()
    // Cards for pastes still being prepared: they appear on the key press, before any encoding or I/O.
    var preparing by remember(attachmentOwnerKey) { mutableStateOf(listOf<PendingPaste>()) }
    val launchImport: (suspend () -> Unit) -> Unit = { operation ->
        if (pending || !onImportState(attachmentOwnerKey, true)) importError = "Сообщение уже отправляется. Добавьте вложение после завершения отправки."
        else {
            scope.launch {
                importError = null
                try { operation() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { importError = "Не удалось добавить вложение. Проверьте файл и повторите попытку." }
                finally { onImportState(attachmentOwnerKey, false) }
            }
        }
    }
    suspend fun appendImported(items: List<ComposerAttachment>) {
        if (items.isEmpty()) {
            importError = "Не удалось добавить вложение. Проверьте файл и повторите попытку."
            return
        }
        if (!onAppendAttachments(attachmentOwnerKey, items)) importError = "Можно прикрепить не больше восьми файлов к одному диалогу."
    }
    val addFiles: () -> Unit = {
        launchImport {
            val paths = withContext(Dispatchers.IO) {
                val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Добавить файлы", java.awt.FileDialog.LOAD)
                dialog.isMultipleMode = true
                dialog.isVisible = true
                dialog.files.map { it.absolutePath }
            }
            if (paths.isNotEmpty()) appendImported(onImportFiles(attachmentOwnerKey, paths))
        }
    }
    val importTransferable: (Transferable) -> Unit = { transferable ->
        if (pending) importError = "Сообщение уже отправляется. Добавьте вложение после завершения отправки."
        else {
            var snapshotFailed = false
            val payload = try { snapshotComposerTransferPayload(transferable) }
            catch (_: Exception) { snapshotFailed = true; null }
            if (payload == null) {
                importError = if (snapshotFailed) "Не удалось получить данные из буфера обмена. Повторите попытку." else "Этот формат буфера обмена не поддерживается."
            } else if (payload is ComposerTransferPayload.Text && textPasteTarget(payload.value) == TextPasteTarget.FIELD) {
                // Short text goes straight into the field, synchronously, like any editor.
                val pasted = payload.value
                val start = field.selection.min.coerceIn(0, field.text.length)
                val end = field.selection.max.coerceIn(start, field.text.length)
                val text = field.text.replaceRange(start, end, pasted)
                field = TextFieldValue(text, TextRange(start + pasted.length)); onDraft(text); onComposition(false)
            } else {
                val card = PendingPaste(newId(), when (payload) {
                    is ComposerTransferPayload.Files -> if (payload.paths.size == 1) java.io.File(payload.paths.single()).name else "${payload.paths.size} ${plural(payload.paths.size, "файл", "файла", "файлов")}"
                    is ComposerTransferPayload.Image -> pasteLabelForImage(payload.value)
                    is ComposerTransferPayload.Text -> pasteLabelForText(payload.value)
                })
                preparing = preparing + card
                launchImport {
                    try {
                        when (payload) {
                            is ComposerTransferPayload.Files -> appendImported(onImportFiles(attachmentOwnerKey, payload.paths))
                            is ComposerTransferPayload.Image -> {
                                // PNG encoding (and scaling an oversized image) never runs on the UI thread.
                                val encoded = pastePreparation.withPermit { withContext(Dispatchers.Default) { encodePastedImage(payload.value) } } ?: error("Clipboard image cannot be encoded within the size limit")
                                appendImported(listOf(onImportImageAttachment(attachmentOwnerKey, encoded.png) ?: error("Image import failed")))
                                if (encoded.scaled) importError = "Изображение уменьшено до ${encoded.width}×${encoded.height}, чтобы уложиться в 4 МБ."
                            }
                            is ComposerTransferPayload.Text -> appendImported(listOf(pastePreparation.withPermit { onImportTextAttachment(attachmentOwnerKey, payload.value) } ?: error("Text import failed")))
                        }
                    } finally { preparing = preparing - card }
                }
            }
        }
    }
    val dropTarget = remember(attachmentOwnerKey, attachments) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val transferable = event.awtTransferable
                if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) && !transferable.isDataFlavorSupported(DataFlavor.imageFlavor) && !transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return false
                importTransferable(transferable)
                return true
            }
        }
    }
    val borderColor by animateColorAsState(if (focused) c.muted.copy(alpha = .55f) else c.border, tween(if (LocalChillerReducedMotion.current) 0 else 120))
    Column(
        modifier.dragAndDropTarget(shouldStartDragAndDrop = { event -> event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || event.awtTransferable.isDataFlavorSupported(DataFlavor.imageFlavor) || event.awtTransferable.isDataFlavorSupported(DataFlavor.stringFlavor) }, target = dropTarget)
            .shadow(3.dp, shape, ambientColor = c.text.copy(alpha = .04f), spotColor = c.text.copy(alpha = .04f)).clip(shape).background(c.surface)
            .border(1.dp, borderColor, shape).padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // @-mentions: project files listed once per folder, matched against the token under the cursor.
        val files by produceState(emptyList<String>(), workspace) { value = workspace?.let { root -> withContext(Dispatchers.IO) { runCatching { listProjectFiles(java.nio.file.Path.of(root)) }.getOrDefault(emptyList()) } }.orEmpty() }
        val mention = if (field.selection.collapsed && files.isNotEmpty()) mentionAt(field.text, field.selection.start) else null
        var dismissedAt by remember { mutableStateOf(-1) }
        val matches = mention?.takeIf { it.first != dismissedAt }?.let { rankFiles(files, it.second) }.orEmpty()
        var highlight by remember(mention?.second) { mutableStateOf(0) }
        fun insert(path: String) {
            val (start, _) = mention ?: return
            val end = field.selection.start
            val text = field.text.replaceRange(start, end, "@$path ")
            field = TextFieldValue(text, TextRange(start + path.length + 2)); onDraft(text)
        }
        val mentioned = mentionedFiles(field.text, files)
        BasicTextField(field, { field = it; onDraft(it.text); onComposition(it.composition != null); if (mentionAt(it.text, it.selection.start)?.first != dismissedAt) dismissedAt = -1 },
                onTextLayout = { layout -> measuredFieldHeight = with(density) { layout.size.height.toDp() }.coerceIn(26.dp, fieldMaxHeight) },
                modifier = Modifier.fillMaxWidth().height(measuredFieldHeight.coerceIn(26.dp, fieldMaxHeight)).onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.V && (event.isCtrlPressed || event.isMetaPressed)) {
                            val clipboard = runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }.getOrNull()
                            if (clipboard != null) importTransferable(clipboard)
                            return@onPreviewKeyEvent clipboard != null
                        }
                        // Shift+Tab switches Plan / Edit. Plain Tab inserts the highlighted file in the @ menu and is ignored
                        // otherwise: an invisible tab character in a chat message is never what was meant.
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Tab && !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed) {
                            if (event.isShiftPressed) { if (!running) onMode(toggledMode(mode)); return@onPreviewKeyEvent true }
                            if (matches.isEmpty()) return@onPreviewKeyEvent true
                        }
                        if (matches.isEmpty() || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> { highlight = (highlight + 1) % matches.size; true }
                            Key.DirectionUp -> { highlight = (highlight - 1 + matches.size) % matches.size; true }
                            Key.Enter, Key.NumPadEnter, Key.Tab -> { insert(matches[highlight.coerceIn(0, matches.lastIndex)]); true }
                            Key.Escape -> { dismissedAt = mention?.first ?: -1; true }
                            else -> false
                        }
                    }.verticalScroll(rememberScrollState()),
                textStyle = TextStyle(color = c.text, fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.sp), cursorBrush = SolidColor(c.text),
                decorationBox = { inner -> Box { if (draft.isEmpty()) Text(if (workspace != null) "Напишите задачу… @ — упомянуть файл" else "Напишите задачу…", color = c.faint, fontSize = 14.sp, lineHeight = 22.sp); inner() } },
            )
        if (mentioned.isNotEmpty()) MentionChips(mentioned) { path ->
            val text = field.text.replace(Regex("(?<=^|\\s)@" + Regex.escape(path) + "\\s?"), "")
            field = TextFieldValue(text, TextRange(text.length)); onDraft(text)
        }
        if (attachments.isNotEmpty() || preparing.isNotEmpty()) AttachmentChips(attachments, onRemove = { item -> onRemoveAttachment(attachmentOwnerKey, item.artifactId) }, preparing = preparing)
        if (importing && preparing.isEmpty()) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) { Pulse(c.accent); Spacer(Modifier.width(6.dp)); Text("Добавляю вложение…", color = c.faint, fontSize = 10.sp) }
        importError?.let { Text(it, color = c.negative, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp)) }
        if (attachments.any { it.mimeType.startsWith("image/") }) Text(if (vision) "Модель увидит изображения: они уменьшаются до 1568 px и отправляются с этим сообщением." else "Эта модель не принимает изображения: она получит только имя и размер файла. Включить можно в настройках профиля.", color = c.faint, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Box {
            if (matches.isNotEmpty() && focused) MentionPopover(matches, highlight.coerceIn(0, matches.lastIndex), ::insert) { dismissedAt = mention?.first ?: -1 }
        }
        Spacer(Modifier.height(4.dp))
        ComposerToolbar(mode, onMode, model, running, pending, importing, draft.isNotBlank() || attachments.isNotEmpty(), modelMenu, { if (System.currentTimeMillis() - modelClosedAt > 200) modelMenu = !modelMenu }, allowProcesses, onAllow, onSend, onStop, addFiles,
            connectionDot, contextTokens, effectiveWindow, contextIsEstimate, workspace, onChooseWorkspace, folderMenu = folderMenu) {
            ModelPickerPopover(
                model = model, models = models, configured = configured, running = running, catalog = catalog, contextNote = contextNote,
                onRefresh = onRefreshModels, onCancelRefresh = onCancelCatalogRefresh,
                onClose = { modelMenu = false; modelClosedAt = System.currentTimeMillis() }, onModel = onModel,
                onSettings = { modelMenu = false; onSettings() },
            )
        }
    }
}

@Composable
internal fun AttachmentChips(items: List<ComposerAttachment>, onRemove: (ComposerAttachment) -> Unit, preparing: List<PendingPaste> = emptyList()) {
    val c = LocalChillerPalette.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        preparing.forEach { card ->
            Row(Modifier.height(30.dp).clip(RoundedCornerShape(7.dp)).background(c.accent.copy(alpha = .08f)).border(1.dp, c.accent.copy(alpha = .3f), RoundedCornerShape(7.dp)).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Pulse(c.accent); Spacer(Modifier.width(7.dp))
                Text("${card.label} · подготовка…", color = c.text, fontSize = 10.sp, maxLines = 1)
            }
        }
        items.forEach { item ->
            Row(Modifier.clip(RoundedCornerShape(7.dp)).background(c.sidebar).border(1.dp, c.border, RoundedCornerShape(7.dp)).padding(start = 8.dp, end = 3.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (item.mimeType.startsWith("image/")) Icons.Outlined.Image else Icons.Outlined.InsertDriveFile, null, Modifier.size(13.dp), tint = c.accent)
                Spacer(Modifier.width(5.dp))
                Text("${item.name} · ${formatPresentedSize(item.sizeBytes)}", color = c.text, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ChillerIconButton(Icons.Outlined.Close, "Убрать ${item.name}", { onRemove(item) }, Modifier.size(22.dp))
            }
        }
    }
}

internal fun promptWithAttachments(text: String, attachments: List<ComposerAttachment>): String = buildString {
    append(text.trim())
    attachments.forEach { attachment ->
        if (isNotEmpty()) append("\n\n")
        append("Прикреплённый файл: ").append(attachment.name)
            .append(" (только метаданные; содержимое изображения не передано модели).\n")
            .append("Для чтения текстового файла используй read_artifact с artifact_id=").append(attachment.artifactId)
    }
}

internal fun mergeComposerAttachments(current: List<ComposerAttachment>, additions: List<ComposerAttachment>, limit: Int = 8): List<ComposerAttachment>? {
    val merged = (current + additions).distinctBy { it.artifactId }
    return merged.takeIf { limit >= 0 && it.size <= limit }
}

internal fun nextPendingImportCount(current: Int, delta: Int): Int? {
    val next = (current + delta).coerceAtLeast(0)
    return next.takeIf { it > 0 }
}

internal sealed interface ComposerTransferPayload {
    data class Files(val paths: List<String>) : ComposerTransferPayload
    data class Text(val value: String) : ComposerTransferPayload
    data class Image(val value: AwtImage) : ComposerTransferPayload
}

/** Must be called while the clipboard or DnD callback still owns the Transferable. */
internal fun snapshotComposerTransferPayload(transferable: Transferable): ComposerTransferPayload? = when {
    transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
        @Suppress("UNCHECKED_CAST")
        val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
            ?: error("Clipboard file list has an unexpected type")
        ComposerTransferPayload.Files(files.map { it.absolutePath }.toList())
    }
    transferable.isDataFlavorSupported(DataFlavor.imageFlavor) -> {
        val image = transferable.getTransferData(DataFlavor.imageFlavor) as? AwtImage
            ?: error("Clipboard image has an unexpected type")
        ComposerTransferPayload.Image(image)
    }
    transferable.isDataFlavorSupported(DataFlavor.stringFlavor) -> {
        val text = transferable.getTransferData(DataFlavor.stringFlavor) as? String
            ?: error("Clipboard text has an unexpected type")
        ComposerTransferPayload.Text(text)
    }
    else -> null
}

@Composable
internal fun MentionChips(paths: List<String>, onRemove: (String) -> Unit) {
    val c = LocalChillerPalette.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        paths.take(6).forEach { path ->
            Row(Modifier.clip(RoundedCornerShape(6.dp)).background(c.accent.copy(alpha = .10f)).padding(start = 7.dp, end = 3.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Description, null, Modifier.size(12.dp), tint = c.accent)
                Spacer(Modifier.width(4.dp))
                Text(path.substringAfterLast('/'), color = c.text, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.padding(start = 2.dp).size(18.dp).clip(CircleShape).pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button) { onRemove(path) }.semantics { contentDescription = "Убрать $path" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Close, null, Modifier.size(11.dp), tint = c.muted)
                }
            }
        }
        if (paths.size > 6) Text("+${paths.size - 6}", color = c.faint, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
internal fun ComposerToolbar(
    mode: ExecutionMode, onMode: (ExecutionMode) -> Unit, model: String, running: Boolean,
    pending: Boolean, importing: Boolean, draftNotEmpty: Boolean, modelMenuOpen: Boolean, onToggleModel: () -> Unit,
    allowProcesses: Boolean, onAllow: (Boolean) -> Unit, onSend: () -> Unit, onStop: () -> Unit,
    onAttach: () -> Unit,
    connectionDot: androidx.compose.ui.graphics.Color, contextTokens: Int?, contextWindow: Int?, contextIsEstimate: Boolean,
    workspace: String?, onChooseWorkspace: () -> Unit,
    folderMenu: FolderMenu? = null,
    modelPopover: @Composable () -> Unit,
) {
    val c = LocalChillerPalette.current
    var modeMenu by remember { mutableStateOf(false) }
    // A click on the anchor while its popover is open first dismisses it (outside click), then would reopen it.
    var modeClosedAt by remember { mutableStateOf(0L) }
    val toggleMode = { if (System.currentTimeMillis() - modeClosedAt > 200) modeMenu = !modeMenu }
    // At larger type the model label needs proportionally more room before the toolbar is allowed to wrap.
    val fontScale = LocalDensity.current.fontScale
    val minModelWidth = (104f * fontScale.coerceIn(1f, 2f)).dp
    val modeMenuWidth = (264f * fontScale.coerceIn(1f, 1.4f)).dp
    Box(Modifier.fillMaxWidth()) {
        Layout(
            content = {
                if (workspace == null) Box {
                    ChillerControl("Разговор", toggleMode, icon = Icons.AutoMirrored.Outlined.Chat, enabled = !running, style = if (modeMenu) ControlStyle.SELECTED else ControlStyle.QUIET)
                    if (modeMenu) ChillerPopover(onDismiss = { modeMenu = false; modeClosedAt = System.currentTimeMillis() }, width = modeMenuWidth) {
                        Text("Диалог без папки: модель отвечает, ищет в вебе и рисует, но не видит ваших файлов и не запускает команды.",
                            color = c.muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 6.dp))
                        MenuDivider()
                        MenuRow("Выбрать папку…", { modeMenu = false; onChooseWorkspace() }, subtitle = "Диалог продолжится в проекте", icon = Icons.Outlined.CreateNewFolder)
                        Spacer(Modifier.height(6.dp))
                    }
                } else Box {
                    val confirming = LocalEditApproval.current.enabled
                    ChillerControl(if (mode == ExecutionMode.PLAN) "План" else "Изменения", toggleMode, icon = if (mode == ExecutionMode.PLAN) Icons.Outlined.Lightbulb else if (confirming) Icons.Outlined.FactCheck else Icons.Outlined.Edit, enabled = !running, style = if (modeMenu) ControlStyle.SELECTED else ControlStyle.QUIET)
                    if (modeMenu) ChillerPopover(onDismiss = { modeMenu = false; modeClosedAt = System.currentTimeMillis() }, width = modeMenuWidth) {
                        Spacer(Modifier.height(6.dp))
                        MenuRow("План", { onMode(ExecutionMode.PLAN); modeMenu = false }, subtitle = "Только чтение, без изменений", icon = Icons.Outlined.Lightbulb, selected = mode == ExecutionMode.PLAN)
                        MenuRow("Изменения", { onMode(ExecutionMode.WORKSPACE_EDIT); modeMenu = false }, subtitle = "Правит файлы проекта", icon = Icons.Outlined.Edit, selected = mode == ExecutionMode.WORKSPACE_EDIT)
                        if (mode == ExecutionMode.WORKSPACE_EDIT) {
                            MenuDivider()
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Команды процессов", color = c.text, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Запуск тестов и сборки", color = c.faint, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                ChillerToggle(allowProcesses, { onAllow(it) }, "Команды процессов", enabled = !running)
                            }
                            val approval = LocalEditApproval.current
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Подтверждать правки", color = c.text, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Показывать каждую правку до записи", color = c.faint, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                ChillerToggle(approval.enabled, { approval.onChange(it) }, "Подтверждать правки", enabled = !running)
                            }
                        }
                        MenuDivider()
                        Text("Shift+Tab в поле ввода — переключить режим", color = c.faint, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(start = 16.dp, end = 14.dp, top = 2.dp))
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Box {
                    // The provider's name when its listing gives one; the menu keeps showing name and id.
                    ChillerControl(modelName(model).ifBlank { "Выбрать модель" }, onToggleModel, enabled = !running, trailing = "⌄", inlineTrailing = true, style = if (modelMenuOpen) ControlStyle.SELECTED else ControlStyle.QUIET, dot = connectionDot)
                    if (modelMenuOpen) modelPopover()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (folderMenu != null) FolderChip(folderMenu, enabled = !running) else FolderToolbarChip(workspace, onChooseWorkspace, enabled = !running)
                    Spacer(Modifier.width(6.dp))
                    ChillerIconButton(Icons.Outlined.AttachFile, "Добавить файлы", onAttach, enabled = !running)
                    Spacer(Modifier.width(8.dp))
                    contextTokens?.let { ContextMeter(it, contextWindow, contextIsEstimate); Spacer(Modifier.width(10.dp)) }
                    ChillerIconButton(if (running && !pending) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward, if (pending) "Сохранение сообщения" else if (running) "Остановить" else if (importing) "Добавление вложения" else "Отправить", if (running && !pending) onStop else onSend, enabled = !pending && (running || (!importing && draftNotEmpty)), primary = true)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { measurables, constraints ->
            val gap = 8.dp.roundToPx()
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
            val leading = measurables[0].measure(Constraints())
            val trailing = measurables[2].measure(Constraints())
            val singleLine = (width - leading.width - trailing.width - gap * 2).coerceAtLeast(0)
            val wrapped = singleLine < minModelWidth.roundToPx()
            val modelControl = measurables[1].measure(Constraints(maxWidth = if (wrapped) (width - leading.width - gap).coerceAtLeast(0) else singleLine))
            if (wrapped) {
                val line = maxOf(leading.height, modelControl.height)
                val rowGap = 6.dp.roundToPx()
                layout(width, line + rowGap + trailing.height) {
                    leading.placeRelative(0, (line - leading.height) / 2)
                    modelControl.placeRelative(leading.width + gap, (line - modelControl.height) / 2)
                    trailing.placeRelative(width - trailing.width, line + rowGap)
                }
            } else {
                val height = maxOf(leading.height, modelControl.height, trailing.height)
                layout(width, height) {
                    leading.placeRelative(0, (height - leading.height) / 2)
                    modelControl.placeRelative(leading.width + gap, (height - modelControl.height) / 2)
                    trailing.placeRelative(width - trailing.width, (height - trailing.height) / 2)
                }
            }
        }
    }
}

@Composable
internal fun FolderToolbarChip(workspace: String?, onChoose: () -> Unit, enabled: Boolean) {
    val c = LocalChillerPalette.current
    Row(
        Modifier.widthIn(max = 150.dp).height(28.dp).clip(RoundedCornerShape(7.dp)).background(c.sidebar)
            .pointerHoverIcon(PointerIcon.Hand).clickable(enabled = enabled, role = Role.Button, onClick = onChoose)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (workspace == null) Icons.Outlined.CreateNewFolder else Icons.Outlined.Folder, null, Modifier.size(13.dp), tint = if (workspace == null) c.faint else c.muted)
        Spacer(Modifier.width(5.dp))
        Text(workspace?.let(::folderName) ?: "Без папки", color = if (workspace == null) c.faint else c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
