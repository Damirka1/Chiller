# Chiller — backlog реализации

Дата: 16 октября 2026. Все задачи ниже **запланированы**, ни одна не помечена выполненной. Git и документация подготовлены отдельно. Ветка интеграции: `master`.

## 1. Как читать

- **N0** — минимальный сквозной результат первого запуска субагентов.
- **N1** — продолжение той же ночи, если N0 уже зелёный; входит в полноценную alpha.
- **A** — завершение alpha после первого сквозного результата.
- **B** — beta / платформы / расширенные возможности.
- **V1** — последующие версии.

Приоритет не является обещанием успеть за конкретную ночь. Координатор выбирает готовые задачи по зависимостям, оставляя время на интеграцию. Истинный статус вести в отдельном RUN_REPORT.md при запуске; пока не создавать фиктивные passed/complete.

## 2. Основа

| ID | Приоритет | Задача и результат | Зависимости | Владелец | Приёмка |
|---|---|---|---|---|---|
| FND-01 | N0 | Проверить JDK/Gradle, выбрать совместимые стабильные версии, wrapper, version catalog, 10 модулей | — | Координатор | Wrapper запускается, все modules компилируются; toolchain зафиксирован |
| FND-02 | N0 | Материализовать contracts v0.1 и общие examples | FND-01 | Координатор | Message/tool/event round-trip fixtures; API freeze commit |
| FND-03 | N0 | Test fixtures, fake clock/provider, disposable workspace helpers | FND-02 | Координатор | Общие helpers используют реальные contracts; deterministic execution |
| FND-04 | A | Module boundary checks и baseline CI | FND-01,FND-02 | Координатор | Offline check в CI, secrets не нужны; зависимости без циклов |

## 3. Провайдеры

| ID | Приоритет | Задача и результат | Зависимости | Владелец | Приёмка |
|---|---|---|---|---|---|
| NET-01 | N0 | Ktor transport, Chat DTO, SSE decoder/assembler | FND-02 | Provider worker | Fragmented JSON/UTF-8/calls, cancellation, EOF, errors |
| NET-02 | N0 | DeepSeek profile, request codec, credential reference | NET-01 | Provider worker | `deepseek-flash`, unknown options не отправляются, ключ redacted |
| NET-03 | N1 | Thinking replay и history validation | NET-02 | Provider worker | Multi-round thinking/tools fixture сохраняет required payload |
| NET-04 | N1 | Passive probe + opt-in billed smoke, capability UI data | NET-02 | Provider worker | Unknown ≠ supported; budget учитывает probe; actual outcomes recorded |
| NET-05 | A | Generic + vLLM Gemma profile и fixtures | NET-01 | Provider worker | Профиль configurable, Gemma live marked pending |
| NET-06 | A | Live DeepSeek smoke | NET-02,TOOL-01,AGT-02; key+budget | Координатор | Реальный tool round-trip на fixture; иначе SKIPPED с причиной |
| NET-07 | B | Gemma live validation и сравнительный eval | NET-05,EVAL-03; server access | Координатор | Версии/templates записаны, целевые tasks выполнены |

## 4. Файловое ядро

| ID | Приоритет | Задача и результат | Зависимости | Владелец | Приёмка |
|---|---|---|---|---|---|
| FS-01 | N0 | Workspace resolver, bounded file read, snapshot store | FND-02 | Workspace worker | Paths/encoding/line ranges + version ID, no outside-root access |
| FS-02 | N0 | list/search с ripgrep, limits и cursor | FS-01 | Workspace worker | Ignore rules, stable result page, недоступный rg объясняется |
| FS-03 | N0 | EditPlanner + apply + create | FS-01,DATA-01 | Workspace worker | stale/overlap отклонены, UTF-8/CRLF/EOF preserved, diff returned |
| FS-04 | N0 | Before images, prepare/commit и reconcile | FS-03,DATA-02 | Workspace worker + координатор | Crash до/после write не вызывает повторную слепую запись |
| FS-05 | N1 | Undo, move, delete отдельных файлов | FS-04 | Workspace worker | No overwrite destination; undo conflict safe; recovery refs |
| FS-06 | A | Watcher/invalidation UX и metadata hardening | FS-04 | Workspace worker | Внешнее изменение видно; advisory nature documented |
| FS-07 | B | Матрица FS/OS, atomic replace restrictions | FS-05 | Platform worker | Windows/Linux/macOS fixtures, unsupported явно |

FS-03 разрабатывается против EventStore/ArtifactStore ports и fake implementations, пока DATA-01 не интегрирован. Это позволяет параллельную работу; зелёная end-to-end приёмка требует реального storage.

