# Аудит перед продолжением — 17 октября 2026

Начало: 14:43 Asia/Almaty. Пользователь разрешил начать раньше после сброса лимитов. Сохраняется 30-минутная фаза анализа без изменения продуктового кода; реализация — не раньше 15:13. Итоговый дедлайн остаётся 20:00. Это уточнение времени имеет приоритет над прежним ожиданием 15:00 в EVENING_PLAN.

## Проверяемая исходная точка

- master, последний план 36e2072, implementation checkpoint 4639000.
- До начала аудита уже существует одна незакоммиченная правка ChillerApp.kt: квалификация `this@BoxWithConstraints.maxWidth`. Сохранить её при переработке composer.
- Последний полный зелёный прогон: 81 тест + offlineSmoke + macOS package. Позднейшая правка геометрии в checkpoint не проверена точной сборкой. Во время planning-фазы сборки не запускались.
- Три экономичных исполнителя сделали независимые read-only обзоры UI, каталога и делегации. Реализация им пока не назначена.

## Матрица фактического состояния

| Область | Найдено в коде | Пробел / решение |
|---|---|---|
| Composer | ChillerApp.kt: min текст48 + padding24 + gap10 + toolbar30 ≈112+ dp | Начальная высота80–88, соседние режим/модель, content-sized chevron, adaptive wrap |
| Draft/send | SendReceipt после durable append; snapshot match, IME guard, dedup500 | Сохранить логику при визуальной правке, regression отказа/двойной отправки |
| Studio/scale | Sidebar увеличивается до397dp при scale2; fixed48 header | Ограничить раздувание sidebar, позволить перенос/рост header; pointer drag ещё не принят |
| Каталог | configuredModels возвращает selected+remembered, максимум20 | Реальный GET discovery отсутствует; отдельный provider client + explicit refresh и состояния |
| Endpoint/ключ | Controller сбрасывает credential при смене origin; checker использует revision | Переиспользовать подход для discovery, отдельная защита stale cache при смене ключа |
| Provider | bounded SSE, incomplete calls отвергаются; replay сохраняется | observed usage не выдаётся в ModelEvent, profile capabilities частично неизвестны; не подменять их каталогом |
| Loop | AgentCoordinator24 rounds/10min, maxOutput4096/round | Нет общего бюджета для детей; добавить консервативный ledger до делегации |
| Субагенты | Контрактов/супервизора/инструментов нет | Новый законченный read-only lifecycle без рекурсии, с реальными fixture loops |
| Политика | PLAN допускает READ и USER_INTERACTION; READ включает process/artifact | Детям разрешить только list_files/search_text/read_file; отдельные path scopes и deny других tools |
| История | Jsonl fsync, проверка seq/schema, torn-tail recovery | append перечитывает всю историю, list читает все сессии; не создавать детям видимые synthetic sessions |
| Вопросы | PendingQuestions живёт в памяти; restore показывает read-only prompt | Durable продолжение вопросов пока отсутствует, отдельный P1 после новой интеграции |
| Файлы | snapshot observed lines/hash; prepare/commit/reconcile; undo conflict | Watcher отсутствует; move только внутри директории; ACL/xattrs не сохраняются; platform matrix не готова |
| Процессы | Два pipe drain, timeout/caps, best-effort descendants | stopRun исключает background; нет durable registry/OS containment. Детям процессы запрещены |
| Данные | bounded artifacts, markdown/json export, schemaVersion1 | Нет retention/migrations; улучшение производительности journal отдельным небольшим шагом при необходимости |
| Приёмка | Real offline search/read/edit/Python/restart/stale smoke | Добавить реальный read-only delegated smoke; live provider loop, Gemma, IME/VoiceOver и Windows/Linux остаются непроверенными |

## Архитектурные решения до кода

### Каталог

