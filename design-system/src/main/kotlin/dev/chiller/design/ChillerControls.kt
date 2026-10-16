package dev.chiller.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

enum class ControlStyle { QUIET, OUTLINE, PRIMARY, SELECTED }

@Composable
fun ChillerControl(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: ImageVector? = null, style: ControlStyle = ControlStyle.QUIET,
    enabled: Boolean = true, trailing: String? = null,
) {
    val c = LocalChillerPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(when {
        style == ControlStyle.PRIMARY -> if (pressed) c.text.copy(alpha = .8f) else c.text
        pressed -> c.selected
        style == ControlStyle.SELECTED -> c.selected
        hovered && enabled -> c.hover
        else -> Color.Transparent
    }, tween(110))
    val foreground = if (style == ControlStyle.PRIMARY) c.canvas else c.text
    Row(
        modifier.defaultMinSize(minHeight = 30.dp).clip(RoundedCornerShape(7.dp)).background(fill)
            .then(if (style == ControlStyle.OUTLINE) Modifier.border(1.dp, c.border, RoundedCornerShape(7.dp)) else Modifier)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        icon?.let { Icon(it, null, Modifier.size(15.dp), tint = foreground.copy(alpha = if (enabled) .85f else .35f)) }
        Text(label, color = foreground.copy(alpha = if (enabled) 1f else .35f), fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = if (trailing != null) Modifier.weight(1f) else Modifier)
        trailing?.let { Text(it, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.sp, color = if (style == ControlStyle.PRIMARY) c.canvas.copy(alpha = .5f) else c.faint) }
    }
}

@Composable
fun ChillerIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false) {
    val c = LocalChillerPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(when { primary && enabled -> c.text; pressed -> c.selected; hovered && enabled -> c.hover; else -> Color.Transparent }, tween(100))
    Box(modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(fill)
        .pointerHoverIcon(PointerIcon.Hand)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(16.dp), tint = when { primary && enabled -> c.canvas; !enabled -> c.faint.copy(alpha = .45f); else -> c.muted })
    }
}

@Composable
fun ChillerToggle(checked: Boolean, onChange: (Boolean) -> Unit, label: String, enabled: Boolean = true) {
    val c = LocalChillerPalette.current
    val offset by animateDpAsState(if (checked) 14.dp else 2.dp, tween(140))
    Box(Modifier.size(34.dp, 20.dp).clip(CircleShape).background(if (checked) c.text else c.border)
        .pointerHoverIcon(PointerIcon.Hand).clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
        .semantics { contentDescription = label }) {
        Box(Modifier.offset(x = offset, y = 2.dp).size(16.dp).clip(CircleShape).background(c.surface))
    }
}

@Composable
fun ChillerField(value: String, onValueChange: (String) -> Unit, label: String, placeholder: String = "", secret: Boolean = false) {
    val c = LocalChillerPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, lineHeight = 18.sp, color = c.muted)
        BasicTextField(value, onValueChange, singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
            cursorBrush = SolidColor(c.text), visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(c.canvas).border(1.dp, c.border, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 9.dp)
                .semantics { contentDescription = label },
            decorationBox = { field -> Box { if (value.isEmpty()) Text(placeholder, color = c.faint, fontSize = 13.sp, lineHeight = 20.sp); field() } },
        )
    }
}

@Composable
fun ChillerMark(modifier: Modifier = Modifier, color: Color = LocalChillerPalette.current.text) {
    Canvas(modifier.size(24.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension * .37f
        for (i in 0 until 6) {
            val a = i * Math.PI / 3 - Math.PI / 2
            val direction = Offset(cos(a).toFloat(), sin(a).toFloat())
            val tip = center + direction * radius
            drawLine(color, center, tip, strokeWidth = size.minDimension * .065f, cap = StrokeCap.Round)
            val fork = center + direction * radius * .58f
            for (side in listOf(-1, 1)) {
                val b = a + side * Math.PI / 3
                val end = fork + Offset(cos(b).toFloat(), sin(b).toFloat()) * radius * .32f
                drawLine(color, fork, end, strokeWidth = size.minDimension * .055f, cap = StrokeCap.Round)
            }
        }
    }
}
