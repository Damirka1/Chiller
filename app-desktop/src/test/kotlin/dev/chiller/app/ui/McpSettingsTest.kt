package dev.chiller.app.ui

import androidx.compose.ui.test.*
import dev.chiller.app.di.DesktopController
import dev.chiller.app.di.OfflineCatalog
import dev.chiller.design.ChillerTheme
import dev.chiller.tools.McpServerConfig
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class McpSettingsTest {
    // Helpers read the global language; these checks are written against the Russian texts.
    @kotlin.test.BeforeTest fun russian() { dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.RuStrings; dev.chiller.contracts.CoreLanguage.russian = true }
    @kotlin.test.AfterTest fun english() { dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.EnStrings; dev.chiller.contracts.CoreLanguage.russian = false }

    @Test fun `common mcpServers JSON imports local servers and skips remote ones`() {
        val servers = parseMcpServersJson("""{"mcpServers": {
            "github": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-github"], "env": {"GITHUB_PERSONAL_ACCESS_TOKEN": "${'$'}{GITHUB_TOKEN}"}},
            "remote": {"url": "https://example.com/mcp"}}}""")
        assertEquals(listOf(McpServerConfig("github", "npx", listOf("-y", "@modelcontextprotocol/server-github"), mapOf("GITHUB_PERSONAL_ACCESS_TOKEN" to "\${GITHUB_TOKEN}"))), servers)
        assertEquals(1, parseMcpServersJson("""{"files": {"command": "mcp-files"}}""").size, "the inner map alone works too")
    }

    @Test fun `environment lines and secret detection`() {
        assertEquals(mapOf("A" to "1", "B" to "\${TOKEN}"), parseEnvLines("A=1\n\n B = \${TOKEN} "))
        assertEquals(mapOf("A" to "1", "C" to "x=y"), parseEnvLines("A=1; C=x=y"))
        assertTrue(looksLikeSecret("ghp_1234567890abcdefghijklmnop"))
        assertFalse(looksLikeSecret("\${GITHUB_TOKEN}"))
        assertFalse(looksLikeSecret("production"))
    }

    @Test fun `the controller keeps servers, refuses bad lists and reports stopped ones`() = runBlocking {
        val controller = DesktopController(Files.createTempDirectory("mcp-settings"), initialCredential = null, catalogClient = OfflineCatalog)
        try {
            controller.initialize()
            assertEquals("У сервера нужны название и команда", controller.saveMcpServers(listOf(McpServerConfig(" ", "npx"))))
            assertEquals("Названия серверов должны различаться", controller.saveMcpServers(listOf(McpServerConfig("Git Hub", "a"), McpServerConfig("git hub", "b"))))
            assertEquals("Имена переменных окружения: заглавные латинские буквы, цифры и _", controller.saveMcpServers(listOf(McpServerConfig("x", "y", env = mapOf("bad name" to "1")))))
            assertNull(controller.saveMcpServers(listOf(McpServerConfig("Off", "never-started", enabled = false))))
            assertEquals(listOf("Off"), controller.state.value.settings.mcpServers.map { it.name })
            kotlinx.coroutines.withTimeout(5_000) { while (controller.state.value.mcp["off"]?.status != "stopped") kotlinx.coroutines.delay(20) }
        } finally { controller.close() }
    }

    @Test fun `a server is added from the form`() = runComposeUiTest {
        var saved: List<McpServerConfig>? = null
        setContent {
            ChillerTheme(darkTheme = false, reducedMotion = true) {
                McpSection(emptyList(), emptyMap()) { list, done -> saved = list; done(null) }
            }
        }
        onNodeWithContentDescription("Name").performTextInput("github")
        onNodeWithContentDescription("Command and arguments").performTextInput("npx -y \"@modelcontextprotocol/server-github\"")
        onNodeWithContentDescription("Environment variables (optional)").performTextInput("GITHUB_PERSONAL_ACCESS_TOKEN=ghp_1234567890abcdefghijklmnop")
        onNodeWithText("looks like a key", substring = true).assertExists()
        onNodeWithText("Add").performClick()
        assertEquals(McpServerConfig("github", "npx", listOf("-y", "@modelcontextprotocol/server-github"), mapOf("GITHUB_PERSONAL_ACCESS_TOKEN" to "ghp_1234567890abcdefghijklmnop")), saved!!.single())
    }
}
