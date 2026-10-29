package dev.chiller.app.ui

import dev.chiller.app.i18n.*
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

// Modal dialogs over the app: rename, delete, edit or delete a message, and the export preview.

@Composable
internal fun RenameDialog(session: SessionInfo, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val c = LocalChillerPalette.current
    var title by remember(session.id) { mutableStateOf(session.title) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(360.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(strings.dialogs.dialogTitle, color = c.text, fontSize = 18.sp)
            ChillerField(title, { title = it.take(120) }, strings.dialogs.title)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ChillerControl(strings.common.cancel, onDismiss)
                ChillerControl(strings.dialogs.save, { onSave(title.trim()) }, style = ControlStyle.PRIMARY, enabled = title.trim().length in 1..120)
            }
        }
    }
}

@Composable
internal fun SessionDeleteDialog(
    session: SessionInfo,
    preview: String?,
    loading: Boolean,
    previewError: String?,
    deleting: Boolean,
    deleteError: String?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalChillerPalette.current
    Dialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !deleting, dismissOnClickOutside = !deleting),
    ) {
        Column(
            Modifier.widthIn(min = 380.dp, max = 540.dp).clip(RoundedCornerShape(13.dp)).background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(13.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(strings.dialogs.deleteDialogQuestion, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(session.title.ifBlank { strings.common.newDialog }, color = c.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when {
                loading -> Text(strings.dialogs.loadingPreview, color = c.muted, fontSize = 12.sp, lineHeight = 18.sp)
                preview != null -> SelectionContainer {
                    Text(preview, color = c.text, fontSize = 12.sp, lineHeight = 19.sp,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).background(c.sidebar, RoundedCornerShape(8.dp)).padding(12.dp))
                }
                else -> Text(previewError ?: strings.dialogs.previewUnavailable, color = c.negative, fontSize = 12.sp, lineHeight = 18.sp)
            }
            if (previewError != null && preview != null) Text(previewError, color = c.negative, fontSize = 12.sp, lineHeight = 18.sp)
            if (deleteError != null) Text(deleteError, color = c.negative, fontSize = 12.sp, lineHeight = 18.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                ChillerControl(strings.common.cancel, onDismiss, enabled = !deleting)
                if (preview != null) {
                    Spacer(Modifier.width(8.dp))
                    ChillerControl(strings.dialogs.deleteDialog, onDelete, icon = Icons.Outlined.DeleteOutline, style = ControlStyle.PRIMARY, enabled = !loading && !deleting)
                }
            }
        }
    }
}

@Composable
internal fun MessageEditDialog(
    text: String, error: String?, busy: Boolean, onText: (String) -> Unit, onDismiss: () -> Unit, onSubmit: () -> Unit,
) {
    val c = LocalChillerPalette.current
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        Column(Modifier.widthIn(min = 360.dp, max = 560.dp).clip(RoundedCornerShape(13.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(13.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Text(strings.dialogs.editMessage, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(strings.dialogs.editNote, color = c.muted, fontSize = 12.sp, lineHeight = 18.sp)
            Text(strings.dialogs.messageText, color = c.muted, fontSize = 12.sp)
            BasicTextField(text, onText, textStyle = TextStyle(color = c.text, fontSize = 13.sp, lineHeight = 20.sp), cursorBrush = SolidColor(c.text),
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 300.dp).clip(RoundedCornerShape(7.dp)).background(c.canvas).border(1.dp, c.border, RoundedCornerShape(7.dp)).padding(11.dp).verticalScroll(rememberScrollState()))
            error?.let { Text(it, color = c.negative, fontSize = 12.sp, lineHeight = 18.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ChillerControl(strings.common.cancel, onDismiss, enabled = !busy)
                Spacer(Modifier.width(8.dp))
                ChillerControl(strings.dialogs.saveAndSend, onSubmit, icon = Icons.Outlined.Save, style = ControlStyle.PRIMARY, enabled = !busy && text.isNotBlank())
            }
        }
    }
}

@Composable
internal fun MessageDeleteDialog(text: String, error: String?, busy: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val c = LocalChillerPalette.current
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        Column(Modifier.widthIn(min = 340.dp, max = 500.dp).clip(RoundedCornerShape(13.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(13.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Text(strings.dialogs.deleteTurnQuestion, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            if (text.isNotBlank()) Text(text.take(400), color = c.muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
            Text(strings.dialogs.deleteTurnNote, color = c.muted, fontSize = 12.sp, lineHeight = 18.sp)
            error?.let { Text(it, color = c.negative, fontSize = 12.sp, lineHeight = 18.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ChillerControl(strings.common.cancel, onDismiss, enabled = !busy)
                Spacer(Modifier.width(8.dp))
                ChillerControl(strings.dialogs.deleteTurn, onDelete, icon = Icons.Outlined.DeleteOutline, style = ControlStyle.PRIMARY, enabled = !busy)
            }
        }
    }
}

@Composable
internal fun ExportPreview(content: String, format: String, onDismiss: () -> Unit, onSave: () -> Unit) {
    val c = LocalChillerPalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(680.dp).heightIn(max = 650.dp).clip(RoundedCornerShape(13.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(13.dp)).padding(23.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(strings.dialogs.exportTitle(if (format == "json") "JSON" else "Markdown"), color = c.text, fontSize = 18.sp, letterSpacing = (-.4).sp)
                Spacer(Modifier.weight(1f)); ChillerIconButton(Icons.Outlined.Close, strings.dialogs.closeExport, onDismiss)
            }
            Text(strings.dialogs.exportNote, color = c.muted, fontSize = 12.sp, lineHeight = 19.sp)
            SelectionContainer {
                Text(content, color = c.text, fontFamily = ChillerFonts.mono, fontSize = 11.sp, lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 150.dp, max = 430.dp).verticalScroll(rememberScrollState()).background(c.canvas).padding(13.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ChillerControl(strings.common.cancel, onDismiss); Spacer(Modifier.width(8.dp)); ChillerControl(strings.dialogs.saveFile, onSave, style = ControlStyle.PRIMARY)
            }
        }
    }
}
