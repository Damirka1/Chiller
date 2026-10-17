package dev.chiller.contracts

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Subagents: read-only helpers the main agent starts for one run. Depth is one (a child cannot delegate),
 * children never write files, run processes or ask the user, and every child action is re-checked
 * against its path scope. See docs/IMPLEMENTATION_AUDIT_2026_10_17.md for the design this follows.
 */
@Serializable
enum class SubagentStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

val SubagentStatus.isTerminal: Boolean get() = this != SubagentStatus.QUEUED && this != SubagentStatus.RUNNING

@Serializable
data class SubagentRequest(
    /** Caller-chosen id; the same id with the same payload returns the existing child, a different payload is a conflict. */
    val taskId: String,
    val goal: String,
    val context: String = "",
    /** Workspace-relative folders or files the child may read; "." is the whole project. */
    val allowedPaths: List<String> = listOf("."),
    val maxRounds: Int = 4,
)

@Serializable
data class SubagentSnapshot(
    val id: String,
    val parentRunId: String,
    val taskId: String,
    val goal: String,
    val modelId: String,
    val status: SubagentStatus,
    /** Monotonic per supervisor; wait() returns once any snapshot passes the caller's cursor. */
    val revision: Long = 0,
    val maxRounds: Int = 4,
    val rounds: Int = 0,
    val toolCalls: Int = 0,
    val failedToolCalls: Int = 0,
    /** What the child is doing now, e.g. "read_file src/App.kt"; empty when idle or finished. */
    val activity: String = "",
    val readPaths: List<String> = emptyList(),
    val allowedPaths: List<String> = listOf("."),
    val acceptedMessages: Int = 0,
    val processedMessages: Int = 0,
    val result: String? = null,
    val error: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    /** Provider-reported tokens of this child's own requests; null when the provider did not report them. */
    val usage: TokenUsage = TokenUsage(),
)

@Serializable
data class SubagentPage(val agents: List<SubagentSnapshot>, val cursor: Long)

@Serializable
data class SubagentMessageReceipt(val agentId: String, val messageId: String, val sequence: Long, val accepted: Boolean)

interface SubagentService {
    /** All children of the current session's recent runs, for the UI. Bounded. */
    val state: StateFlow<List<SubagentSnapshot>>
    /** Called by the parent loop before its first model request. */
    suspend fun begin(parentRunId: String)
    /** Stops every child of [parentRunId] that is still running. Idempotent. */
    suspend fun finish(parentRunId: String)
    /** Marks children that were running when the app stopped as interrupted; never restarts them. */
    suspend fun restore(history: List<StoredEvent>)
    suspend fun spawn(context: ToolContext, request: SubagentRequest): SubagentSnapshot
    suspend fun list(context: ToolContext): SubagentPage
    suspend fun send(context: ToolContext, agentId: String, messageId: String, text: String): SubagentMessageReceipt
    suspend fun wait(context: ToolContext, afterCursor: Long, timeoutMs: Long): SubagentPage
    suspend fun cancel(context: ToolContext, agentId: String): SubagentSnapshot
    suspend fun result(context: ToolContext, agentId: String): SubagentSnapshot
    /** The user's stop button in the UI; same terminal rules as [cancel]. */
    suspend fun cancelByUser(agentId: String): SubagentSnapshot?
}
