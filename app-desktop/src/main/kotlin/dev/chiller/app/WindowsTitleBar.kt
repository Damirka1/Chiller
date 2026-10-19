package dev.chiller.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import dev.chiller.app.ui.AppMenuSection
import dev.chiller.app.ui.TitleBarSpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.awt.Window

/**
 * Native custom title bar on Windows through the JetBrains Runtime: the window keeps its native caption buttons,
 * snap layouts, resize borders and double-click maximize, while the app draws the rest of the bar itself.
 * [install] returns null on other systems or JDKs, and the standard frame with its menu bar is used instead.
 */
internal class WindowsTitleBar private constructor(private val frame: Window, private val bar: WindowDecorations.CustomTitleBar) {
    private var captionWidth by mutableFloatStateOf(DEFAULT_CAPTION_WIDTH)
    private var dark: Boolean? = null

    /** Caption buttons follow the app theme, not the system one. Re-attaching applies the new properties. */
    fun setDark(value: Boolean) {
        if (dark == value) return
        dark = value
        runCatching {
            bar.putProperty("controls.dark", value)
            when (frame) { is java.awt.Frame -> JBR.getWindowDecorations().setCustomTitleBar(frame, bar); is java.awt.Dialog -> JBR.getWindowDecorations().setCustomTitleBar(frame, bar) }
            captionWidth = bar.rightInset.takeIf { it > 0f } ?: DEFAULT_CAPTION_WIDTH
        }
    }

    fun spec(menu: List<AppMenuSection>) = TitleBarSpec(
        height = HEIGHT.dp, trafficLightsWidth = 0.dp, captionButtonsWidth = captionWidth.dp,
        // Empty bar space is non-client, so Windows itself drags, snaps and maximizes the window there.
        dragArea = {}, hitTest = Modifier.titleBarHitTest(bar), menu = menu, onDark = ::setDark,
    )

    companion object {
        /** Matches the toolbar's minimum height, so the caption buttons line up with it. AWT units, i.e. dp. */
        const val HEIGHT = 48f
        /** Three 46 dp Windows caption buttons; used until the runtime reports the real width. */
        private const val DEFAULT_CAPTION_WIDTH = 138f

        /** The main frame or a dialog such as the image viewer. */
        fun install(frame: Window): WindowsTitleBar? = runCatching {
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows") || !JBR.isWindowDecorationsSupported()) return@runCatching null
            val decorations = JBR.getWindowDecorations()
            val bar = decorations.createCustomTitleBar()
            bar.height = HEIGHT
            when (frame) { is java.awt.Frame -> decorations.setCustomTitleBar(frame, bar); is java.awt.Dialog -> decorations.setCustomTitleBar(frame, bar); else -> return@runCatching null }
            WindowsTitleBar(frame, bar).also { it.captionWidth = bar.rightInset.takeIf { inset -> inset > 0f } ?: DEFAULT_CAPTION_WIDTH }
        }.getOrNull()
    }
}

/**
 * Tells the runtime, for every pointer event, whether the pointer is over one of our controls. Children see events
 * first; a control that consumed the press keeps the gesture as client input until release, and anything else in
 * the bar area behaves like a native caption. The runtime ignores the answer outside the bar area.
 */
private fun Modifier.titleBarHitTest(bar: WindowDecorations.CustomTitleBar): Modifier = pointerInput(bar) {
    val context = currentCoroutineContext()
    awaitPointerEventScope {
        var inControl = false
        while (context.isActive) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val consumed = event.changes.any { it.isConsumed }
            if (consumed && event.type == PointerEventType.Press) inControl = true
            runCatching { bar.forceHitTest(consumed || inControl) }
            if (event.type == PointerEventType.Release) inControl = false
        }
    }
}
