@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.chiller.app.ui

import dev.chiller.app.i18n.*
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
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
    val saveCustomProvider: (CustomProvider, String?, (String?) -> Unit) -> Unit = { _, _, done -> done(I18n.current.settings.saveUnavailable) },
    val deleteCustomProvider: (String) -> Unit = {},
    /** Saves the cost budget; the callback gets why it was refused, or null. Unwired, it refuses. */
    val costBudget: (CostBudgetSettings, (String?) -> Unit) -> Unit = { _, done -> done(I18n.current.settings.saveUnavailable) },
    val refreshCaptureTargets: () -> Unit = {}, val selectCaptureTarget: (String) -> Unit = {},
    /** Saves the MCP servers; the callback gets why they were refused, or null. Unwired, it refuses. */
    val saveMcpServers: (List<dev.chiller.tools.McpServerConfig>, (String?) -> Unit) -> Unit = { _, done -> done(I18n.current.settings.saveUnavailable) },
    /** Saves the proxy settings; the callback gets why they were refused, or null. Unwired, it refuses. */
    val saveProxy: (ProxySettings, (String?) -> Unit) -> Unit = { _, done -> done(I18n.current.settings.saveUnavailable) },
)

internal enum class SettingsSection(val icon: ImageVector) {
    CONNECTION(Icons.Outlined.Cable), AGENT(Icons.Outlined.SmartToy), MCP(Icons.Outlined.Extension), APPEARANCE(Icons.Outlined.Palette),
    DATA(Icons.Outlined.FolderOpen), KEYS(Icons.Outlined.Keyboard), ABOUT(Icons.Outlined.Info);

    fun title(s: SettingsStrings): String = when (this) {
        CONNECTION -> s.connection; AGENT -> s.agent; MCP -> s.mcp; APPEARANCE -> s.appearance; DATA -> s.data; KEYS -> s.keys; ABOUT -> s.about
    }

