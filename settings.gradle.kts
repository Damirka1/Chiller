pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }
dependencyResolutionManagement { repositories { mavenCentral(); google() } }
rootProject.name = "Chiller"
include("contracts", "agent-core", "providers", "workspace", "runtime", "tools", "sessions", "design-system", "app-desktop", "evals")
