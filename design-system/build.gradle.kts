plugins { id("org.jetbrains.compose"); id("org.jetbrains.kotlin.plugin.compose") }
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
}
