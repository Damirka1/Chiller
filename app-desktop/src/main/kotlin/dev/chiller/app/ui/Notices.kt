package dev.chiller.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chiller.app.di.Notice
import dev.chiller.design.ChillerIconButton
import dev.chiller.design.LocalChillerPalette
import dev.chiller.design.LocalChillerReducedMotion
import kotlinx.coroutines.delay

/** How long a notice stays unless hovered; one that waits for the user's answer stays until read. */
private const val NOTICE_MILLIS = 8_000L

/** News from dialogs in the background, stacked at the top right. A click opens the dialog. */
@Composable
internal fun NoticeStack(notices: List<Notice>, onOpen: (Notice) -> Unit, onDismiss: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        notices.forEach { notice -> key(notice.id) { NoticeCard(notice, { onOpen(notice) }, { onDismiss(notice.id) }) } }
    }
}

@Composable
private fun NoticeCard(notice: Notice, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalChillerPalette.current
    val reduced = LocalChillerReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val appear = remember { MutableTransitionState(reduced).apply { targetState = true } }
    LaunchedEffect(notice.id, hovered) {
        if (notice.tone != "attention" && !hovered) { delay(NOTICE_MILLIS); onDismiss() }
    }
    val (icon, tint) = when (notice.tone) {
        "positive" -> Icons.Outlined.CheckCircleOutline to c.positive
        "negative" -> Icons.Outlined.ErrorOutline to c.negative
        "attention" -> Icons.Outlined.HelpOutline to c.focus
        else -> Icons.Outlined.StopCircle to c.muted
    }
    val shape = RoundedCornerShape(12.dp)
    AnimatedVisibility(appear, enter = if (reduced) EnterTransition.None else slideInHorizontally(tween(220)) { it / 3 } + fadeIn(tween(180))) {
        Row(
            Modifier.fillMaxWidth()
                .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = .10f), spotColor = Color.Black.copy(alpha = .18f))
                .clip(shape).background(if (hovered) c.sidebar else c.surface).border(1.dp, c.border, shape)
                .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpen)
                .semantics { contentDescription = "${notice.title}: ${notice.message}. Открыть диалог" }
                .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(tint.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(16.dp), tint = tint)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(top = 1.dp)) {
                Text(notice.title, color = c.text, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(notice.message, color = c.muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            ChillerIconButton(Icons.Outlined.Close, "Скрыть уведомление", onDismiss, Modifier.size(24.dp))
        }
    }
}
