package dev.chiller.tools

import dev.chiller.contracts.*
import dev.chiller.workspace.LocalWorkspace
import dev.chiller.runtime.LocalProcessService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class BuiltinToolsTest {
    @Test fun permissionsAndSchemaGuardEffects() = runBlocking {
        val root = Files.createTempDirectory("chiller-tools")
        val workspace = LocalWorkspace(root)
        val processes = LocalProcessService(root)
        val artifacts = object : ArtifactStore {
            override suspend fun put(sessionId: String, content: String) = ArtifactRef("unused", sessionId, content.length.toLong())
            override suspend fun read(sessionId: String, artifactId: String, offset: Int, limit: Int) = ""
        }
        val questions = object : InteractionBroker { override suspend fun ask(question: UserQuestion) = "answer" }
        var captures = 0
        val screen = object : ScreenCaptureService {
            override val supported = true
            override suspend fun targets() = listOf(CaptureTarget("selected", "Synthetic window", "Fixture"))
            override suspend fun capture(): FilePresentation {
                captures++
                return FilePresentation("synthetic.png", "synthetic.png", "image/png", 42)
            }
        }
        val tools = BuiltinTools(workspace, processes, artifacts, questions, screens = screen)
        try {
            val plan = ToolContext("s", "r", ExecutionMode.PLAN)
            assertEquals("PERMISSION_DENIED", tools.execute(ToolCall("capture-denied", "capture_window", "{}"), plan).error?.code)
            assertEquals(0, captures)
            assertTrue(tools.execute(ToolCall("capture-allowed", "capture_window", "{}"), plan.copy(allowScreenshots = true)).ok)
            assertEquals(1, captures)
            val network = tools.execute(ToolCall("net-1", "web_search", """{"query":"Kotlin"}"""), plan)
            assertEquals("PERMISSION_DENIED", network.error?.code)
            val explicitlyAllowedNetwork = tools.execute(ToolCall("net-2", "web_search", """{"query":"Kotlin"}"""), plan.copy(allowNetwork = true))
            assertEquals("NETWORK_DISABLED", explicitlyAllowedNetwork.error?.code)
            val create = tools.execute(ToolCall("1", "create_file", "{\"path\":\"new.txt\",\"content\":\"x\"}"), plan)
            assertEquals("PERMISSION_DENIED", create.error?.code)
            assertFalse(Files.exists(root.resolve("new.txt")))
            val editMode = plan.copy(mode = ExecutionMode.WORKSPACE_EDIT)
            val process = tools.execute(ToolCall("2", "run_process", "{\"argv\":[\"echo\",\"x\"]}"), editMode)
            assertEquals("PERMISSION_DENIED", process.error?.code)
            val unknown = tools.execute(ToolCall("3", "create_file", "{\"path\":\"new.txt\",\"content\":\"x\",\"overwrite\":true}"), editMode)
            assertEquals("INVALID_ARGUMENTS", unknown.error?.code)
            assertFalse(Files.exists(root.resolve("new.txt")))
            val invalid = tools.execute(ToolCall("4", "read_file", "{\"path\":42}"), plan)
            assertEquals("INVALID_ARGUMENTS", invalid.error?.code)
            // The error names the argument and what was expected, so a small model can fix the call in one step.
            assertEquals("Unknown argument overwrite", unknown.error?.message)
            assertEquals("Allowed arguments: path, content", unknown.error?.recovery)
            assertEquals("path must be a string, got 42", invalid.error?.message)
            val quoted = tools.execute(ToolCall("5", "read_file", "{\"path\":\"a.txt\",\"start_line\":\"10\"}"), plan)
            assertEquals("start_line must be an integer (a JSON number without quotes), got the string \"10\"", quoted.error?.message)
            assertEquals("Missing required argument path", tools.execute(ToolCall("6", "read_file", "{}"), plan).error?.message)
            assertEquals("Arguments are not valid JSON", tools.execute(ToolCall("7", "read_file", "{\"path\": \"a.txt\""), plan).error?.message)
        } finally { processes.close(); root.toFile().deleteRecursively() }
    }

    @Test fun editResultsCarrySnapshotIdForChaining() = runBlocking {
        val root = Files.createTempDirectory("chiller-chain")
        val workspace = LocalWorkspace(root)
        val processes = LocalProcessService(root)
        val artifacts = object : ArtifactStore {
            override suspend fun put(sessionId: String, content: String) = ArtifactRef("unused", sessionId, content.length.toLong())
            override suspend fun read(sessionId: String, artifactId: String, offset: Int, limit: Int) = ""
        }
        val questions = object : InteractionBroker { override suspend fun ask(question: UserQuestion) = "answer" }
        val tools = BuiltinTools(workspace, processes, artifacts, questions)
        try {
            Files.writeString(root.resolve("a.txt"), "one\ntwo\n")
            val context = ToolContext("s", "r", ExecutionMode.WORKSPACE_EDIT)
            val read = tools.execute(ToolCall("1", "read_file", """{"path":"a.txt"}"""), context)
            val snapshot = read.data!!.jsonObject.getValue("snapshot_id").jsonPrimitive.content
            val first = tools.execute(ToolCall("2", "edit_file", """{"path":"a.txt","snapshot_id":"$snapshot","edits":[{"op":"replace","start_line":1,"content":"ONE"}]}"""), context)
            assertTrue(first.ok)
            val chained = first.data!!.jsonObject.getValue("snapshot_id").jsonPrimitive.content
            assertEquals("ONE\ntwo\n", Files.readString(root.resolve("a.txt")))
            val second = tools.execute(ToolCall("3", "edit_file", """{"path":"a.txt","snapshot_id":"$chained","edits":[{"op":"replace","start_line":2,"content":"TWO"}]}"""), context)
            assertTrue(second.ok)
            assertEquals("ONE\nTWO\n", Files.readString(root.resolve("a.txt")))
            val rg = runCatching { ProcessBuilder("rg", "--version").start().waitFor() == 0 }.getOrDefault(false)
            if (rg) {
                val search = tools.execute(ToolCall("4", "search_text", """{"query":"ONE","context":1,"limit":5}"""), context)
                assertTrue(search.ok)
            }
            // A new file is written in parts: create_file's snapshot_id feeds append_file without a read.
            val created = tools.execute(ToolCall("5", "create_file", """{"path":"big.txt","content":"line 1\n"}"""), context)
            var snapshotId = created.data!!.jsonObject.getValue("snapshot_id").jsonPrimitive.content
            for (part in 0 until 3) {
                val content = (2 + part * 100 until 102 + part * 100).joinToString("\\n") { "line $it" }
                val appended = tools.execute(ToolCall("6-$part", "append_file", """{"path":"big.txt","snapshot_id":"$snapshotId","content":"$content"}"""), context)
                assertTrue(appended.ok, appended.error?.message)
                snapshotId = appended.data!!.jsonObject.getValue("snapshot_id").jsonPrimitive.content
            }
            assertEquals((1..301).joinToString("") { "line $it\n" }, Files.readString(root.resolve("big.txt")))
            // The change result leads with snapshot_id and has no hashes to mistake for it; a change id passed as one gets
            // the file's current snapshot back instead of a re-read.
            val createdData = created.data!!.jsonObject
            assertEquals("snapshot_id", createdData.keys.first())
            assertFalse("afterHash" in createdData)
            val wrong = tools.execute(ToolCall("6x", "append_file", """{"path":"big.txt","snapshot_id":"${createdData.getValue("id").jsonPrimitive.content}","content":"x"}"""), context)
            assertEquals("UNKNOWN_SNAPSHOT", wrong.error?.code)
            assertTrue(snapshotId in wrong.error?.recovery.orEmpty(), wrong.error?.recovery)
            val other = tools.execute(ToolCall("6y", "append_file", """{"path":"a.txt","snapshot_id":"$snapshotId","content":"x"}"""), context)
            assertEquals("SNAPSHOT_PATH_MISMATCH", other.error?.code)
            assertTrue("big.txt" in other.error?.recovery.orEmpty(), other.error?.recovery)
            // A cut read says which lines it showed and where to go on.
            val page = tools.execute(ToolCall("7", "read_file", """{"path":"big.txt","start_line":50}"""), context).data!!.jsonObject
            assertEquals("50-249", page.getValue("shown_lines").jsonPrimitive.content)
            assertEquals(250, page.getValue("next_start_line").jsonPrimitive.int)
            val whole = tools.execute(ToolCall("8", "read_file", """{"path":"a.txt"}"""), context).data!!.jsonObject
            assertFalse("next_start_line" in whole, "a complete read has no continuation")
        } finally { processes.close(); root.toFile().deleteRecursively() }
    }

    @Test fun replaceTextAndPlanAreModelFriendly() = runBlocking {
        val root = Files.createTempDirectory("chiller-replace-tool")
        val workspace = LocalWorkspace(root)
        val processes = LocalProcessService(root)
        val artifacts = object : ArtifactStore {
            override suspend fun put(sessionId: String, content: String) = ArtifactRef("unused", sessionId, content.length.toLong())
            override suspend fun read(sessionId: String, artifactId: String, offset: Int, limit: Int) = ""
        }
        val tools = BuiltinTools(workspace, processes, artifacts, object : InteractionBroker { override suspend fun ask(question: UserQuestion) = "a" })
        try {
            Files.writeString(root.resolve("a.py"), "x = 1\ny = 2\n")
            val plan = ToolContext("s", "r", ExecutionMode.PLAN)
            val edit = plan.copy(mode = ExecutionMode.WORKSPACE_EDIT)
            val snapshot = tools.execute(ToolCall("1", "read_file", """{"path":"a.py"}"""), plan).data!!.jsonObject.getValue("snapshot_id").jsonPrimitive.content
            assertEquals("PERMISSION_DENIED", tools.execute(ToolCall("2", "replace_text", """{"path":"a.py","snapshot_id":"$snapshot","old_text":"y = 2","new_text":"y = 3"}"""), plan).error?.code)
            val missing = tools.execute(ToolCall("3", "replace_text", """{"path":"a.py","snapshot_id":"$snapshot","old_text":"z = 2","new_text":"z = 3"}"""), edit)
            assertEquals("NO_MATCH", missing.error?.code)
            assertTrue(tools.execute(ToolCall("4", "replace_text", """{"path":"a.py","snapshot_id":"$snapshot","old_text":"y = 2","new_text":"y = 3"}"""), edit).ok)
            assertEquals("x = 1\ny = 3\n", Files.readString(root.resolve("a.py")))

            val steps = """{"steps":[{"step":"read","status":"done"},{"step":"fix","status":"in_progress"},{"step":"test","status":"pending"}]}"""
            val planned = tools.execute(ToolCall("5", "update_plan", steps), plan)
            assertTrue(planned.ok)
            assertEquals("fix", planned.data!!.jsonObject.getValue("next").jsonPrimitive.content)
            val twoActive = tools.execute(ToolCall("6", "update_plan", """{"steps":[{"step":"a","status":"in_progress"},{"step":"b","status":"in_progress"}]}"""), plan)
            assertEquals("INVALID_ARGUMENTS", twoActive.error?.code)
        } finally { processes.close(); root.toFile().deleteRecursively() }
    }

    @Test fun readArtifactFindsLinesAcrossChunksAndReportsMore() = runBlocking<Unit> {
        // ~200 KiB log; the needle line straddles the 64 KiB chunk boundary to exercise the carried partial line.
        val prefix = "x".repeat(65_530) + "\n"
        val content = prefix + "lead ORDER-ID: ORD-424242 tail\n" + (1..3000).joinToString("") { "line $it ok\n" } + "late order-id: second\n"
        val artifacts = object : ArtifactStore {
            override suspend fun put(sessionId: String, content: String) = ArtifactRef("a", sessionId, content.length.toLong())
            override suspend fun read(sessionId: String, artifactId: String, offset: Int, limit: Int): String {
                require(limit in 1..65_536)
                return content.substring(offset.coerceAtMost(content.length), (offset + limit).coerceAtMost(content.length))
            }
        }
        val root = Files.createTempDirectory("chiller-artifact-search")
        val tools = BuiltinTools(LocalWorkspace(root), LocalProcessService(root), artifacts, object : InteractionBroker { override suspend fun ask(question: UserQuestion) = "" })
        val context = ToolContext("s", "r", ExecutionMode.PLAN)
        val found = tools.execute(ToolCall("1", "read_artifact", """{"artifact_id":"a","query":"order-id"}"""), context)
        assertTrue(found.ok, found.error?.message)
        val matches = found.data!!.jsonObject.getValue("matches").jsonArray.map { it.jsonObject }
        assertEquals(2, matches.size)
        assertEquals(2, matches[0].getValue("line").jsonPrimitive.int)
        assertEquals(prefix.length, matches[0].getValue("offset").jsonPrimitive.int)
        assertTrue(matches[0].getValue("text").jsonPrimitive.content.contains("ORD-424242"))
        assertEquals(content.lines().indexOfFirst { it.startsWith("late") } + 1, matches[1].getValue("line").jsonPrimitive.int)
        val part = tools.execute(ToolCall("2", "read_artifact", """{"artifact_id":"a","offset":${prefix.length},"limit":40}"""), context).data!!.jsonObject
        assertTrue(part.getValue("content").jsonPrimitive.content.startsWith("lead ORDER-ID"))
        assertEquals(prefix.length + 40, part.getValue("next_offset").jsonPrimitive.int)
        val tail = tools.execute(ToolCall("3", "read_artifact", """{"artifact_id":"a","offset":${content.length - 10},"limit":100}"""), context).data!!.jsonObject
        assertEquals(true, tail.getValue("end").jsonPrimitive.boolean)
        root.toFile().deleteRecursively()
    }

    @Test fun longFinishedProcessOutputShowsItsBeginningAndEnd() = runBlocking<Unit> {
        // A test runner puts its summary last; the model got only the first page and ran the same command again.
        val text = (0 until 3000).joinToString("") { if (it == 1500) "case 1500: FAILED, expected 15\n" else "case $it: ok\n" } + "1 failure: case 1500\n"
        val processes = object : ProcessService {
            fun page(cursor: Long): ProcessOutput {
                val end = minOf(text.length.toLong(), cursor + 16_384)
                return ProcessOutput("p", text.substring(cursor.toInt(), end.toInt()), end, "exited", 1, end < text.length)
            }
            override suspend fun start(request: StartProcessRequest, runId: String) = page(0)
            override suspend fun read(processId: String, cursor: Long) = page(cursor)
            override suspend fun stop(processId: String) = page(0)
            override suspend fun stopRun(runId: String) {}
            override suspend fun close() {}
        }
        val root = Files.createTempDirectory("chiller-process-tail")
        val artifacts = object : ArtifactStore {
            override suspend fun put(sessionId: String, content: String) = ArtifactRef("a", sessionId, content.length.toLong())
            override suspend fun read(sessionId: String, artifactId: String, offset: Int, limit: Int) = ""
        }
        val tools = BuiltinTools(LocalWorkspace(root), processes, artifacts, object : InteractionBroker { override suspend fun ask(question: UserQuestion) = "" })
        val result = tools.execute(ToolCall("1", "run_process", """{"argv":["python","checks.py"]}"""), ToolContext("s", "r", ExecutionMode.WORKSPACE_EDIT, allowProcesses = true))
        val data = result.data!!.jsonObject
        val output = data.getValue("output").jsonPrimitive.content
        assertTrue(output.startsWith("case 0: ok") && output.endsWith("1 failure: case 1500\n"), output.takeLast(200))
        assertTrue("characters omitted here; read_process with cursor=2000 reads them. The 1 omitted line(s) that mention failures or errors:]\ncase 1500: FAILED, expected 15\n" in output, output)
        assertTrue(output.length < 8_500, "head and tail stay bounded: ${output.length}")
        assertEquals(text.length.toLong(), data.getValue("cursor").jsonPrimitive.long)
        assertEquals(false, data.getValue("truncated").jsonPrimitive.boolean)
        assertEquals(1, data.getValue("exitCode").jsonPrimitive.int)
        root.toFile().deleteRecursively()
    }
}
