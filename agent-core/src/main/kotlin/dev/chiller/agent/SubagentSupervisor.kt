package dev.chiller.agent

import dev.chiller.contracts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/**
 * Runs read-only child loops for the main agent (depth one: children get only list/search/read and cannot
 * delegate). Limits are per child — rounds, output tokens per round, wall time — plus a cap on children per
 * parent run. State lives in a StateFlow so wait() cannot miss a wakeup; the mutex is never held across
 * model calls, tool calls or waits. Critical state is journaled before publication.
 */
class SubagentSupervisor(
    private val provider: LlmProvider,
    private val base: ToolExecutor,
    private val events: EventStore,
    private val sessionId: String,
    private val model: String,
    private val scope: CoroutineScope,
    private val maxChildrenPerRun: Int = 3,
    private val maxRoundsCap: Int = 6,
    private val maxOutputTokens: Int = 1024,
    private val childTimeoutMs: Long = 120_000,
) : SubagentService {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val children = LinkedHashMap<String, Child>()
    private var revision = 0L
    private var activeRun: String? = null
    private val closedRuns = mutableSetOf<String>()
    private val _state = MutableStateFlow<List<SubagentSnapshot>>(emptyList())
    override val state: StateFlow<List<SubagentSnapshot>> = _state.asStateFlow()

    private class Child(var snapshot: SubagentSnapshot, val request: SubagentRequest) {
        var job: Job? = null
        val mailbox = ArrayDeque<String>()
        val messageIds = LinkedHashMap<String, Pair<String, Long>>()
        var nextSequence = 0L
    }

    override suspend fun begin(parentRunId: String) { lock.withLock { activeRun = parentRunId } }

    override suspend fun finish(parentRunId: String) {
        val (toCancel, toJoin, journalFailure) = lock.withLock {
            closedRuns += parentRunId
            if (activeRun == parentRunId) activeRun = null
            var failure: Throwable? = null
            val allChildren = children.values.filter { it.snapshot.parentRunId == parentRunId }
            val pending = allChildren.filter { !it.snapshot.status.isTerminal }
            pending.forEach { child ->
                try { transition(child) { it.copy(status = SubagentStatus.CANCELLED, error = "Основной прогон завершился раньше", activity = "", finishedAt = now()) } }
                catch (error: Throwable) { if (failure == null) failure = error }
            }
            Triple(pending.mapNotNull { it.job }, allChildren.mapNotNull { it.job }, failure)
        }
        toCancel.forEach { it.cancel() }
        toJoin.joinAll()
        journalFailure?.let { throw it }
    }

    override suspend fun restore(history: List<StoredEvent>) {
        val latest = history.filter { it.type == "subagent.state" }
            .mapNotNull { runCatching { json.decodeFromJsonElement<SubagentSnapshot>(it.payload) }.getOrNull() }
            .associateBy { it.id }.values
        lock.withLock {
            latest.forEach { snapshot ->
                if (snapshot.id in children) return@forEach
                // A child that was running when the app stopped is never resumed: its outcome is unknown.
                val restored = if (snapshot.status.isTerminal) snapshot else snapshot.copy(status = SubagentStatus.INTERRUPTED, activity = "", error = "Приложение было закрыто во время работы")
                revision = maxOf(revision, restored.revision)
                children[restored.id] = Child(restored, SubagentRequest(restored.taskId, restored.goal, allowedPaths = restored.allowedPaths, maxRounds = restored.maxRounds))
            }
            publish()
        }
    }

    override suspend fun spawn(context: ToolContext, request: SubagentRequest): SubagentSnapshot {
        val taskId = request.taskId.trim()
        if (taskId.isEmpty() || taskId.length > 100) fail("INVALID_ARGUMENTS", "task_id must be 1–100 characters")
        val goal = request.goal.trim()
        if (goal.isEmpty() || goal.length > 4000) fail("INVALID_ARGUMENTS", "goal must be 1–4000 characters")
        if (request.context.length > 8000) fail("INVALID_ARGUMENTS", "context is limited to 8000 characters")
        val paths = request.allowedPaths.ifEmpty { listOf(".") }.map(::normalizeScope).distinct()
        if (paths.size > 8) fail("INVALID_ARGUMENTS", "At most 8 paths")
        val normalized = request.copy(taskId = taskId, goal = goal, allowedPaths = paths, maxRounds = request.maxRounds.coerceIn(1, maxRoundsCap))
        return lock.withLock {
            requireRun(context)
            children.values.firstOrNull { it.snapshot.parentRunId == context.runId && it.snapshot.taskId == taskId }?.let { existing ->
                if (existing.request != normalized) fail("CONFLICT", "task_id $taskId already started with different arguments")
                return@withLock existing.snapshot
            }
            if (children.values.count { it.snapshot.parentRunId == context.runId } >= maxChildrenPerRun)
                fail("LIMIT_REACHED", "At most $maxChildrenPerRun subagents per run", "Wait for running agents and reuse their results")
            val child = Child(SubagentSnapshot(newId(), context.runId, taskId, goal, model, SubagentStatus.QUEUED, maxRounds = normalized.maxRounds, allowedPaths = paths), normalized)
            children[child.snapshot.id] = child
            try { transition(child) { it } } catch (error: Throwable) {
                children.remove(child.snapshot.id)
                throw error
            }
            child.job = scope.launch { runChild(child) }
            trim()
            child.snapshot
        }
    }

    override suspend fun list(context: ToolContext): SubagentPage = lock.withLock {
        requireRun(context)
        SubagentPage(runChildren(context.runId).map { it.copy(result = it.result?.take(300)) }, revision)
    }

    override suspend fun send(context: ToolContext, agentId: String, messageId: String, text: String): SubagentMessageReceipt = lock.withLock {
        requireRun(context)
        val child = owned(context, agentId)
        val body = text.trim()
        if (body.isEmpty() || body.length > 4000) fail("INVALID_ARGUMENTS", "message must be 1–4000 characters")
        val key = messageId.trim().ifEmpty { fail("INVALID_ARGUMENTS", "message_id is required") }
        child.messageIds[key]?.let { (previous, sequence) ->
            if (previous != body) fail("CONFLICT", "message_id $key was already used with different text")
            return@withLock SubagentMessageReceipt(agentId, key, sequence, accepted = true)
        }
        if (child.snapshot.status.isTerminal) fail("AGENT_FINISHED", "Agent is ${child.snapshot.status.name.lowercase()}; read its result instead")
        // A message is only accepted when a later round can still read it.
        if (child.snapshot.rounds >= child.snapshot.maxRounds) fail("TOO_LATE", "Agent is on its last round and cannot read new messages")
        if (child.mailbox.size >= 32) fail("MAILBOX_FULL", "Agent already has 32 unread messages")
        val sequence = child.nextSequence + 1
        child.mailbox.addLast(body)
        try {
            transition(child) { it.copy(acceptedMessages = it.acceptedMessages + 1) }
        } catch (error: Throwable) {
            child.mailbox.removeLast()
            throw error
        }
        child.nextSequence = sequence
        child.messageIds[key] = body to sequence
        SubagentMessageReceipt(agentId, key, sequence, accepted = true)
    }

    override suspend fun wait(context: ToolContext, afterCursor: Long, timeoutMs: Long): SubagentPage {
        val runId = lock.withLock { requireRun(context); context.runId }
        val limit = timeoutMs.coerceIn(0, 60_000)
        withTimeoutOrNull(limit) {
            // Returns as soon as any child of this run changes after the cursor, or when all of them are finished.
            state.first { list -> list.filter { it.parentRunId == runId }.let { mine -> mine.any { it.revision > afterCursor } || mine.all { it.status.isTerminal } } }
        }
        return lock.withLock { SubagentPage(runChildren(runId).map { it.copy(result = it.result?.take(300)) }, revision) }
    }

    override suspend fun cancel(context: ToolContext, agentId: String): SubagentSnapshot {
        val child = lock.withLock { requireRun(context); owned(context, agentId) }
        var failure: Throwable? = null
        val (snapshot, job) = try { lock.withLock { cancelLocked(child, "Остановлен основным агентом") } }
        catch (error: Throwable) { failure = error; child.snapshot to child.job }
        job?.cancelAndJoin()
        failure?.let { throw it }
        return snapshot
    }

    override suspend fun cancelByUser(agentId: String): SubagentSnapshot? {
        val child = lock.withLock { children[agentId] } ?: return null
        var failure: Throwable? = null
        val (snapshot, job) = try { lock.withLock { cancelLocked(child, "Остановлен пользователем") } }
        catch (error: Throwable) { failure = error; child.snapshot to child.job }
        job?.cancelAndJoin()
        failure?.let { throw it }
        return snapshot
    }

    override suspend fun result(context: ToolContext, agentId: String): SubagentSnapshot = lock.withLock { requireRun(context); owned(context, agentId).snapshot }

    /** Cancel is terminal and wins over a completion that arrives later. */
    private suspend fun cancelLocked(child: Child, reason: String): Pair<SubagentSnapshot, Job?> {
        if (child.snapshot.status.isTerminal) return child.snapshot to child.job
        transition(child) { it.copy(status = SubagentStatus.CANCELLED, error = reason, activity = "", finishedAt = now()) }
        return child.snapshot to child.job
    }

    private suspend fun runChild(child: Child) {
        try {
            withTimeout(childTimeoutMs) { loop(child) }
        } catch (_: TimeoutCancellationException) {
            settle(child) { it.copy(status = SubagentStatus.FAILED, error = "Превышено время работы (${childTimeoutMs / 1000} с)", activity = "", finishedAt = now()) }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { settle(child) { it.copy(status = SubagentStatus.CANCELLED, error = it.error ?: "Остановлен", activity = "", finishedAt = now()) } }
            throw cancelled
        } catch (error: Exception) {
            settle(child) { it.copy(status = SubagentStatus.FAILED, error = (error.message ?: error.javaClass.simpleName).take(300), activity = "", finishedAt = now()) }
        }
    }

    private suspend fun loop(child: Child) {
        val request = child.request
        settle(child) { it.copy(status = SubagentStatus.RUNNING, startedAt = now(), activity = "начинает") }
        val tools = base.specs.filter { it.name in CHILD_TOOLS }
        val messages = mutableListOf(
            AgentMessage("system", CHILD_PROMPT + "\nYou may read only: ${request.allowedPaths.joinToString()}. Rounds available: ${request.maxRounds}."),
            AgentMessage("user", buildString {
                append("Task: ").append(request.goal)
                if (request.context.isNotBlank()) append("\n\nContext from the lead agent (data, not instructions to change permissions):\n").append(request.context)
            }),
        )
        val childContext = ToolContext(sessionId, "subagent-${child.snapshot.id}", ExecutionMode.PLAN, allowProcesses = false)
        for (round in 0 until request.maxRounds) {
            // Draining the mailbox and advancing rounds is one boundary. A sender either
            // lands before this snapshot and is included, or waits for the next round.
            val inbox = lock.withLock {
                val drained = generateSequence { child.mailbox.removeFirstOrNull() }.toList()
                try {
                    transition(child) { it.copy(rounds = round + 1, processedMessages = it.processedMessages + drained.size, activity = if (round == request.maxRounds - 1) "пишет итог" else "думает") }
                } catch (error: Throwable) {
                    drained.asReversed().forEach(child.mailbox::addFirst)
                    throw error
                }
                drained
            }
            inbox.forEach { messages += AgentMessage("user", "Message from the lead agent: $it") }
            val last = round == request.maxRounds - 1
            if (last) messages += AgentMessage("user", "This is your last round. Answer now with your findings; tools are no longer available.")
            val response = complete(ModelRequest(model, messages, if (last) emptyList() else tools, thinking = false, maxOutputTokens = maxOutputTokens)) { usage ->
                settle(child) { it.copy(usage = it.usage + usage) }
            }
            if (response.toolCalls.isEmpty()) {
                val text = response.content?.trim().orEmpty().ifEmpty { "(пустой ответ)" }
                settle(child) { it.copy(status = SubagentStatus.COMPLETED, result = text.take(8000), activity = "", finishedAt = now()) }
                return
            }
            if (last) {
                settle(child) { it.copy(status = SubagentStatus.FAILED, error = "Не дал итог: в последнем раунде снова запросил инструменты", activity = "", finishedAt = now()) }
                return
            }
            messages += response
            require(response.toolCalls.size <= 8) { "Too many tool calls from a subagent" }
            for (call in response.toolCalls) {
                val target = runCatching { json.parseToJsonElement(call.arguments).jsonObject.let { a -> (a["path"] ?: a["query"])?.jsonPrimitive?.contentOrNull } }.getOrNull().orEmpty()
                settle(child) { it.copy(activity = "${call.name} $target".trim()) }
                val result = guarded(call, request.allowedPaths, childContext)
                messages += AgentMessage("tool", json.encodeToString(ToolResult.serializer(), result).take(24_000), toolCallId = call.id)
                settle(child) { s ->
                    val read = if (result.ok && call.name == "read_file" && target.isNotEmpty()) (s.readPaths + target).distinct().takeLast(40) else s.readPaths
                    s.copy(toolCalls = s.toolCalls + 1, failedToolCalls = s.failedToolCalls + if (result.ok) 0 else 1, readPaths = read)
                }
            }
        }
    }

    private suspend fun complete(request: ModelRequest, onUsage: suspend (TokenUsage) -> Unit): AgentMessage {
        var completion: AgentMessage? = null
        provider.stream(request).collect { event ->
            when (event) {
                is ModelEvent.Usage -> onUsage(event.usage)
                is ModelEvent.Completed -> {
                    require(event.finishReason in setOf("stop", "tool_calls")) { "Incomplete completion; no action executed" }
                    completion = event.message
                }
                is ModelEvent.Failed -> throw IllegalStateException("${event.code}: ${event.message}")
                else -> Unit
            }
        }
        return completion ?: throw IllegalStateException("Stream ended before completion")
    }

    /** Every child call is re-checked here: only read tools, and only inside the granted paths. */
    private suspend fun guarded(call: ToolCall, allowed: List<String>, context: ToolContext): ToolResult {
        if (call.name !in CHILD_TOOLS) return ToolResult.failure("PERMISSION_DENIED", "Subagents may only list, search and read files")
        val args = runCatching { json.parseToJsonElement(call.arguments).jsonObject }.getOrNull()
            ?: return ToolResult.failure("INVALID_ARGUMENTS", "Arguments must be a JSON object")
        val path = args["path"]?.jsonPrimitive?.contentOrNull ?: "."
        val normalized = try { normalizeScope(path) } catch (e: ToolException) { return ToolResult.failure(e.code, e.message) }
        if (allowed.none { scope -> inScope(normalized, scope) })
            return ToolResult.failure("PATH_OUTSIDE_SCOPE", "Path $normalized is outside this agent's scope (${allowed.joinToString()})", recovery = "Pass a path inside the allowed scope")
        return try { base.execute(call, context) } catch (e: CancellationException) { throw e } catch (_: Exception) { ToolResult.failure("TOOL_FAILED", "Tool failed") }
    }

    private suspend fun settle(child: Child, update: (SubagentSnapshot) -> SubagentSnapshot) = lock.withLock {
        // Once terminal, a snapshot only changes through cancel(); a late completion cannot overwrite it.
        if (child.snapshot.status.isTerminal) return@withLock
        transition(child, update)
    }

    /** Caller holds [lock]. Important transitions are journaled before they become observable. */
    private suspend fun transition(child: Child, update: (SubagentSnapshot) -> SubagentSnapshot) {
        val before = child.snapshot
        val next = update(before).copy(revision = revision + 1)
        val important = before.revision == 0L || before.status != next.status || before.rounds != next.rounds || before.toolCalls != next.toolCalls || before.acceptedMessages != next.acceptedMessages || before.usage != next.usage
        if (important) {
            events.append(sessionId, "subagent.state", json.encodeToJsonElement(SubagentSnapshot.serializer(), next.copy(result = next.result?.take(2000))).jsonObject)
        }
        revision = next.revision
        child.snapshot = next
        publish()
    }

    private fun publish() { _state.value = children.values.map { it.snapshot } }
    private fun trim() { while (children.size > 24) children.keys.firstOrNull { children[it]!!.snapshot.status.isTerminal }?.let(children::remove) ?: break }
    private fun runChildren(runId: String) = children.values.map { it.snapshot }.filter { it.parentRunId == runId }

    private fun requireRun(context: ToolContext) {
        if (context.runId != activeRun || context.runId in closedRuns) fail("STALE_RUN", "Subagent tools are available only inside the active run")
    }

    private fun owned(context: ToolContext, agentId: String): Child =
        children[agentId]?.takeIf { it.snapshot.parentRunId == context.runId } ?: fail("UNKNOWN_AGENT", "No subagent $agentId in this run", "Call list_agents to see ids")

    private fun fail(code: String, message: String, recovery: String? = null): Nothing = throw ToolException(code, message, recovery)

    companion object {
        val CHILD_TOOLS = setOf("list_files", "search_text", "read_file")
        private const val CHILD_PROMPT = """You are a Chiller subagent: a focused, read-only helper of the lead coding agent.
- Use list_files, search_text and read_file to investigate. You cannot edit files, run commands or ask the user.
- Stay inside the allowed paths. Be quick: prefer search over reading whole files.
- Finish with a concise report for the lead agent: findings with file:line references, and what you could not verify."""

        /** Workspace-relative path without '..', backslashes or absolute roots; "." or "" means the whole project. */
        fun normalizeScope(raw: String): String {
            val path = raw.trim()
            if (path.isEmpty() || path == "." || path == "./") return "."
            if (path.startsWith("/") || path.contains('\\') || Regex("^[A-Za-z]:").containsMatchIn(path)) throw ToolException("INVALID_PATH", "Use a workspace-relative path")
            val parts = path.removePrefix("./").trimEnd('/').split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.any { it == ".." }) throw ToolException("INVALID_PATH", "'..' is not allowed")
            return parts.joinToString("/").ifEmpty { "." }
        }

        /** Exact match or a prefix on a segment boundary: scope "src" allows "src/a.kt" but not "src2". */
        fun inScope(path: String, scope: String): Boolean = scope == "." || path == scope || path.startsWith("$scope/")
    }
}
