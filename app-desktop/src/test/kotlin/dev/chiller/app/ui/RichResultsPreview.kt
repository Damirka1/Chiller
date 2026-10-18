package dev.chiller.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.chiller.contracts.Activity
import dev.chiller.contracts.RunState
import dev.chiller.contracts.TokenUsage
import dev.chiller.design.*
import java.awt.Color
import java.awt.BasicStroke
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/** Opt-in isolated visual fixture: no provider, credentials, process tools or user session data. */
private val table = """| Дата | Суточный максимум | Суточный минимум |
|---|---:|---:|
| 17.10.2026 | +9.5 °C | +0.4 °C |
| 18.10.2026 | +8.8 °C | +3.3 °C |
| 19.10.2026 | +12.5 °C | +3.6 °C |
| 20.10.2026 | +8.0 °C | +0.7 °C |
| 21.10.2026 | +6.8 °C | +1.1 °C |
| 22.10.2026 | +4.7 °C | +0.3 °C |
| 23.10.2026 | +3.7 °C | −1.2 °C |"""

private fun chart(): ByteArray {
    val image = BufferedImage(1500, 825, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color.WHITE; g.fillRect(0, 0, 1500, 825)
    g.color = Color.DARK_GRAY; g.font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 30)
    g.drawString("Synthetic preview — daily maximum / minimum", 100, 65)
    g.stroke = BasicStroke(3f)
    g.drawLine(100, 700, 1410, 700); g.drawLine(100, 110, 100, 700)
    val high = listOf(350, 380, 210, 410, 450, 520, 560)
    val low = listOf(650, 560, 550, 640, 620, 650, 690)
    for ((values, color) in listOf(high to Color(190, 65, 65), low to Color(50, 100, 180))) {
        g.color = color
        values.zipWithNext().forEachIndexed { i, (a, b) -> g.drawLine(150 + i * 195, a, 345 + i * 195, b) }
        values.forEachIndexed { i, y -> g.fillOval(142 + i * 195, y - 8, 16, 16) }
    }
    g.color = Color.DARK_GRAY
    (0..6).forEach { g.drawString("${17 + it}.10", 115 + it * 195, 760) }
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}

@Composable
private fun Fixture(bytes: ByteArray, dark: Boolean, narrow: Boolean) {
    ChillerTheme(darkTheme = dark, reducedMotion = true) {
        val c = LocalChillerPalette.current
        val activity = Activity(id = "show-plot", name = "show_file", target = "plot.png", status = "completed",
            output = """{"ok":true,"data":{"file":{"path":"plot.png","name":"plot.png","mimeType":"image/png","sizeBytes":${bytes.size}}}}""")
        Row(Modifier.fillMaxSize().background(c.canvas)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Синтетическая проверка таблиц и файлов", color = c.text, fontSize = 18.sp)
                MarkdownText(table)
                ToolGroup(listOf(Activity(id = "read", name = "read_file", target = "data.csv", status = "completed"), activity),
                    reduced = true, onUndo = {}, canUndo = false, onPreviewFile = { bytes })
                MarkdownText("График доступен выше: нажмите на изображение, чтобы открыть просмотр.")
            }
            if (!narrow) RunPanel(RunState(status = "completed", modelRounds = 12, toolCalls = 14, failedToolCalls = 1, runDurationMs = 78305,
                usage = TokenUsage(200801, 3828, 162048)), Modifier.width(320.dp).fillMaxHeight())
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val bytes = chart()
    if (args.contains("--interactive")) {
        application {
            Window(onCloseRequest = ::exitApplication, title = "Chiller · Results Preview", state = rememberWindowState(width = 1120.dp, height = 900.dp)) {
                var dark by remember { mutableStateOf(false) }
                Column {
                    ChillerTheme(darkTheme = dark) { ChillerControl("Сменить тему", { dark = !dark }) }
                    Fixture(bytes, dark, narrow = false)
                }
            }
        }
    } else {
        val output = Path.of("build/reports/rich-results"); Files.createDirectories(output)
        for (dark in listOf(false, true)) for (width in listOf(1120, 560)) {
            lateinit var scene: ImageComposeScene
            SwingUtilities.invokeAndWait { scene = ImageComposeScene(width, 1100) { Fixture(bytes, dark, width < 800) } }
            try {
                // Allow the actual asynchronous preview loader and composition effects to settle.
                repeat(12) { frame ->
                    SwingUtilities.invokeAndWait { scene.render(frame * 100_000_000L).close() }
                    Thread.sleep(25)
                }
                // Pointer over the table: its copy button is part of the accepted look.
                SwingUtilities.invokeAndWait {
                    scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter, androidx.compose.ui.geometry.Offset(160f, 90f))
                    scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, androidx.compose.ui.geometry.Offset(160f, 92f))
                    scene.render(1_900_000_000L).close()
                }
                SwingUtilities.invokeAndWait {
                    scene.render(2_000_000_000L).use { rendered ->
                        rendered.encodeToData()!!.use { Files.write(output.resolve("${if (dark) "dark" else "light"}-$width.png"), it.bytes) }
                    }
                }
            } finally { SwingUtilities.invokeAndWait { scene.close() } }
        }
        println("Rich results fixture rendered to $output (synthetic, no live calls)")
    }
}