    fun description(s: SettingsStrings): String = when (this) {
        CONNECTION -> s.connectionDescription; AGENT -> s.agentDescription; MCP -> s.mcpDescription; APPEARANCE -> s.appearanceDescription
        DATA -> s.dataDescription; KEYS -> s.keysDescription; ABOUT -> s.aboutDescription
    }
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
        ChillerControl(LocalStrings.current.settings.backToDialog, onClose, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Outlined.ArrowBack, trailing = "esc", inlineTrailing = true)
        Spacer(Modifier.height(14.dp))
        Text(LocalStrings.current.settings.title, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.4).sp, modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
        SettingsSection.entries.forEach { s ->
            Box(contentAlignment = Alignment.CenterEnd) {
                ChillerControl(s.title(LocalStrings.current.settings), { onSection(s) }, Modifier.fillMaxWidth(), icon = s.icon, style = if (s == section) ControlStyle.SELECTED else ControlStyle.QUIET)
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
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val scroll = rememberScrollState()
                Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = if (maxWidth < 700.dp) 20.dp else 36.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(current.title(LocalStrings.current.settings), color = c.text, fontSize = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.7).sp)
                    Text(current.description(LocalStrings.current.settings), color = c.muted, fontSize = 13.sp, lineHeight = 19.sp)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.border.copy(alpha = .65f)))
                    when (current) {
                        SettingsSection.CONNECTION -> ConnectionSection(data, actions)
                        SettingsSection.AGENT -> SettingsGrid { AgentSection(data, actions) }
                        SettingsSection.MCP -> McpSection(data.mcpServers, data.mcpStates, actions.saveMcpServers)
                        SettingsSection.APPEARANCE -> SettingsGrid { AppearanceSection(data, actions) }
                        SettingsSection.DATA -> SettingsGrid { DataSection(data, actions) }
                        SettingsSection.KEYS -> KeysSection()
                        SettingsSection.ABOUT -> SettingsGrid { AboutSection(data, actions) }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 12.dp, horizontal = 4.dp))
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
    var search by remember { mutableStateOf("") }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val catalogHeight = if (maxWidth < 860.dp) 240.dp else 480.dp
    SettingsGrid(providerDetail = true) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChillerField(search, { search = it }, strings.settings.providers, strings.settings.searchByName)
        ChillerControl(strings.settings.addProvider, { creating = true; url = ""; model = ""; key = "" },
            Modifier.fillMaxWidth(), icon = Icons.Outlined.Add, style = if (creating) ControlStyle.SELECTED else ControlStyle.OUTLINE)
        val groups = listOf(
            strings.settings.cloud to ProviderPresets.all.filter { !it.keyless },
            strings.settings.local to ProviderPresets.all.filter { it.keyless },
            strings.settings.myConnections to data.customProviders.map { it.preset() },
        )
        val catalogScroll = rememberScrollState()
        Box {
        Column(Modifier.heightIn(max = catalogHeight).verticalScroll(catalogScroll).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var matches = 0
        groups.forEach { (title, providers) ->
            val visible = providers.filter { it.name.contains(search.trim(), ignoreCase = true) || it.baseUrl.contains(search.trim(), ignoreCase = true) }
            matches += visible.size
            if (visible.isNotEmpty()) {
                Text(title, color = c.faint, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp, start = 10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    visible.forEach { p ->
                        val origin = originOf(p.baseUrl)
                        val status = when {
                            p.keyless -> strings.settings.noKeyNeeded to c.muted
                            data.keySources[origin] == "env" -> strings.settings.keyFromEnv to c.positive
                            data.keySources[origin] == "keychain" -> strings.settings.keySaved to c.positive
                            data.keySources[origin] == "ui" -> strings.settings.keySet to c.positive
                            else -> strings.settings.keyNeeded to c.faint
                        }
                        PresetCard(p, status, selected = !creating && origin == formOrigin, Modifier.fillMaxWidth()) {
                            creating = false; url = p.baseUrl; key = ""
                            model = if (originOf(data.baseUrl) == origin) data.model else p.defaultModel.orEmpty()
                            data.customProviders.firstOrNull { "custom:${it.id}" == p.id }?.thinking?.takeIf { originOf(data.baseUrl) != origin }?.let { thinking = it }
                        }
                    }
                }
            }
        }
        if (matches == 0) Text(strings.settings.noProvidersFound, color = c.muted, fontSize = 12.sp)
        }
        VerticalScrollbar(rememberScrollbarAdapter(catalogScroll), Modifier.align(Alignment.CenterEnd).height(catalogHeight))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
    Card {
        SectionTitle(when { creating -> strings.settings.ownProvider; custom != null -> custom.name; else -> preset?.name ?: strings.settings.ownAddress },
            when {
                isCustom -> strings.settings.ownProviderNote
                else -> preset?.note?.ifBlank { null } ?: strings.settings.anyServerNote
            })
        if (isCustom) ChillerField(name, { name = it }, strings.settings.name, "Qarmet")
        ChillerField(url, { url = it }, strings.settings.apiAddress, "https://api.example.com/v1")
        val keyHint = when {
            preset?.keyless == true || isLoopback(url) -> strings.settings.localNoKey
            data.keySources[formOrigin] == "env" -> strings.settings.keyFromEnvHint
            data.keySources[formOrigin] == "keychain" -> strings.settings.keySavedHint(data.secureStorageName)
            data.keySources[formOrigin] == "ui" -> strings.settings.keySetHint
            else -> strings.settings.pasteKey
        }
        ChillerField(key, { key = it }, strings.settings.apiKey, keyHint, secret = true)
        Text(if (data.secureStorageSupported) strings.settings.keyStoredNote(data.secureStorageName)
            else strings.settings.keySessionNote, color = c.faint, fontSize = 11.sp)
        // Models come from the provider's list once connected; typing an id is only a fallback for servers without one.
        val connected = !creating && originOf(data.baseUrl) == formOrigin
        val listed = if (connected) data.catalog.chatModels else emptyList()
        val otherChanges = url.trimEnd('/') != data.baseUrl || thinking != data.thinking || key.isNotEmpty() || customChanged
        if (connected) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(strings.settings.modelLine(catalogLine(data.catalog, data.keyConfigured, strings.composer)), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                ChillerControl(strings.settings.refreshList, actions.refreshModels, icon = Icons.Outlined.Refresh, enabled = data.catalog.status != "loading")
            }
            if (listed.isNotEmpty()) ChipFlow(listed.map { it.id }.take(24), model, listed.associate { it.id to (it.displayName?.let { name -> "$name · ${it.id}" } ?: it.id) }) { picked ->
                model = picked
                // With nothing else pending, a picked model takes effect at once.
                if (!otherChanges) actions.applyConnection(data.baseUrl, picked, data.thinking, null)
            }
            else if (data.catalog.status in setOf("ready", "failed")) ChillerField(model, { model = it }, strings.settings.modelNoList, strings.settings.modelIdHint)
        } else Text(strings.settings.modelAfterApply, color = c.faint, fontSize = 11.sp)
        preset?.keysUrl?.let { link -> ChillerControl(strings.settings.whereKey(link.removePrefix("https://")), { actions.openUrl(link) }, icon = Icons.AutoMirrored.Outlined.OpenInNew) }
        SettingToggle(strings.settings.modelReasoning, strings.settings.modelReasoningHint, thinking) { thinking = it }
        if (isCustom) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.settings.providerProfile, color = c.text, fontSize = 13.sp)
            listOf(null to strings.settings.keepProfile, "auto" to strings.settings.auto, "full" to strings.settings.full, "compact" to strings.settings.compact).forEach { (value, label) ->
                ChillerControl(label, { profile = value }, style = if (profile == value) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChillerControl(strings.settings.apply, ::apply, style = ControlStyle.PRIMARY, enabled = changed && url.isNotBlank() && (!isCustom || name.isNotBlank()))
            if (data.connection.status == "checking") ChillerControl(strings.settings.cancelCheck, actions.cancelCheck, style = ControlStyle.OUTLINE)
            else ChillerControl(strings.settings.checkConnection, actions.checkConnection, style = ControlStyle.OUTLINE, enabled = !changed && data.keyConfigured && model.isNotBlank())
            if (originOf(data.baseUrl) == formOrigin && data.keyConfigured && !isLoopback(url) && !creating) ChillerControl(strings.settings.deleteKey, actions.deleteCredential, enabled = !changed)
            if (custom != null) ChillerControl(strings.settings.deleteProvider, { actions.deleteCustomProvider(custom.id) })
        }
        problem?.let { Text(it, color = c.negative, fontSize = 12.sp) }
        val (line, color) = connectionSummary(data.connection, data.keyConfigured)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(8.dp))
            Text(if (changed) strings.settings.notApplied else data.connection.message.ifBlank { line }, color = if (data.connection.status == "failed" && !changed) c.negative else c.muted, fontSize = 12.sp)
        }
        Text(strings.settings.applyNote, color = c.faint, fontSize = 11.sp, lineHeight = 17.sp)
    }
    ProxyCard(data, actions)
    }
    }
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
        val text = strings // read before lambdas that are not composable
        SectionTitle(strings.settings.budget, strings.settings.budgetNote)
        SettingToggle(strings.settings.limitSpending, if (saved.enabled) strings.settings.budgetOnFor(saved.model, saved.baseUrl.removePrefix("https://")) else strings.settings.off, saved.enabled) { on ->
            actions.costBudget(draft(on)) { refused -> message = refused }
        }
        ChillerField(limit, { limit = it }, strings.settings.budgetLimit, "1.00")
        ChillerField(input, { input = it }, strings.settings.priceInput, "0.27")
        ChillerField(output, { output = it }, strings.settings.priceOutput, "1.10")
        if (data.imageModel != null) ChillerField(image, { image = it }, strings.settings.priceImage(data.imageModel), "0.04")
        ChillerField(ceiling, { ceiling = it }, strings.settings.inputCeiling, "32768")
        Text(strings.settings.ceilingNote,
            color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        if (bound) Text(strings.settings.budgetBound(saved.model, data.model),
            color = c.negative, fontSize = 12.sp, lineHeight = 17.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChillerControl(text.settings.saveFor(data.model.ifBlank { text.settings.currentModel }), { actions.costBudget(draft(true)) { refused -> message = refused ?: text.settings.saved } }, style = ControlStyle.PRIMARY)
        }
        message?.let { Text(it, color = if (it == strings.settings.saved) c.positive else c.negative, fontSize = 12.sp) }
    }
}

