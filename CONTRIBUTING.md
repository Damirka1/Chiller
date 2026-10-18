# Разработка

Нужны JDK 21+, ripgrep (`rg`) в PATH и Python 3 для offline smoke. Используйте Gradle wrapper; первый запуск скачивает зависимости.

```sh
./gradlew check :evals:offlineSmoke
./gradlew :app-desktop:run
./gradlew :app-desktop:createDistributable
```

На Windows используйте `gradlew.bat`; сквозной smoke требует доступной команды `python3`. CI запускает unit-тесты на Linux/macOS/Windows, offline smoke на Linux/macOS и собирает DMG/MSI. Нативная UI-приёмка выполняется отдельно.

[Архитектура](docs/ARCHITECTURE.md) · [Провайдеры](docs/PROVIDERS.md) · [Дизайн](docs/DESIGN.md) · [Зависимости](docs/DEPENDENCIES.md)

Делайте небольшие изменения и выполняйте подходящие проверки. Сохраняйте чужую работу; не запускайте несколько Gradle-сборок одного checkout одновременно. Изменения поведения должны сопровождаться регрессионной проверкой. Указывайте, какие проверки выполнены, пропущены или заблокированы.

Секреты, пользовательские чаты, traces и локальные отчёты не коммитятся. `.env` и `reports/local/` игнорируются. Live API-тесты запускайте только с отдельным разрешённым бюджетом; они не входят в обычный check. Не включайте реальные ключи в fixtures.

Публикация релиза выполняется workflow по тегу `vX.Y.Z` или `vX.Y.Z-rc.N` (release candidate публикуется как pre-release, текст берётся из соответствующего раздела `CHANGELOG.md`). Workflow сначала проверяет помеченный коммит на Linux, macOS и Windows, затем собирает установщики. Перед тегом проверьте точный коммит и CI; отчёт о локальных тестах не подтверждает опубликованный бинарник.

Перед DMG/MSI выполняется `:app-desktop:verifyBundledRipgrep`: встроенный ripgrep в готовом приложении должен найти файл с кириллицей в пути при пустом PATH. `:app-desktop:prepareThirdPartyNotices` собирает в приложение перечень сторонних библиотек с лицензиями из POM и файлами LICENSE/NOTICE; это перечень для проверки, а не юридическое заключение. Unit-тесты по-прежнему используют `rg` из PATH.
