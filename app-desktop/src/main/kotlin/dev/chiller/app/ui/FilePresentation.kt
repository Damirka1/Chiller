package dev.chiller.app.ui

import dev.chiller.app.i18n.*
import androidx.compose.ui.graphics.luminance

import androidx.compose.ui.input.pointer.pointerHoverIcon

import androidx.compose.ui.input.pointer.PointerIcon

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
    // The chat list disposes cards that scroll away; the cache gives a returning card its image in the first frame,
    // so it comes back at full height instead of growing (and pushing the list) once a reload finishes.
    val cacheKey = PreviewImages.key(file.path, file.sizeBytes)
    var bitmap by remember(cacheKey) { mutableStateOf(PreviewImages.get(cacheKey)) }
    var attempted by remember(cacheKey) { mutableStateOf(bitmap != null) }
    var viewing by remember(file.path, file.sizeBytes) { mutableStateOf(false) }
    val previewer by rememberUpdatedState(onPreviewFile)
    LaunchedEffect(file.path, file.sizeBytes, imageMime) {
        if (imageMime && file.path.isNotBlank() && bitmap == null) {
            bitmap = withContext(Dispatchers.IO) { runCatching { previewer(file.path)?.let(::decodePreviewImage) }.getOrNull() }
            bitmap?.let { PreviewImages.put(cacheKey, it) }
            attempted = true
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape)) {
        bitmap?.let { image ->
            // Fit includes all axes and labels; large charts remain useful without opening the viewer. The slot height
            // is computed explicitly: width + max height + aspect ratio together over-constrain a wide image, which then
            // overflowed its slot, cut its top and laid its hover highlight over the caption.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val slot = minOf(maxWidth * (image.height.toFloat() / image.width), 380.dp)
                Image(image, strings.studio.openImageNamed(file.name), contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(slot).clipToBounds().pointerHoverIcon(PointerIcon.Hand)
                        .clickable(role = Role.Button, onClickLabel = strings.studio.openImage) { viewing = true })
            }
            // The hover highlight stops at the image: a divider and some air before the caption.
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (bitmap != null) 10.dp else 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (imageMime) Icons.Outlined.Image else Icons.Outlined.InsertDriveFile, null, Modifier.size(18.dp), tint = c.muted)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(file.name, color = c.text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${file.mimeType} · ${formatPresentedSize(file.sizeBytes)}" + (bitmap?.let { " · ${it.width} × ${it.height}" } ?: ""), color = c.faint, fontSize = 10.sp)
                if (imageMime && bitmap == null) Text(if (attempted) strings.studio.previewUnavailable else strings.studio.loadingImage, color = c.faint, fontSize = 10.sp)
            }
            if (bitmap != null) ChillerIconButton(Icons.Outlined.OpenInFull, strings.studio.openImage, { viewing = true })
            if (showReveal) ChillerIconButton(Icons.Outlined.FolderOpen, strings.studio.showInFolder, { onRevealFile(file.path) })
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
        // Like the main window on Windows/JBR: the header is the title bar, under the native caption buttons.
        val nativeBar = remember { if (System.getProperty("os.name").orEmpty().startsWith("Mac")) null else dev.chiller.app.WindowsTitleBar.install(window) }
        val bar = nativeBar?.spec(emptyList())
        val dark = c.canvas.luminance() < .5f
        LaunchedEffect(dark, nativeBar) { nativeBar?.setDark(dark) }
        Column(Modifier.fillMaxSize().background(c.canvas).focusRequester(focus).focusable()) {
            Row(Modifier.fillMaxWidth().then(if (bar != null) Modifier.height(bar.height).then(bar.hitTest).padding(start = 12.dp, end = bar.captionButtonsWidth + 8.dp)
                else Modifier.padding(horizontal = 12.dp, vertical = 6.dp)), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(file.name, color = c.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${image.width} × ${image.height} · ${formatPresentedSize(file.sizeBytes)}", color = c.faint, fontSize = 11.sp)
                }
                ChillerIconButton(Icons.Outlined.Remove, strings.studio.zoomOut, { setScale(actualScale / 1.25f) })
                Text("${(actualScale * 100).roundToInt()}%", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
                ChillerIconButton(Icons.Outlined.Add, strings.studio.zoomIn, { setScale(actualScale * 1.25f) })
                ChillerControl(strings.studio.fit, { reset() }, style = ControlStyle.QUIET)
                ChillerControl("100%", { setScale(1f) }, style = ControlStyle.QUIET)
                onReveal?.let { ChillerIconButton(Icons.Outlined.FolderOpen, strings.studio.showInFolder, it) }
                if (bar == null) ChillerIconButton(Icons.Outlined.Close, strings.studio.closeViewer, onClose)
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
            Text(strings.studio.viewerHint, color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(10.dp))
        }
    }
}

/**
 * Decoded image previews shared by all cards, least recently used first out, within a pixel budget (about
 * 4 bytes per pixel). Keyed by path and size, the identity a presentation card already carries.
 */
internal object PreviewImages {
    private const val MAX_PIXELS = 24_000_000L
    private val cache = LinkedHashMap<String, ImageBitmap>(16, .75f, true)
    private var pixels = 0L

    fun key(path: String, sizeBytes: Long): String = "$path#$sizeBytes"

    @Synchronized fun get(key: String): ImageBitmap? = cache[key]

    @Synchronized fun put(key: String, image: ImageBitmap) {
        cache.remove(key)?.let { pixels -= it.width.toLong() * it.height }
        cache[key] = image; pixels += image.width.toLong() * image.height
        val iterator = cache.entries.iterator()
        while (pixels > MAX_PIXELS && cache.size > 1 && iterator.hasNext()) {
            val eldest = iterator.next()
            if (eldest.key == key) continue
            pixels -= eldest.value.width.toLong() * eldest.value.height
            iterator.remove()
        }
    }

    @Synchronized internal fun clear() { cache.clear(); pixels = 0 }
}