Provider-owned интерфейс `ModelCatalogClient` с suspend discover(baseUrl, key?, modelsUrl?) и типизированным результатом: exact model ID, optional bounded displayName, server-declared context/output metadata; status/error отдельно. Ключ не является частью сериализуемых данных. Реализация владеет HttpClient с redirects=false и deadline; один GET, без fallback-перебора адресов. Generic root/gateway prefix получает /v1/models, уже versioned base получает /models. Явный URL допускается только того же origin, иначе ошибка до сети.

Данные каталога: до500 моделей, до1MiB ответа, ID/name до200 символов. Неверная обязательная форма отклоняется; необязательные повреждённые метаданные игнорируются, без угадывания возможностей. Не создавать модель из displayName. Server-declared лимиты не увеличивают автоматически бюджет приложения.

Controller: injected client, per-endpoint/override cache в памяти, explicit refresh и cancellation/revision guard. Смена credential очищает cache, чтобы не показывать доступ прежней учётной записи. Успех меняет last-success timestamp; ошибка сохраняет последний каталог с пометкой stale. Выбранный/manual ID сохраняется при empty/404. Настройки содержат models URL override без секрета. UI показывает name и точный ID, источник API/вручную и отдельное состояние загрузки. Список не означает успешный chat request.

Официальная документация DeepSeek описывает GET /models и optional name/context_window/max_output_tokens. Источник: https://api-docs.deepseek.com/api/list-models/ (поиск официальной страницы 17 октября; прямое открытие timeout). Для прямого профиля разрешён явный /models URL; generic /v1 не дублировать. Конкретные доступные аккаунту версии не проверялись.

### Субагенты

Добавить contracts/Subagents.kt, не смешивать lifecycle с wire DTO. Фиксируются request/status/result/message/page и порт SubagentService. Shared RequestBudget с reserve и immutable usage — отдельный контракт; реализация ledger в agent-core. Сохранить существующие публичные constructors через default optional параметры.

Supervisor в agent-core получает provider, read-only executor, EventStore, sessionId, model allowlist, default model и scope. Сам исполняет небольшой bounded child loop; не создаёт рекурсивный AgentCoordinator, не получает API key. Child tools: только чтение файлов в выданных scope, без processes/ask_user/delegation/artifact доступа чужой сессии. Каждое действие проходит проверки даже если модель вызвала tool вне переданного каталога.

До каждого parent/child model call атомарно резервируются request count, estimated input tokens и max output tokens. Первые лимиты консервативны: общий parent run максимум24 requests и65536 reserved output tokens, input estimate bounded; ребёнок максимум6 rounds/1024 output за round/120s; всего3 детей на parent run. Эти числа не являются денежным лимитом или измеренным usage. Ошибка резерва не запускает запрос; резерв при отмене не возвращается без достоверного исхода.

Журналировать `subagent.state` и `subagent.message` в родительской сессии с parentRunId/agentId/revision. Не писать дочерние `message` в основной transcript и не создавать отдельные строки истории для дочерних сессий. После restart все незавершённые дети — interrupted, без запуска provider. Result bounded; не показывать непроверенные заявления модели как выполненные тесты.

Mailbox: messageId+agentId dedup, FIFO, максимум32 сообщения ограниченной длины. Принимать только для активного ребёнка текущего parent run. Применять сообщения на безопасной границе полного tool round; message acceptance и terminal transition синхронизированы. Принятое до завершения сообщение нельзя молча потерять: либо обработать следующим round, либо явно показать interrupted/cancelled. Сведения детей считаются данными, а не новыми разрешениями.

Ожидание — StateFlow revision + timeout, без polling. Каждая команда проверяет parentRunId и agentId; wait/list/result bounded. Cancel терминален и побеждает поздний completion; отмена/ошибка/успех parent завершает оставшихся детей. Не удерживать supervisor mutex во время network/model/tool execution, wait или cancelAndJoin. Durable event append разрешён внутри короткой state transition, без обратного захвата controller locks.

Path scope: нормализованные относительные пути без '..', backslash, absolute; scope '.' разрешает корень, иначе требуется точное совпадение или segment-boundary prefix. search/list получают явный path внутри scope; glob не расширяет search root. LocalWorkspace повторно проверяет symlink/outside-root. Запись не входит в первую поставку, до leases/serialization/conflict tests.

