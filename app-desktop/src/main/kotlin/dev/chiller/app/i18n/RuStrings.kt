package dev.chiller.app.i18n

object RuStrings : Strings {
    override val language = Language.RU
    override val settings = object : SettingsStrings {
        override val title = "Настройки"
        override val backToDialog = "Назад к диалогу"
        override val connection = "Подключение"
        override val agent = "Агент"
        override val mcp = "Инструменты MCP"
        override val appearance = "Внешний вид"
        override val data = "Проекты и данные"
        override val keys = "Клавиши"
        override val about = "О программе"
        override val connectionDescription = "Ваши модели и подключения. Выберите провайдера, чтобы настроить его параметры."
        override val agentDescription = "Поведение модели, возможности и ограничения для ваших задач."
        override val mcpDescription = "Подключайте инструменты и сервисы, с которыми работает агент."
        override val appearanceDescription = "Рабочее пространство, в котором удобно сосредоточиться."
        override val dataDescription = "Проекты, история и файлы приложения на этом компьютере."
        override val keysDescription = "Быстрый доступ к диалогам, проектам и командам."
        override val aboutDescription = "Версия приложения и сведения для диагностики."

        override val theme = "Тема"
        override val themeSystem = "Системная"
        override val themeLight = "Светлая"
        override val themeDark = "Тёмная"
        override val interfaceTitle = "Интерфейс"
        override val language = "Язык"
        override val density = "Плотность"
        override val densityNormal = "Обычная"
        override val densityCompact = "Компактная"
        override val defaultLayout = "Раскладка по умолчанию"
        override val reducedMotion = "Уменьшить движение"
        override val reducedMotionHint = "Без анимаций появления, переливов и мигающего курсора"
        override val notifications = "Уведомления"
        override val notificationsHint = "О диалогах в фоне Chiller сообщает внутри окна. Системные уведомления приходят, только когда окно не в фокусе."
        override val systemNotifications = "Системные уведомления"
        override val systemNotificationsHint = "Задача завершена, проверка пройдена или агент ждёт ответа. Для них нужна иконка Chiller в области уведомлений."
    }
}
