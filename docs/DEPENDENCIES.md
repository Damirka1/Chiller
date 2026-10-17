# Дополнение к зависимостям

17 октября 2026: app-desktop явно фиксирует `net.java.dev.jna:jna:5.6.0` для прямых вызовов macOS Security.framework/CoreFoundation. Версия совпадает с доступной локальной совместимой JNA, без jna-platform и без shell-команд с секретами. Лицензирование JNA: dual Apache-2.0 / LGPL-2.1; при распространении использовать Apache-2.0 и сохранять upstream LICENSE/NOTICE. Это дополнение, не полный SBOM проекта. Системные frameworks предоставляет macOS.
