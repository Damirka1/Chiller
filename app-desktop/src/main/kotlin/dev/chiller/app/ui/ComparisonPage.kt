package dev.chiller.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chiller.app.di.ComparisonRecord
import dev.chiller.app.di.ComparisonSide
import dev.chiller.app.di.ComparisonState
import dev.chiller.evals.EvalResult
import dev.chiller.design.*

class ComparisonActions(
    val start: (sides: List<ComparisonSide>, tasks: Set<String>, repeats: Int) -> Unit = { _, _, _ -> },
    val cancel: () -> Unit = {},
    val show: (ComparisonRecord?) -> Unit = {},
    val reset: () -> Unit = {},
    val close: () -> Unit = {},
)

/**
 * Compares models (and harness profiles) on the eval tasks against the current endpoint. Each task runs a real
 * agent loop with real, paid requests and independent checks, so the numbers are measured, not estimated.
 */
@Composable
internal fun ComparisonPage(
    state: ComparisonState, endpoint: String, models: List<String>, tasks: List<Pair<String, String>>,
    actions: ComparisonActions, modifier: Modifier = Modifier,
) {
    val c = LocalChillerPalette.current
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 980.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChillerControl("Назад к диалогу", actions.close, icon = Icons.AutoMirrored.Outlined.ArrowBack)
                Spacer(Modifier.weight(1f))
                Text(endpoint.removePrefix("https://").removePrefix("http://"), color = c.faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Сравнение моделей", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.5).sp)
                Text("Один и тот же набор задач, настоящий цикл агента и независимая проверка результата. Видно, какая модель и какой профиль справляются лучше и во что это обходится.", color = c.muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
            val shown = state.shown
            when {
                shown != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Сравнение от ${formatStamp(shown.startedAt)}" + if (!shown.complete) " · остановлено" else "", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        ChillerControl("Новое сравнение", { actions.show(null) }, icon = Icons.Outlined.Add)
                    }
                    ResultsTable(shown.sides, shown.tasks, shown.reports.mapIndexed { i, r -> i to r.results }.toMap(), tasks, running = null)
                }
                state.running || state.results.isNotEmpty() -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.running) { Pulse(c.accent); Spacer(Modifier.width(8.dp)) }
                        Text(if (state.running) "Идёт: ${state.current ?: "подготовка"}" else state.error?.let { "Завершено: $it" } ?: "Готово", color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (state.running) ChillerControl("Остановить", actions.cancel, icon = Icons.Outlined.Stop, style = ControlStyle.OUTLINE)
                        else ChillerControl("Новое сравнение", actions.reset, icon = Icons.Outlined.Add)
                    }
                    ResultsTable(state.sides, state.tasks, state.results, tasks, running = state.current.takeIf { state.running })
                }
                else -> Setup(models, tasks, actions)
            }
            if (state.history.isNotEmpty()) History(state.history, actions)
        }
    }
}

