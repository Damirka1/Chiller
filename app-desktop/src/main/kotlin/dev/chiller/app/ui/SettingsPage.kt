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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
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
import dev.chiller.contracts.CaptureTarget
import dev.chiller.design.*
import kotlinx.coroutines.launch

/** Everything the settings page shows. Keys themselves are never part of it — only where they came from. */
data class SettingsData(
    val baseUrl: String, val model: String, val thinking: Boolean, val keyConfigured: Boolean,
    val keySources: Map<String, String> = emptyMap(), val secureStorageSupported: Boolean = false,
    val connection: ConnectionState = ConnectionState(), val catalog: ModelCatalogState = ModelCatalogState(),
    val preferences: UiPreferences = UiPreferences(), val allowProcesses: Boolean = false, val agent: AgentOptions = AgentOptions(),
    val customProviders: List<CustomProvider> = emptyList(),
    val recent: List<String> = emptyList(), val dataDir: String = "", val demoCount: Int = 0,
    val version: String = "dev", val build: String = "", val commit: String = "", val runtime: String = "", val os: String = "",
    val captureTargets: List<CaptureTarget> = emptyList(), val selectedCaptureTarget: String? = null, val screenshotSupported: Boolean = false,
    /** The open project, its saved check command and a suggestion from its build files. */
    val workspace: String? = null, val verifyCommand: String = "", val suggestedVerifyCommand: String? = null,
)

class SettingsActions(
    val applyConnection: (url: String, model: String, thinking: Boolean, key: String?) -> Unit = { _, _, _, _ -> },
    val checkConnection: () -> Unit = {}, val cancelCheck: () -> Unit = {}, val refreshModels: () -> Unit = {},
    val preferences: (UiPreferences) -> Unit = {}, val allowProcesses: (Boolean) -> Unit = {}, val agent: (AgentOptions) -> Unit = {},
    val removeRecent: (String) -> Unit = {}, val openPath: (String) -> Unit = {}, val openUrl: (String) -> Unit = {},
    val deleteDemos: () -> Unit = {},
    val openComparison: (() -> Unit)? = null, val close: () -> Unit = {},
    val setVerifyCommand: (root: String, command: String) -> Unit = { _, _ -> },
    val saveCredential: () -> Unit = {}, val deleteCredential: () -> Unit = {},
    /** Saves a custom provider; the callback gets why it was refused, or null. */
    val saveCustomProvider: (CustomProvider, (String?) -> Unit) -> Unit = { _, done -> done(null) },
    val deleteCustomProvider: (String) -> Unit = {},
    val refreshCaptureTargets: () -> Unit = {}, val selectCaptureTarget: (String) -> Unit = {},
)

internal enum class SettingsSection(val title: String, val icon: ImageVector) {
    CONNECTION("Подключение", Icons.Outlined.Cable), AGENT("Агент", Icons.Outlined.SmartToy), APPEARANCE("Внешний вид", Icons.Outlined.Palette),
    DATA("Проекты и данные", Icons.Outlined.FolderOpen), KEYS("Клавиши", Icons.Outlined.Keyboard), ABOUT("О программе", Icons.Outlined.Info),
}

/** The settings section list. Lives in the app sidebar while it is shown, otherwise at the page's left edge. */
@Composable
internal fun SettingsNav(
    section: SettingsSection, onSection: (SettingsSection) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,
    /** Connection status colour shown next to "Подключение"; the sidebar footer that normally shows it is hidden here. */
    connectionDot: androidx.compose.ui.graphics.Color? = null,
) {
    val c = LocalChillerPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ChillerControl("Назад к диалогу", onClose, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Outlined.ArrowBack, trailing = "esc", inlineTrailing = true)
        Spacer(Modifier.height(14.dp))
        Text("Настройки", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.4).sp, modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
        SettingsSection.entries.forEach { s ->
            Box(contentAlignment = Alignment.CenterEnd) {
                ChillerControl(s.title, { onSection(s) }, Modifier.fillMaxWidth(), icon = s.icon, style = if (s == section) ControlStyle.SELECTED else ControlStyle.QUIET)
                if (s == SettingsSection.CONNECTION && connectionDot != null) Box(Modifier.padding(end = 12.dp).size(7.dp).clip(CircleShape).background(connectionDot))
            }
        }
    }
}

