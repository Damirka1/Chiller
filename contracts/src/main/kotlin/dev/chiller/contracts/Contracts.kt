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
enum class MessageOriginKind { MODEL, APPLICATION, OFFLINE }

@Serializable
data class MessageOrigin(val kind: MessageOriginKind, val modelId: String? = null)

@Serializable
data class AgentMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val replay: JsonObject = JsonObject(emptyMap()),
    val origin: MessageOrigin? = null,
    val attachments: List<ComposerAttachment> = emptyList(),
)

@Serializable
/** [EXTERNAL]: a tool of a connected MCP server whose effects outside Chiller are unknown; never offered in Plan mode. */
enum class ToolEffect { READ, FILE_WRITE, PROCESS, USER_INTERACTION, NETWORK, SCREEN_CAPTURE, EXTERNAL }

@Serializable
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject, val effect: ToolEffect)

data class ModelRequest(
    val model: String,
    val messages: List<AgentMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val thinking: Boolean = false,
    val maxOutputTokens: Int = 4096,
)

/** Provider-reported token counters. Null means the provider did not report the value. */
@Serializable
data class TokenUsage(
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val cachedPromptTokens: Long? = null,
) {
    val isEmpty: Boolean get() = promptTokens == null && completionTokens == null && cachedPromptTokens == null
}

operator fun TokenUsage.plus(other: TokenUsage) = TokenUsage(
    promptTokens = listOfNotNull(promptTokens, other.promptTokens).takeIf { it.isNotEmpty() }?.sum(),
    completionTokens = listOfNotNull(completionTokens, other.completionTokens).takeIf { it.isNotEmpty() }?.sum(),
    cachedPromptTokens = listOfNotNull(cachedPromptTokens, other.cachedPromptTokens).takeIf { it.isNotEmpty() }?.sum(),
)

sealed interface ModelEvent {
    data class TextDelta(val text: String) : ModelEvent
    data class ReasoningDelta(val text: String) : ModelEvent

    /** Reported at most once per request, before the terminal [Completed] or [Failed] event. */
    data class Usage(val usage: TokenUsage) : ModelEvent
    /** Non-terminal: the stateless request failed before any delta and is resent after [delayMs]. */
    data class Retrying(val code: String, val delayMs: Long, val attempt: Int, val maxAttempts: Int) : ModelEvent
    data class Completed(val message: AgentMessage, val finishReason: String) : ModelEvent
    data class Failed(val code: String, val message: String) : ModelEvent
}

data class CapabilityReport(val profile: String, val tools: Boolean?, val reasoning: Boolean?, val liveVerified: Boolean = false)
interface LlmProvider {
    /** Known model/output budget ceiling before constructing context; null means unknown. */
    fun outputTokenLimit(model: String): Int? = null
    suspend fun probe(): CapabilityReport
    fun stream(request: ModelRequest): Flow<ModelEvent>
}

/** Transport-level spending gate shared by chat retries, helpers and image requests. */
interface RequestBudget {
    /** Reset only for an explicitly started user task, never for an automatic retry/follow-up. */
    fun beginTask()
    fun reserveChat(maxOutputTokens: Int): RequestReservation
    fun reserveImage(): RequestReservation
    fun snapshot(): CostBudgetSnapshot
}

interface RequestReservation {
    /** Call once even on cancellation. Unknown/partial usage keeps the reservation. */
    fun finish(usage: TokenUsage? = null)
}

@Serializable
data class CostBudgetSnapshot(
    val limitUsd: String,
    val accountedUsd: String,
    val reservedUsd: String,
    val attempts: Int,
    val uncertainAttempts: Int,
    val exceededEstimate: Boolean = false,
)

/** Prices are user-supplied upper bounds, not a built-in provider price list. Empty prices fail closed. */
@Serializable
data class CostBudgetSettings(
    val enabled: Boolean = false,
    val limitUsd: String = "1.00",
    val inputUsdPerMillion: String = "",
    val outputUsdPerMillion: String = "",
    val imageUsdPerRequest: String = "",
    val inputTokenCeiling: Int = 32768,
    /** Rates belong to this endpoint/model. Switching either requires configuring the budget again. */
    val baseUrl: String = "",
    val model: String = "",
    val imageModel: String = "",
)