## Распределение после planning gate

1. Coordinator: shared contracts, ledger, parent lifecycle, DesktopController/Main, интеграционные smoke/тесты, Git/Gradle. Перед началом workers публикует signatures.
2. Composer worker: только ui/** и ChillerControls.kt/ChillerTheme.kt по необходимости. Сохранить исходную пользовательскую правку; D1–D4, затем UI каталога/детей после объявления controller API.
3. Catalog worker: только providers/**, ModelCatalogClient/implementation и fixtures. Не меняет root build/contracts/controller.
4. Supervisor worker: новый agent-core/SubagentSupervisor.kt и его tests; новый tools/SubagentTools.kt и tests после заморозки контрактов. Не редактирует AgentCoordinator или BuiltinTools.

## Gates

- G0: чтение/аудит/план завершены; код до конца30мин не изменяется.
- G1: существующая baseline компилируется; D1 и M1 targeted tests зелёные, ручной ввод сохранён.
- G2: parent+2 children fixture с настоящим чтением disposable workspace; mailbox/wait/result/cancel/budget/path/restore проверки. No orphan child после parent terminal.
- G3: native preview обе темы/layout и representative scale matrix; screenshot artifacts; честно разделить проверенные и ручные ограничения.
- G4: один общий check + offlineSmoke + distributable после последней правки; локальный commit и RUN_REPORT. Не расширять scope ради занятости агентов.

## Дополнительные решения после adversarial review

1. Общий output reserve65536 намеренно может остановить родителя раньше24 rounds: parent request явно4096, child1024. UI называет это резервом, не фактически потраченными токенами. Лимит считается по родителю и всем детям атомарно; повторная попытка после transport error тоже требует нового резерва.
2. Mailbox ack содержит accepted и sequence; одинаковый messageId с другим телом — конфликт. На последнем допустимом раунде новые сообщения не принимаются, если следующий раунд уже невозможен. Принятое сообщение отражается в обработанном cursor или в явном terminal limitation, а не теряется.
3. Wait cursor — seq родительского журнала. И mailbox, и state transitions его продвигают. StateFlow должен предотвращать lost wakeup: ожидать актуальный value>cursor, не разовое событие без replay.
4. Parent terminal event записывается после остановки оставшихся children. finish идемпотентен; сначала closing flag и snapshot jobs под lock, затем cancel/join вне lock. Stale task/run ID не может отменить ребёнка нового run.
5. DTO/счётчики валидируются на отрицательные/слишком большие значения и overflow. Не кастовать Long в Int до проверки. Ключ API не хранится в data class request/result/cache/toString.
6. Child executor не должен доверять переданным specs: permission/path check повторяется при execute. Например, scope src не разрешает src2; '..' отклоняется до normalize. Default path '.' отклоняется для scope src, а не незаметно расширяется.
7. Catalog refresh работает только по явному действию, отдельному от платного connection check. Изменённые несохранённые поля не должны отправлять ключ по старому адресу; UI ясно указывает, для какого сохранённого профиля получен результат.
8. Preserved source edit `this@BoxWithConstraints` — квалификация receiver, не новая feature; первый build проверит её вместе с последней geometry правкой. Уже запущенный preview может быть старее исходников; его снимок — baseline appearance, не подтверждение сборки checkpoint.

## Не расширяем вечерний scope без доказанной необходимости

Read-only аудит дополнительно обнаружил: локальный vLLM без auth сейчас не поддержан полноценно, потому что provider и controller считают отсутствие credential offline-режимом; usage из SSE не проецируется в общий контракт; настройки профиля записываются простым writeString без atomic replacement. Это реальные отдельные задачи следующего этапа, а не повод одновременно менять protocol/auth/storage при разработке M1/A1. Child model selection остаётся в allowlist выбранного endpoint, без cross-provider credentials и автоматического failover.

Долгосрочные цели: явные auth modes для localhost, observed usage/requested-vs-returned model metadata, versioned atomic settings, масштабируемый journal index, durable user interactions, filesystem watcher, Keychain. Проверки безопасности записи и отмены приоритетнее увеличения количества функций.

## Предлагаемая заморозка API для реализации после planning gate

Окончательный source контракт публикует координатор в contracts/Subagents.kt; до этого worker не пишет заменяющие типы.

```kotlin
data class RequestUsage(val requests: Int = 0,
    val estimatedInputTokens: Long = 0, val reservedOutputTokens: Long = 0)
interface ModelRequestBudget {
    val usage: RequestUsage
    fun reserve(estimatedInputTokens: Long, maxOutputTokens: Int): RequestUsage
}
enum class SubagentStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }
data class SubagentRequest(val taskId: String, val goal: String,
    val modelId: String? = null, val context: String = "",
    val allowedPaths: List<String> = listOf("."), val maxRounds: Int = 4)
data class SubagentSnapshot(val id: String, val parentRunId: String,
    val taskId: String, val goal: String, val modelId: String,
    val status: SubagentStatus, val revision: Long = 0,
    val usage: RequestUsage = RequestUsage(), val result: String? = null,
    val error: String? = null, val readPaths: List<String> = emptyList(),
    val acceptedMessages: Int = 0, val processedMessages: Int = 0)
data class SubagentPage(val agents: List<SubagentSnapshot>, val cursor: Long)
data class SubagentMessageReceipt(val agentId: String, val messageId: String, val sequence: Long)
interface SubagentService {
    val state: StateFlow<List<SubagentSnapshot>>
    suspend fun begin(context: ToolContext, budget: ModelRequestBudget)
    suspend fun restore(history: List<StoredEvent>)
    suspend fun finish(parentRunId: String)
    suspend fun spawn(context: ToolContext, request: SubagentRequest): SubagentSnapshot
    suspend fun list(context: ToolContext): SubagentPage
    suspend fun send(context: ToolContext, agentId: String, messageId: String, text: String): SubagentMessageReceipt
    suspend fun wait(context: ToolContext, afterCursor: Long, timeoutMs: Long): SubagentPage
    suspend fun cancel(context: ToolContext, agentId: String): SubagentSnapshot
    suspend fun result(context: ToolContext, agentId: String): SubagentSnapshot
}
```

Serializable DTO, кроме live budget/service. Ошибки — ToolException со стабильным code, adapter возвращает ToolResult.failure. В ToolContext не добавлять секреты/HTTP profile. begin получает один и тот же ledger, который parent использует до provider.stream. finish без нового пользовательского разрешения, idempotent. request/taskId dedup: совпавший request возвращает существующий snapshot, другой payload с тем же taskId — conflict. max3 детей заrun; UI state хранит последние bounded snapshots сессии, tools list возвращает только текущий run без больших result body. Не вводить WAITING, если в первой версии нет реальной операции ожидания ребёнком: никаких мёртвых статусов.

Tools adapter — новый SubagentTools(base: ToolExecutor, service: SubagentService), routing шести новых имён и delegation остальных base. Детский loop получает исходный base, обёрнутый строгим read-only scope guard, а не этот adapter; глубина1 обеспечена конструкцией.

Provider API: ProviderModel(id, displayName?, contextWindow?, maxOutputTokens?); ModelCatalogResult(models, errorCode?, message); ModelCatalogClient.discover(baseUrl, apiKey?, modelsUrl?). Concrete KtorModelCatalogClient владеет HTTP client и AutoCloseable; scope/lifecycle закрывает controller. Provider implementation не зависит от app-desktop.

UI integration после composer: новые optional props catalog state/callbacks/model metadata, subagent snapshots и cancel callback. Не менять onSend/SendReceipt. onConfigureProvider получает дополнительный models URL аргумент только согласованным изменением в Main+UI; controller configure сохраняет default параметр для существующих callers/tests.
