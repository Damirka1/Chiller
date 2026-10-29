package dev.chiller.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import dev.chiller.design.ChillerTheme
import dev.chiller.design.LocalChillerPalette
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SettingsLayoutTest {
    private val data = SettingsData("https://api.deepseek.com", "deepseek-flash", false, true)

    @Test fun `search preserves the connection draft and applying uses the selected provider`() = runComposeUiTest {
        var applied = ""
        setContent {
            ChillerTheme(darkTheme = false, reducedMotion = true) {
                SettingsPage(data, SettingsActions(applyConnection = { url, _, _, _ -> applied = url }), SettingsSection.CONNECTION, {}, showNav = false)
            }
        }
        onNodeWithContentDescription("Providers").performTextInput("OpenAI")
        onNode(hasText("OpenAI") and !hasSetTextAction()).performClick()
        onNodeWithContentDescription("API address").performScrollTo().performTextReplacement("https://example.com/v1")
        onNodeWithContentDescription("Providers").performScrollTo().performTextReplacement("no such provider")
        onNodeWithText("No providers found").assertExists()
        onNodeWithText("https://example.com/v1").assertExists()
        onNodeWithText("Apply").performScrollTo().performClick()
        assertEquals("https://example.com/v1", applied)
    }

    @Test fun `render settings at wide and narrow sizes in both themes`() {
        val output = Path.of("build/settings-preview")
        Files.createDirectories(output)
        for (dark in listOf(false, true)) for (width in listOf(1440, 760, 560)) {
            for (section in SettingsSection.entries) {
                lateinit var scene: ImageComposeScene
                SwingUtilities.invokeAndWait {
                    scene = ImageComposeScene(width, 1000, Density(1f)) {
                        ChillerTheme(darkTheme = dark, reducedMotion = true) {
                            SettingsPage(data, SettingsActions(), section, {},
                                Modifier.fillMaxSize().background(LocalChillerPalette.current.canvas), showNav = width > 1000)
                        }
                    }
                }
                try {
                    repeat(3) { frame -> SwingUtilities.invokeAndWait { scene.render(frame * 100_000_000L).close() } }
                    SwingUtilities.invokeAndWait {
                        scene.render(500_000_000L).use { image ->
                            image.encodeToData()!!.use { Files.write(output.resolve("${section.name}-$width-${if (dark) "dark" else "light"}.png"), it.bytes) }
                        }
                    }
                } finally { SwingUtilities.invokeAndWait { scene.close() } }
            }
        }
    }
}
