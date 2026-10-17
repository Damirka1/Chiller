package dev.chiller.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.chiller.contracts.FilePresentation
import dev.chiller.design.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.pow

@Composable
internal fun FilePresentationCard(file: FilePresentation, onPreviewFile: suspend (String) -> ByteArray?, onRevealFile: (String) -> Unit, showReveal: Boolean = true) {
    val c = LocalChillerPalette.current
    val imageMime = file.mimeType.lowercase() in setOf("image/png", "image/jpeg", "image/jpg")
    var bitmap by remember(file.path, file.sizeBytes) { mutableStateOf<ImageBitmap?>(null) }
    var attempted by remember(file.path, file.sizeBytes) { mutableStateOf(false) }
    var viewing by remember(file.path, file.sizeBytes) { mutableStateOf(false) }
    val previewer by rememberUpdatedState(onPreviewFile)
    LaunchedEffect(file.path, file.sizeBytes, imageMime) {
        if (imageMime && file.path.isNotBlank()) {
            bitmap = withContext(Dispatchers.IO) { runCatching { previewer(file.path)?.let(::decodePreviewImage) }.getOrNull() }
            attempted = true
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape)) {
        bitmap?.let { image ->
            // Fit includes all axes and labels; large charts remain useful without opening the viewer.
            Image(image, "Открыть изображение ${file.name}", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)
                    .aspectRatio(image.width.toFloat() / image.height)
                    .clickable(role = Role.Button, onClickLabel = "Открыть изображение") { viewing = true })
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (imageMime) Icons.Outlined.Image else Icons.Outlined.InsertDriveFile, null, Modifier.size(18.dp), tint = c.muted)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(file.name, color = c.text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${file.mimeType} · ${formatPresentedSize(file.sizeBytes)}" + (bitmap?.let { " · ${it.width} × ${it.height}" } ?: ""), color = c.faint, fontSize = 10.sp)
                if (imageMime && bitmap == null) Text(if (attempted) "Предварительный просмотр недоступен" else "Загружаю изображение…", color = c.faint, fontSize = 10.sp)
            }
            if (bitmap != null) ChillerIconButton(Icons.Outlined.OpenInFull, "Открыть изображение", { viewing = true })
            if (showReveal) ChillerIconButton(Icons.Outlined.FolderOpen, "Показать в папке", { onRevealFile(file.path) })
        }
    }
    if (viewing) bitmap?.let { ImageViewer(file, it, { viewing = false }, if (showReveal) ({ onRevealFile(file.path) }) else null) }
}

/** Geometry uses image/viewport pixels, so 100% is one source pixel per screen pixel. */
internal fun imageFitScale(image: IntSize, viewport: IntSize): Float =
    if (image.width <= 0 || image.height <= 0 || viewport.width <= 0 || viewport.height <= 0) 1f
    else min(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)

internal fun clampImagePan(pan: Offset, image: IntSize, viewport: IntSize, scale: Float): Offset {
    val x = ((image.width * scale - viewport.width) / 2).coerceAtLeast(0f)
    val y = ((image.height * scale - viewport.height) / 2).coerceAtLeast(0f)
    return Offset(pan.x.coerceIn(-x, x), pan.y.coerceIn(-y, y))
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ImageViewer(file: FilePresentation, bitmap: ImageBitmap, onClose: () -> Unit, onReveal: (() -> Unit)? = null) {
    val c = LocalChillerPalette.current
    val image = IntSize(bitmap.width, bitmap.height)
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var fit by remember { mutableStateOf(true) }
    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val actualScale = if (fit) imageFitScale(image, viewport) else scale
    fun setScale(value: Float, anchor: Offset = Offset.Zero) {
        val next = value.coerceIn(min(.05f, imageFitScale(image, viewport)), 16f)
        pan = clampImagePan(anchor - (anchor - pan) * (next / actualScale), image, viewport, next)
        scale = next
        fit = false
    }
    fun reset() { fit = true; pan = Offset.Zero }
    val transform by rememberUpdatedState<(Offset, Offset, Float) -> Unit>({ centroid, delta, zoom ->
        if (zoom != 1f) setScale(actualScale * zoom, centroid - Offset(viewport.width / 2f, viewport.height / 2f))
        pan = clampImagePan(pan + delta, image, viewport, if (fit) actualScale else scale)
    })
    val focus = remember { FocusRequester() }
    DialogWindow(onCloseRequest = onClose, title = file.name, state = rememberDialogState(width = 1000.dp, height = 720.dp),
        onPreviewKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.Escape -> { onClose(); true }
                Key.Equals, Key.Plus, Key.NumPadAdd -> { setScale(actualScale * 1.25f); true }
                Key.Minus, Key.NumPadSubtract -> { setScale(actualScale / 1.25f); true }
                Key.Zero -> { reset(); true }
                else -> false
            }
        }) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        LaunchedEffect(viewport, actualScale) { pan = clampImagePan(pan, image, viewport, actualScale) }
        Column(Modifier.fillMaxSize().background(c.canvas).focusRequester(focus).focusable()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(file.name, color = c.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${image.width} × ${image.height} · ${formatPresentedSize(file.sizeBytes)}", color = c.faint, fontSize = 11.sp)
                }
                ChillerIconButton(Icons.Outlined.Remove, "Уменьшить", { setScale(actualScale / 1.25f) })
                Text("${(actualScale * 100).roundToInt()}%", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
                ChillerIconButton(Icons.Outlined.Add, "Увеличить", { setScale(actualScale * 1.25f) })
                ChillerControl("Вписать", { reset() }, style = ControlStyle.QUIET)
                ChillerControl("100%", { setScale(1f) }, style = ControlStyle.QUIET)
                onReveal?.let { ChillerIconButton(Icons.Outlined.FolderOpen, "Показать в папке", it) }
                ChillerIconButton(Icons.Outlined.Close, "Закрыть просмотр", onClose)
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(c.sidebar)
                .onSizeChanged { viewport = it }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    val center = Offset(viewport.width / 2f, viewport.height / 2f)
                    setScale(actualScale * 1.15f.pow(-change.scrollDelta.y.coerceIn(-4f, 4f)), change.position - center)
                    event.changes.forEach { it.consume() }
                }
                .pointerInput(image) {
                    detectTransformGestures { centroid, delta, zoom, _ -> transform(centroid, delta, zoom) }
                }) {
                Canvas(Modifier.fillMaxSize()) {
                    val width = (image.width * actualScale).roundToInt().coerceAtLeast(1)
                    val height = (image.height * actualScale).roundToInt().coerceAtLeast(1)
                    drawImage(bitmap, dstOffset = IntOffset(((size.width - width) / 2 + pan.x).roundToInt(), ((size.height - height) / 2 + pan.y).roundToInt()),
                        dstSize = IntSize(width, height), filterQuality = FilterQuality.High)
                }
            }
            Text("Колесо / жест — масштаб · Перетаскивание — перемещение · 0 — вписать · Esc — закрыть", color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(10.dp))
        }
    }
}
