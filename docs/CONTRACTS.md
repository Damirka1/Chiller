# Chiller — общие контракты v0.1

> **Источник истины — код:** `contracts/src/main/kotlin/dev/chiller/contracts/Contracts.kt`.
> Этот документ описывает целевую форму контрактов и местами опережает реализацию
> (например, `RunId`/`ToolCallId`, `ProbeRequest`, `ToolCallDelta` ещё не введены).
> При расхождении выигрывает код; не создавай второй набор типов по этому файлу.

Этот документ задаёт форму будущего API. Приведённые Kotlin-сигнатуры — проект, а не уже существующий исходный код. Координатор материализует их в `:contracts` в задаче FND-02 и после компиляции объявляет контракт замороженным для первой волны.

## 1. Идентификаторы и время

Непересекающиеся value classes: WorkspaceId, SessionId, RunId, TurnId, ToolCallId, OperationId, SnapshotId, ArtifactId, ProcessId, QuestionId, ApprovalId. UUID/ULID — для durable IDs; короткие opaque handles — для model-facing snapshots, с серверной привязкой к исходному ID.

Timestamp в UTC, порядок по seq. TestClock позволяет проверять deadlines без wall-clock sleeps. Один пользовательский turn может породить несколько model rounds и множество tool calls. OperationId назначает приложение; provider call ID не является глобальным ключом идемпотентности.

## 2. Messages и provider payload

```kotlin
data class AgentMessage(
    val id: String,
    val role: MessageRole,
    val parts: List<ContentPart>,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: ToolCallId? = null,
    val replay: ProviderReplayPayload? = null,
)

data class ProviderReplayPayload(
    val providerProfileId: String,
    val formatVersion: Int,
    val data: JsonObject,
)
```

ContentPart: Text, ImageReference (artifact, MIME, dimensions), ToolResultReference при необходимости. ProviderReplayPayload содержит протокольно необходимые поля, например reasoning. Это private данные, не лог debug. Чужому провайдеру opaque payload не передавать автоматически.

Agent transcript и видимый UI transcript — разные проекции. Видимость reasoning и протокольная необходимость его сохранить — разные настройки. Если пользователь отключает durable replay payload, после restart недоступно точное продолжение соответствующего thinking history; UI предлагает начать новый контекст и отмечает ограничение. Нельзя заявить восстановление, отправив неполную историю.

Смена провайдера во время run запрещена. Между turns — новый контекст с явной сводкой и ссылками на изменения, без передачи несовместимых replay fields.

## 3. LLM порт и поток

```kotlin
interface LlmProvider {
    suspend fun probe(): CapabilityReport
    fun stream(request: ModelRequest): Flow<ModelEvent>
}
```

ModelRequest: model, ordered messages, enabled tools, thinking flag, max output tokens. Credentials разрешаются adapter-side через lambda и не попадают в toString/DTO.

ModelEvent (реализовано): TextDelta, ReasoningDelta, Usage, Completed, Failed. Usage приходит не более одного раза за запрос и несёт nullable `promptTokens`/`completionTokens`/`cachedPromptTokens`; неизвестные значения остаются null, а не нулём. Completed содержит финальную согласованную assistant message и finish reason. Только успешно собранные и проверенные вызовы из complete message передаются ToolExecutor. Truncated/length completion с неполным вызовом → failure, не action.

Поток имеет ровно одно terminal event либо coroutine cancellation. Consumer не предполагает последний текстовый delta обязательным. Raw transport bytes не попадают в доменные события. Повтор stateless model-запроса при 429/5xx допустим только до первого delta; повтор tool-эффекта — никогда.

## 4. Tools и результаты

```kotlin
interface ToolExecutor {
    suspend fun execute(request: ToolExecutionRequest): ToolOutcome
}

data class ToolExecutionRequest(
    val runId: RunId,
    val callId: ToolCallId,
    val operationId: OperationId,
    val name: String,
    val arguments: JsonObject,
    val context: ToolContext,
)
```

ToolOutcome: Success(data, meta), Failure(error, meta), Cancelled(meta), UnknownOutcome(recoveryRef, meta). ToolContext включает workspace identity, policy grant, budget, session-scoped ports и coroutine context; не всю конфигурацию приложения/ключи.

Meta: duration, truncated, nextCursor, artifactRefs, effectReceipt. EffectReceipt содержит operation ID, state prepared/committed/failed/unknown, changeRefs или process reference. Preview не считается committed.

ToolError: code, message, retryable, recovery hint. INVALID_ARGUMENTS имеет field path. Исключение не возвращается как гигантский stacktrace модели. Системный лог отдельно, с redaction.

Основной envelope и кодировки ошибок — CHILLER_PLAN.md. Исполнитель и schemas не изменяют смысл arguments между validation и execute. Approval привязан к хешу запланированного действия и сроку/области grant, а не только к имени tool.

## 5. Workspace API

```kotlin
interface WorkspaceService {
    suspend fun read(request: ReadFileRequest): FileReadResult
    suspend fun list(request: ListFilesRequest): FileListing
    suspend fun search(request: SearchRequest): SearchPage
    suspend fun planEdit(request: EditFileRequest): PlannedFileChange
    suspend fun apply(change: PlannedFileChange, grant: WriteGrant): AppliedFileChange
    suspend fun undo(request: UndoChangeRequest): UndoResult
}
```

Create/move/delete используют отдельные typed requests и тот же EffectReceipt. Путь — WorkspacePath, а не произвольная java.nio.Path из UI.

