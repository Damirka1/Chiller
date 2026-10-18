package dev.chiller.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.chiller.contracts.RunState
import dev.chiller.contracts.TokenUsage
import dev.chiller.design.ChillerTheme
import dev.chiller.design.LocalChillerPalette
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities

/** Renders the turn result card (clean and with issues, light and dark) into build/reports/turn-result. Synthetic data. */
fun main() {
    val state = RunState(status = "completed", modelRounds = 9, toolCalls = 14, failedToolCalls = 1, runDurationMs = 21_400, usage = TokenUsage(48_200, 1_900, 39_000))
    val clean = TurnResult(
        files = listOf(ChangedFile("src/cart.py", "edited", 12, 3), ChangedFile("tests/test_cart.py", "created", 24, 0), ChangedFile("docs/old.md", "deleted", 0, 8)),
        commands = listOf(CommandRun("python3 -m pytest -q", "exited", 0, true), CommandRun("grep -rn discount src", "exited", 1, false)),
        presented = listOf("reports/coverage.png"), issues = emptyList())
    val issues = clean.copy(
        commands = listOf(CommandRun("python3 -m pytest -q", "exited", 1, true), CommandRun("npm run lint", "running", null, true)),
        issues = listOf("«python3 -m pytest -q» завершилась с кодом 1 и после этого не прошла", "Результат «npm run lint» не прочитан: неизвестно, прошла ли проверка", "Правка «src/api.py» не применилась"))
    val output = Path.of("build/reports/turn-result"); Files.createDirectories(output)
    for (dark in listOf(false, true)) for ((name, result) in listOf("clean" to clean, "issues" to issues)) {
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(1520, 1300, Density(2f)) {
                ChillerTheme(darkTheme = dark, reducedMotion = true) {
                    Box(Modifier.fillMaxSize().background(LocalChillerPalette.current.canvas).padding(20.dp)) { TurnResultCard(state, result.copy(), Modifier, initiallyOpen = true, outside = if (name == "issues") dev.chiller.app.di.OutsideChanges(0, added = listOf("node_modules/.cache/build.json", "dist/bundle.js"), modified = listOf("package-lock.json")) else null) }
                }
            }
        }
        try {
            repeat(6) { SwingUtilities.invokeAndWait { scene.render(it * 100_000_000L).close() } }
            SwingUtilities.invokeAndWait { scene.render(1_000_000_000L).use { img -> img.encodeToData()!!.use { Files.write(output.resolve("${if (dark) "dark" else "light"}-$name.png"), it.bytes) } } }
        } finally { SwingUtilities.invokeAndWait { scene.close() } }
    }
    println("Turn result cards rendered to $output")
}
