package dev.chiller.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chiller.app.di.*
import dev.chiller.design.*

/** Everything the settings page shows. Keys themselves are never part of it — only where they came from. */
data class SettingsData(
    val baseUrl: String, val model: String, val thinking: Boolean, val keyConfigured: Boolean,
    val keySources: Map<String, String> = emptyMap(),
    val connection: ConnectionState = ConnectionState(), val catalog: ModelCatalogState = ModelCatalogState(),
    val preferences: UiPreferences = UiPreferences(), val allowProcesses: Boolean = false, val agent: AgentOptions = AgentOptions(),
    val recent: List<String> = emptyList(), val dataDir: String = "", val demoCount: Int = 0,
    val version: String = "dev", val build: String = "", val commit: String = "", val runtime: String = "", val os: String = "",
)

class SettingsActions(
    val applyConnection: (url: String, model: String, thinking: Boolean, key: String?) -> Unit = { _, _, _, _ -> },
    val checkConnection: () -> Unit = {}, val cancelCheck: () -> Unit = {}, val refreshModels: () -> Unit = {},
    val preferences: (UiPreferences) -> Unit = {}, val allowProcesses: (Boolean) -> Unit = {}, val agent: (AgentOptions) -> Unit = {},
    val removeRecent: (String) -> Unit = {}, val openPath: (String) -> Unit = {}, val openUrl: (String) -> Unit = {},
    val deleteDemos: () -> Unit = {}, val close: () -> Unit = {},
)

private enum class Section(val title: String, val icon: ImageVector) {
    CONNECTION("Подключение", Icons.Outlined.Cable), AGENT("Агент", Icons.Outlined.SmartToy), APPEARANCE("Внешний вид", Icons.Outlined.Palette),
    DATA("Проекты и данные", Icons.Outlined.FolderOpen), KEYS("Клавиши", Icons.Outlined.Keyboard), ABOUT("О программе", Icons.Outlined.Info),
}

/** Settings as a full page in the main area: section list on the left, the section on the right. Escape goes back. */
@Composable
internal fun SettingsPage(data: SettingsData, actions: SettingsActions, modifier: Modifier = Modifier, initial: String? = null) {
    val c = LocalChillerPalette.current
    var section by remember { mutableStateOf(Section.entries.firstOrNull { it.name == initial } ?: Section.CONNECTION) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(modifier.focusRequester(focus).onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { actions.close(); true } else false }.focusable()) {
        Column(Modifier.width(220.dp).fillMaxHeight().padding(start = 16.dp, top = 8.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ChillerControl("Назад к диалогу", actions.close, icon = Icons.AutoMirrored.Outlined.ArrowBack, trailing = "esc", inlineTrailing = true)
            Spacer(Modifier.height(14.dp))
            Text("Настройки", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.4).sp, modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
            Section.entries.forEach { s -> ChillerControl(s.title, { section = s }, Modifier.fillMaxWidth(), icon = s.icon, style = if (s == section) ControlStyle.SELECTED else ControlStyle.QUIET) }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.border.copy(alpha = .6f)))
        Crossfade(section, Modifier.weight(1f).fillMaxHeight(), animationSpec = tween(if (LocalChillerReducedMotion.current) 0 else 150), label = "settings") { current ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(current.title, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.5).sp)
                    when (current) {
                        Section.CONNECTION -> ConnectionSection(data, actions)
                        Section.AGENT -> AgentSection(data, actions)
                        Section.APPEARANCE -> AppearanceSection(data, actions)
                        Section.DATA -> DataSection(data, actions)
                        Section.KEYS -> KeysSection()
                        Section.ABOUT -> AboutSection(data, actions)
                    }
                }
            }
        }
    }
}