/** One model returned by a provider's model listing. Absent metadata stays null, never guessed. */
@Serializable
data class ProviderModel(
    val id: String,
    val displayName: String? = null,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    /** Accepts image input, as the listing states it (input modalities or capabilities); null when it does not say. */
    val vision: Boolean? = null,
    /** Can produce reasoning, as the listing's capabilities state it; null when it does not say. */
    val reasoning: Boolean? = null,
    /** Answers chat completions; false only when the listing shows it cannot (an image-only model), null when silent. */
    val chat: Boolean? = null,
    /** Generates images from text (capability or /images/generations endpoint); null when the listing is silent. */
    val imageGeneration: Boolean? = null,
    /** Edits a reference image (capability or /images/edits endpoint); null when the listing is silent. */
    val imageEditing: Boolean? = null,
)

@Serializable
data class ModelCatalogResult(
    val models: List<ProviderModel> = emptyList(),
    val errorCode: String? = null,
    val message: String? = null,
) {
    val ok: Boolean get() = errorCode == null
}

/** Lists models for one endpoint. Never sends project text and never implies tool/vision support. */
interface ModelCatalogClient {
    suspend fun discover(baseUrl: String, apiKey: String?): ModelCatalogResult
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

/** An encoded image (PNG, JPEG or WebP) and its type. */
class ImageData(val bytes: ByteArray, val mimeType: String) { override fun toString() = "ImageData($mimeType, ${bytes.size} bytes)" }

/** Text-to-image generation and image editing through an endpoint's image model; bounded in size and time. */
interface ImageService {
    val model: String
    val supportsEditing: Boolean
    /** One image for [prompt]; with [reference] the model edits that image. Failures are [ToolException]s with provider codes. */
    suspend fun generate(prompt: String, size: String, reference: ImageData? = null): ImageData
}

/** PNG, JPEG or WebP by signature; null for anything else. */
fun imageMimeType(bytes: ByteArray): String? = when {
    bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
    bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
    else -> null
}

/** Width and height from a PNG header; null for other formats or a damaged header. */
fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
    if (imageMimeType(bytes) != "image/png" || bytes.size < 24) return null
    fun int(offset: Int) = (bytes[offset].toInt() and 0xFF shl 24) or (bytes[offset + 1].toInt() and 0xFF shl 16) or (bytes[offset + 2].toInt() and 0xFF shl 8) or (bytes[offset + 3].toInt() and 0xFF)
    return (int(16) to int(20)).takeIf { it.first > 0 && it.second > 0 }
}

/** The original bytes of an image the user attached in a session; null when the id is unknown or not an image. */
fun interface AttachmentImages { suspend fun load(sessionId: String, artifactId: String): ImageData? }

enum class ExecutionMode { PLAN, WORKSPACE_EDIT }
data class ToolContext(val sessionId: String, val runId: String, val mode: ExecutionMode, val allowProcesses: Boolean = false, val allowNetwork: Boolean = false, val allowScreenshots: Boolean = false)
interface ToolExecutor {
    val specs: List<ToolSpec>
    suspend fun execute(call: ToolCall, context: ToolContext): ToolResult
}

/** A file explicitly presented to the user; path is resolved again by the application before opening. */
@Serializable
data class FilePresentation(val path: String, val name: String, val mimeType: String, val sizeBytes: Long)

@Serializable
data class ComposerAttachment(val artifactId: String, val name: String, val mimeType: String, val sizeBytes: Long)

