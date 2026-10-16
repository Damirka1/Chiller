# Chiller — план параллельной разработки

Статус: подготовка будущего запуска. Этот документ не запускает агентов и не создаёт расписание. Основная ветка: `master`. Пользователь согласовал направление продукта и оба режима UI; текущая работа — Git и детализация плана.

## 1. Цель и пределы первого запуска

Первая цель — N0 из BACKLOG.md: собираемая desktop alpha, Focus/Studio, настоящий файловый/процессный цикл на deterministic model fixtures, сохранение и отмена. При наличии ключа и бюджета — DeepSeek live smoke. Затем N1 и A по зависимостям.

Весь production-проект за одну ночь не обещается. Нативные секреты/разрешения, Windows/Linux, Gemma eval, PTY и выпуск требуют отдельных проверок. Субагенты сокращают независимую работу, но не отменяют последовательные зависимости и интеграцию.

План подразумевает активный запуск в среде разработки. Сам файл не поддерживает компьютер бодрствующим и не гарантирует выполнение после закрытия приложения, сна или исчерпания лимитов. При остановке сохраняются checkpoint и отчёт, позволяющие продолжить.

## 2. Команда

В доступной конфигурации использовать **координатор + до трёх работников**. Названия ниже — роли, не фиксированные модели. Модель workers наследуется из текущего запуска; отдельное изменение требует явного выбора пользователя. DeepSeek API используется приложением/тестами, а не автоматически всеми coding workers.

| Роль | Ответственность |
|---|---|
| Координатор | Contracts, build/version catalog, sessions/core, распределение файлов, интеграция, общие проверки, отчёт |
| Worker A | Сначала workspace, затем runtime/hardening по зависимости |
| Worker B | Сначала providers, затем tools/storage в следующей волне |
| Worker C | Design system, app presentation, затем UI polish |

Workers не создают собственных субагентов. Смена роли возможна после передачи результата; одна директория не имеет двух активных writers.

## 3. До запуска

Координатор читает README, AGENTS, MODULES, CONTRACTS, BACKLOG, PROVIDERS, DESIGN; проверяет текущий Git status и сохраняет существующие изменения пользователя. Рабочая ветка — master, если пользователь не изменил решение.

Проверить локальные JDK/Gradle/rg и возможность разрешить зависимости; не предполагать, что всё установлено. Версии выбрать и закрепить до массового параллелизма. Не устанавливать системный софт без нужды: использовать корректный wrapper/toolchain, доступные в среде средства и явно фиксировать ограничения.

Ключ и finite live-test budget могут быть настроены пользователем локально. Без них все агенты продолжают offline работу, а live checks получают skipped. Не читать файлы вне проекта в поисках секрета. Не просить присылать ключ в чат.

Создать `docs/RUN_REPORT.md` с baseline commit, целями, владельцами, статусами и ограничениями. Приложение на этот момент ещё не существует; статус до исполнения unknown, а не passed.

## 4. Волны

### Волна 0 — фундамент, один writer

Координатор выполняет FND-01/02/03: wrapper/modules, совместимые версии, compilable contracts, fixtures/helpers, скелет composition root. Если сборка не разрешает зависимости, сначала решить/зафиксировать этот blocker; не отправлять трёх workers создавать разные build configurations.

Gate: compile всех skeleton modules и contract round-trip. Координатор фиксирует commit C0 и публикует workers список ports и владельцев.

### Волна 1 — три независимых направления

