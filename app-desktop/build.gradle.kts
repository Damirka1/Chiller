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
    // Ktor logs through slf4j-api; without a binding SLF4J prints a startup warning. Logging stays off.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
}
compose.desktop {
    application {
        mainClass = "dev.chiller.app.MainKt"
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED", "-Dapple.awt.application.name=Chiller")
        if (providers.gradleProperty("chillerPreview").isPresent) jvmArgs += "-Dchiller.offlinePreview=true"
        nativeDistributions {
            val preview = providers.gradleProperty("chillerPreview").isPresent
            packageName = if (preview) "ChillerPreview" else "Chiller"
            macOS.bundleID = if (preview) "dev.chiller.preview" else "dev.chiller.app"
            packageVersion = project.version.toString()
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)
            windows {
                menuGroup = "Chiller"
                // Stable upgrade code so a newer MSI replaces an older install.
                upgradeUuid = "6b1f3c2e-8a4d-4f8e-9c1a-3d2b7e5f9a10"
                shortcut = true
                dirChooser = true
            }
            includeAllModules = true
        }
    }
}
// Run from the repository root so the root .env and relative paths resolve as in the README.
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach { workingDir = rootProject.projectDir }

// Version and build metadata shown in Settings → About. The commit comes from CI when available.
val versionResources = layout.buildDirectory.dir("generated/version")
val writeVersion by tasks.registering {
    val version = project.version.toString()
    val commit = providers.environmentVariable("GITHUB_SHA").orElse("local")
    inputs.property("version", version)
    inputs.property("commit", commit)
    outputs.dir(versionResources)
    doLast {
        val file = versionResources.get().file("chiller-version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$version\ncommit=${commit.get().take(12)}\n")
    }
}
sourceSets.named("main") { resources.srcDir(versionResources) }
tasks.named("processResources") { dependsOn(writeVersion) }