**Реализовано сейчас** (`Contracts.kt`): `replaceText(path, snapshotId, oldText, newText, expectedCount = 1)` (добавлено 17.10 с default `UNSUPPORTED`; точное совпадение без нормализации, иначе `NO_MATCH`/`MATCH_COUNT_MISMATCH`; совпадения превращаются в построчные правки того же snapshot), `read`, `list(path, glob, limit)`, `search(query, path, glob, regex, limit, context)`, `edit`, `create`, `move`, `delete`, `undo`; отдельного `plan/apply` нет. `list` возвращает `entries: [string]` (относительные пути), `search` — `matches: [{path,line,text,context?}]` с обрезкой строки до 400 символов, успешный `edit` возвращает `snapshotId` новой версии файла: следующая правка того же файла может использовать его без повторного чтения. `observed`-строки переносятся через применённые диапазоны, а расхождение хеша по-прежнему даёт `STALE_SNAPSHOT`.

FileSnapshot: workspace/path binding, revision hash, size, encoding, EOL, lineCount, observedRanges, createdAt. Snapshot ID не переиспользуется для других байтов. Новое чтение другой части того же snapshot может расширить observedRanges только после проверки текущих байтов.

FileEdit: Replace(startLine,endLine,content), Insert(beforeLine,content), Delete(startLine,endLine). JSON schema model-facing остаётся простой и документированной; конкретный sealed class не навязывает provider-unsupported oneOf. Номера 1-based inclusive, исходный snapshot один для всего batch. Новое содержимое не содержит декоративных номеров строк.

PlannedFileChange: before/after hash, candidate artifact, diff artifact, target identity, operation ID. Между plan и apply обязательна повторная проверка текущего файла. При изменении grant/файла старый plan недействителен. AtomicReplaceUnsupported — явный результат; не делать вид, что получена транзакционная гарантия.

Контрольные копии хранят исходные байты и необходимую metadata. Файловая запись и журнал координируются через durable prepare/commit. Не делать успешный tool result до фактического committed outcome.

## 6. Process API

```kotlin
interface ProcessService {
    suspend fun start(request: StartProcessRequest): ProcessStartResult
    suspend fun read(request: ReadProcessRequest): ProcessOutputPage
    suspend fun stop(request: StopProcessRequest): ProcessStopResult
}
```

StartProcessRequest: argv, workspace/cwd, environment additions, foreground/background, timeout, output cap, grant, run/operation ID. Не объединять argv в shell string. Shell включается только через явный executable/argv и policy.

ReadProcessRequest: opaque process ID и cursor, bounded wait. Page: ordered chunks с channel stdout/stderr, nextCursor, state, exitCode?, truncated/incomplete. Объединённый порядок chunks — порядок наблюдения, а не обещание точного глобального порядка записи процессов.

Start возвращает ID после регистрации процесса. Timeout/stop вносит terminal state и effect receipt. Background service не исчезает из UI после завершения ответа модели. После crash состояние unknown до проверки идентичности; повторное использование PID недопустимо.

## 7. Journal и artifacts

```kotlin
interface EventStore {
    suspend fun append(event: PendingEvent, durability: Durability): StoredEvent
    fun read(sessionId: SessionId, afterSeq: Long): Flow<StoredEvent>
}

interface ArtifactStore {
    suspend fun put(input: ArtifactInput): ArtifactRef
    suspend fun read(request: ArtifactReadRequest): ArtifactPage
}
```

seq назначает store при append. Unique event ID и session seq гарантируются single writer. Critical append возвращает управление после согласованной durability boundary. Дельты можно буферизовать; допустимая потеря незавершённого streaming tail отражается в interrupted state.

Artifacts immutable, scoped by session and access grants, имеют MIME/size/hash. Cursor относится к конкретной версии artifact; размер страницы ограничен host. Чтение artifact не даёт доступ к другим session IDs по угадыванию.

Recovery — reconciliation, не повторное исполнение журнала. UI replay воспроизводит события; operation replay разрешается только после проверки отсутствия effect или явного нового действия пользователя.

## 8. Questions и approvals

InteractionBroker: suspend awaitAnswer(question), awaitApproval(actionPreview), cancelPending(run). Pending interaction сохраняется до выдачи UI. Ответ содержит ID и revision запроса; поздний ответ на отменённый run отвергается. Закрытие диалога/истечение времени не является согласием.

UserQuestion и ActionApproval — разные сущности. Ask-user tool не может сам выдать себе permission. При общении пользователя с активным run steering сохраняется в отдельной очереди и применяется на определённой границе цикла; не вставлять новый user message внутрь незавершённой tool-call group.

## 9. Budgets и контекст

RunBudget: maxRounds, maxWallTime, maxInput/OutputTokens, maxToolOutputBytes, liveCostCeiling?; observed and estimated usage раздельны. До live request резервировать upper bound output, учитывать retries/probes. Неизвестная цена не равна бесплатности; при невозможности оценить cost cap нужен token/request cap и явная отметка ограничений.

Compaction работает только с завершёнными группами сообщений. Не разрывать assistant tool calls / tool results. Replay payload у оставшихся сообщений сохраняется согласно профилю. Начальный вариант — deterministic context selection и ограниченная сводка; дополнительный LLM summarizer не нужен для первого вертикального среза.

## 10. Заморозка и изменение API

После FND-02 публикуются commit ID, список DTO/ports и набор examples. Worker сообщает минимальное change request: проблема, предлагаемая сигнатура, потребители и совместимость. Координатор меняет contracts, обновляет fixtures и уведомляет все затронутые направления. До этого worker использует существующий mock/adapter, не создаёт дублирующий public API.

Обязательные fixtures: complete tool conversation, stale-edit failure, interrupted operation, pending user question, DeepSeek replay payload, missing usage. Все module tests используют именно общий API, а не собственную копию модели.