| Исполнитель | Задачи | Разрешённые пути |
|---|---|---|
| A | FS-01/02, подготовка FS-03 против ports | workspace/** |
| B | NET-01/02, затем NET-03 | providers/** |
| C | UI-01/02/03 на synthetic props | design-system/**; app-desktop/.../ui/** |
| Координатор | DATA-01/02, AGT-01; сопровождение shared API | sessions/**; agent-core/**; contracts/**; build/config |

Координатор интегрирует реальный EventStore с workspace. Технический UI preview не ждёт сети; engine tests не ждут UI. Не закрывать FS-03/04 по fake storage без реального recovery integration.

Gate G1: provider fixture stream, workspace edit/reject, session append/replay, UI shell compile. Commit C1 после общего check.

### Волна 2 — исполнение и сквозной путь

| Исполнитель | Задачи | Разрешённые пути |
|---|---|---|
| A | RUN-01/02 | runtime/** |
| B | TOOL-01/02; использует fake ProcessService до готовности runtime | tools/** |
| C | UI-04/05, ViewModels и real state binding | app-desktop/** кроме composition root |
| Координатор | AGT-02/04, composition root, FS-04 integration, EVAL-01/02 | agent-core/**; app-desktop/.../di/**; evals/**; integration fixes по согласованию |

Координатор не правит файл, пока его владелец активно редактирует. Для cross-module fixes сначала передать исправление владельцу или явно забрать ownership.

Gate G2: headless real file/process loop, cancellation и recovery. Gate G3: тот же путь из desktop UI. Commit C2/C3 только с фактическими результатами проверок.

### Волна 3 — N1 и доводка alpha

После зелёного N0:

- A: FS-05, RUN-03, точечное filesystem/process hardening.
- B: TOOL-03/04, DATA-03/04 при передаче владения sessions.
- C: UI-06/07, visual review, keyboard/IME/reduced-motion.
- Координатор: AGT-03, live DeepSeek smoke при наличии условий, package/run, общие проверки.

После N1 брать A items из backlog по готовности dependencies. Новые B/V1 features не начинать, если это рискует разрушить зелёный alpha checkpoint в конце сессии.

## 5. Владение и Git

По умолчанию общая рабочая папка с жёстким владением путями: это соответствует текущей среде совместных агентов. Branch сама по себе не изолирует файлы в одном checkout; workers не переключают HEAD.

Если будущий запуск предоставляет отдельные worktrees, координатор может выделить их по задаче и интегрировать последовательно. Но нельзя делать вид, что subagents изолированы, если им дали ту же директорию. Стратегию записать в RUN_REPORT до делегирования.

В общей папке workers не делают git add/commit/reset/checkout. Координатор создаёт небольшие локальные integration commits с перечнем включённых задач. Не коммитить секреты, runtime sessions, output logs или чужие незавершённые изменения. Remote/push/publication вне scope.

## 6. Общие файлы

Только координатор меняет:

```text
settings.gradle.kts
build.gradle.kts
gradle/**
gradlew / gradlew.bat
gradle.properties
contracts/**
app-desktop/src/main/kotlin/dev/chiller/app/di/**
docs/RUN_REPORT.md
```

Module build.gradle.kts также согласовывать: worker пишет dependency request, координатор добавляет dependency/version alias. Это исключает несколько несовместимых версий одной библиотеки.

Существенное изменение контракта: короткий запрос → решение координатора → code+fixtures → уведомление затронутых workers. Нельзя обходить конфликт созданием LocalToolResult/OtherAgentEvent вместо общего типа.

## 7. Проверки и интеграция

Workers пишут meaningful module tests. Координатор выдаёт слот на Gradle execution: одновременные сборки одного shared checkout не запускаются без доказанной безопасной изоляции outputs. CPU workers могут продолжать код/анализ во время build.

На handoff worker сообщает: задачи; файлы; public API assumptions; команды и фактические результаты; незавершённые сценарии; необходимые изменения contracts. Координатор запускает targeted tests, затем интеграционные проверки. Падение общего check устраняется до расширения scope.

Golden fixture не «исправляется» для подгонки под ошибочную реализацию. Нельзя отключать failure теста, подменять real service fake-сервисом в production wiring или помечать проверку skipped как passed.

## 8. Промпты работников

### Worker A — workspace, первая волна

> Реализуй FS-01, FS-02 и подготовь FS-03 в workspace/. Прочитай AGENTS.md, MODULES.md и CONTRACTS.md. Используй замороженные contracts, тестовые ports и реальные UTF-8 bytes. Обязательны snapshot binding, range checks, path boundaries, stale rejection, bounded search/read и diff. Запись координируй через EventStore/ArtifactStore ports. Не меняй build files/contracts/чужие модули, не делай Git-операций и не запускай дополнительных агентов. Запрос на изменение shared API отправь координатору. В конце дай список файлов, фактически выполненные проверки, ограничения и integration requirements.

### Worker B — providers, первая волна

> Реализуй NET-01/02 и затем NET-03 в providers/. Прочитай PROVIDERS.md и общий контракт. Сделай Ktor transport, SSE decoder, tool-call assembler и профиль deepseek-flash. Никаких live requests/поиска ключей. Проверяй fragmentation, partial calls, cancellation, provider errors и thinking replay синтетическими fixtures. Ключ не попадает в логи. Не редактируй shared build/contracts, не делай Git-операций и не создавай агентов. Handoff включает проверки и неподтверждённые возможности.

### Worker C — UI, первая волна

> Реализуй UI-01/02/03 в design-system/ и app-desktop UI packages. Следуй DESIGN.md: Focus и Studio — один state, матовые поверхности, спокойная типографика, короткий motion, светлая/тёмная тема, reduced motion, keyboard focus. Начни с synthetic props с явной demo маркировкой. Подготовь tool timeline, composer и inline diff; не вызывай сеть/файлы из composable. Не меняй contracts/build/composition root; без Git-операций и дополнительных агентов. Отдельно укажи, какие visual/IME/accessibility проверки реально выполнены.

Промпты второй волны строятся аналогично по RUN/TOOL/UI задачам с точным ownership и commit contracts. Координатор перед отправкой должен добавить текущий baseline, доступные реальные зависимости и известные blockers.

## 9. Поведение при блокировках

| Ситуация | Действие |
|---|---|
| Нет API-ключа/бюджета | Offline работа продолжается, live tests skipped |
| Нет Gemma-сервера | Gemma live pending, DeepSeek/FakeProvider work continues |
| Dependency download/JDK заблокирован | Сохранить диагностику; не утверждать сборку; не генерировать unverified lockfile |
| Требуется решение shared API | Worker присылает change request и делает независимые tests/implementation |
| Contracts не совместимы | Координатор чинит общий API до следующей волны |
| Недоступна другая ОС | Native checks marked unverified; не заменять Linux test утверждением о Windows |
| GUI не проверить | Структурные/UI tests отдельно; visual acceptance pending |
| Live budget исчерпан | Остановить платные вызовы; продолжить deterministic validation |

Два повторных исправления без прогресса — повод пересмотреть подход и изолировать проблему, а не повторять одинаковую команду. После budget/time limit завершить текущий безопасный checkpoint; не оставлять agent-controlled background processes без записи состояния.

## 10. Итоговый отчёт

RUN_REPORT содержит:

```text
Baseline / final commit:
Branch: master
Scope: N0 / N1 / A completed or partial
Worker ownership:
Implemented task IDs:
Build/test commands and actual exit results:
GUI checks performed:
DeepSeek live: passed / failed / skipped + reason
Gemma live: pending until server access
Known limitations / remaining blockers:
How to run:
Next three tasks:
```

Приоритет отчёта: что пользователь может запустить и проверить. Не количество созданных файлов, строк или работавших агентов.

## 11. Готовый запрос для будущего запуска

> Начинай реализацию Chiller по docs/AGENT_RUNBOOK.md и docs/BACKLOG.md. Работай в master. Используй координатора и максимум три субагента одновременно, с владением разными файлами и общими контрактами. Сначала доведи N0 до работающего desktop-приложения с Focus и Studio, точными файловыми правками, процессами, журналом и отменой. Затем выполняй N1 и доступные задачи alpha. Проверяй интеграцию после каждой волны и сохраняй локальные checkpoints. DeepSeek — первый реальный провайдер; live API tests делай только при явно настроенном локальном ключе и конечном бюджете. Без них продолжай offline. Gemma/vLLM пока недоступна: сохрани профиль и отметь live-проверку pending. В конце оставь RUN_REPORT с реальными проверками, инструкцией запуска и оставшимися задачами. Не публикуй и не отправляй изменения в remote.

Этот запрос предназначен для отдельного решения пользователя о старте. Сейчас выполнено только планирование.