@Composable
private fun Setup(models: List<String>, tasks: List<Pair<String, String>>, actions: ComparisonActions) {
    val c = LocalChillerPalette.current
    var sides by remember { mutableStateOf(listOf(ComparisonSide(models.firstOrNull() ?: "", "full"), ComparisonSide(models.firstOrNull() ?: "", "compact"))) }
    var selected by remember { mutableStateOf(tasks.map { it.first }.toSet()) }
    var repeats by remember { mutableStateOf(1) }
    PageCard {
        Text("Стороны", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        sides.forEachIndexed { index, side ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.sidebar.copy(alpha = .6f)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${'A' + index}", color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(22.dp))
                    Box(Modifier.weight(1f)) { ChillerField(side.model, { v -> sides = sides.toMutableList().also { it[index] = side.copy(model = v.trim()) } }, "Модель", "ID модели на этом адресе") }
                    if (sides.size > 1) ChillerIconButton(Icons.Outlined.Close, "Убрать сторону", { sides = sides.filterIndexed { i, _ -> i != index } })
                }
                if (models.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    models.take(12).forEach { m -> ChillerControl(m, { sides = sides.toMutableList().also { it[index] = side.copy(model = m) } }, style = if (m == side.model) ControlStyle.SELECTED else ControlStyle.QUIET) }
                }
                Row(Modifier.clip(RoundedCornerShape(8.dp)).background(c.surface).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("auto" to "Авто", "full" to "Полный профиль", "compact" to "Компактный").forEach { (id, label) ->
                        ChillerControl(label, { sides = sides.toMutableList().also { it[index] = side.copy(profile = id) } }, style = if (side.profile == id) ControlStyle.SELECTED else ControlStyle.QUIET)
                    }
                }
            }
        }
        if (sides.size < 3) ChillerControl("Добавить сторону", { sides = sides + ComparisonSide(models.firstOrNull() ?: "", "auto") }, icon = Icons.Outlined.Add)
    }
    PageCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Задачи · ${selected.size} из ${tasks.size}", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            ChillerControl("Все", { selected = tasks.map { it.first }.toSet() })
            ChillerControl("Ни одной", { selected = emptySet() })
        }
        tasks.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (id, title) ->
                    val on = id in selected
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) c.selected.copy(alpha = .55f) else c.surface).border(1.dp, c.border, RoundedCornerShape(8.dp))
                        .pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Checkbox) { selected = if (on) selected - id else selected + id }.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (on) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, Modifier.size(15.dp), tint = if (on) c.accent else c.faint)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, color = c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(id, color = c.faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Повторов на задачу", color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            listOf(1, 2, 3).forEach { r -> ChillerControl("$r", { repeats = r }, style = if (repeats == r) ControlStyle.SELECTED else ControlStyle.QUIET) }
        }
    }
    val runs = sides.size * selected.size * repeats
    val ready = sides.all { it.model.isNotBlank() } && selected.isNotEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$runs ${plural(runs, "прогон", "прогона", "прогонов")} агента · не больше ${runs * 14} запросов к модели", color = c.text, fontSize = 13.sp)
            Text("Каждый прогон — настоящие платные запросы. Результат проверяется Python-скриптами и сравнением файлов, не словами модели. Нужен python3.", color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        }
        ChillerControl("Запустить сравнение", { actions.start(sides, selected, repeats) }, icon = Icons.Outlined.PlayArrow, style = ControlStyle.PRIMARY, enabled = ready)
    }
}

