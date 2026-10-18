# Chiller — провайдер разработки и совместимость

Проверено по официальной документации 16 октября 2026 года. Live API в ходе подготовки плана не вызывался; ключ пользователя не запрашивался и не читался.

## 1. DeepSeek V4.1 Flash

Первый профиль для разработки:

```json
{
  "id": "deepseek-direct",
  "label": "DeepSeek V4.1 Flash",
  "protocol": "chat-completions",
  "base_url": "https://api.deepseek.com",
  "chat_path": "/chat/completions",
  "model": "deepseek-flash",
  "credential_ref": "env:DEEPSEEK_API_KEY",
  "thinking": "disabled",
  "live_tests": false
}
```

Это пример конфигурации без ключа, не существующий runtime-конфиг. Если ключ пользователя выдан посредником, нужны base URL и model ID именно посредника; прямой профиль автоматически не подойдёт. Не перенаправлять ключ между хостами при redirect.

Официальное имя для V4.1 Flash — `deepseek-flash`; старые имена маршрутизируются как compatibility aliases. В приложении хранить и requested model, и фактическую модель/fingerprint, если API их возвращает. [Релиз DeepSeek](https://api-docs.deepseek.com/news/news260910/).

## 2. Порядок интеграции

1. Offline FakeProvider и записанные синтетические SSE fixtures.
2. Простой текстовый ответ с thinking disabled.
3. Один tool call → настоящий read-only tool → результат → final.
4. Streaming tool arguments и два последовательных model rounds.
5. Thinking enabled и проверка provider replay data.
6. Read/edit/test на disposable fixture workspace.
7. Restart/resume и ограничение бюджета.
8. Image smoke test позже; отсутствие live vision test отображать как unknown.

Так отделяем ошибки общего engine от ошибок thinking-профиля. Thinking — явная настройка профиля, не фрагмент prompt.

## 3. Особенности протокола

В Chat Completions DeepSeek thinking возвращается в `reasoning_content`. При tools документация требует возвращать reasoning предыдущих ходов, включая ходы без вызова инструмента. Это означает, что скрытие reasoning в UI не позволяет удалить его из provider replay state. В thinking mode не использовать forced required/named tool choice; sampling-options фильтровать по профилю. [Thinking](https://api-docs.deepseek.com/guides/thinking_mode/), [Chat API](https://api-docs.deepseek.com/api/create-chat-completion/).

Strict tool schema — отдельная beta-возможность, не prerequisite alpha. Не включать её глобально для всех OpenAI-compatible серверов. Первый путь — обычные tools + локальная валидация. [Tool calls](https://api-docs.deepseek.com/guides/tool_calls/).

ContextPlanner не знает детали ключа `reasoning_content`, но обязан сохранять opaque replay payload оставшихся сообщений. Политика trim/restart задаётся профилем; удалять произвольные assistant messages посередине незавершённой tool group нельзя. При переходе на другого провайдера создавать новый контекст со сводкой, не пересылать opaque данные вслепую.

## 4. Ключ и стоимость

- Код читает credential по ссылке, не из закоммиченного файла.
- Первый local test: явно настроенная `DEEPSEEK_API_KEY` в окружении запуска приложения. Не печатать env, значение ключа или request headers.
- UI alpha: ввод на время сессии; системный keychain отдельной задачей. Поле скрыто, debug state не содержит секрета.
- Default tests offline; live task запускается отдельно и требует finite request/token budget. Денежный лимит пользователь выбирает до платных автоматических прогонов; конкретную сумму этот план не назначает.
- Rate limits, стоимость и окно контекста не хардкодить из чужих бенчмарков. Начальный контекст Chiller консервативный, например 16–32K как гипотеза, затем измерение. Max output также ограничен приложением.
- 401 → настройка credentials; 429 → bounded retry с учётом Retry-After; обрыв после side effect → recovery, не автоматическое повторение команды.
- Fixture tests не тарифицируются; отсутствие ключа или лимита не блокирует остальные направления работы.

Live smoke budget минимум задаёт число запросов, суммарные output/input caps и deadline. Probes и retries учитываются в том же бюджете. При исчерпании тест записывает skipped/budget-exhausted, а не failed-model или success.

## 5. Gemma/vLLM — отложенный live gate

Временная замена модели не отменяет специализацию tools под Gemma. Уже в offline фазе держим короткие схемы, ограниченный каталог, компактный output и snapshot IDs. Нельзя переносить результаты DeepSeek eval на Gemma.

Когда сервер доступен, получить: base URL, served model ID, vLLM version, chat template, tool/reasoning parser configuration, quantization и рабочий context limit. Не угадывать hardware или доступность endpoint.

Проверить: text streaming; tools; partial calls; reasoning protocol; tool history; cancellation; 10 одинаковых fixture tasks с DeepSeek; затем полный eval corpus. При необходимости изменить внешнюю форму edit tool, сохранив внутренний snapshot engine.

Статус до этого момента: `configured / offline-tested / live-unverified`. Отсутствие сервера не блокирует development alpha, но блокирует формулировку «проверено с Gemma».

## 6. Разделение двух видов агентов

Субагенты, которые разрабатывают Chiller, работают средствами текущей среды разработки. DeepSeek — провайдер будущего приложения и его live tests. Не предполагается автоматически заменить модель всех субагентов на DeepSeek или передать им ключ.

## 7. Матрица подтверждений

| Проверка | Fake/fixture | DeepSeek live | Gemma live |
|---|---|---|---|
| Streaming, arguments assembly | Обязательна в default check | Opt-in smoke | После доступа |
| Read/edit/test loop | Обязательна на fixtures | Opt-in на disposable workspace | После доступа |
| Thinking history | DeepSeek/Gemma-specific fixtures | Отдельный smoke | Отдельный smoke |
| Cancellation | Детерминированная | Client cancel + наблюдаемый исход | Аналогично |
| Качество решения задач | Не измеряется fake-моделью | Отдельный benchmark | Целевой benchmark |

Каждая клетка отчёта имеет passed/failed/skipped/unknown; один зелёный индикатор «всё совместимо» недостаточен.

## Автозагрузка каталога моделей

Desktop выполняет один фоновый `GET <baseUrl>/models` после загрузки настроек и ключей при старте, а также после смены endpoint или ключа. Base URL с `/v1` даёт `/v1/models`; префикс не добавляется повторно. Для удалённого API автоматический запрос требует настроенного ключа, loopback работает без ключа. Смена только model ID не обновляет каталог повторно. Кнопка ручного обновления остаётся доступной.

Последний успешный список с метаданными сохраняется в настройках отдельно по endpoint (до 16 адресов, до 500 моделей на адрес). При следующем старте он доступен во время обновления; при ошибке сохраняется как устаревший. Смена ключа очищает кеш всех адресов того же API-сервера, чтобы не подставлять список другого аккаунта. Загрузка каталога не выполняет chat-completions/probe и не подтверждает способность модели вызывать tools.
