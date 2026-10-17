package dev.chiller.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chiller.contracts.AgentMessage
import dev.chiller.design.*
import kotlinx.serialization.json.*

/** One step of the model's checklist: done, in_progress or pending. */
data class PlanStep(val title: String, val status: String)

/**
 * The model's latest update_plan for the current turn only (calls after the last user message), so a new
 * request never shows the previous task's plan. Null when the turn has no plan.
 */
fun currentTurnPlan(messages: List<AgentMessage>): List<PlanStep>? {
    val lastUser = messages.indexOfLast { it.role == "user" }
    val call = messages.drop(lastUser + 1).asReversed().asSequence()
        .flatMap { it.toolCalls.asReversed().asSequence() }.firstOrNull { it.name == "update_plan" } ?: return null
    return runCatching {
        (Json.parseToJsonElement(call.arguments).jsonObject["steps"] as JsonArray).map { item ->
            val o = item.jsonObject
            PlanStep(o.getValue("step").jsonPrimitive.content, o.getValue("status").jsonPrimitive.content)
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/**
 * A live checklist under the conversation, updated as the model reports progress: finished steps are
 * ticked, the current one pulses. When the run is over and every step is done it folds into one line.
 */
@Composable
internal fun PlanChecklist(steps: List<PlanStep>, running: Boolean, modifier: Modifier = Modifier) {
    val c = LocalChillerPalette.current
    val reduced = LocalChillerReducedMotion.current
    val done = steps.count { it.status == "done" }
    val finished = !running && done == steps.size
    var open by remember(finished) { mutableStateOf(!finished) }
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape)) {
        Row(
            Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button) { open = !open }.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (finished) "План выполнен" else "План", color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Text("$done/${steps.size}", color = c.faint, fontSize = 12.sp)
            Spacer(Modifier.width(10.dp))
            // Thin progress bar: how much of the plan is done.
            Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(c.border)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(if (steps.isEmpty()) 0f else done.toFloat() / steps.size).clip(CircleShape).background(c.accent))
            }
            Spacer(Modifier.width(10.dp))
            Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, if (open) "Свернуть план" else "Развернуть план", Modifier.size(15.dp), tint = c.faint)
        }
        AnimatedVisibility(open, enter = if (reduced) fadeIn(tween(0)) else expandVertically(tween(180)) + fadeIn(tween(140)), exit = if (reduced) fadeOut(tween(0)) else shrinkVertically(tween(150)) + fadeOut(tween(100))) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEach { step -> StepRow(step, running) }
            }
        }
    }
}

@Composable
private fun StepRow(step: PlanStep, running: Boolean) {
    val c = LocalChillerPalette.current
    val done = step.status == "done"
    val active = step.status == "in_progress"
    val textColor by animateColorAsState(if (done) c.muted else c.text, tween(if (LocalChillerReducedMotion.current) 0 else 200), label = "step")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when {
                done -> Box(Modifier.size(15.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.material.icons.Icons.Outlined.Check, null, Modifier.size(11.dp), tint = c.canvas)
                }
                active && running -> Pulse(c.accent)
                active -> Box(Modifier.size(13.dp).clip(CircleShape).border(1.5.dp, c.accent, CircleShape))
                else -> Box(Modifier.size(13.dp).clip(CircleShape).border(1.5.dp, c.faint.copy(alpha = .6f), CircleShape))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(step.title, color = textColor, fontSize = 13.sp, lineHeight = 19.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            textDecoration = if (done) TextDecoration.LineThrough else null)
    }
}
