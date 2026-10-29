package dev.chiller.app.i18n

object EnStrings : Strings {
    override val language = Language.EN
    override val settings = object : SettingsStrings {
        override val title = "Settings"
        override val backToDialog = "Back to dialog"
        override val connection = "Connection"
        override val agent = "Agent"
        override val mcp = "MCP tools"
        override val appearance = "Appearance"
        override val data = "Projects and data"
        override val keys = "Shortcuts"
        override val about = "About"
        override val connectionDescription = "Your models and connections. Choose a provider to set it up."
        override val agentDescription = "How the model behaves, what it can do and the limits of your tasks."
        override val mcpDescription = "Connect tools and services the agent works with."
        override val appearanceDescription = "A workspace that makes it easy to focus."
        override val dataDescription = "Projects, history and app files on this computer."
        override val keysDescription = "Quick access to dialogs, projects and commands."
        override val aboutDescription = "App version and details for diagnostics."

        override val theme = "Theme"
        override val themeSystem = "System"
        override val themeLight = "Light"
        override val themeDark = "Dark"
        override val interfaceTitle = "Interface"
        override val language = "Language"
        override val density = "Density"
        override val densityNormal = "Normal"
        override val densityCompact = "Compact"
        override val defaultLayout = "Default layout"
        override val reducedMotion = "Reduce motion"
        override val reducedMotionHint = "No fade-ins, shimmer or blinking cursor"
        override val notifications = "Notifications"
        override val notificationsHint = "Chiller tells you about background dialogs inside the window. System notifications arrive only while the window is not in focus."
        override val systemNotifications = "System notifications"
        override val systemNotificationsHint = "A task finished, a check passed or the agent is waiting for an answer. They need the Chiller icon in the notification area."
    }
}
