# Chiller — спецификация Focus и Studio

Оба направления согласованы пользователем. Это два layout режима одного рабочего пространства. Визуальная концепция из чата служит ориентиром; её демонстрационные тесты/статусы не являются данными приложения.

## Корректировка после первого desktop review, 17 октября

Пользователь отверг первую реализацию со стандартным Material-оформлением. Переработка координатором: собственные Foundation controls, нейтральные состояния выбора, явная desktop Typography без Material tracking, sidebar 224 dp, toolbar 48 dp, компактные контролы 28–30 dp, приветствие и composer в одном центре композиции, простые действия вместо трёх карточек, одна строка статуса подключения вместо demo-плашек. UI переведён на русский; Focus/Studio остаются.

Источники текущих токенов — ChillerTheme.kt и ChillerControls.kt, они уточняют первоначальную таблицу ниже. Primary контролы графитовые; сдержанный зелёный используется для бренда и успешного результата. Selection светло-серая / графитовая. Никаких фиолетовых Material secondary containers.

В собранном Chiller.app визуально проверены Focus, Studio, обе темы, settings dialog и offline conversation. Это первый исправленный visual pass, а не полная приёмка всех состояний. Native titlebar при ручном dark toggle, keyboard shortcuts и rich Markdown/code/diff scrolling требуют дальнейшей доводки.

## 1. Основные решения

- Focus — стартовый режим: sidebar + диалог; diff раскрывается в ходе работы, inspector по запросу.
- Studio — тот же диалог + постоянно открытая панель изменений. Sidebar можно свернуть.
- Общие тема, компоненты, ViewModel, выбранная сессия, composer draft и scroll position. Переключение layout не отменяет run.
- Настройки провайдера находятся в отдельном экране/панели; в composer только короткий selector модели и execution mode.
- Матовые непрозрачные поверхности, тонкие разделители, слабые тени у overlays; стекло не требуется.

## 2. Предлагаемые токены

| Token family | Начальные значения |
|---|---|
| Spacing | 4, 8, 12, 16, 24, 32 dp |
| Radius | control 7–8; card 10–12; composer 12–14 |
| Type | body 14–15 sp; secondary 12–13; title 22; code 12–13 |
| Surface light | canvas #FCFCFB; sidebar #F2F3F1; elevated #FFFFFF |
| Surface dark | canvas #1C1E20; sidebar #17191B; elevated #222527 |
| Text | light #252A28 / secondary #626D68; dark #E7ECE9 / secondary #A3AEA8 |
| Accent | light #376D59; dark #9AC9B2; использование умеренное |
| Borders | light #DFE4E0; dark #353C38 |
| Motion | hover/press 100–140ms; disclosure 160–220ms; inspector 200–260ms |

Токены — старт для Compose UI review; проверка контраста и реальных fonts обязательна. Поддерживать system/light/dark и scaling. Не использовать light palette как fallback для dark. Иконки одного семейства, 16–20 dp; accessible hit area важнее размера картинки.

## 3. Геометрия desktop

Sidebar ориентировочно 220–260 dp, chat content max width около 760 dp, inspector 340–480 dp с resize. На маленьком окне inspector переходит в переключаемую панель; не сжимать код и кнопки до нечитабельности. Сохранить доступ к Changes при любом layout.

Оконные controls — платформенные, без имитации traffic lights на Windows/Linux. Titlebar integration и draggable regions проверяются отдельно. Все важные действия доступны без hover.

## 4. Экраны и состояния

| Экран/область | Состояния | Обязательные действия |
|---|---|---|
| Welcome/workspace | Нет проекта, recent, недоступный путь | Выбрать папку, открыть recent, понять ошибку |
| Provider settings | Не настроен, testing, ready, unauthorized, unsupported | URL/model, credential mode, test connection, cancel |
| Sessions | Empty, list, loading, interrupted | Новый чат, открыть, rename, export позднее |
| Chat | Idle, streaming, tool running, question, error, cancelled | Send, stop, ответить, посмотреть действие |
| Changes | Empty, proposed, applied, conflict, undone | Просмотр diff, открыть файл, безопасный undo |
| Context | Selected files, snippets, approximate tokens, compacted | Увидеть, что отправляется; убрать attachment до отправки |
| Process | Running, exited, timed out, unknown | Открыть output, остановить, увидеть exit code |

Onboarding не должен требовать ключ для просмотра UI: явный demo mode с synthetic data. Реальное выполнение недоступно до настройки provider; никакого скрытого переключения на fake в production run.

## 5. Chat timeline

Сообщения пользователя визуально отделены, ответы модели спокойные без избыточных карточек. Tool groups по умолчанию компактны: action + target + status + duration, детали раскрываются. Reasoning свёрнут; статус действия выводится из событий engine, а не из догадки по тексту.

Code blocks копируются без номеров UI. Markdown рендерится как данные: embedded HTML/scripts не исполняются, удалённые картинки не подгружаются автоматически с утечкой контекста. Очень длинные blocks ограничены с открытием полного artifact.

Streaming обновляется пакетами. Автоскролл только если пользователь у нижней границы; иначе кнопка «К последнему сообщению». Expand/collapse не ломает привязку видимого участка. Стабильные keys виртуализированного списка.

## 6. Diff и результат проверки

Inline diff обязателен в alpha; side-by-side можно после первого сквозного сценария. Отображать путь, added/removed, номера строк, before/after revision metadata по запросу. Added/removed отличаются знаками и цветом.

Applied → кнопки Open / Undo; Proposed → Apply / Discard; Conflict → Re-read / Compare. Не показывать одновременно Applied и основное действие Apply. Undo проверяет текущую версию; конфликт не замалчивается.

Проверка кода: команда, cwd, время, exit status, сокращённый stdout/stderr. «Пройдено» допустимо только по фактическому результату. Для не запущенной проверки — «Не запускалась», для незавершённой — «Исход неизвестен». Демо-данные маркируются.

## 7. Composer и вопросы

Multiline input, file/image chips, selected model, Plan/Workspace edit, Send/Stop. Отправка не теряет draft при ошибке соединения. Во время IME composition Enter не отправляет сообщение. Изображение недоступно text-only профилю с понятной причиной.

Ask-user — карточка внутри текущего run с options и свободным вводом. Approval — отдельная карточка с preview действия и областью разрешения. Отказ/закрытие не выглядит как согласие. Pending question после restart видна в том же месте.

## 8. Motion и доступность

Motion объясняет смену состояния; нет фоновых пульсаций ради украшения. Reduced motion убирает spatial transitions. Loading не требует постоянного recomposition всего окна. Keyboard focus видимый, native tab order, controls с семантическими labels.

Проверки: keyboard-only полный сценарий, 100/125/150/200% scale, длинные русские/английские пути, VoiceOver на macOS, IME, обе темы, narrow window, 1000+ synthetic events, expanded 1000-line diff с virtualization.

## 9. Поставка UI агентом

Сначала component gallery и synthetic screen states; затем реальные ChatViewModel/EventStore projections. Worker не вызывает сеть из composable и не правит core contracts. Минимум review captures: Focus idle, Focus running, Studio applied diff, question, connection error, dark theme. Если нет GUI automation, явно перечислить visual checks как unverified; screenshot creation не заменять утверждением «UI проверен».

Visual reference по итогам реализации хранить в документации/артефактах проверки без секретов. Сам source исходного inline-концепта живёт в чате и не нужен как runtime-зависимость проекта.