## 5. Процессы и платформы

| ID | Приоритет | Задача и результат | Зависимости | Владелец | Приёмка |
|---|---|---|---|---|---|
| RUN-01 | N0 | ProcessSupervisor, argv/cwd, concurrent output drain | FND-02 | Runtime worker | stdout+stderr flood не блокирует; process identity и exit |
| RUN-02 | N0 | Deadlines, output cap, cancel/stop, bounded drain | RUN-01 | Runtime worker | timeout, held pipe, repeated stop; supported termination documented |
| RUN-03 | N1 | Background process registry + restart unknown | RUN-02,DATA-02 | Runtime worker | Процесс не теряется из UI, PID не переиспользуется вслепую |
| RUN-04 | A | Native credential stores и fallback memory | FND-02 | Platform worker | Ключ не в repo/log/state dumps; unavailable UX |
| RUN-05 | B | OS process groups/job containment + isolation spike | RUN-02 | Platform worker | Проверены descendants, ограничения названы честно |
| RUN-06 | B | Screenshot adapters + permissions + image artifacts | RUN-04,UI-06 | Platform worker | Выбранная поверхность, denial/unsupported, no silent full screen |
| RUN-07 | B | PTY + visible terminal spike | RUN-02 | Platform worker | IME/DPI/resize/input проверены; решение по библиотеке |

## 6. Инструменты

| ID | Приоритет | Задача и результат | Зависимости | Владелец | Приёмка |
|---|---|---|---|---|---|
| TOOL-01 | N0 | Registry, schemas, typed validation, file tool wrappers | FND-02,FS-01..03 | Tools worker | list/search/read/edit/create работают, error envelope стабилен |
| TOOL-02 | N0 | run/read/stop process wrappers, read_artifact | TOOL-01,RUN-01..02 | Tools worker | Bounds, effects, cancellation, output paging |
| TOOL-03 | N1 | ask_user, move/delete, mode-based catalog | TOOL-01,FS-05,AGT-03 | Tools worker | 12 tools и Plan restrictions проверены |
| TOOL-04 | A | Idempotency ledger/unknown outcome hardening | TOOL-01,DATA-02 | Tools worker | Повтор operation ID не повторяет side effect |
| TOOL-05 | B | image/screenshot и read-only Git tools | RUN-06,NET-05 | Tools worker | Capability gating и исходные пользовательские изменения сохранены |

## 7. Сессии

| ID | Приоритет | Задача и результат | Зависимости | Владелец | Приёмка |
|---|---|---|---|---|---|
| DATA-01 | N0 | EventStore single writer + artifact blobs | FND-02 | Координатор | Ordered seq, durable critical events, bounded blobs |
| DATA-02 | N0 | Projection/restart/recovery scanner | DATA-01 | Координатор | Tail truncation/interior corruption/pending operation distinguished |
| DATA-03 | N1 | Session index, listing и pending interactions | DATA-02,AGT-03 | Storage worker | История и вопрос восстанавливаются без дублирования |
| DATA-04 | A | Markdown/JSON export + preview redaction | DATA-03 | Storage worker | Экспорт читаем/переносим, secrets excluded, blobs referenced |
| DATA-05 | A | Retention/cleanup/schema evolution | DATA-04 | Storage worker | Удаление одной сессии не ломает другие; orphan blobs cleanup |

## 8. Агентное ядро

| ID | Приоритет | Задача и результат | Зависимости | Владелец | Приёмка |
|---|---|---|---|---|---|
| AGT-01 | N0 | RunReducer, commands/events, budget и базовый context planner | FND-02 | Координатор | State transition fixtures, context reserve, no unbounded loop |
| AGT-02 | N0 | Model/tool loop и policy execution | AGT-01,NET-01,TOOL-01..02,DATA-01 | Координатор | Headless search/read/edit/test/final, no partial execution |
| AGT-03 | N1 | Questions, approvals, steering queue | AGT-01,DATA-01 | Координатор | Resume/late answer/cancel semantics, no implicit permission |
| AGT-04 | N0 | Stop run, interrupted state и no automatic side-effect retry | AGT-02,RUN-02,DATA-02 | Координатор | Stop during stream/process, restart reconcile |
| AGT-05 | A | Context compaction и profile-aware replay | AGT-02,NET-03 | Core worker | Tool groups сохранены, stale handles требуют reread |
| AGT-06 | A | Parallel reads и conflict scheduling | AGT-04 | Core worker | Bounded concurrency, writes/process effects serialized |

AGT-02 может разрабатываться на fakes до готовности реализаций; закрывается только интеграционным сценарием.

## 9. UI

