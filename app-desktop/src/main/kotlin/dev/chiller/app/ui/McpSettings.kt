package dev.chiller.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chiller.app.di.splitCommand
import dev.chiller.design.*
import dev.chiller.tools.McpServerConfig
import dev.chiller.tools.McpServerState
import kotlinx.serialization.json.*

/**
 * Servers in the common `mcpServers` JSON shape (`{"mcpServers": {"name": {"command", "args", "env"}}}`, or the inner
 * map alone). Remote (`url`) servers are skipped: Chiller connects to local stdio servers only.
 */
fun parseMcpServersJson(text: String): List<McpServerConfig> {
    val root = Json.parseToJsonElement(text).jsonObject
    val servers = (root["mcpServers"] as? JsonObject) ?: root
    return servers.mapNotNull { (name, value) ->
        val server = value as? JsonObject ?: return@mapNotNull null
        val command = server["command"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        McpServerConfig(name, command, server["args"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            (server["env"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty())
    }
}

/** `KEY=value` per line (or separated by `;`); empty lines are ignored. */
fun parseEnvLines(text: String): Map<String, String> = text.split('\n', ';').map(String::trim).filter(String::isNotEmpty).associate { line ->
    line.substringBefore('=').trim() to line.substringAfter('=', "").trim()
}

/** A literal value long enough to be a key: it belongs in the environment, referenced as `${NAME}`. */
fun looksLikeSecret(value: String): Boolean = !value.contains("\${") && value.length >= 20 && value.none { it.isWhitespace() }

@Composable
internal fun McpSection(servers: List<McpServerConfig>, states: Map<String, McpServerState>, save: (List<McpServerConfig>, (String?) -> Unit) -> Unit) {
    val c = LocalChillerPalette.current
    var error by remember { mutableStateOf<String?>(null) }
    val apply: (List<McpServerConfig>) -> Unit = { list -> save(list) { error = it } }
    SettingsCard {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Серверы MCP", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("Инструменты внешних сервисов (GitHub, базы данных, трекеры) через Model Context Protocol. Сервер — локальная программа, которую Chiller запускает сам. " +
                "Его инструменты получает каждый диалог; в режиме «План» — только те, что сервер отметил как чтение.", color = c.muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        if (servers.isEmpty()) Text("Серверов пока нет", color = c.faint, fontSize = 12.sp)
        servers.forEach { server -> McpServerRow(server, states[server.id], { enabled -> apply(servers.map { if (it.id == server.id) it.copy(enabled = enabled) else it }) }) {
            apply(servers.filterNot { it.id == server.id })
        } }
        error?.let { Text(it, color = c.negative, fontSize = 12.sp) }
    }
    SettingsCard {
        var name by remember { mutableStateOf("") }
        var command by remember { mutableStateOf("") }
        var env by remember { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Добавить сервер", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("Команда запускается без оболочки, в домашней папке. Ключи не пишите в настройки: укажите их как \${ИМЯ}, значение возьмётся из окружения или .env.", color = c.muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        ChillerField(name, { name = it }, "Название", "github")
        ChillerField(command, { command = it }, "Команда и аргументы", "npx -y @modelcontextprotocol/server-github")
        ChillerField(env, { env = it }, "Переменные окружения (необязательно)", "GITHUB_PERSONAL_ACCESS_TOKEN=\${GITHUB_TOKEN}")
        val parsedEnv = runCatching { parseEnvLines(env) }.getOrDefault(emptyMap())
        if (parsedEnv.values.any(::looksLikeSecret)) Text("Похоже на ключ, записанный прямо в настройки. Лучше \${ИМЯ_ПЕРЕМЕННОЙ}.", color = c.negative, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChillerControl("Добавить", {
                val argv = runCatching { splitCommand(command) }.getOrNull()
                if (argv.isNullOrEmpty()) error = "Команда не разобрана: проверьте кавычки"
                else apply(servers + McpServerConfig(name, argv.first(), argv.drop(1), parsedEnv)).also { name = ""; command = ""; env = "" }
            }, icon = Icons.Outlined.Add, style = ControlStyle.PRIMARY, enabled = name.isNotBlank() && command.isNotBlank())
        }
    }
    SettingsCard {
        var text by remember { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Импорт из JSON", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("Вставьте блок mcpServers из конфигурации другого клиента. Серверы по адресу (url) пропускаются: поддерживаются локальные.", color = c.muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        BasicTextField(text, { text = it }, textStyle = TextStyle(color = c.text, fontSize = 12.sp, lineHeight = 18.sp, fontFamily = ChillerFonts.mono), cursorBrush = SolidColor(c.text),
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 220.dp).clip(RoundedCornerShape(7.dp)).background(c.canvas).border(1.dp, c.border, RoundedCornerShape(7.dp))
                .padding(10.dp).semantics { contentDescription = "JSON серверов MCP" },
            decorationBox = { field -> Box { if (text.isEmpty()) Text("{\"mcpServers\": {\"github\": {\"command\": \"npx\", \"args\": [\"-y\", \"@modelcontextprotocol/server-github\"]}}}", color = c.faint, fontSize = 12.sp, lineHeight = 18.sp, fontFamily = ChillerFonts.mono); field() } })
        ChillerControl("Импортировать", {
            val imported = runCatching { parseMcpServersJson(text) }.getOrNull()
            if (imported.isNullOrEmpty()) error = "В JSON нет локальных серверов (command)"
            else apply(servers.filterNot { old -> imported.any { it.id == old.id } } + imported).also { text = "" }
        }, icon = Icons.Outlined.Download, enabled = text.isNotBlank())
    }
}

@Composable
private fun McpServerRow(server: McpServerConfig, state: McpServerState?, onEnabled: (Boolean) -> Unit, onRemove: () -> Unit) {
    val c = LocalChillerPalette.current
    var open by remember(server.id) { mutableStateOf(false) }
    val status = if (!server.enabled) "stopped" else state?.status ?: "starting"
    val (dot, line) = when (status) {
        "ready" -> c.positive to "${state!!.tools.size} ${plural(state.tools.size, "инструмент", "инструмента", "инструментов")}"
        "failed" -> c.negative to "Не запустился"
        "starting" -> c.focus to "Запускается…"
        else -> c.faint to "Выключен"
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).border(1.dp, c.border, RoundedCornerShape(9.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(server.name, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$line · ", color = if (status == "failed") c.negative else c.muted, fontSize = 11.sp, maxLines = 1)
                    Text((listOf(server.command) + server.args).joinToString(" "), color = c.faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = ChillerFonts.mono)
                }
            }
            if (status == "ready" || status == "failed") ChillerIconButton(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) "Скрыть подробности" else "Подробности", { open = !open }, Modifier.size(28.dp))
            ChillerToggle(server.enabled, onEnabled, "Сервер ${server.name}")
            ChillerIconButton(Icons.Outlined.DeleteOutline, "Удалить сервер ${server.name}", onRemove, Modifier.size(28.dp))
        }
        if (open) Column(Modifier.fillMaxWidth().background(c.sidebar.copy(alpha = .5f)).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state?.message?.let { Text(it, color = c.negative, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = ChillerFonts.mono) }
            state?.tools?.forEach { tool ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (tool.readOnly) Icons.Outlined.Visibility else Icons.Outlined.Bolt, if (tool.readOnly) "Только чтение" else "Может менять данные", Modifier.size(12.dp), tint = c.faint)
                    Spacer(Modifier.width(6.dp))
                    Text(tool.name, color = c.text, fontSize = 11.sp, fontFamily = ChillerFonts.mono)
                    Text("  " + tool.description, color = c.faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val c = LocalChillerPalette.current
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun Icon(image: androidx.compose.ui.graphics.vector.ImageVector, description: String?, modifier: Modifier, tint: androidx.compose.ui.graphics.Color) =
    androidx.compose.material3.Icon(image, description, modifier, tint = tint)