/** The proxy for a corporate network: the system's, none, or one typed in, with hosts that always go direct. */
@Composable
private fun ProxyCard(data: SettingsData, actions: SettingsActions) {
    val c = LocalChillerPalette.current
    val text = strings // read before lambdas that are not composable
    val saved = data.proxy
    var mode by remember(saved) { mutableStateOf(saved.mode) }
    var url by remember(saved) { mutableStateOf(saved.url) }
    var bypass by remember(saved) { mutableStateOf(saved.bypass) }
    var message by remember(saved) { mutableStateOf<String?>(null) }
    val draft = ProxySettings(mode, url, bypass).normalized()
    val changed = draft != saved.normalized()
    val problem = draft.problem()
    Card {
        SectionTitle(strings.settings.proxy, strings.settings.proxyNote)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("system" to strings.settings.proxySystem, "manual" to strings.settings.proxyManual, "off" to strings.settings.proxyOff).forEach { (id, label) ->
                ChillerControl(label, { mode = id; message = null }, style = if (mode == id) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        Text(when (mode) {
            "system" -> strings.settings.proxySystemHint
            "manual" -> strings.settings.proxyManualHint
            else -> strings.settings.proxyOffHint
        }, color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        if (mode == "manual") ChillerField(url, { url = it; message = null }, strings.settings.proxyAddress, "http://proxy.company.local:3128")
        if (mode != "off") ChillerField(bypass, { bypass = it; message = null }, strings.settings.proxyBypass, "*.company.local, 10.0.0.5")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChillerControl(text.settings.save, { actions.saveProxy(draft) { refused -> message = refused ?: text.settings.proxySaved } },
                style = ControlStyle.PRIMARY, enabled = changed && problem == null)
        }
        val note = if (changed && problem != null) problem else message
        note?.let { Text(it, color = if (it == strings.settings.proxySaved) c.positive else c.negative, fontSize = 12.sp, lineHeight = 17.sp) }
    }
}

@Composable
private fun PresetCard(preset: ProviderPreset, status: Pair<String, Color>, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalChillerPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.clip(shape).background(if (selected) c.accent.copy(alpha = .10f) else if (hovered) c.hover else c.surface)
            .semantics { this.selected = selected }
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand).clickable(interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(preset.name, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (preset.verified) Icon(Icons.Outlined.Verified, strings.settings.verifiedInChiller, Modifier.size(14.dp), tint = c.accent)
        }
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
        SectionTitle(strings.settings.runLimits, strings.settings.runLimitsNote)
        Text(strings.settings.taskTime, color = c.text, fontSize = 13.sp)
        Text(strings.settings.taskTimeNote,
            color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val minutes = listOf(10, 30, 60, 120).let { if (a.maxRunMinutes != AgentOptions.UNLIMITED && a.maxRunMinutes !in it) (it + a.maxRunMinutes).sorted() else it }
            (minutes.map { it to strings.settings.minutes(it) } + (AgentOptions.UNLIMITED to strings.settings.noLimit)).forEach { (v, label) ->
                ChillerControl(label, { actions.agent(a.copy(maxRunMinutes = v)) }, style = if (a.maxRunMinutes == v) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        Text(strings.settings.modelSteps, color = c.text, fontSize = 13.sp)
        Text(strings.settings.modelStepsNote, color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Same chips as the other limits; a value saved by the old stepper still shows as its own selected chip.
            val steps = listOf(24, 48, 96, 200).let { if (a.maxRounds != AgentOptions.UNLIMITED && a.maxRounds !in it) (it + a.maxRounds).sorted() else it }
            (steps.map { it to "$it" } + (AgentOptions.UNLIMITED to strings.settings.noLimit)).forEach { (v, label) ->
                ChillerControl(label, { actions.agent(a.copy(maxRounds = v)) }, style = if (a.maxRounds == v) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        Text(strings.settings.sharedBudget, color = c.text, fontSize = 13.sp)
        Text(strings.settings.sharedBudgetNote, color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(24 to "24", 48 to "48", 96 to "96", 192 to "192", AgentOptions.UNLIMITED to strings.settings.noLimit).forEach { (v, label) ->
                ChillerControl(label, { actions.agent(a.copy(maxRequests = v)) }, style = if (a.maxRequests == v) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        Text(strings.settings.outputTokensNote, color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(32768 to "32k", 65536 to "64k", 262144 to "256k", 1_048_576 to "1M", AgentOptions.UNLIMITED to strings.settings.noLimit).forEach { (v, label) ->
                ChillerControl(label, { actions.agent(a.copy(totalOutputTokens = v)) }, style = if (a.totalOutputTokens == v) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        Text(strings.settings.replyLength, color = c.text, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(4096 to "4k", 8192 to "8k", 16384 to "16k", 32768 to "32k", 65536 to "64k", AgentOptions.UNLIMITED to strings.settings.modelMaximum).forEach { (v, label) ->
                ChillerControl(label, { actions.agent(a.copy(maxOutputTokens = v)) }, style = if (a.maxOutputTokens == v) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        Text(strings.settings.modelMaximumNote,
            color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        if (a.maxRounds == AgentOptions.UNLIMITED || a.maxRequests == AgentOptions.UNLIMITED || a.totalOutputTokens == AgentOptions.UNLIMITED || a.maxRunMinutes == AgentOptions.UNLIMITED)
            Text(strings.settings.noLimitWarning(AgentOptions.UNLIMITED_ROUNDS_BACKSTOP),
                color = c.negative, fontSize = 11.sp, lineHeight = 16.sp)
    }
    Card {
        SectionTitle(strings.settings.capabilities, null)
        SettingToggle(strings.settings.helpers, strings.settings.helpersHint, a.subagents) { actions.agent(a.copy(subagents = it)) }
        SettingToggle(strings.settings.projectInstructions, strings.settings.projectInstructionsHint, a.projectInstructions) { actions.agent(a.copy(projectInstructions = it)) }
        SettingToggle(strings.settings.processCommands, strings.settings.processCommandsHint, data.allowProcesses) { actions.allowProcesses(it) }
        SettingToggle(strings.settings.webSearch, strings.settings.webSearchHint, a.allowNetwork) { actions.agent(a.copy(allowNetwork = it)) }
        SettingToggle(strings.settings.windowSnapshots, strings.settings.windowSnapshotsHint, a.allowScreenshots) { actions.agent(a.copy(allowScreenshots = it)) }
        when {
            !data.screenshotSupported -> Text(strings.settings.captureUnsupported, color = c.faint, fontSize = 11.sp)
            !a.allowScreenshots -> Text(strings.settings.captureEnableFirst, color = c.faint, fontSize = 11.sp)
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.settings.captureWindow, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    ChillerControl(strings.settings.refreshList, actions.refreshCaptureTargets, icon = Icons.Outlined.Refresh)
                }
                val selected = data.captureTargets.firstOrNull { it.id == data.selectedCaptureTarget }
                if (selected != null) Text(strings.settings.captureSelected(selected.application, selected.title), color = c.muted, fontSize = 11.sp, lineHeight = 16.sp)
                else Text(strings.settings.captureNone, color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
                data.captureTargets.forEach { target ->
                    ChillerControl("${target.application} · ${target.title}", { actions.selectCaptureTarget(target.id) }, icon = Icons.Outlined.CropFree,
                        style = if (target.id == data.selectedCaptureTarget) ControlStyle.SELECTED else ControlStyle.QUIET)
                }
                if (data.captureTargets.isEmpty()) Text(strings.settings.captureEmpty, color = c.faint, fontSize = 11.sp)
            }
        }
    }
}

/** Reflow the same composition: resizing must not discard drafts or field focus. */
@Composable
private fun SettingsGrid(providerDetail: Boolean = false, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = 24.dp.roundToPx()
        val wide = constraints.maxWidth >= (if (providerDetail) 860.dp else 1040.dp).roundToPx()
        val columns = if (wide) 2 else 1
        val firstWidth = if (!wide) constraints.maxWidth else if (providerDetail) 248.dp.roundToPx() else (constraints.maxWidth - gap) / 2
        val heights = IntArray(columns)
        val positions = mutableListOf<Pair<Int, Int>>()
        val placeables = measurables.mapIndexed { index, measurable ->
            val column = index % columns
            val width = if (column == 0) firstWidth else constraints.maxWidth - firstWidth - gap
            positions += (if (column == 0) 0 else firstWidth + gap) to heights[column]
            measurable.measure(Constraints.fixedWidth(width)).also { heights[column] += it.height + gap }
        }
        layout(constraints.maxWidth, (heights.maxOrNull()?.minus(gap) ?: 0).coerceAtLeast(0)) {
            placeables.forEachIndexed { index, placeable ->
                val (x, y) = positions[index]
                placeable.placeRelative(x, y)
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
        SectionTitle(strings.settings.autoVerify, strings.settings.autoVerifyNote)
        SettingToggle(strings.settings.verifyAfterEdits, strings.settings.verifyAfterEditsHint, a.autoVerify) { actions.agent(a.copy(autoVerify = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(strings.settings.fixAttempts, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            listOf(1, 2, 3).forEach { n -> ChillerControl("$n", { actions.agent(a.copy(autoVerifyAttempts = n)) }, style = if (a.autoVerifyAttempts == n) ControlStyle.SELECTED else ControlStyle.QUIET) }
        }
        val root = data.workspace
        if (root == null) Text(strings.settings.openProjectForCheck, color = c.faint, fontSize = 11.sp)
        else {
            var command by remember(root, data.verifyCommand) { mutableStateOf(data.verifyCommand) }
            ChillerField(command, { command = it }, strings.settings.checkCommandFor(folderName(root)), data.suggestedVerifyCommand ?: strings.settings.checkCommandExample)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChillerControl(strings.settings.save, { actions.setVerifyCommand(root, command) }, style = ControlStyle.OUTLINE, enabled = command.trim() != data.verifyCommand)
                data.suggestedVerifyCommand?.takeIf { it != command.trim() }?.let { s -> ChillerControl(strings.settings.useSuggestion(s), { command = s }) }
            }
            if (a.autoVerify && !data.allowProcesses) Text(strings.settings.processesOffWarning, color = c.negative, fontSize = 11.sp)
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
        SectionTitle(strings.settings.modelProfile, strings.settings.modelProfileNote)
        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(c.sidebar).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("auto" to strings.settings.auto, "full" to strings.settings.full, "compact" to strings.settings.compact).forEach { (id, label) ->
                ChillerControl(label, { actions.agent(a.copy(profile = id)) }, style = if (a.profile == id) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(c.accent)); Spacer(Modifier.width(8.dp))
            Text(buildString {
                append(strings.settings.profileFor(data.model.ifBlank { strings.settings.theModel }, strings.settings.profileName(resolved.id)))
                if (a.profile == "auto") append(if (listed?.displayName != null) strings.settings.autoByIdAndName(listed.displayName!!) else strings.settings.autoById)
            }, color = c.text, fontSize = 12.sp)
        }
        Text(strings.settings.profileDescription(resolved.id, dev.chiller.agent.ModelProfiles.CORE_TOOLS.size), color = c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        resolved.tools?.let { tools ->
            Text(strings.settings.tools(tools.sorted().joinToString(", ")), color = c.muted, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = ChillerFonts.mono)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(strings.settings.images, color = c.text, fontSize = 13.sp)
                Text(if (visionEnabled(a, data.model, listed)) strings.settings.imagesOn else strings.settings.imagesOff,
                    color = c.faint, fontSize = 11.sp, lineHeight = 15.sp)
            }
            listOf("auto" to strings.settings.auto, "on" to strings.settings.on, "off" to strings.settings.offShort).forEach { (id, label) ->
                ChillerControl(label, { actions.agent(a.copy(vision = id)) }, style = if (a.vision == id) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        if (data.imageProviders.isNotEmpty()) {
            Column {
                Text(strings.settings.imageProvider, color = c.text, fontSize = 13.sp)
                Text(strings.settings.imageProviderNote, color = c.faint, fontSize = 11.sp, lineHeight = 15.sp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (listOf("" to strings.settings.sameAsChat) + data.imageProviders).forEach { (url, label) ->
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
            Text(strings.settings.imageModel, color = c.text, fontSize = 13.sp)
            Text(when {
                imageModel == null -> strings.settings.imageToolOff + if (a.imageModel == "auto") strings.settings.imageNoModel else ""
                else -> strings.settings.imageDrawsWith(imageModel.displayName ?: imageModel.id, imageModel.imageEditing == true)
            }, color = c.faint, fontSize = 11.sp, lineHeight = 15.sp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (listOf("auto" to strings.settings.auto, "off" to strings.settings.offShort) + listedImageModels.map { it.id to (it.displayName ?: it.id) }).forEach { (id, label) ->
                ChillerControl(label, { actions.agent(a.copy(imageModel = id)) }, style = if (a.imageModel == id) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        Text(strings.settings.temperature, color = c.text, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val options = listOf<Double?>(null, 0.0, 0.2, 0.5, 0.8, 1.0)
            val recommended = ModelProfiles.recommendedSampling(data.model, listed?.displayName) != null
            options.forEach { t ->
                val label = t?.toString() ?: (defaultTemperature(resolved, data.model, listed)?.let { if (recommended) strings.settings.recommended(it.toString()) else strings.settings.profileValue(it.toString()) } ?: strings.settings.byDefault)
                ChillerControl(label, { actions.agent(a.copy(temperature = t)) }, style = if (a.temperature == t) ControlStyle.SELECTED else ControlStyle.QUIET)
            }
        }
        var extras by remember(a.requestExtras) { mutableStateOf(a.requestExtras) }
        val problem = validateRequestExtras(extras)
        ChillerField(extras, { extras = it }, strings.settings.requestExtras, "{\"top_p\": 0.9}")
        Text(problem ?: strings.settings.requestExtrasNote,
            color = if (problem != null) c.negative else c.faint, fontSize = 11.sp, lineHeight = 16.sp)
        ChillerControl(strings.settings.saveFields, { actions.agent(a.copy(requestExtras = extras.trim())) }, style = ControlStyle.OUTLINE, enabled = extras.trim() != a.requestExtras && (problem == null || problem.startsWith(I18n.current.di.extrasReservedPrefix)))
        actions.openComparison?.let { open ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(strings.settings.whichProfile, color = c.text, fontSize = 13.sp)
                    Text(strings.settings.whichProfileNote, color = c.faint, fontSize = 11.sp)
                }
                ChillerControl(strings.settings.modelComparison, open, icon = Icons.Outlined.Insights, style = ControlStyle.OUTLINE)
            }
        }
    }
}

@Composable
private fun AppearanceSection(data: SettingsData, actions: SettingsActions) {
    val p = data.preferences
    val s = LocalStrings.current.settings
    Card {
        SectionTitle(s.theme, null)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("system" to s.themeSystem, "light" to s.themeLight, "dark" to s.themeDark).forEach { (id, label) -> ThemeSwatch(id, label, p.theme == id, Modifier.weight(1f)) { actions.preferences(p.copy(theme = id)) } }
        }
    }
    Card {
        SectionTitle(s.interfaceTitle, null)
        // Each language is named in itself, so the way back is readable whichever one is on.
        Choice(s.language, Language.entries.map { it.id to it.nativeName }, Language.fromId(p.language).id) { actions.preferences(p.copy(language = it)) }
        Choice(s.density, listOf("normal" to s.densityNormal, "compact" to s.densityCompact), p.density) { actions.preferences(p.copy(density = it)) }
        Choice(s.defaultLayout, listOf("focus" to "Focus", "studio" to "Studio"), p.layout) { actions.preferences(p.copy(layout = it)) }
        SettingToggle(s.reducedMotion, s.reducedMotionHint, p.reducedMotion) { actions.preferences(p.copy(reducedMotion = it)) }
    }
    Card {
        SectionTitle(s.notifications, s.notificationsHint)
        SettingToggle(s.systemNotifications, s.systemNotificationsHint, p.systemNotifications) { actions.preferences(p.copy(systemNotifications = it)) }
    }
}

@Composable
private fun ThemeSwatch(id: String, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalChillerPalette.current
    val (bg, fg) = when (id) { "dark" -> Color(0xFF1C1E20) to Color(0xFF2D3238); "light" -> Color(0xFFFFFFFF) to Color(0xFFE4E7EA); else -> Color(0xFFF4F5F6) to Color(0xFF2D3238) }
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.clip(shape).border(if (selected) 1.5.dp else 1.dp, if (selected) c.accent else c.border, shape).pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.RadioButton, onClick = onClick).padding(8.dp)) {
        Row(Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(7.dp)).background(bg).border(1.dp, c.border, RoundedCornerShape(7.dp))) {
            Column(Modifier.fillMaxHeight().fillMaxWidth(.25f).background(fg.copy(alpha = .16f)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                ChillerMark(Modifier.size(16.dp), Color(0xFF609D82))
                repeat(4) { Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(fg.copy(alpha = .65f))) }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(.5f).height(5.dp).clip(CircleShape).background(fg))
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth(.9f).height(4.dp).clip(CircleShape).background(fg.copy(alpha = .6f)))
                Box(Modifier.fillMaxWidth(.7f).height(4.dp).clip(CircleShape).background(fg.copy(alpha = .6f)))
                Box(Modifier.fillMaxWidth(.4f).height(16.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF609D82).copy(alpha = .35f)))
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(5.dp)).border(1.dp, fg.copy(alpha = .7f), RoundedCornerShape(5.dp)))
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
        SectionTitle(strings.settings.recentProjects, strings.settings.recentProjectsNote)
        if (data.recent.isEmpty()) Text(strings.settings.emptyYet, color = c.faint, fontSize = 12.sp)
        data.recent.forEach { path ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, null, Modifier.size(15.dp), tint = c.muted); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(folderName(path), color = c.text, fontSize = 13.sp)
                    Text(homeRelative(path), color = c.faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ChillerControl(strings.common.showInFileManager(), { actions.openPath(path) })
                ChillerControl(strings.settings.removeShort, { actions.removeRecent(path) })
            }
        }
    }
    Card {
        SectionTitle(strings.settings.appData, strings.settings.appDataNote)
        PathRow(strings.settings.dataFolder, data.dataDir) { actions.openPath(data.dataDir) }
        PathRow(strings.settings.requestTraces, "${data.dataDir}/traces") { actions.openPath("${data.dataDir}/traces") }
        var confirm by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(strings.settings.demoProjects, color = c.text, fontSize = 13.sp)
                Text(if (data.demoCount == 0) strings.settings.none else strings.settings.demoFolders(data.demoCount, "${homeRelative(data.dataDir)}/demo"), color = c.faint, fontSize = 11.sp)
            }
            if (data.demoCount > 0) ChillerControl(if (confirm) strings.settings.reallyDelete else strings.settings.delete, { if (confirm) { actions.deleteDemos(); confirm = false } else confirm = true }, icon = Icons.Outlined.DeleteOutline, style = if (confirm) ControlStyle.OUTLINE else ControlStyle.QUIET)
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
        ChillerControl(strings.settings.open, onOpen, icon = Icons.Outlined.FolderOpen)
    }
}

@Composable
private fun KeysSection() {
    val c = LocalChillerPalette.current
    Card {
        listOf(shortcut("N") to strings.settings.keyNewDialog, shortcut("O") to strings.settings.keyOpenProject, shortcut("K") to strings.settings.keySearch, shortcut("P") to strings.settings.keyJump,
            (if (isMacOs) "⌘ ⇧ P" else "Ctrl+Shift+P") to strings.settings.keyAllCommands, shortcut("1…9") to strings.settings.keyNewInProject, shortcut("↵") to strings.settings.keySend, shortcut(".") to strings.settings.keyStop,
            shortcut(",") to strings.settings.keySettings, "Esc" to strings.settings.keyEsc, "↑ ↓ ↵" to strings.settings.keyModelMenu, "↑ ↓ → ←" to strings.settings.keySidebar, "Shift+F10" to strings.settings.keyRowMenu, "↑ ↓" to strings.settings.keySections, "Tab" to strings.settings.keyTab).forEach { (keys, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(label, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(keys, color = c.muted, fontSize = 12.sp, fontFamily = ChillerFonts.mono, modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, c.border, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
    }
}

internal fun aboutRows(data: SettingsData): List<Pair<String, String>> = listOf(
    I18n.current.settings.aboutVersion to data.version, I18n.current.settings.aboutBuild to data.build, I18n.current.settings.aboutCommit to data.commit,
    I18n.current.settings.aboutRuntime to data.runtime, I18n.current.settings.aboutSystem to data.os, I18n.current.settings.aboutData to homeRelative(data.dataDir),
)

/** Plain text for bug reports; the data folder is left out because it contains the user's home path. */
internal fun aboutText(rows: List<Pair<String, String>>): String =
    "Chiller\n" + rows.filter { it.first != I18n.current.settings.aboutData }.joinToString("\n") { (k, v) -> "$k: ${v.ifBlank { "—" }}" }

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
                    if ('-' in data.version) Text(strings.settings.preRelease, color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(5.dp)).background(c.selected).padding(horizontal = 7.dp, vertical = 2.dp))
                }
                Text(strings.settings.tagline, color = c.muted, fontSize = 12.sp)
                Text("© 2026 Damirka1 · Apache License 2.0", color = c.faint, fontSize = 11.sp)
            }
        }
        val rows = aboutRows(data)
        rows.forEach { (k, v) ->
            Row {
                Text(k, color = c.faint, fontSize = 12.sp, modifier = Modifier.width(150.dp))
                val commitUrl = if (k == strings.settings.aboutCommit && Regex("[0-9a-f]{7,40}").matches(v)) "https://github.com/Damirka1/Chiller/commit/$v" else null
                if (commitUrl != null) Text(v, color = c.accent, fontSize = 12.sp, fontFamily = ChillerFonts.mono, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { actions.openUrl(commitUrl) })
                else Text(v.ifBlank { "—" }, color = c.text, fontSize = 12.sp, fontFamily = ChillerFonts.mono)
            }
        }
        val clipboard = LocalClipboardManager.current
        var copied by remember { mutableStateOf(false) }
        LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChillerControl(if (copied) strings.settings.copied else strings.settings.copyDetails, {
                clipboard.setText(AnnotatedString(aboutText(rows))); copied = true
            }, icon = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, style = ControlStyle.OUTLINE)
            ChillerControl(strings.settings.githubRepo, { actions.openUrl("https://github.com/Damirka1/Chiller") }, icon = Icons.AutoMirrored.Outlined.OpenInNew)
        }
    }
    Card {
        SectionTitle(strings.settings.diagnostics, strings.settings.diagnosticsNote)
        val scope = rememberCoroutineScope()
        var running by remember { mutableStateOf(false) }
        var results by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        ChillerControl(if (running) strings.settings.checking else strings.settings.checkEnvironment, {
            running = true
            scope.launch { try { results = EnvironmentDiagnostics.inspect() } finally { running = false } }
        }, enabled = !running, style = ControlStyle.OUTLINE)
        results.forEach { (name, value) -> Text("$name: $value", color = c.muted, fontSize = 12.sp, lineHeight = 18.sp) }
        Text(strings.settings.permissionsLine(data.allowProcesses, data.agent.allowNetwork), color = c.muted, fontSize = 12.sp)
        Text(strings.settings.screenshotsLine(data.screenshotSupported), color = c.muted, fontSize = 12.sp)
    }
    Card {
        SectionTitle(strings.settings.whatWasChecked, null)
        Text(strings.settings.whatWasCheckedNote, color = c.muted, fontSize = 12.sp, lineHeight = 18.sp)
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
