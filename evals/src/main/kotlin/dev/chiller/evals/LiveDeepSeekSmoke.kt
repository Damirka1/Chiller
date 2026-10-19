package dev.chiller.evals

import dev.chiller.agent.AgentCoordinator
import dev.chiller.contracts.ExecutionMode
import dev.chiller.contracts.InteractionBroker
import dev.chiller.contracts.UserQuestion
import dev.chiller.providers.ChatCompletionsProfile
import dev.chiller.providers.ChatCompletionsProvider
import dev.chiller.providers.useSystemTrust
import dev.chiller.runtime.LocalProcessService
import dev.chiller.sessions.JsonlEventStore
import dev.chiller.sessions.LocalArtifactStore
import dev.chiller.tools.BuiltinTools
import dev.chiller.workspace.LocalWorkspace
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.Comparator
import kotlin.system.exitProcess

/**
 * Opt-in live check of the real multi-round tool loop.
 *
 * It is never part of `check`. It requires an explicitly configured credential
 * (DEEPSEEK_API_KEY or API_TOKEN) and deliberately small budgets. The scripted
 * offline smoke stays the default verification; this one answers the different
 * question "does a real model actually drive these tools end to end".
 */
fun main() = runBlocking {
    val key = System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }
        ?: System.getenv("API_TOKEN")?.takeIf { it.isNotBlank() }
    if (key == null) {
        System.err.println("LIVE SKIPPED: set DEEPSEEK_API_KEY or API_TOKEN to run this opt-in check.")
        exitProcess(2)
    }
    val baseUrl = System.getenv("DEEPSEEK_BASE_URL")?.takeIf { it.isNotBlank() } ?: "https://api.deepseek.com"
    val model = System.getenv("DEEPSEEK_MODEL")?.takeIf { it.isNotBlank() } ?: "deepseek-flash"
    val maxRounds = (System.getenv("CHILLER_LIVE_MAX_ROUNDS")?.toIntOrNull() ?: 8).coerceIn(1, 12)
    val maxOutputTokens = (System.getenv("CHILLER_LIVE_MAX_OUTPUT")?.toIntOrNull() ?: 1024).coerceIn(64, 2048)

    val root = Files.createTempDirectory("chiller-live-smoke-")
    val project = root.resolve("project")
    Files.createDirectories(project)
    Files.writeString(
        project.resolve("greeting.py"),
        "def greet(name):\n    if not name.strip():\n        return 'Hello, '\n    return f'Hello, {name}'\n",
    )
    Files.writeString(
        project.resolve("checks.py"),
        "from greeting import greet\nassert greet('') == 'Hello', f'blank name returned {greet(\"\")!r}'\nassert greet('Ada') == 'Hello, Ada'\nprint('2 greeting checks passed')\n",
    )

    val store = JsonlEventStore(root.resolve("sessions"))
    val artifacts = LocalArtifactStore(root.resolve("artifacts"))
    val workspace = LocalWorkspace(project, store, artifacts, "live-smoke")
    val processes = LocalProcessService(project)
    val tools = BuiltinTools(workspace, processes, artifacts, object : InteractionBroker {
        override suspend fun ask(question: UserQuestion) = error("Live smoke does not answer questions")
    })
    val http = HttpClient(CIO) {
        engine { useSystemTrust() }
        followRedirects = false
        install(HttpTimeout) { connectTimeoutMillis = 20_000; socketTimeoutMillis = 180_000; requestTimeoutMillis = 300_000 }
    }
    val provider = ChatCompletionsProvider(http, ChatCompletionsProfile.deepSeekFlash(baseUrl, model)) { key }
    val engine = AgentCoordinator(provider, tools, store, processes, "live-smoke", model, maxRounds = maxRounds, maxOutputTokens = maxOutputTokens, scope = this)

    val started = System.nanoTime()
    var failure: String? = null
    try {
        withTimeout(6 * 60_000L) {
            engine.send(
                "The project has a failing test. Run `python3 checks.py` with the run_process tool, " +
                    "find the bug in greeting.py, fix it with edit_file, then run the checks again. " +
                    "Report the exact command output. Do not claim the checks passed without running them.",
                ExecutionMode.WORKSPACE_EDIT,
                allowProcesses = true,
            ).join()
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        failure = "Timed out after 6 minutes"
    } catch (error: Exception) {
        failure = error.javaClass.simpleName
    }
    val state = engine.state.value
    val durationMs = (System.nanoTime() - started) / 1_000_000
    val finalText = state.messages.lastOrNull { it.role == "assistant" }?.content.orEmpty()
    val toolNames = state.activities.map { it.name }.distinct()
    val usage = state.usage

    // Independent verification: the model's claim is not evidence.
    val verified = runCatching {
        val process = ProcessBuilder("python3", "checks.py").directory(project.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor() to output.trim()
    }.getOrNull()

    println("=== CHILLER LIVE DEEPSEEK SMOKE ===")
    println("model=$model maxRounds=$maxRounds maxOutput=$maxOutputTokens")
    println("status=${state.status}${state.error?.let { " error=" + it.take(300) } ?: ""}${failure?.let { " failure=$it" } ?: ""}")
    println("rounds=${state.modelRounds} toolCalls=${state.toolCalls} failedToolCalls=${state.failedToolCalls} durationMs=$durationMs")
    println("tools=${toolNames.joinToString(",")}")
    println("tokens: prompt=${usage.promptTokens ?: "n/a"} completion=${usage.completionTokens ?: "n/a"} cached=${usage.cachedPromptTokens ?: "n/a"}")
    println("final=${finalText.take(800)}")
    println("independent check: ${verified?.let { "exit=" + it.first + " output=" + it.second } ?: "could not run"}")

    http.close()
    processes.close()
    Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }

    val passed = state.status == "completed" && verified?.first == 0
    println(if (passed) "LIVE SMOKE PASSED" else "LIVE SMOKE FAILED")
    if (!passed) exitProcess(1)
}