/**
 * Settings as a full page in the main area. The section list is drawn here only when the sidebar is hidden;
 * otherwise the sidebar shows it in place of the dialogs. Escape goes back.
 */
@Composable
internal fun SettingsPage(
    data: SettingsData, actions: SettingsActions, section: SettingsSection, onSection: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier, showNav: Boolean = true,
) {
    val c = LocalChillerPalette.current
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(modifier.focusRequester(focus).onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { actions.close(); true } else false }
        // Arrows switch sections only when no field took them first (this listener runs after the focused child).
        .onKeyEvent {
            val step = when { it.type != KeyEventType.KeyDown -> 0; it.key == Key.DirectionDown -> 1; it.key == Key.DirectionUp -> -1; else -> 0 }
            if (step == 0) false else { onSection(SettingsSection.entries[(section.ordinal + step).coerceIn(0, SettingsSection.entries.lastIndex)]); true }
        }.focusable()) {
        if (showNav) {
            SettingsNav(section, onSection, actions.close, Modifier.width(220.dp).fillMaxHeight().padding(start = 16.dp, top = 8.dp, end = 8.dp))
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.border.copy(alpha = .6f)))
        }
        Crossfade(section, Modifier.weight(1f).fillMaxHeight(), animationSpec = tween(if (LocalChillerReducedMotion.current) 0 else 150), label = "settings") { current ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(current.title, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.5).sp)
                    when (current) {
                        SettingsSection.CONNECTION -> ConnectionSection(data, actions)
                        SettingsSection.AGENT -> AgentSection(data, actions)
                        SettingsSection.APPEARANCE -> AppearanceSection(data, actions)
                        SettingsSection.DATA -> DataSection(data, actions)
                        SettingsSection.KEYS -> KeysSection()
                        SettingsSection.ABOUT -> AboutSection(data, actions)
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
    val preset = ProviderPresets.byOrigin(url, data.customProviders)
    val custom = data.customProviders.firstOrNull { originOf(it.baseUrl) == formOrigin }
    var editing by remember { mutableStateOf<CustomProvider?>(null) }
    val changed = url.trimEnd('/') != data.baseUrl || model.trim() != data.model || thinking != data.thinking || key.isNotEmpty()
    Card {
        SectionTitle("Провайдер", "Все провайдеры совместимы с OpenAI Chat Completions. Ключ уходит только на адрес, для которого он задан.")
        (ProviderPresets.all + data.customProviders.map { it.preset() }).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { p ->
                    val origin = originOf(p.baseUrl)
                    val status = when {
                        p.keyless -> "без ключа" to c.muted
                        data.keySources[origin] == "env" -> "ключ из .env" to c.positive
                        data.keySources[origin] == "keychain" -> "сохранён в Keychain" to c.positive
                        data.keySources[origin] == "ui" -> "ключ задан" to c.positive
                        else -> "нужен ключ" to c.faint
                    }
                    PresetCard(p, status, selected = origin == formOrigin, Modifier.weight(1f)) {
                        url = p.baseUrl; key = ""
                        model = if (originOf(data.baseUrl) == origin) data.model else p.defaultModel.orEmpty()
                        // A custom provider brings its own thinking default and opens for editing.
                        data.customProviders.firstOrNull { "custom:${it.id}" == p.id }.let { chosen ->
                            editing = chosen
                            chosen?.thinking?.takeIf { originOf(data.baseUrl) != origin }?.let { thinking = it }
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    CustomProviderCard(data, actions, editing, onEdit = { editing = it }, onUse = { p -> url = p.baseUrl; key = ""; model = p.defaultModel.orEmpty(); p.thinking?.let { thinking = it } })
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
            if (data.catalog.chatModels.isNotEmpty()) ChipFlow(data.catalog.chatModels.map { it.id }.take(24), model, data.catalog.chatModels.associate { it.id to (it.displayName?.let { name -> "$name · ${it.id}" } ?: it.id) }) { model = it }
        } else Text("Примените адрес и ключ, затем загрузите список моделей. ID заранее знать не нужно.", color = c.faint, fontSize = 11.sp)
        val keyHint = when {
            preset?.keyless == true || isLoopback(url) -> "Локальный сервер: ключ не нужен"
            data.keySources[formOrigin] == "env" -> "Ключ загружен из .env или переменной окружения. Новый заменит его до выхода"
            data.keySources[formOrigin] == "keychain" -> "Ключ сохранён в macOS Keychain. Новый заменит его только после сохранения"
            data.keySources[formOrigin] == "ui" -> "Ключ задан. Пустое поле оставляет его"
            else -> "Введите ключ" + (preset?.keyVariables?.firstOrNull()?.let { " или задайте $it в .env" } ?: "")
        }
        ChillerField(key, { key = it }, "API-ключ", keyHint, secret = true)
        if (originOf(data.baseUrl) == formOrigin && data.keyConfigured && !isLoopback(url)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (data.secureStorageSupported) ChillerControl("Сохранить текущий ключ в Keychain", actions.saveCredential, enabled = !changed)
                ChillerControl("Удалить текущий ключ", actions.deleteCredential, enabled = !changed)
            }
        }
        Text(if (data.secureStorageSupported) "Применить — использовать до выхода. Для постоянного хранения нажмите «Сохранить текущий ключ в Keychain»." else "На этой платформе ключ из поля действует до выхода. Для запуска используйте переменную окружения или .env.", color = c.faint, fontSize = 11.sp)
        preset?.keysUrl?.let { link -> ChillerControl("Где взять ключ: ${link.removePrefix("https://")}", { actions.openUrl(link) }, icon = Icons.AutoMirrored.Outlined.OpenInNew) }
        SettingToggle("Рассуждения модели", "Если провайдер поддерживает режим thinking", thinking) { thinking = it }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChillerControl("Применить", {
                custom?.profile?.takeIf { it != data.agent.profile }?.let { actions.agent(data.agent.copy(profile = it)) }
                actions.applyConnection(url.trim(), model.trim(), thinking, key.takeIf { it.isNotBlank() }); key = ""
            }, style = ControlStyle.PRIMARY, enabled = changed && url.isNotBlank() )
            if (data.connection.status == "checking") ChillerControl("Отменить проверку", actions.cancelCheck, style = ControlStyle.OUTLINE)
            else ChillerControl("Проверить подключение", actions.checkConnection, style = ControlStyle.OUTLINE, enabled = !changed && data.keyConfigured && model.isNotBlank())
        }
        val (line, color) = connectionSummary(data.connection, data.keyConfigured)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(8.dp))
            Text(if (changed) "Изменения не применены" else data.connection.message.ifBlank { line }, color = if (data.connection.status == "failed" && !changed) c.negative else c.muted, fontSize = 12.sp)
        }
        Text("Ключ: ${if (data.keyConfigured) "настроен" else "не задан"} · каталог: ${if (data.catalog.models.isEmpty()) "не загружен" else "${data.catalog.models.size} моделей"}. Проверка генерации — отдельный запрос.", color = c.faint, fontSize = 11.sp, lineHeight = 17.sp)
        Text("Применение начинает новый диалог. Проверка отправляет один короткий запрос без файлов и инструментов. Ключ действует до выхода, если не сохранён явно в Keychain; из .env и переменных окружения читается при запуске. Вживую в Chiller проверен только DeepSeek.", color = c.faint, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

/**
 * Adds or edits a provider kept only in this computer's settings: name, address, the env/.env variable that holds
 * its key, and what to apply with it. Nothing typed here is sent anywhere until the provider is applied.
 */
@Composable
private fun CustomProviderCard(data: SettingsData, actions: SettingsActions, editing: CustomProvider?, onEdit: (CustomProvider?) -> Unit, onUse: (CustomProvider) -> Unit) {
    val c = LocalChillerPalette.current
    val base = editing ?: CustomProvider(java.util.UUID.randomUUID().toString().take(8), "", "")
    var name by remember(base) { mutableStateOf(base.name) }
    var address by remember(base) { mutableStateOf(base.baseUrl) }
    var keyVariable by remember(base) { mutableStateOf(base.keyVariable.orEmpty()) }
    var defaultModel by remember(base) { mutableStateOf(base.defaultModel.orEmpty()) }
    var thinking by remember(base) { mutableStateOf(base.thinking) }
    var profile by remember(base) { mutableStateOf(base.profile) }
    var message by remember(base) { mutableStateOf<String?>(null) }
    val draft = CustomProvider(base.id, name, address, keyVariable, defaultModel, thinking, profile).normalized()
    // Folded until asked for or a custom provider is chosen, so the connection form stays near the presets.
    var open by remember(editing) { mutableStateOf(editing != null) }
    if (!open) {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Свой провайдер: шлюз компании или домашний сервер, хранится только на этом компьютере", color = c.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                ChillerControl("Добавить свой провайдер", { open = true }, style = ControlStyle.OUTLINE)
            }
        }
        return
    }
    Card {
        SectionTitle(if (editing == null) "Свой провайдер" else "Свой провайдер · ${editing.name}",
            "Например, шлюз компании. Хранится только в настройках этого компьютера и не попадает в репозиторий. Ключ читается из переменной окружения или .env.")
        ChillerField(name, { name = it }, "Название", "Qarmet")
        ChillerField(address, { address = it }, "Адрес API", "https://gateway.example.com/v1")
        ChillerField(keyVariable, { keyVariable = it }, "Переменная с ключом (необязательно)", "QARMET_API_KEY")
        ChillerField(defaultModel, { defaultModel = it }, "Модель по умолчанию (необязательно)", "quasar")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Рассуждения", color = c.text, fontSize = 12.sp, modifier = Modifier.width(110.dp))
            listOf(null to "Не менять", true to "Вкл", false to "Выкл").forEach { (value, label) ->
                ChillerControl(label, { thinking = value }, style = if (thinking == value) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Профиль", color = c.text, fontSize = 12.sp, modifier = Modifier.width(110.dp))
            listOf(null to "Не менять", "auto" to "Авто", "full" to "Полный", "compact" to "Компактный").forEach { (value, label) ->
                ChillerControl(label, { profile = value }, style = if (profile == value) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChillerControl(if (editing == null) "Добавить" else "Сохранить", {
                val problem = draft.problem()
                if (problem != null) message = problem
                else actions.saveCustomProvider(draft) { refused -> message = refused ?: "Сохранено"; if (refused == null) { onEdit(draft); onUse(draft) } }
            }, style = ControlStyle.PRIMARY, enabled = name.isNotBlank() && address.isNotBlank())
            if (editing != null) {
                ChillerControl("Удалить", { actions.deleteCustomProvider(editing.id); onEdit(null) }, style = ControlStyle.OUTLINE)
            }
            ChillerControl("Свернуть", { if (editing != null) onEdit(null) else open = false })
        }
        message?.let { Text(it, color = if (it == "Сохранено") c.positive else c.negative, fontSize = 11.sp) }
        if (draft.keyVariable != null) Text("Ключ берётся из ${draft.keyVariable} при запуске и при сохранении; уходит только на ${originOf(draft.baseUrl) ?: "этот адрес"}.", color = c.faint, fontSize = 11.sp, lineHeight = 15.sp)
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
private fun ChipFlow(items: List<String>, selected: String, labels: Map<String, String> = emptyMap(), onPick: (String) -> Unit) {
    val c = LocalChillerPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { id ->
                    val active = id == selected
                    Text(labels[id] ?: id, color = if (active) c.text else c.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
    ProfileCard(data, actions)
    VerifyCard(data, actions)
    Card {
        SectionTitle("Лимиты прогона", "Ограничивают одну задачу. Применяются со следующего сообщения, история сохраняется.")
        Text("Шагов модели за задачу", color = c.text, fontSize = 13.sp)
        Text("Каждый шаг — один запрос к модели; большой файл или длинная задача может занять десятки шагов", color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Same chips as the other limits; a value saved by the old stepper still shows as its own selected chip.
            val steps = listOf(24, 48, 96, 200).let { if (a.maxRounds != AgentOptions.UNLIMITED && a.maxRounds !in it) (it + a.maxRounds).sorted() else it }
            (steps.map { it to "$it" } + (AgentOptions.UNLIMITED to "Без лимита")).forEach { (v, label) ->
                ChillerControl(label, { actions.agent(a.copy(maxRounds = v)) }, style = if (a.maxRounds == v) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        Text("Общий бюджет: основной агент и все помощники", color = c.text, fontSize = 13.sp)
        Text("Логические запросы к модели на всю задачу; транспортные повторы не считаются", color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(24 to "24", 48 to "48", 96 to "96", 192 to "192", AgentOptions.UNLIMITED to "Без лимита").forEach { (v, label) ->
                ChillerControl(label, { actions.agent(a.copy(maxRequests = v)) }, style = if (a.maxRequests == v) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        Text("Сумма сгенерированных выходных токенов на всю задачу, включая помощников. Это не лимит стоимости; входные токены не учитываются.", color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(32768 to "32k", 65536 to "64k", 262144 to "256k", 1_048_576 to "1M", AgentOptions.UNLIMITED to "Без лимита").forEach { (v, label) ->
                ChillerControl(label, { actions.agent(a.copy(totalOutputTokens = v)) }, style = if (a.totalOutputTokens == v) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        Text("Длина ответа за шаг, токенов", color = c.text, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(4096 to "4k", 8192 to "8k", 16384 to "16k", 32768 to "32k", 65536 to "64k", AgentOptions.UNLIMITED to "Максимум модели").forEach { (v, label) ->
                ChillerControl(label, { actions.agent(a.copy(maxOutputTokens = v)) }, style = if (a.maxOutputTokens == v) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        Text("«Максимум модели» просит столько, сколько разрешает сама модель (например, у DeepSeek Flash до 384k): предел узнаётся из ответа провайдера и запоминается. " +
            "Рассуждения модели расходуют этот же лимит. Если ответ всё же упрётся в предел, обрезанный вызов не выполняется, а агент продолжит, записывая файл частями.",
            color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        if (a.maxRounds == AgentOptions.UNLIMITED || a.maxRequests == AgentOptions.UNLIMITED || a.totalOutputTokens == AgentOptions.UNLIMITED)
            Text("Без лимита задача идёт, пока не закончит, пока вы её не остановите или до страховочных ${AgentOptions.UNLIMITED_ROUNDS_BACKSTOP} шагов. Следите за расходом токенов в Studio → Прогон.",
                color = c.negative, fontSize = 11.sp, lineHeight = 16.sp)
    }
    Card {
        SectionTitle("Возможности", null)
        SettingToggle("Помощники (субагенты)", "До 3 параллельных помощников только на чтение: ищут и читают код, присылают отчёт", a.subagents) { actions.agent(a.copy(subagents = it)) }
        SettingToggle("Инструкции проекта", "Читать AGENTS.md, CLAUDE.md и .chiller/instructions.md как указания (не как разрешения)", a.projectInstructions) { actions.agent(a.copy(projectInstructions = it)) }
        SettingToggle("Команды процессов", "Разрешить запуск тестов и сборки в режиме «Изменения». Это не песочница ОС", data.allowProcesses) { actions.allowProcesses(it) }
        SettingToggle("Веб-поиск", "Поисковые запросы отправляются внешнему сервису и покидают этот компьютер", a.allowNetwork) { actions.agent(a.copy(allowNetwork = it)) }
        SettingToggle("Снимки окна", "Захват доступен по явному запросу; ОС может спросить разрешение. Просмотр изображения зависит от модели", a.allowScreenshots) { actions.agent(a.copy(allowScreenshots = it)) }
        when {
            !data.screenshotSupported -> Text("Захват окна недоступен на этой платформе.", color = c.faint, fontSize = 11.sp)
            !a.allowScreenshots -> Text("Сначала включите «Снимки окна», затем выберите конкретное окно для захвата.", color = c.faint, fontSize = 11.sp)
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Окно для снимка", color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    ChillerControl("Обновить список", actions.refreshCaptureTargets, icon = Icons.Outlined.Refresh)
                }
                val selected = data.captureTargets.firstOrNull { it.id == data.selectedCaptureTarget }
                if (selected != null) Text("Выбрано: ${selected.application} · ${selected.title}", color = c.muted, fontSize = 11.sp, lineHeight = 16.sp)
                else Text("Окно не выбрано. Снимок не будет сделан, пока вы явно не выберете цель.", color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
                data.captureTargets.forEach { target ->
                    ChillerControl("${target.application} · ${target.title}", { actions.selectCaptureTarget(target.id) }, icon = Icons.Outlined.CropFree,
                        style = if (target.id == data.selectedCaptureTarget) ControlStyle.SELECTED else ControlStyle.QUIET)
                }
                if (data.captureTargets.isEmpty()) Text("Список окон пуст. Обновите его и выберите окно явно.", color = c.faint, fontSize = 11.sp)
            }
        }
    }
}

private fun formatK(v: Int) = if (v >= 1024) "${v / 1024}k" else "$v"

/** Auto-verify: the project's own check command runs after editing runs; failures go back to the agent. */
@Composable
private fun VerifyCard(data: SettingsData, actions: SettingsActions) {
    val c = LocalChillerPalette.current
    val a = data.agent
    Card {
        SectionTitle("Автопроверка", "После задачи в режиме «Изменения», если агент менял файлы, запускается команда проверки проекта. Если она падает, агент получает вывод и исправляет — не больше заданного числа попыток.")
        SettingToggle("Проверять после правок", "Нужны включённые «Команды процессов». Команда запускается без shell, в корне проекта", a.autoVerify) { actions.agent(a.copy(autoVerify = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Попыток исправления", color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            listOf(1, 2, 3).forEach { n -> ChillerControl("$n", { actions.agent(a.copy(autoVerifyAttempts = n)) }, style = if (a.autoVerifyAttempts == n) ControlStyle.SELECTED else ControlStyle.QUIET) }
        }
        val root = data.workspace
        if (root == null) Text("Откройте проект, чтобы задать его команду проверки.", color = c.faint, fontSize = 11.sp)
        else {
            var command by remember(root, data.verifyCommand) { mutableStateOf(data.verifyCommand) }
            ChillerField(command, { command = it }, "Команда проверки для ${root.substringAfterLast('/')}", data.suggestedVerifyCommand ?: "например, ./gradlew test")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChillerControl("Сохранить", { actions.setVerifyCommand(root, command) }, style = ControlStyle.OUTLINE, enabled = command.trim() != data.verifyCommand)
                data.suggestedVerifyCommand?.takeIf { it != command.trim() }?.let { s -> ChillerControl("Подставить «$s»", { command = s }) }
            }
            if (a.autoVerify && !data.allowProcesses) Text("Сейчас «Команды процессов» выключены — проверка не запустится.", color = c.negative, fontSize = 11.sp)
        }
    }
}

/** Which harness profile the model gets: toolbox size, prompt style, temperature and raw request extras. */
@Composable
private fun ProfileCard(data: SettingsData, actions: SettingsActions) {
    val c = LocalChillerPalette.current
    val a = data.agent
    val listed = data.catalog.entry(data.model)
    val resolved = effectiveProfile(a, data.model, listed)
    Card {
        SectionTitle("Профиль модели", "Маленьким моделям проще с коротким списком инструментов и пошаговым промптом. Применяется со следующего сообщения.")
        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(c.sidebar).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("auto" to "Авто", "full" to "Полный", "compact" to "Компактный").forEach { (id, label) ->
                ChillerControl(label, { actions.agent(a.copy(profile = id)) }, style = if (a.profile == id) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(c.accent)); Spacer(Modifier.width(8.dp))
            Text(buildString {
                append("Для ${data.model.ifBlank { "модели" }}: ${resolved.name.lowercase()}")
                if (a.profile == "auto") append(if (listed?.displayName != null) " (выбран автоматически по ID и названию «${listed.displayName}»)" else " (выбран автоматически по ID)")
            }, color = c.text, fontSize = 12.sp)
        }
        Text(resolved.description, color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        resolved.tools?.let { tools ->
            Text("Инструменты: " + tools.sorted().joinToString(", "), color = c.muted, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Изображения", color = c.text, fontSize = 13.sp)
                Text(if (visionEnabled(a, data.model, listed)) "Вложенные картинки отправляются модели как изображения" else "Модель получает только имя и размер картинки",
                    color = c.faint, fontSize = 11.sp, lineHeight = 15.sp)
            }
            listOf("auto" to "Авто", "on" to "Вкл", "off" to "Выкл").forEach { (id, label) ->
                ChillerControl(label, { actions.agent(a.copy(vision = id)) }, style = if (a.vision == id) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        val imageModel = imageModelFor(a, data.catalog)
        val listedImageModels = data.catalog.models.filter { it.imageGeneration == true }
        Column {
            Text("Модель для картинок", color = c.text, fontSize = 13.sp)
            Text(when {
                imageModel == null -> "Инструмент generate_image выключен" + if (a.imageModel == "auto") ": провайдер не указал модель для картинок" else ""
                else -> "generate_image рисует через ${imageModel.displayName ?: imageModel.id}" + if (imageModel.imageEditing == true) " и умеет редактировать картинки" else ""
            }, color = c.faint, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (listOf("auto" to "Авто", "off" to "Выкл") + listedImageModels.map { it.id to (it.displayName ?: it.id) }).forEach { (id, label) ->
                ChillerControl(label, { actions.agent(a.copy(imageModel = id)) }, style = if (a.imageModel == id) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        Text("Температура", color = c.text, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val options = listOf<Double?>(null, 0.0, 0.2, 0.5, 0.8)
            options.forEach { t ->
                val label = t?.toString() ?: (resolved.temperature?.let { "профиль ($it)" } ?: "по умолчанию")
                ChillerControl(label, { actions.agent(a.copy(temperature = t)) }, style = if (a.temperature == t) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        var extras by remember(a.requestExtras) { mutableStateOf(a.requestExtras) }
        val problem = validateRequestExtras(extras)
        ChillerField(extras, { extras = it }, "Дополнительные поля запроса (JSON)", "{\"top_p\": 0.9}")
        Text(problem ?: "Добавляются в каждый запрос к модели, например параметры structured output вашего vLLM. Поля model, messages, tools, stream и max_tokens изменить нельзя.",
            color = if (problem != null) c.negative else c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        ChillerControl("Сохранить поля", { actions.agent(a.copy(requestExtras = extras.trim())) }, style = ControlStyle.OUTLINE, enabled = extras.trim() != a.requestExtras && (problem == null || problem.startsWith("Служебные")))
        actions.openComparison?.let { open ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Какой профиль лучше для вашей модели?", color = c.text, fontSize = 13.sp)
                    Text("Сравните профили и модели на eval-задачах с настоящей проверкой результата", color = c.faint, fontSize = 11.sp)
                }
                ChillerControl("Сравнение моделей", open, icon = Icons.Outlined.Insights, style = ControlStyle.OUTLINE)
            }
        }
    }
}

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
        listOf(shortcut("N") to "Новый диалог", shortcut("O") to "Открыть проект", shortcut("K") to "Поиск по диалогам", shortcut("↵") to "Отправить", shortcut(".") to "Остановить задачу",
            shortcut(",") to "Настройки", "Esc" to "Закрыть меню, настройки или поиск", "↑ ↓ ↵" to "Выбор в меню модели", "↑ ↓" to "Разделы настроек", "Tab" to "Навигация с клавиатуры (видимый фокус)").forEach { (keys, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(label, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(keys, color = c.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, c.border, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
    }
}

internal fun aboutRows(data: SettingsData): List<Pair<String, String>> = listOf(
    "Версия" to data.version, "Сборка" to data.build, "Коммит" to data.commit,
    "Среда выполнения" to data.runtime, "Система" to data.os, "Данные" to homeRelative(data.dataDir),
)

/** Plain text for bug reports; the data folder is left out because it contains the user's home path. */
internal fun aboutText(rows: List<Pair<String, String>>): String =
    "Chiller\n" + rows.filter { it.first != "Данные" }.joinToString("\n") { (k, v) -> "$k: ${v.ifBlank { "—" }}" }

@Composable
private fun AboutSection(data: SettingsData, actions: SettingsActions) {
    val c = LocalChillerPalette.current
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) { ChillerMark(Modifier.size(28.dp), c.accent) }
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Chiller ${data.version}", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    if ('-' in data.version) Text("предварительная версия", color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(5.dp)).background(c.selected).padding(horizontal = 7.dp, vertical = 2.dp))
                }
                Text("Harness для локальных и облачных моделей", color = c.muted, fontSize = 12.sp)
                Text("© 2026 Damirka1 · Apache License 2.0", color = c.faint, fontSize = 11.sp)
            }
        }
        val rows = aboutRows(data)
        rows.forEach { (k, v) ->
            Row {
                Text(k, color = c.faint, fontSize = 12.sp, modifier = Modifier.width(150.dp))
                val commitUrl = if (k == "Коммит" && Regex("[0-9a-f]{7,40}").matches(v)) "https://github.com/Damirka1/Chiller/commit/$v" else null
                if (commitUrl != null) Text(v, color = c.accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { actions.openUrl(commitUrl) })
                else Text(v.ifBlank { "—" }, color = c.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
        val clipboard = LocalClipboardManager.current
        var copied by remember { mutableStateOf(false) }
        LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChillerControl(if (copied) "Скопировано" else "Скопировать сведения", {
                clipboard.setText(AnnotatedString(aboutText(rows))); copied = true
            }, icon = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, style = ControlStyle.OUTLINE)
            ChillerControl("Репозиторий на GitHub", { actions.openUrl("https://github.com/Damirka1/Chiller") }, icon = Icons.AutoMirrored.Outlined.OpenInNew)
        }
    }
    Card {
        SectionTitle("Диагностика окружения", "Локальные проверки версий, без запросов к модели")
        val scope = rememberCoroutineScope()
        var running by remember { mutableStateOf(false) }
        var results by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        ChillerControl(if (running) "Проверяю…" else "Проверить окружение", {
            running = true
            scope.launch { try { results = EnvironmentDiagnostics.inspect() } finally { running = false } }
        }, enabled = !running, style = ControlStyle.OUTLINE)
        results.forEach { (name, value) -> Text("$name: $value", color = c.muted, fontSize = 12.sp, lineHeight = 18.sp) }
        Text("Разрешения Chiller: команды ${if (data.allowProcesses) "включены" else "выключены"}; интернет ${if (data.agent.allowNetwork) "включён" else "выключен"}.", color = c.muted, fontSize = 12.sp)
        Text("Снимки экрана: ${if (data.screenshotSupported) "поддерживаются; системный доступ проверяется при выборе окна" else "не поддерживаются на этой платформе"}.", color = c.muted, fontSize = 12.sp)
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