/** Tasks down, sides across; a summary row on top answers "which is better" at a glance. */
@Composable
private fun ResultsTable(sides: List<ComparisonSide>, taskIds: List<String>, results: Map<Int, List<EvalResult>>, titles: List<Pair<String, String>>, running: String?) {
    val c = LocalChillerPalette.current
    val summaries = sides.indices.map { i -> summary(results[i].orEmpty()) }
    val best = summaries.withIndex().filter { it.value.runs > 0 }.maxWithOrNull(compareBy<IndexedValue<SideSummary>> { it.value.passRate }.thenByDescending { it.value.prompt ?: Long.MAX_VALUE })?.index
    PageCard {
        Row {
            Spacer(Modifier.width(220.dp))
            sides.forEachIndexed { i, side ->
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${'A' + i}", color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(6.dp))
                        Text(side.model, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (i == best && sides.size > 1) { Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.EmojiEvents, "Лучший результат", Modifier.size(14.dp), tint = c.accent) }
                    }
                    Text(dev.chiller.agent.ModelProfiles.resolve(side.profile, side.model).name.lowercase() + " профиль", color = c.faint, fontSize = 11.sp)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        // Summary row.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Итого", color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(220.dp))
            summaries.forEach { s ->
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(if (s.runs == 0) "—" else "${s.passed}/${s.runs} · ${(s.passRate * 100).toInt()}%", color = if (s.runs > 0 && s.passed == s.runs) c.positive else c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    if (s.runs > 0) Text(listOfNotNull("%.1f с".format(s.seconds), s.prompt?.let { "${compactTokens(it)} → ${compactTokens(s.completion)} ток." }, "раундов ${"%.1f".format(s.avgRounds)}", s.toolErrors.takeIf { it > 0 }?.let { "ошибок инстр. $it" }).joinToString(" · "),
                        color = c.faint, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        taskIds.forEach { task ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Column(Modifier.width(220.dp)) {
                    Text(titles.firstOrNull { it.first == task }?.second ?: task, color = c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(task, color = c.faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                sides.forEachIndexed { i, side ->
                    val rows = results[i].orEmpty().filter { it.task == task }
                    Box(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        when {
                            rows.isEmpty() && running?.startsWith(side.label) == true -> Text("в очереди", color = c.faint, fontSize = 11.sp)
                            rows.isEmpty() -> Text("—", color = c.faint, fontSize = 12.sp)
                            else -> Cell(rows)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(rows: List<EvalResult>) {
    val c = LocalChillerPalette.current
    val passed = rows.count { it.passed }
    val ok = passed == rows.size
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).clip(CircleShape).background((if (ok) c.positive else if (passed > 0) c.muted else c.negative).copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            Icon(if (ok) Icons.Outlined.Check else Icons.Outlined.Close, null, Modifier.size(12.dp), tint = if (ok) c.positive else if (passed > 0) c.muted else c.negative)
        }
        Spacer(Modifier.width(6.dp))
        Column {
            Text((if (rows.size > 1) "$passed/${rows.size} · " else "") + "%.1f с · %d р.".format(rows.sumOf { it.durationMs } / 1000.0 / rows.size, rows.sumOf { it.rounds } / rows.size), color = c.text, fontSize = 11.sp)
            Text(rows.last().verdict.take(60), color = c.faint, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun History(history: List<ComparisonRecord>, actions: ComparisonActions) {
    val c = LocalChillerPalette.current
    PageCard {
        Text("История", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        history.forEach { record ->
            HoverRow({ actions.show(record) }) {
                Icon(Icons.Outlined.Insights, null, Modifier.size(15.dp), tint = c.muted)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(record.sides.joinToString("  vs  ") { it.label }, color = c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatStamp(record.startedAt) + " · ${record.tasks.size} задач" + if (!record.complete) " · остановлено" else "", color = c.faint, fontSize = 11.sp)
                }
                Text(record.reports.joinToString("  ") { r -> "${r.results.count { it.passed }}/${r.results.size}" }, color = c.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private data class SideSummary(val runs: Int, val passed: Int, val seconds: Double, val prompt: Long?, val completion: Long?, val avgRounds: Double, val toolErrors: Int) {
    val passRate: Double get() = if (runs == 0) 0.0 else passed.toDouble() / runs
}

private fun summary(rows: List<EvalResult>) = SideSummary(
    runs = rows.size, passed = rows.count { it.passed }, seconds = rows.sumOf { it.durationMs } / 1000.0,
    prompt = rows.takeIf { r -> r.isNotEmpty() && r.all { it.promptTokens != null } }?.sumOf { it.promptTokens!! },
    completion = rows.takeIf { r -> r.isNotEmpty() && r.all { it.completionTokens != null } }?.sumOf { it.completionTokens!! },
    avgRounds = if (rows.isEmpty()) 0.0 else rows.sumOf { it.rounds }.toDouble() / rows.size,
    toolErrors = rows.sumOf { it.failedToolCalls },
)

private fun formatStamp(stamp: String) = runCatching { "${stamp.substring(6, 8)}.${stamp.substring(4, 6)} ${stamp.substring(9, 11)}:${stamp.substring(11, 13)}" }.getOrDefault(stamp)

@Composable
private fun PageCard(content: @Composable ColumnScope.() -> Unit) {
    val c = LocalChillerPalette.current
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}
