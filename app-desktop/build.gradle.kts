plugins { id("org.jetbrains.compose"); id("org.jetbrains.kotlin.plugin.compose") }
dependencies {
    implementation("io.ktor:ktor-client-core:3.4.1")
    implementation("io.ktor:ktor-client-cio:3.4.1")
    implementation(project(":contracts"))
    implementation(project(":agent-core"))
    implementation(project(":providers"))
    implementation(project(":workspace"))
    implementation(project(":runtime"))
    implementation(project(":tools"))
    implementation(project(":sessions"))
    implementation(project(":design-system"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
}
compose.desktop {
    application {
        mainClass = "dev.chiller.app.MainKt"
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED", "-Dapple.awt.application.name=Chiller")
        nativeDistributions {
            packageName = "Chiller"
            packageVersion = "1.0.0"
            includeAllModules = true
        }
    }
}
