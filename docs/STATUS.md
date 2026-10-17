# Chiller — текущий статус

Обновлено: 17 октября 2026, ветка `master`. Это карта состояния, а не маркетинговый отчёт:
код и проверки важнее текста. Хронология прогонов — в [RUN_REPORT](RUN_REPORT.md);
устаревшие планы (`EVENING_PLAN`, `IMPLEMENTATION_AUDIT_2026_10_17`, `UI_FINISHING_RUNBOOK`)
описывают процесс и местами уже опережены кодом.

## Проверено в этом проходе

| Проверка | Команда | Результат |
|---|---|---|
| Юнит-тесты | `./gradlew check` | 138 тестов, 0 падений, 0 skipped |
| Offline smoke | `./gradlew :evals:offlineSmoke` | пройден: search → read → chained edit → реальные Python-проверки → final → restart → stale-отказ; все 8 eval-проверок падают на нетронутой задаче и проходят на эталоне |
| Live eval | `./gradlew :evals:liveEval` | `deepseek-flash`, 17.10: **8/8**, 1 прогон на задачу; 32 раунда, 38 tool-вызовов, 0 ошибок, 55.9 с; prompt 75.3k, completion 3.8k, cached 61.4k. Задачи простые, n=1 — это базовая линия, не статистика |
| Live DeepSeek | `./gradlew :evals:liveDeepSeekSmoke` | пройден дважды на финальном коде: 5 раундов, 6 tool-вызовов, 0 ошибок, 9.1–9.8 с; prompt ~10.8k, completion 576–634, cached 8 192–9 600 |
| macOS-пакет | `./gradlew :app-desktop:createDistributable` | собран |
| Запуск приложения | `./gradlew :app-desktop:run` (изолированные данные, без ключа) | процесс стартовал, исключений в логе нет; **визуальная приёмка — вручную** |

Live-прогон — это **один** сценарий на `deepseek-flash`. Он подтверждает, что реальная модель
ведёт многораундовый tool-loop, но не заменяет eval-корпус и не подтверждает Gemma.

## Матрица

| Область | Состояние | Что реально есть / предел |
|---|---|---|
| Контракты | реализовано | `contracts/Contracts.kt` — источник истины; String ID, `ToolEffect`, typed results |
| Агентный цикл | реализовано | до 24 раундов (настраивается), параллельные READ-вызовы, эффекты строго по порядку, отмена, лимит повторяющихся ошибок |
| Провайдер DeepSeek | live-подтверждён | SSE, thinking replay, usage, bounded retry 429/5xx до первого delta |
| Каталог моделей | реализовано | явное `GET /models` с bounded-разбором, кэш на endpoint, ручной fallback; список не доказывает поддержку tools/reasoning |
| Файловые инструменты | реализовано | 14 tools: + `replace_text` (точная замена, число совпадений обязано совпасть, через тот же snapshot/undo) и `update_plan` (чек-лист модели); snapshot-lineage, clamp чтения, компактный search с context |
| Инструкции проекта | реализовано | `AGENTS.md`, `CLAUDE.md`, `.chiller/instructions.md` → отдельное system-сообщение (≤12k символов, без symlink), читаются раз на диалог; права не дают |
| Трассировка | реализовано | JSONL на раунд: запрос как его собрал Chiller (не wire-формат) и ответ, usage, finish, время; desktop — в данных приложения, eval — в `reports/local/evals` |
| Undo / recovery | реализовано | before-images, журнал, reconcile, отказ при расхождении; move только внутри каталога |
| Процессы | реализовано | argv, cwd, timeout, caps, отмена; это не OS-sandbox и не process groups |
| Сессии | реализовано | JSONL + fsync, torn-tail recovery, экспорт MD/JSON; retention и миграций нет |
| Контекст | реализовано | оценка по символам/4, целые ходы, pruning старых tool-результатов; точного tokenizer нет |
| Метрики прогона | реализовано | `run.summary` в журнале: раунды, tool-вызовы, ошибки, время, usage |
| UI Focus/Studio | частично | стартовый экран (недавние проекты, drag-and-drop папки, карточка проекта, стартовые задачи), лента прогона с итогами, composer с папкой/контекстом/подключением, поиск диалогов ⌘K, вкладка «Прогон» в Studio; проверено рендером вне экрана, ручной клик-приёмки нет; keyboard/IME/VoiceOver и платформы не проверены |
| Субагенты | нет | контракты/супервизор/mailbox не реализованы |
| Gemma / vLLM | подготовлено частично | профиль провайдера может не требовать ключ, eval-раннер использует это для loopback; desktop всё ещё считает отсутствие ключа offline; live-проверки нет |
| Windows/Linux | не проверено | упаковка и поведение не проверялись |

## Известные ограничения

- `deepseek-flash` подтверждён как модель разработки; версии, лимиты и стоимость конкретного
  аккаунта не измерялись.
- Проверка cwd/путей — не OS sandbox.
- Локальный vLLM без ключа сейчас недоступен: отсутствие credential трактуется как offline.
- Keychain не реализован; ключ из UI живёт только в памяти процесса, `.env` читается при старте.
- Поиск/листинг требуют `rg` на PATH; offline smoke дополнительно требует `python3`.
- Экспорт скрывает известные секреты, но произвольный текст нужно проверять вручную.

## Как запускать

```sh
./gradlew check :evals:offlineSmoke
./gradlew :evals:liveDeepSeekSmoke   # opt-in: ключ в env или .env, малый бюджет
./gradlew :evals:liveEval            # opt-in: 8 задач, отчёт в reports/local/evals; CHILLER_EVAL_* для endpoint/модели/задач
./gradlew :app-desktop:run
```
