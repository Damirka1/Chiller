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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chiller.agent.ModelProfiles
import dev.chiller.app.di.*
import dev.chiller.contracts.CaptureTarget
import dev.chiller.contracts.CostBudgetSettings
import dev.chiller.design.*
import kotlinx.coroutines.launch

/** Everything the settings page shows. Keys themselves are never part of it — only where they came from. */
data class SettingsData(
    val baseUrl: String, val model: String, val thinking: Boolean, val keyConfigured: Boolean,
    val keySources: Map<String, String> = emptyMap(), val secureStorageSupported: Boolean = false, val secureStorageName: String = "",
    val connection: ConnectionState = ConnectionState(), val catalog: ModelCatalogState = ModelCatalogState(),
    val preferences: UiPreferences = UiPreferences(), val allowProcesses: Boolean = false, val agent: AgentOptions = AgentOptions(),
    val customProviders: List<CustomProvider> = emptyList(),
    val proxy: ProxySettings = ProxySettings(),
    /** The task's money budget and the image model in use, which its prices must cover. */
    val costBudget: CostBudgetSettings = CostBudgetSettings(), val imageModel: String? = null,
    /** Models generate_image picks from, and other endpoints that can draw, as (url, name). */
    val imageCatalog: ModelCatalogState = ModelCatalogState(), val imageProviders: List<Pair<String, String>> = emptyList(),
    val recent: List<String> = emptyList(), val dataDir: String = "", val demoCount: Int = 0,
    val version: String = "dev", val build: String = "", val commit: String = "", val runtime: String = "", val os: String = "",
    val captureTargets: List<CaptureTarget> = emptyList(), val selectedCaptureTarget: String? = null, val screenshotSupported: Boolean = false,
    /** The open project, its saved check command and a suggestion from its build files. */
    val workspace: String? = null, val verifyCommand: String = "", val suggestedVerifyCommand: String? = null,
    /** Connected MCP servers and their states by server id. */
    val mcpServers: List<dev.chiller.tools.McpServerConfig> = emptyList(), val mcpStates: Map<String, dev.chiller.tools.McpServerState> = emptyMap(),
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
    /** Saves a custom provider with an optional typed key; the callback gets why it was refused, or null. Unwired, it refuses instead of pretending. */
    val saveCustomProvider: (CustomProvider, String?, (String?) -> Unit) -> Unit = { _, _, done -> done("Сохранение недоступно") },
    val deleteCustomProvider: (String) -> Unit = {},
    /** Saves the cost budget; the callback gets why it was refused, or null. Unwired, it refuses. */
    val costBudget: (CostBudgetSettings, (String?) -> Unit) -> Unit = { _, done -> done("Сохранение недоступно") },
    val refreshCaptureTargets: () -> Unit = {}, val selectCaptureTarget: (String) -> Unit = {},
    /** Saves the MCP servers; the callback gets why they were refused, or null. Unwired, it refuses. */
    val saveMcpServers: (List<dev.chiller.tools.McpServerConfig>, (String?) -> Unit) -> Unit = { _, done -> done("Сохранение недоступно") },
    /** Saves the proxy settings; the callback gets why they were refused, or null. Unwired, it refuses. */
    val saveProxy: (ProxySettings, (String?) -> Unit) -> Unit = { _, done -> done("Сохранение недоступно") },
)