@Composable
private fun ConnectionSection(data: SettingsData, actions: SettingsActions) {
    val c = LocalChillerPalette.current
    var url by remember(data.baseUrl) { mutableStateOf(data.baseUrl) }
    var model by remember(data.model) { mutableStateOf(data.model) }
    var thinking by remember(data.thinking) { mutableStateOf(data.thinking) }
    var key by remember { mutableStateOf("") }
    val formOrigin = originOf(url)
    val preset = ProviderPresets.byOrigin(url)
    val changed = url.trimEnd('/') != data.baseUrl || model.trim() != data.model || thinking != data.thinking || key.isNotEmpty()
    Card {
        SectionTitle("Провайдер", "Все провайдеры совместимы с OpenAI Chat Completions. Ключ уходит только на адрес, для которого он задан.")
        ProviderPresets.all.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { p ->
                    val origin = originOf(p.baseUrl)
                    val status = when {
                        p.keyless -> "без ключа" to c.muted
                        data.keySources[origin] == "env" -> "ключ из .env" to c.positive
                        data.keySources[origin] == "ui" -> "ключ задан" to c.positive
                        else -> "нужен ключ" to c.faint
                    }
                    PresetCard(p, status, selected = origin == formOrigin, Modifier.weight(1f)) {
                        url = p.baseUrl; key = ""
                        model = if (originOf(data.baseUrl) == origin) data.model else p.defaultModel.orEmpty()
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    Card {
        SectionTitle(preset?.name ?: "Свой адрес", preset?.note?.ifBlank { null } ?: "Любой сервер с /chat/completions; HTTP разрешён только для localhost.")
        ChillerField(url, { url = it }, "Адрес API", "https://api.example.com/v1")
        ChillerField(model, { model = it }, "Модель", preset?.defaultModel ?: "ID модели у провайдера")
        // Discovered models of the applied endpoint, one click to use.
        if (originOf(data.baseUrl) == formOrigin) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(catalogLine(data.catalog, data.keyConfigured), color = c.faint, fontSize = 11.sp, modifier = Modifier.weight(1f))
                ChillerControl("Загрузить список моделей", actions.refreshModels, icon = Icons.Outlined.Refresh, enabled = data.catalog.status != "loading")
            }
            if (data.catalog.models.isNotEmpty()) ChipFlow(data.catalog.models.map { it.id }.take(24), model) { model = it }
        } else Text("Список моделей можно загрузить после применения адреса.", color = c.faint, fontSize = 11.sp)
        val keyHint = when {
            preset?.keyless == true || isLoopback(url) -> "Локальный сервер: ключ не нужен"
            data.keySources[formOrigin] == "env" -> "Ключ загружен из .env или переменной окружения. Новый заменит его до выхода"
            data.keySources[formOrigin] == "ui" -> "Ключ задан. Пустое поле оставляет его"
            else -> "Введите ключ" + (preset?.keyVariables?.firstOrNull()?.let { " или задайте $it в .env" } ?: "")
        }
        ChillerField(key, { key = it }, "API-ключ", keyHint, secret = true)
        preset?.keysUrl?.let { link -> ChillerControl("Где взять ключ: ${link.removePrefix("https://")}", { actions.openUrl(link) }, icon = Icons.AutoMirrored.Outlined.OpenInNew) }
        SettingToggle("Рассуждения модели", "Если провайдер поддерживает режим thinking", thinking) { thinking = it }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChillerControl("Применить", { actions.applyConnection(url.trim(), model.trim(), thinking, key.takeIf { it.isNotBlank() }); key = "" }, style = ControlStyle.PRIMARY, enabled = changed && url.isNotBlank() && model.isNotBlank())
            if (data.connection.status == "checking") ChillerControl("Отменить проверку", actions.cancelCheck, style = ControlStyle.OUTLINE)
            else ChillerControl("Проверить подключение", actions.checkConnection, style = ControlStyle.OUTLINE, enabled = !changed && data.keyConfigured)
        }
        val (line, color) = connectionSummary(data.connection, data.keyConfigured)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(8.dp))
            Text(if (changed) "Изменения не применены" else data.connection.message.ifBlank { line }, color = if (data.connection.status == "failed" && !changed) c.negative else c.muted, fontSize = 12.sp)
        }
        Text("Применение начинает новый диалог. Проверка отправляет один короткий запрос без файлов и инструментов. Ключи хранятся в памяти до выхода; из .env и переменных окружения читаются при запуске. Вживую в Chiller проверен только DeepSeek.", color = c.faint, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun PresetCard(preset: ProviderPreset, status: Pair<String, Color>, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalChillerPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.clip(shape).background(if (selected) c.selected.copy(alpha = .6f) else if (hovered) c.hover else c.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) c.accent.copy(alpha = .7f) else c.border, shape)
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand).clickable(interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(preset.name, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (preset.verified) Icon(Icons.Outlined.Verified, "Проверено в Chiller", Modifier.size(14.dp), tint = c.accent)
        }
        Text(preset.baseUrl.removePrefix("https://").removePrefix("http://"), color = c.faint, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(status.second)); Spacer(Modifier.width(5.dp))
            Text(status.first, color = c.muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ChipFlow(items: List<String>, selected: String, onPick: (String) -> Unit) {
    val c = LocalChillerPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { id ->
                    val active = id == selected
                    Text(id, color = if (active) c.text else c.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (active) c.selected else c.sidebar)
                            .pointerHoverIcon(PointerIcon.Hand).clickable { onPick(id) }.padding(horizontal = 8.dp, vertical = 5.dp))
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AgentSection(data: SettingsData, actions: SettingsActions) {
    val c = LocalChillerPalette.current
    val a = data.agent
    Card {
        SectionTitle("Лимиты прогона", "Ограничивают одну задачу. Применяются со следующего диалога.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Шагов модели за задачу", color = c.text, fontSize = 13.sp)
                Text("Каждый шаг — один запрос к модели", color = c.faint, fontSize = 11.sp)
            }
            ChillerIconButton(Icons.Outlined.Remove, "Меньше", { actions.agent(a.copy(maxRounds = a.maxRounds - 4)) }, enabled = a.maxRounds > 4)
            Text("${a.maxRounds}", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(min = 36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            ChillerIconButton(Icons.Outlined.Add, "Больше", { actions.agent(a.copy(maxRounds = a.maxRounds + 4)) }, enabled = a.maxRounds < 60)
        }
        Text("Длина ответа за шаг, токенов", color = c.text, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(2048, 4096, 8192, 16384).forEach { v -> ChillerControl(formatK(v), { actions.agent(a.copy(maxOutputTokens = v)) }, style = if (a.maxOutputTokens == v) ControlStyle.SELECTED else ControlStyle.QUIET) }
        }
    }
    Card {
        SectionTitle("Возможности", null)
        SettingToggle("Помощники (субагенты)", "До 3 параллельных помощников только на чтение: ищут и читают код, присылают отчёт", a.subagents) { actions.agent(a.copy(subagents = it)) }
        SettingToggle("Инструкции проекта", "Читать AGENTS.md, CLAUDE.md и .chiller/instructions.md как указания (не как разрешения)", a.projectInstructions) { actions.agent(a.copy(projectInstructions = it)) }
        SettingToggle("Команды процессов", "Разрешить запуск тестов и сборки в режиме «Изменения». Это не песочница ОС", data.allowProcesses) { actions.allowProcesses(it) }
    }
}

private fun formatK(v: Int) = if (v >= 1024) "${v / 1024}k" else "$v"

@Composable
private fun AppearanceSection(data: SettingsData, actions: SettingsActions) {
    val p = data.preferences
    Card {
        SectionTitle("Тема", null)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("system" to "Системная", "light" to "Светлая", "dark" to "Тёмная").forEach { (id, label) -> ThemeSwatch(id, label, p.theme == id, Modifier.weight(1f)) { actions.preferences(p.copy(theme = id)) } }
        }
    }
    Card {
        SectionTitle("Интерфейс", null)
        Choice("Плотность", listOf("normal" to "Обычная", "compact" to "Компактная"), p.density) { actions.preferences(p.copy(density = it)) }
        Choice("Раскладка по умолчанию", listOf("focus" to "Focus", "studio" to "Studio"), p.layout) { actions.preferences(p.copy(layout = it)) }
        SettingToggle("Уменьшить движение", "Без анимаций появления, переливов и мигающего курсора", p.reducedMotion) { actions.preferences(p.copy(reducedMotion = it)) }
    }
}

@Composable
private fun ThemeSwatch(id: String, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalChillerPalette.current
    val (bg, fg) = when (id) { "dark" -> Color(0xFF1C1E20) to Color(0xFF2D3238); "light" -> Color(0xFFFFFFFF) to Color(0xFFE4E7EA); else -> Color(0xFFF4F5F6) to Color(0xFF2D3238) }
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.clip(shape).border(if (selected) 1.5.dp else 1.dp, if (selected) c.accent else c.border, shape).pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.RadioButton, onClick = onClick).padding(8.dp)) {
        Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(7.dp)).background(bg).border(1.dp, c.border, RoundedCornerShape(7.dp)).padding(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.width(46.dp).height(6.dp).clip(CircleShape).background(fg))
                Box(Modifier.width(70.dp).height(6.dp).clip(CircleShape).background(fg.copy(alpha = .6f)))
                Box(Modifier.width(30.dp).height(6.dp).clip(CircleShape).background(Color(0xFF376D59)))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (selected) c.text else c.muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun DataSection(data: SettingsData, actions: SettingsActions) {
    val c = LocalChillerPalette.current
    Card {
        SectionTitle("Недавние проекты", "Список на стартовом экране. Убрать проект из списка — не удалить папку.")
        if (data.recent.isEmpty()) Text("Пока пусто", color = c.faint, fontSize = 12.sp)
        data.recent.forEach { path ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, null, Modifier.size(15.dp), tint = c.muted); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(path.substringAfterLast('/'), color = c.text, fontSize = 13.sp)
                    Text(homeRelative(path), color = c.faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ChillerControl("Открыть в Finder", { actions.openPath(path) })
                ChillerControl("Убрать", { actions.removeRecent(path) })
            }
        }
    }
    Card {
        SectionTitle("Данные приложения", "Диалоги, журналы, трассировки запросов и настройки. Ключи сюда не записываются.")
        PathRow("Папка данных", data.dataDir) { actions.openPath(data.dataDir) }
        PathRow("Трассировки запросов", "${data.dataDir}/traces") { actions.openPath("${data.dataDir}/traces") }
        var confirm by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Демо-проекты", color = c.text, fontSize = 13.sp)
                Text(if (data.demoCount == 0) "Нет" else "${data.demoCount} ${plural(data.demoCount, "папка", "папки", "папок")} в ${homeRelative(data.dataDir)}/demo", color = c.faint, fontSize = 11.sp)
            }
            if (data.demoCount > 0) ChillerControl(if (confirm) "Точно удалить?" else "Удалить", { if (confirm) { actions.deleteDemos(); confirm = false } else confirm = true }, icon = Icons.Outlined.DeleteOutline, style = if (confirm) ControlStyle.OUTLINE else ControlStyle.QUIET)
        }
    }
}

