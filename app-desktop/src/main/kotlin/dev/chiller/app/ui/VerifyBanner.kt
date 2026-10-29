package dev.chiller.app.ui

import dev.chiller.app.i18n.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chiller.app.di.VerifyState
import dev.chiller.design.*

/** One line above the composer: what the project's check command did after the last editing run. */
@Composable
internal fun VerifyBanner(state: VerifyState, onDismiss: () -> Unit) {
    val c = LocalChillerPalette.current
    val reduced = LocalChillerReducedMotion.current
    AnimatedVisibility(state.status != "idle", enter = if (reduced) fadeIn(tween(0)) else expandVertically(tween(160)) + fadeIn(tween(160)), exit = if (reduced) fadeOut(tween(0)) else shrinkVertically(tween(140)) + fadeOut(tween(100))) {
        var open by remember(state.status, state.attempt) { mutableStateOf(false) }
        val (color, text) = when (state.status) {
            "running" -> c.accent to strings.run.verifyRunning(state.command, state.attempt, state.maxAttempts)
            "passed" -> c.positive to strings.run.verifyPassed(state.command, formatDuration(state.durationMs ?: 0))
            "failed" -> c.negative to strings.run.verifyFailed(state.exitCode?.toString() ?: "?", state.message, state.attempt, state.maxAttempts)
            "gave_up" -> c.negative to strings.run.verifyGaveUp(state.attempt, state.exitCode?.toString() ?: "?")
            else -> c.negative to state.message.ifBlank { strings.run.verifyNotStarted }
        }
        val shape = RoundedCornerShape(10.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(color.copy(alpha = .07f)).border(1.dp, color.copy(alpha = .25f), shape).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.status == "running") Pulse(color) else Icon(if (state.status == "passed") Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, Modifier.size(15.dp), tint = color)
                Spacer(Modifier.width(8.dp))
                Text(text, color = c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (state.outputTail.isNotBlank()) ChillerControl(if (open) strings.run.hideOutput else strings.run.output, { open = !open })
                if (state.status != "running") ChillerIconButton(Icons.Outlined.Close, strings.run.hide, onDismiss, Modifier.size(24.dp))
            }
            if (open) SelectionContainer {
                Text(state.outputTail.takeLast(3000), color = c.muted, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = ChillerFonts.mono,
                    modifier = Modifier.padding(top = 6.dp).heightIn(max = 220.dp).horizontalScroll(rememberScrollState()))
            }
        }
    }
}
