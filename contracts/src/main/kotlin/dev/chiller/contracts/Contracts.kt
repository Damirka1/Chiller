package dev.chiller.contracts

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun now(): String = Instant.now().toString()

@Serializable
data class ToolCall(val id: String, val name: String, val arguments: String)

@Serializable
data class AgentMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val replay: JsonObject = JsonObject(emptyMap()),
)

@Serializable
enum class ToolEffect { READ, FILE_WRITE, PROCESS, USER_INTERACTION }

@Serializable
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject, val effect: ToolEffect)

data class ModelRequest(
    val model: String,
    val messages: List<AgentMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val thinking: Boolean = false,
    val maxOutputTokens: Int = 4096,
)

sealed interface ModelEvent {
    data class TextDelta(val text: String) : ModelEvent
    data class ReasoningDelta(val text: String) : ModelEvent
    data class Completed(val message: AgentMessage, val finishReason: String) : ModelEvent
    data class Failed(val code: String, val message: String) : ModelEvent
}

data class CapabilityReport(val profile: String, val tools: Boolean?, val reasoning: Boolean?, val liveVerified: Boolean = false)
interface LlmProvider {
    suspend fun probe(): CapabilityReport
    fun stream(request: ModelRequest): Flow<ModelEvent>
}

@Serializable
data class ToolError(val code: String, val message: String, val retryable: Boolean = false, val recovery: String? = null)

@Serializable
data class ToolResult(
    val ok: Boolean,
    val data: JsonElement? = null,
    val error: ToolError? = null,
    val truncated: Boolean = false,
    val nextCursor: String? = null,
    val artifactId: String? = null,
) {
    companion object {
        fun failure(code: String, message: String, retryable: Boolean = false, recovery: String? = null) =
            ToolResult(false, error = ToolError(code, message, retryable, recovery))
    }
}

class ToolException(val code: String, override val message: String, val recovery: String? = null) : RuntimeException(message)

enum class ExecutionMode { PLAN, WORKSPACE_EDIT }
data class ToolContext(val sessionId: String, val runId: String, val mode: ExecutionMode, val allowProcesses: Boolean = false)
interface ToolExecutor {
    val specs: List<ToolSpec>
    suspend fun execute(call: ToolCall, context: ToolContext): ToolResult
}

@Serializable
data class ReadFileRequest(val path: String, val startLine: Int = 1, val endLine: Int? = null)
@Serializable
data class FileReadResult(val path: String, val snapshotId: String, val totalLines: Int, val content: String, val truncated: Boolean = false, val eol: String = "LF")
@Serializable
data class FileEdit(val op: String = "replace", val startLine: Int? = null, val endLine: Int? = null, val beforeLine: Int? = null, val content: String = "")
@Serializable
data class EditFileRequest(val path: String, val snapshotId: String, val edits: List<FileEdit>)
@Serializable
data class FileChange(val id: String, val path: String, val beforeHash: String?, val afterHash: String?, val diff: String, val snapshotId: String? = null)
interface WorkspaceService {
    val root: String
    suspend fun read(request: ReadFileRequest): FileReadResult
    suspend fun list(path: String = ".", glob: String? = null, limit: Int = 200): ToolResult
    suspend fun search(query: String, path: String = ".", glob: String? = null, regex: Boolean = false, limit: Int = 100): ToolResult
    suspend fun edit(request: EditFileRequest): FileChange
    suspend fun create(path: String, content: String): FileChange
    suspend fun move(path: String, snapshotId: String, destination: String): FileChange
    suspend fun delete(path: String, snapshotId: String): FileChange
    suspend fun undo(changeId: String): FileChange
}

@Serializable
data class StartProcessRequest(val argv: List<String>, val cwd: String = ".", val timeoutMs: Long = 60_000, val background: Boolean = false)
@Serializable
data class ProcessOutput(val processId: String, val output: String, val cursor: Long, val state: String, val exitCode: Int? = null, val truncated: Boolean = false)
interface ProcessService {
    suspend fun start(request: StartProcessRequest, runId: String): ProcessOutput
    suspend fun read(processId: String, cursor: Long = 0): ProcessOutput
    suspend fun stop(processId: String): ProcessOutput
    suspend fun stopRun(runId: String)
    suspend fun close()
}

@Serializable
data class StoredEvent(val schemaVersion: Int = 1, val id: String = newId(), val sessionId: String, val seq: Long, val timestamp: String = now(), val type: String, val payload: JsonObject)
@Serializable
data class SessionInfo(val id: String, val title: String, val updatedAt: String)
interface EventStore {
    suspend fun append(sessionId: String, type: String, payload: JsonObject): StoredEvent
    suspend fun read(sessionId: String): List<StoredEvent>
    suspend fun list(): List<SessionInfo>
}

@Serializable
data class ArtifactRef(val id: String, val sessionId: String, val size: Long)
interface ArtifactStore {
    suspend fun put(sessionId: String, content: String): ArtifactRef
    suspend fun read(sessionId: String, artifactId: String, offset: Int = 0, limit: Int = 16_384): String
}

@Serializable
data class UserQuestion(val id: String = newId(), val question: String, val options: List<String> = emptyList())
interface InteractionBroker { suspend fun ask(question: UserQuestion): String }

@Serializable
data class Activity(val id: String = newId(), val name: String, val target: String, val status: String, val output: String = "", val diff: String? = null)
data class RunState(
    val status: String = "idle",
    val messages: List<AgentMessage> = emptyList(),
    val streamingText: String = "",
    val reasoning: String = "",
    val activities: List<Activity> = emptyList(),
    val error: String? = null,
    val question: UserQuestion? = null,
    val contextEstimatedTokens: Int? = null,
    val droppedContextMessages: Int = 0,
    val modelRounds: Int = 0,
)