@Serializable
data class CaptureTarget(val id: String, val title: String, val application: String)
interface ScreenCaptureService {
    val supported: Boolean
    suspend fun targets(): List<CaptureTarget>
    suspend fun capture(): FilePresentation
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
    suspend fun search(query: String, path: String = ".", glob: String? = null, regex: Boolean = false, limit: Int = 100, context: Int = 0): ToolResult
    /** Continuation is scoped to the original query; implementations must reject unknown cursors. */
    suspend fun listPage(path: String = ".", glob: String? = null, limit: Int = 200, cursor: String? = null): ToolResult =
        if (cursor == null) list(path, glob, limit) else ToolResult.failure("UNSUPPORTED", "Listing continuation is unavailable; narrow the path or glob")
    suspend fun searchPage(query: String, path: String = ".", glob: String? = null, regex: Boolean = false, limit: Int = 100, context: Int = 0, cursor: String? = null): ToolResult =
        if (cursor == null) search(query, path, glob, regex, limit, context) else ToolResult.failure("UNSUPPORTED", "Search continuation is unavailable; narrow the query")
    suspend fun edit(request: EditFileRequest): FileChange
    suspend fun create(path: String, content: String): FileChange
    /** Raw bytes of an existing regular workspace file, at most [maxBytes]; for reference images, never for text edits. */
    suspend fun readBinary(path: String, maxBytes: Int): ByteArray = throw ToolException("UNSUPPORTED", "Binary files cannot be read in this workspace")
    /** Creates a new binary file (a generated image); never overwrites. Undoable like [create]. */
    suspend fun createBinary(path: String, bytes: ByteArray): FileChange = throw ToolException("UNSUPPORTED", "Binary files cannot be created in this workspace")
    suspend fun createDirectory(path: String): ToolResult = ToolResult.failure("UNSUPPORTED", "Directory creation is unavailable")
    suspend fun describeFile(path: String): FilePresentation = throw ToolException("UNSUPPORTED", "File presentation is unavailable")
    suspend fun move(path: String, snapshotId: String, destination: String): FileChange
    suspend fun delete(path: String, snapshotId: String): FileChange
    suspend fun undo(changeId: String): FileChange
    /**
     * Replaces exact occurrences of [oldText] in the snapshot's file. The number of matches must equal
     * [expectedCount]; nothing is applied otherwise. Matched lines must have been read in that snapshot.
     */
    suspend fun replaceText(path: String, snapshotId: String, oldText: String, newText: String, expectedCount: Int = 1): FileChange =
        throw ToolException("UNSUPPORTED", "Text replacement is not supported by this workspace")
    /**
     * Adds [content] as whole lines after the last line of the snapshot's file, which must be unchanged and whose last
     * line must have been read (or written) in that snapshot. Existing lines are never changed.
     */
    suspend fun append(path: String, snapshotId: String, content: String): FileChange =
        throw ToolException("UNSUPPORTED", "Appending is not supported by this workspace")
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
/**
 * [workspace] is the folder the dialog is bound to (the latest `session.workspace`), null for a dialog without a folder.
 * [origin] is the project a dialog's own git working copy was made from, when [workspace] is that copy.
 */
data class SessionInfo(val id: String, val title: String, val updatedAt: String, val workspace: String? = null, val origin: String? = null)
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
data class Activity(val id: String = newId(), val name: String, val target: String, val status: String, val output: String = "", val diff: String? = null, val toolCallId: String? = null)
data class RunState(
    val status: String = "idle",
    val messages: List<AgentMessage> = emptyList(),
    val streamingText: String = "",
    val reasoning: String = "",
    val recoveryNotice: String? = null,
    val activities: List<Activity> = emptyList(),
    val error: String? = null,
    val question: UserQuestion? = null,
    val contextEstimatedTokens: Int? = null,
    val droppedContextMessages: Int = 0,
    val prunedContextResults: Int = 0,
    val contextWindowTokens: Int? = null,
    val contextReportedTokens: Long? = null,
    val contextMemory: String? = null,
    val contextOutputReserve: Int? = null,
    val modelRounds: Int = 0,
    val activeModelId: String? = null,
    val usage: TokenUsage = TokenUsage(),
    val usageIncomplete: Boolean = false,
    val toolCalls: Int = 0,
    val failedToolCalls: Int = 0,
    val runDurationMs: Long? = null,
)
