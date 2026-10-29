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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chiller.contracts.RunState
import dev.chiller.design.ChillerFonts
import dev.chiller.design.LocalChillerPalette
import dev.chiller.design.LocalChillerReducedMotion

/**
 * The end of a turn as facts: changed files, commands with their exit codes, files shown and what is still
 * unresolved. It replaces the plain "answer ready" line when the turn did anything; with open issues it
 * starts expanded, otherwise it is one summary line that opens on click.
 */
@Composable
internal fun TurnResultCard(state: RunState, result: TurnResult, modifier: Modifier = Modifier, initiallyOpen: Boolean? = null, outside: dev.chiller.app.di.OutsideChanges? = null) {
    val c = LocalChillerPalette.current
    val reduced = LocalChillerReducedMotion.current
    val issues = result.issues.isNotEmpty()
    var open by remember(issues) { mutableStateOf(initiallyOpen ?: issues) }
    val (title, tint, icon) = when {
        state.status == "failed" && state.error?.let(::isStepLimitError) == true -> Triple(strings.turn.stoppedStepLimit, c.muted, Icons.Outlined.StopCircle)
        state.status == "failed" -> Triple(strings.turn.error, c.negative, Icons.Outlined.ErrorOutline)
        state.status == "cancelled" -> Triple(strings.turn.stopped, c.muted, Icons.Outlined.StopCircle)
        state.status == "interrupted" -> Triple(strings.turn.interrupted, c.muted, Icons.Outlined.StopCircle)
        issues -> Triple(strings.turn.doneWithIssues, c.negative, Icons.Outlined.ReportProblem)
        else -> Triple(strings.turn.turnDone, c.positive, Icons.Outlined.CheckCircle)
    }
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, if (issues) c.negative.copy(alpha = .35f) else c.border, shape)) {
        Row(
            Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button) { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = tint)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                currentTurnPlan(state.messages)?.let { steps ->
                    val skipped = steps.count { it.status == "skipped" }
                    Text(strings.turn.planLine(steps.count { it.status == "done" }, steps.size, skipped), color = c.faint, fontSize = 11.sp)
                }
                Text(summaryLine(result, outside?.count ?: 0), color = c.muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.width(8.dp))
            Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, if (open) strings.turn.collapseResult else strings.turn.expandResult, Modifier.size(15.dp), tint = c.faint)
        }
        AnimatedVisibility(open, enter = if (reduced) fadeIn(tween(0)) else expandVertically(tween(180)) + fadeIn(tween(140)), exit = if (reduced) fadeOut(tween(0)) else shrinkVertically(tween(150)) + fadeOut(tween(100))) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                currentTurnPlan(state.messages)?.let { steps ->
                    Section(strings.turn.modelPlan) { steps.forEach { Text("${when (it.status) { "done" -> "✓"; "skipped" -> "–"; else -> "○" }} ${it.title}", color = if (it.status in setOf("done", "skipped")) c.muted else c.negative, fontSize = 12.sp, lineHeight = 18.sp) } }
                }
                if (result.issues.isNotEmpty()) Section(strings.turn.needsAttention) {
                    result.issues.forEach { issue -> Line(Icons.Outlined.ReportProblem, c.negative) { Text(issue, color = c.text, fontSize = 12.sp, lineHeight = 17.sp) } }
                }
                if (result.files.isNotEmpty()) Section(strings.turn.changed) {
                    result.files.take(6).forEach { file ->
                        Line(fileIcon(file.kind), if (file.undone) c.faint else c.muted) {
                            Text(file.path, color = if (file.undone) c.faint else c.text, fontSize = 12.sp, fontFamily = ChillerFonts.mono, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(8.dp))
                            when {
                                file.undone -> Text(strings.turn.undone, color = c.faint, fontSize = 11.sp)
                                file.kind == "deleted" -> Text(strings.turn.deleted, color = c.negative, fontSize = 11.sp)
                                else -> {
                                    if (file.kind == "created") { Text(strings.turn.created, color = c.positive, fontSize = 11.sp); Spacer(Modifier.width(6.dp)) }
                                    if (file.added > 0) Text("+${file.added}", color = c.positive, fontSize = 11.sp, fontFamily = ChillerFonts.mono)
                                    if (file.removed > 0) { Spacer(Modifier.width(4.dp)); Text("−${file.removed}", color = c.negative, fontSize = 11.sp, fontFamily = ChillerFonts.mono) }
                                }
                            }
                        }
                    }
                    if (result.files.size > 6) Text(strings.turn.andMore(result.files.size - 6), color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(start = 24.dp))
                }
                if (outside != null && outside.count > 0) Section(strings.turn.outsideTools) {
                    Text(strings.turn.outsideNote, color = c.muted, fontSize = 11.sp, lineHeight = 16.sp)
                    val rows = outside.added.map { it to strings.turn.created } + outside.modified.map { it to strings.turn.modified } + outside.deleted.map { it to strings.turn.deleted }
                    rows.take(6).forEach { (path, label) ->
                        Line(Icons.Outlined.Terminal, c.muted) {
                            Text(path, color = c.text, fontSize = 12.sp, fontFamily = ChillerFonts.mono, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(8.dp)); Text(label, color = c.faint, fontSize = 11.sp)
                        }
                    }
                    if (rows.size > 6 || outside.truncated) Text(strings.turn.andMoreTruncated((outside.count - 6).coerceAtLeast(0), outside.truncated), color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(start = 24.dp))
                }
                if (result.commands.isNotEmpty()) Section(strings.turn.commands) {
                    result.commands.forEach { run ->
                        val (label, color) = when {
                            run.succeeded -> strings.turn.exitZero to c.positive
                            run.state == "timed_out" -> strings.turn.timedOut to (if (run.isCheck) c.negative else c.muted)
                            run.failed -> strings.turn.exitCode(run.exitCode.toString()) to (if (run.isCheck) c.negative else c.muted)
                            run.state == "running" -> strings.turn.resultUnread to c.faint
                            else -> run.state to c.faint
                        }
                        Line(Icons.Outlined.Terminal, c.muted) {
                            Text(run.command, color = c.text, fontSize = 12.sp, fontFamily = ChillerFonts.mono, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(6.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(5.dp))
                            Text(label, color = color, fontSize = 11.sp)
                        }
                    }
                }
                if (result.presented.isNotEmpty()) Section(strings.turn.shown) {
                    result.presented.forEach { path -> Line(Icons.Outlined.Image, c.muted) { Text(path, color = c.text, fontSize = 12.sp, fontFamily = ChillerFonts.mono, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                }
                val stats = runSummaryParts(state)
                if (stats.isNotEmpty()) Text(stats.joinToString("  ·  "), color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

/** One line for the collapsed card: counts only, e.g. "2 файла · проверки: 1 из 1 · показан 1 файл". */
internal fun summaryLine(result: TurnResult, outside: Int = 0): String = buildList {
    // Issues first: the line is cut at the end on narrow windows.
    if (result.issues.isNotEmpty()) add(I18n.current.turn.issuesCount(result.issues.size))
    if (result.files.isNotEmpty()) add(I18n.current.common.files(result.files.size))
    val checks = result.commands.filter { it.isCheck }
    if (checks.isNotEmpty()) add(I18n.current.turn.checksPassed(checks.count { it.succeeded }, checks.size))
    else add(if (result.commands.isEmpty()) I18n.current.turn.checksNotRun else I18n.current.turn.checksUnconfirmed)
    if (result.presented.isNotEmpty()) add(I18n.current.turn.shownCount(result.presented.size))
    if (outside > 0) add(I18n.current.turn.outsideCount(outside))
}.joinToString(" · ")

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalChillerPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = c.faint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable
private fun Line(icon: ImageVector, tint: androidx.compose.ui.graphics.Color, content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = tint)
        Spacer(Modifier.width(10.dp))
        content()
    }
}

private fun fileIcon(kind: String): ImageVector = when (kind) {
    "created" -> Icons.Outlined.NoteAdd
    "deleted" -> Icons.Outlined.Delete
    "moved" -> Icons.Outlined.DriveFileMove
    else -> Icons.Outlined.Edit
}