internal enum class SettingsSection(val title: String, val icon: ImageVector) {
    CONNECTION("Подключение", Icons.Outlined.Cable), AGENT("Агент", Icons.Outlined.SmartToy), MCP("Инструменты MCP", Icons.Outlined.Extension), APPEARANCE("Внешний вид", Icons.Outlined.Palette),
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
                        SettingsSection.CONNECTION -> { ConnectionSection(data, actions); ProxyCard(data, actions) }
                        SettingsSection.AGENT -> AgentSection(data, actions)
                        SettingsSection.MCP -> McpSection(data.mcpServers, data.mcpStates, actions.saveMcpServers)
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
    // One form for every provider. A custom one (picked, or new from the "+" tile) adds a name and a profile.
    var creating by remember { mutableStateOf(false) }
    val formOrigin = originOf(url)
    val custom = if (creating) null else data.customProviders.firstOrNull { originOf(it.baseUrl) == formOrigin }
    val preset = if (creating) null else ProviderPresets.byOrigin(url, data.customProviders)
    val isCustom = creating || custom != null
    var name by remember(custom?.id, creating) { mutableStateOf(custom?.name.orEmpty()) }
    var profile by remember(custom?.id, creating) { mutableStateOf(custom?.profile) }
    var problem by remember(custom?.id, creating) { mutableStateOf<String?>(null) }
    val customChanged = creating || (custom != null && (name.trim() != custom.name || profile != custom.profile))
    val changed = url.trimEnd('/') != data.baseUrl || model.trim() != data.model || thinking != data.thinking || key.isNotEmpty() || customChanged
    fun apply() {
        val typed = key.takeIf { it.isNotBlank() }
        if (isCustom) profile?.takeIf { it != data.agent.profile }?.let { actions.agent(data.agent.copy(profile = it)) }
        if (!isCustom) { key = ""; actions.applyConnection(url.trim(), model.trim(), thinking, typed); return }
        val provider = CustomProvider(custom?.id ?: java.util.UUID.randomUUID().toString().take(8), name, url, custom?.keyVariable, model, thinking, profile).normalized()
        provider.problem()?.let { problem = it; return }
        // Saved first (with the key, remembered in the OS store), then connected with that key.
        actions.saveCustomProvider(provider, typed) { refused ->
            problem = refused
            if (refused == null) { key = ""; creating = false; actions.applyConnection(provider.baseUrl, provider.defaultModel.orEmpty(), thinking, null) }
        }
    }
    Card {
        SectionTitle("Провайдер", "Все провайдеры совместимы с OpenAI Chat Completions. Ключ уходит только на адрес, для которого он задан.")
        (ProviderPresets.all.map { it as ProviderPreset? } + data.customProviders.map { it.preset() } + listOf(null)).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { p ->
                    if (p == null) {
                        AddProviderTile(selected = creating, Modifier.weight(1f)) { creating = true; url = ""; model = ""; key = "" }
                        return@forEach
                    }
                    val origin = originOf(p.baseUrl)
                    val status = when {
                        p.keyless -> "без ключа" to c.muted
                        data.keySources[origin] == "env" -> "ключ из .env" to c.positive
                        data.keySources[origin] == "keychain" -> "ключ сохранён" to c.positive
                        data.keySources[origin] == "ui" -> "ключ задан" to c.positive
                        else -> "нужен ключ" to c.faint
                    }
                    PresetCard(p, status, selected = !creating && origin == formOrigin, Modifier.weight(1f)) {
                        creating = false; url = p.baseUrl; key = ""
                        model = if (originOf(data.baseUrl) == origin) data.model else p.defaultModel.orEmpty()
                        data.customProviders.firstOrNull { "custom:${it.id}" == p.id }?.thinking?.takeIf { originOf(data.baseUrl) != origin }?.let { thinking = it }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    Card {
        SectionTitle(when { creating -> "Свой провайдер"; custom != null -> custom.name; else -> preset?.name ?: "Свой адрес" },
            when {
                isCustom -> "Шлюз компании или свой сервер. Хранится только в настройках этого компьютера и не попадает в репозиторий."
                else -> preset?.note?.ifBlank { null } ?: "Любой сервер с /chat/completions по HTTP или HTTPS."
            })
        if (isCustom) ChillerField(name, { name = it }, "Название", "Qarmet")
        ChillerField(url, { url = it }, "Адрес API", "https://api.example.com/v1")
        val keyHint = when {
            preset?.keyless == true || isLoopback(url) -> "Локальный сервер: ключ не нужен"
            data.keySources[formOrigin] == "env" -> "Ключ загружен из .env или переменной окружения. Новый заменит его"
            data.keySources[formOrigin] == "keychain" -> "Ключ сохранён: ${data.secureStorageName}. Пустое поле оставляет его"
            data.keySources[formOrigin] == "ui" -> "Ключ задан. Пустое поле оставляет его"
            else -> "Вставьте ключ"
        }
        ChillerField(key, { key = it }, "API-ключ", keyHint, secret = true)
        Text(if (data.secureStorageSupported) "При «Применить» ключ сохраняется в «${data.secureStorageName}» и уходит только на этот адрес."
            else "Ключ действует до выхода и уходит только на этот адрес; для постоянного ключа используйте переменную окружения или .env.", color = c.faint, fontSize = 11.sp)
        // Models come from the provider's list once connected; typing an id is only a fallback for servers without one.
        val connected = !creating && originOf(data.baseUrl) == formOrigin
        val listed = if (connected) data.catalog.chatModels else emptyList()
        val otherChanges = url.trimEnd('/') != data.baseUrl || thinking != data.thinking || key.isNotEmpty() || customChanged
        if (connected) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Модель · " + catalogLine(data.catalog, data.keyConfigured), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                ChillerControl("Обновить список", actions.refreshModels, icon = Icons.Outlined.Refresh, enabled = data.catalog.status != "loading")
            }
            if (listed.isNotEmpty()) ChipFlow(listed.map { it.id }.take(24), model, listed.associate { it.id to (it.displayName?.let { name -> "$name · ${it.id}" } ?: it.id) }) { picked ->
                model = picked
                // With nothing else pending, a picked model takes effect at once.
                if (!otherChanges) actions.applyConnection(data.baseUrl, picked, data.thinking, null)
            }
            else if (data.catalog.status in setOf("ready", "failed")) ChillerField(model, { model = it }, "Модель (провайдер не отдал список)", "ID модели у провайдера")
        } else Text("Модель выбирается из списка провайдера после «Применить».", color = c.faint, fontSize = 11.sp)
        preset?.keysUrl?.let { link -> ChillerControl("Где взять ключ: ${link.removePrefix("https://")}", { actions.openUrl(link) }, icon = Icons.AutoMirrored.Outlined.OpenInNew) }
        SettingToggle("Рассуждения модели", "Если провайдер поддерживает режим thinking", thinking) { thinking = it }
        if (isCustom) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Профиль с этим провайдером", color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            listOf(null to "Не менять", "auto" to "Авто", "full" to "Полный", "compact" to "Компактный").forEach { (value, label) ->
                ChillerControl(label, { profile = value }, style = if (profile == value) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChillerControl("Применить", ::apply, style = ControlStyle.PRIMARY, enabled = changed && url.isNotBlank() && (!isCustom || name.isNotBlank()))
            if (data.connection.status == "checking") ChillerControl("Отменить проверку", actions.cancelCheck, style = ControlStyle.OUTLINE)
            else ChillerControl("Проверить подключение", actions.checkConnection, style = ControlStyle.OUTLINE, enabled = !changed && data.keyConfigured && model.isNotBlank())
            if (originOf(data.baseUrl) == formOrigin && data.keyConfigured && !isLoopback(url) && !creating) ChillerControl("Удалить ключ", actions.deleteCredential, enabled = !changed)
            if (custom != null) ChillerControl("Удалить провайдер", { actions.deleteCustomProvider(custom.id) })
        }
        problem?.let { Text(it, color = c.negative, fontSize = 12.sp) }
        val (line, color) = connectionSummary(data.connection, data.keyConfigured)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(8.dp))
            Text(if (changed) "Изменения не применены" else data.connection.message.ifBlank { line }, color = if (data.connection.status == "failed" && !changed) c.negative else c.muted, fontSize = 12.sp)
        }
        Text("Применение начинает новый диалог. Проверка отправляет один короткий запрос без файлов и инструментов.", color = c.faint, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

/**
 * A money limit per task with the user's own prices. Requests reserve their worst case before they start; the
 * prices belong to the endpoint and models it was saved for, and any other model is refused rather than guessed.
 */
@Composable
private fun CostBudgetCard(data: SettingsData, actions: SettingsActions) {
    val c = LocalChillerPalette.current
    val saved = data.costBudget
    var limit by remember(saved) { mutableStateOf(saved.limitUsd) }
    var input by remember(saved) { mutableStateOf(saved.inputUsdPerMillion) }
    var output by remember(saved) { mutableStateOf(saved.outputUsdPerMillion) }
    var image by remember(saved) { mutableStateOf(saved.imageUsdPerRequest) }
    var ceiling by remember(saved) { mutableStateOf(saved.inputTokenCeiling.toString()) }
    var message by remember(saved) { mutableStateOf<String?>(null) }
    // Saving binds the prices to what is in use now.
    fun draft(enabled: Boolean) = CostBudgetSettings(enabled, limit.trim(), input.trim(), output.trim(), image.trim(), ceiling.trim().toIntOrNull() ?: -1,
        baseUrl = data.baseUrl, model = data.model, imageModel = data.imageModel.orEmpty())
    val bound = saved.enabled && (saved.baseUrl.trimEnd('/') != data.baseUrl.trimEnd('/') || saved.model != data.model)
    Card {
        SectionTitle("Денежный бюджет", "Лимит в USD на одну задачу по вашим ценам. Каждый запрос, включая повторы, помощников и картинки, заранее резервирует худший случай; после ответа резерв заменяется фактическим расходом.")
        SettingToggle("Ограничивать расходы", if (saved.enabled) "Включено для ${saved.model} · ${saved.baseUrl.removePrefix("https://")}" else "Выключено", saved.enabled) { on ->
            actions.costBudget(draft(on)) { refused -> message = refused }
        }
        ChillerField(limit, { limit = it }, "Лимит на задачу, USD", "1.00")
        ChillerField(input, { input = it }, "Цена 1M входных токенов, USD", "0.27")
        ChillerField(output, { output = it }, "Цена 1M выходных токенов, USD", "1.10")
        if (data.imageModel != null) ChillerField(image, { image = it }, "Цена одной картинки (${data.imageModel}), USD", "0.04")
        ChillerField(ceiling, { ceiling = it }, "Потолок входных токенов на запрос", "32768")
        Text("Потолок ограничивает контекст запроса: так резерв на вход не может оказаться меньше фактического. Пустая цена — бюджет отказывает, а не считает её нулём.",
            color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        if (bound) Text("Цены сохранены для ${saved.model}; с текущей моделью ${data.model} бюджет откажет в запросах. Сохраните, чтобы указать цены для неё.",
            color = c.negative, fontSize = 12.sp, lineHeight = 17.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChillerControl("Сохранить для ${data.model.ifBlank { "текущей модели" }}", { actions.costBudget(draft(true)) { refused -> message = refused ?: "Сохранено" } }, style = ControlStyle.PRIMARY)
        }
        message?.let { Text(it, color = if (it == "Сохранено") c.positive else c.negative, fontSize = 12.sp) }
    }
}

/** The proxy for a corporate network: the system's, none, or one typed in, with hosts that always go direct. */
@Composable
private fun ProxyCard(data: SettingsData, actions: SettingsActions) {
    val c = LocalChillerPalette.current
    val saved = data.proxy
    var mode by remember(saved) { mutableStateOf(saved.mode) }
    var url by remember(saved) { mutableStateOf(saved.url) }
    var bypass by remember(saved) { mutableStateOf(saved.bypass) }
    var message by remember(saved) { mutableStateOf<String?>(null) }
    val draft = ProxySettings(mode, url, bypass).normalized()
    val changed = draft != saved.normalized()
    val problem = draft.problem()
    Card {
        SectionTitle("Прокси", "Для корпоративной сети. Через прокси идут запросы к провайдерам, список моделей, картинки и веб-поиск; localhost и исключения — напрямую.")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("system" to "Системный", "manual" to "Вручную", "off" to "Без прокси").forEach { (id, label) ->
                ChillerControl(label, { mode = id; message = null }, style = if (mode == id) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        Text(when (mode) {
            "system" -> "Как в настройках системы. Если там только скрипт автонастройки (PAC), укажите прокси вручную."
            "manual" -> "HTTP-прокси; HTTPS идёт через него туннелем. Прокси с логином и паролем пока не поддерживаются."
            else -> "Все запросы идут напрямую."
        }, color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        if (mode == "manual") ChillerField(url, { url = it; message = null }, "Адрес прокси", "http://proxy.company.local:3128")
        if (mode != "off") ChillerField(bypass, { bypass = it; message = null }, "Без прокси, через запятую", "*.company.local, 10.0.0.5")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChillerControl("Сохранить", { actions.saveProxy(draft) { refused -> message = refused ?: "Сохранено. Проверьте подключение выше." } },
                style = ControlStyle.PRIMARY, enabled = changed && problem == null)
        }
        val note = if (changed && problem != null) problem else message
        note?.let { Text(it, color = if (it.startsWith("Сохранено")) c.positive else c.negative, fontSize = 12.sp, lineHeight = 17.sp) }
    }
}

/** The "+" tile that opens the form for a new custom provider. */
@Composable
private fun AddProviderTile(selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalChillerPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.clip(shape).background(if (selected) c.selected.copy(alpha = .6f) else if (hovered) c.hover else c.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) c.accent.copy(alpha = .7f) else c.border, shape)
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand).clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text("+ Свой провайдер", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text("шлюз компании, свой сервер", color = c.faint, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text("хранится только здесь", color = c.muted, fontSize = 11.sp)
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
        Text(preset.baseUrl.removePrefix("https://").removePrefix("http://"), color = c.faint, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = ChillerFonts.mono)
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
                    Text(labels[id] ?: id, color = if (active) c.text else c.muted, fontSize = 11.sp, fontFamily = ChillerFonts.mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
    CostBudgetCard(data, actions)
    VerifyCard(data, actions)
    Card {
        SectionTitle("Лимиты прогона", "Ограничивают одну задачу. Применяются со следующего сообщения, история сохраняется.")
        Text("Время на задачу", color = c.text, fontSize = 13.sp)
        Text("Сколько может идти одна задача, считая все шаги, команды и помощников. Медленной локальной модели может понадобиться больше 10 минут.",
            color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val minutes = listOf(10, 30, 60, 120).let { if (a.maxRunMinutes != AgentOptions.UNLIMITED && a.maxRunMinutes !in it) (it + a.maxRunMinutes).sorted() else it }
            (minutes.map { it to "$it мин" } + (AgentOptions.UNLIMITED to "Без лимита")).forEach { (v, label) ->
                ChillerControl(label, { actions.agent(a.copy(maxRunMinutes = v)) }, style = if (a.maxRunMinutes == v) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
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
        if (a.maxRounds == AgentOptions.UNLIMITED || a.maxRequests == AgentOptions.UNLIMITED || a.totalOutputTokens == AgentOptions.UNLIMITED || a.maxRunMinutes == AgentOptions.UNLIMITED)
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
            ChillerField(command, { command = it }, "Команда проверки для ${folderName(root)}", data.suggestedVerifyCommand ?: "например, ./gradlew test")
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
            Text("Инструменты: " + tools.sorted().joinToString(", "), color = c.muted, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = ChillerFonts.mono)
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
        if (data.imageProviders.isNotEmpty()) {
            Column {
                Text("Провайдер для картинок", color = c.text, fontSize = 13.sp)
                Text("generate_image может рисовать через другой подключённый провайдер, со своим ключом", color = c.faint, fontSize = 11.sp, lineHeight = 15.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (listOf("" to "Как у чата") + data.imageProviders).forEach { (url, label) ->
                    val selected = normalizeEndpoint(a.imageBaseUrl.ifBlank { data.baseUrl }) == normalizeEndpoint(url.ifBlank { data.baseUrl })
                    // A model id belongs to its endpoint: switching providers starts from that provider's own choice.
                    ChillerControl(label, { if (!selected) actions.agent(a.copy(imageBaseUrl = url, imageModel = "auto")) },
                        style = if (selected) ControlStyle.SELECTED else ControlStyle.QUIET)
                }
            }
        }
        val imageModel = imageModelFor(a, data.imageCatalog)
        val listedImageModels = data.imageCatalog.models.filter { it.imageGeneration == true }
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
            val options = listOf<Double?>(null, 0.0, 0.2, 0.5, 0.8, 1.0)
            val recommended = ModelProfiles.recommendedSampling(data.model, listed?.displayName) != null
            options.forEach { t ->
                val label = t?.toString() ?: (defaultTemperature(resolved, data.model, listed)?.let { if (recommended) "рекомендуемая ($it)" else "профиль ($it)" } ?: "по умолчанию")
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
    Card {
        SectionTitle("Уведомления", "О диалогах в фоне Chiller сообщает внутри окна. Системные уведомления приходят, только когда окно не в фокусе.")
        SettingToggle("Системные уведомления", "Задача завершена, проверка пройдена или агент ждёт ответа. Для них нужна иконка Chiller в области уведомлений.",
            p.systemNotifications) { actions.preferences(p.copy(systemNotifications = it)) }
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
                    Text(folderName(path), color = c.text, fontSize = 13.sp)
                    Text(homeRelative(path), color = c.faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ChillerControl("Показать в $fileManagerName", { actions.openPath(path) })
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
            Text(homeRelative(path), color = c.faint, fontSize = 11.sp, fontFamily = ChillerFonts.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ChillerControl("Открыть", onOpen, icon = Icons.Outlined.FolderOpen)
    }
}

@Composable
private fun KeysSection() {
    val c = LocalChillerPalette.current
    Card {
        listOf(shortcut("N") to "Новый диалог", shortcut("O") to "Открыть проект", shortcut("K") to "Поиск по диалогам и сообщениям", shortcut("P") to "Перейти к проекту",
            (if (isMacOs) "⌘ ⇧ P" else "Ctrl+Shift+P") to "Все команды", shortcut("1…9") to "Новый диалог в проекте по номеру", shortcut("↵") to "Отправить", shortcut(".") to "Остановить задачу",
            shortcut(",") to "Настройки", "Esc" to "Закрыть меню, настройки или поиск", "↑ ↓ ↵" to "Выбор в меню модели", "↑ ↓ → ←" to "Боковая панель: чаты и проекты", "Shift+F10" to "Меню выбранного в панели диалога", "↑ ↓" to "Разделы настроек", "Tab" to "Навигация с клавиатуры (видимый фокус)").forEach { (keys, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(label, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(keys, color = c.muted, fontSize = 12.sp, fontFamily = ChillerFonts.mono, modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, c.border, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
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
                if (commitUrl != null) Text(v, color = c.accent, fontSize = 12.sp, fontFamily = ChillerFonts.mono, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { actions.openUrl(commitUrl) })
                else Text(v.ifBlank { "—" }, color = c.text, fontSize = 12.sp, fontFamily = ChillerFonts.mono)
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
