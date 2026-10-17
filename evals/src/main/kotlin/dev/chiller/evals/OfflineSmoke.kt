package dev.chiller.evals

import dev.chiller.agent.AgentCoordinator
import dev.chiller.contracts.*
import dev.chiller.runtime.LocalProcessService
import dev.chiller.sessions.*
import dev.chiller.tools.BuiltinTools
import dev.chiller.workspace.LocalWorkspace
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.Comparator

fun main() = runBlocking {
    val root = Files.createTempDirectory("chiller-offline-smoke-")
    val project = root.resolve("project"); Files.createDirectories(project)
    Files.writeString(project.resolve("greeting.py"), "def greet(name):\n    return f'Hello, {name}'\n")
    Files.writeString(project.resolve("checks.py"), "from greeting import greet\nassert greet('') == 'Hello'\nassert greet('   ') == 'Hello'\nassert greet('Ada') == 'Hello, Ada'\nprint('3 greeting checks passed')\n")
    val store = JsonlEventStore(root.resolve("sessions"))
    val artifacts = LocalArtifactStore(root.resolve("artifacts"))
    val workspace = LocalWorkspace(project, store, artifacts, "smoke")
    val processes = LocalProcessService(project)
    val tools = BuiltinTools(workspace, processes, artifacts, object : InteractionBroker {
        override suspend fun ask(question: UserQuestion) = error("Unexpected user question")
    })
    var round = 0
    val provider = object : LlmProvider {
        override suspend fun probe() = CapabilityReport("offline-smoke", true, false)
        override fun stream(request: ModelRequest) = flow {
            val previous = request.messages.lastOrNull { it.role == "tool" }?.content?.let { Json.parseToJsonElement(it).jsonObject }
            if (previous != null) check(previous["ok"]?.jsonPrimitive?.boolean == true) { "Actual tool failed: $previous" }
            val call = when (round++) {
                0 -> ToolCall("search", "search_text", "{\"query\":\"def greet\",\"glob\":\"*.py\"}")
                1 -> ToolCall("read", "read_file", "{\"path\":\"greeting.py\"}")
                2 -> {
                    val snapshot = previous!!.getValue("data").jsonObject.getValue("snapshot_id").jsonPrimitive.content
                    ToolCall("edit", "edit_file", buildJsonObject {
                        put("path", "greeting.py"); put("snapshot_id", snapshot)
                        put("edits", buildJsonArray { add(buildJsonObject { put("op", "replace"); put("start_line", 2); put("end_line", 2); put("content", "    return 'Hello' if not name.strip() else f'Hello, {name}'") }) })
                    }.toString())
                }
                3 -> ToolCall("test", "run_process", "{\"argv\":[\"python3\",\"checks.py\"],\"timeout_ms\":10000}")
                else -> {
                    val process = previous!!.getValue("data").jsonObject
                    check(process.getValue("exitCode").jsonPrimitive.int == 0) { "Real test process failed" }
                    check(process.getValue("output").jsonPrimitive.content.contains("3 greeting checks passed"))
                    null
                }
            }
            emit(ModelEvent.Completed(if (call != null) AgentMessage("assistant", toolCalls = listOf(call)) else AgentMessage("assistant", "Verified three real greeting checks."), if (call == null) "stop" else "tool_calls"))
        }
    }
    try {
        val engine = AgentCoordinator(provider, tools, store, processes, "smoke", "offline-script", scope = this)
        engine.send("Add blank-name greeting and verify", ExecutionMode.WORKSPACE_EDIT, allowProcesses = true).join()
        check(engine.state.value.status == "completed") { "Agent failed: ${engine.state.value.error}" }
        val restored = AgentCoordinator(provider, tools, JsonlEventStore(root.resolve("sessions")), processes, "smoke", "offline-script", scope = this)
        restored.restore()
        check(restored.state.value.messages == engine.state.value.messages) { "History restart mismatch" }
        val stale = workspace.read(ReadFileRequest("greeting.py"))
        Files.writeString(project.resolve("greeting.py"), "# external edit\n")
        try { workspace.edit(EditFileRequest("greeting.py", stale.snapshotId, listOf(FileEdit("replace", 1, 1, content = "wrong")))); error("Stale edit executed") }
        catch (expected: ToolException) { check(expected.code == "STALE_SNAPSHOT") }
        check(Files.readString(project.resolve("greeting.py")) == "# external edit\n")
        val badChecks = verifyEvalChecks(root)
        check(badChecks.isEmpty()) { "Eval checks are not meaningful: ${badChecks.joinToString("; ")}" }
        println("OFFLINE SMOKE PASSED: search → read → snapshot edit → real Python checks → final → restart; stale snapshot rejected.")
        println("Eval task checks: ${EVAL_TASKS.size} fail on the untouched task and pass on the reference solution.")
        println("Model was scripted. No live DeepSeek/Gemma request was made.")
    } finally {
        processes.close()
        Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