| ID | Приоритет | Задача и результат | Зависимости | Владелец | Приёмка |
|---|---|---|---|---|---|
| UI-01 | N0 | Theme/tokens, controls, gallery | FND-01 | UI worker | Light/dark, focus/disabled/pressed, reduced motion |
| UI-02 | N0 | Focus + Studio shell и synthetic states | UI-01,FND-02 | UI worker | Один state при switch, нет скрытого fake production mode |
| UI-03 | N0 | Chat timeline, composer, tool cards, diff | UI-02 | UI worker | Stream batching, scroll anchoring, actual statuses |
| UI-04 | N0 | Workspace/provider settings + session selection | UI-02,NET-02,DATA-02 | UI worker | App can be configured; keys session-only допустимы в N0 |
| UI-05 | N0 | Bind ViewModels to real coordinator/projections | UI-03..04,AGT-02..04 | UI worker + координатор | Полный desktop сценарий, Stop и restart |
| UI-06 | A | Context inspector, attachments, questions, undo/conflicts | UI-05,AGT-03,FS-05 | UI worker | Все actions имеют real outcomes; text-only restrictions |
| UI-07 | A | Accessibility, scale/IME, long sessions, performance polish | UI-05 | UI worker | Review по DESIGN.md, фактически проверенные платформы |
| UI-08 | B | Platform window integration и native menus | UI-07 | Platform/UI worker | macOS/Windows/Linux поведение и установщики |

## 10. Проверки и выпуск

| ID | Приоритет | Задача и результат | Зависимости | Владелец | Приёмка |
|---|---|---|---|---|---|
| EVAL-01 | N0 | Critical deterministic fixtures | FND-03 | Каждый модуль / координатор | stale edit, partial call, cancel, recovery, Plan denied |
| EVAL-02 | N0 | Headless + desktop end-to-end smoke | AGT-04,UI-05 | Координатор | Реальные implementations, fixture workspace, actual report |
| EVAL-03 | A | Corpus 30–50 задач и runner/report | EVAL-02 | Eval worker | Reproducible inputs, held-out split, costs recorded |
| EVAL-04 | A | Сравнение edit protocols | EVAL-03 | Eval/core worker | Same models/settings/tasks, не субъективный выбор |
| REL-01 | N1 | Локальный run и первый desktop package | UI-05 | Координатор | Чистый checkout собирается, package запущен на доступной ОС |
| REL-02 | B | ОС CI, licenses, installers, signing preparation | REL-01,FS-07,UI-08 | Координатор | Supported targets проверены; signing только с доступными credentials |
| REL-03 | B | Release candidate и known limitations | REL-02,EVAL-03 | Координатор | No unverified compatibility claims; full release gates |
| NEXT-01 | V1 | LSP symbols/references/diagnostics, затем rename | A complete | Отдельное планирование | Server lifecycle + safe edits |
| NEXT-02 | V1 | MCP/browser/Git worktrees/multi-agent в Chiller | A complete | Отдельное планирование | Policy/journal/isolation для новых effects |

## 11. Gate для первого ночного результата

**N0 Complete** только если:

1. Репозиторий собирается воспроизводимыми командами; версии зафиксированы.
2. Focus и Studio реально переключаются в desktop-приложении.
3. На disposable workspace выполняется search/read/edit/test/final через FakeProvider и настоящие файловые/процессные реализации.
4. Неверный snapshot не пишет файл; частичный tool call не исполняется.
5. Stop прекращает run, процесс получает предусмотренную отмену, история сохраняется.
6. Сессия открывается после restart; неопределённые effects не повторяются.
7. Default offline checks проходят; actual live/GUI/platform checks перечислены отдельно.

DeepSeek live smoke входит в N0 только при наличии явно настроенного ключа и бюджета. Без них итог называется «offline alpha, live verification pending». Недоступная Gemma не блокирует этот статус.

Если N0 не завершён, отчёт содержит последний зелёный integration checkpoint и конкретную оставшуюся зависимость. Нельзя называть 10 отдельно компилирующихся модулей готовым приложением.

## 12. Планируемые команды проверки

После FND-01/03 координатор должен создать следующие entry points и записать реальные имена:

```text
./gradlew check
./gradlew :evals:offlineSmoke
./gradlew :app-desktop:run
./gradlew :app-desktop:packageDistributionForCurrentOS
./gradlew :evals:liveDeepSeekSmoke  # отдельно, opt-in, key + budget
```

`offlineSmoke` и `liveDeepSeekSmoke` — будущие custom tasks, пока отсутствуют. Packaging task нужно сверить с выбранной версией Compose plugin; если отличается, обновить документы и run report. Никакие эти проверки на этапе планирования не выполнялись.