@Composable
private fun PathRow(title: String, path: String, onOpen: () -> Unit) {
    val c = LocalChillerPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 13.sp)
            Text(homeRelative(path), color = c.faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ChillerControl("Открыть", onOpen, icon = Icons.Outlined.FolderOpen)
    }
}

@Composable
private fun KeysSection() {
    val c = LocalChillerPalette.current
    Card {
        listOf("⌘ N" to "Новый диалог", "⌘ O" to "Открыть проект", "⌘ K" to "Поиск по диалогам", "⌘ ↵" to "Отправить", "⌘ ." to "Остановить задачу",
            "⌘ ," to "Настройки", "Esc" to "Закрыть меню, настройки или поиск", "↑ ↓ ↵" to "Выбор в меню модели", "Tab" to "Навигация с клавиатуры (видимый фокус)").forEach { (keys, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(label, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(keys, color = c.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, c.border, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        Text("На Windows и Linux вместо ⌘ используется Ctrl.", color = c.faint, fontSize = 11.sp)
    }
}

@Composable
private fun AboutSection(data: SettingsData, actions: SettingsActions) {
    val c = LocalChillerPalette.current
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) { ChillerMark(Modifier.size(28.dp), c.accent) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Chiller ${data.version}", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("Harness для локальных и облачных моделей", color = c.muted, fontSize = 12.sp)
            }
        }
        listOf("Версия" to data.version, "Сборка" to data.build, "Коммит" to data.commit, "Среда выполнения" to data.runtime, "Система" to data.os, "Данные" to homeRelative(data.dataDir)).forEach { (k, v) ->
            Row { Text(k, color = c.faint, fontSize = 12.sp, modifier = Modifier.width(150.dp)); Text(v.ifBlank { "—" }, color = c.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
        }
        ChillerControl("Репозиторий на GitHub", { actions.openUrl("https://github.com/Damirka1/Chiller") }, icon = Icons.AutoMirrored.Outlined.OpenInNew)
    }
    Card {
        SectionTitle("Что проверено", null)
        Text("Вживую: DeepSeek V4.1 Flash (eval-задачи и помощники). Офлайн: весь цикл инструментов, отмена правок, журналы. Не проверено: Gemma/vLLM, другие провайдеры из списка, Windows и Linux вживую.", color = c.muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    val c = LocalChillerPalette.current
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun SectionTitle(title: String, detail: String?) {
    val c = LocalChillerPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        detail?.let { Text(it, color = c.faint, fontSize = 12.sp, lineHeight = 17.sp) }
    }
}

@Composable
private fun SettingToggle(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalChillerPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = c.text, fontSize = 13.sp)
            Text(detail, color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        }
        ChillerToggle(value, onChange, title)
    }
}

@Composable
private fun Choice(title: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    val c = LocalChillerPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(c.sidebar).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { (id, label) -> ChillerControl(label, { onPick(id) }, style = if (selected == id) ControlStyle.SELECTED else ControlStyle.QUIET) }
        }
    }
}
