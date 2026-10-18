plugins { id("org.jetbrains.compose"); id("org.jetbrains.kotlin.plugin.compose") }
dependencies {
    // JNA 5.18.1 (Apache-2.0/LGPL-2.1 dual license): macOS Security.framework credential storage.
    implementation("net.java.dev.jna:jna:5.18.1")
    implementation("io.ktor:ktor-client-core:3.4.1")
    implementation("io.ktor:ktor-client-cio:3.4.1")
    implementation(project(":contracts"))
    implementation(project(":agent-core"))
    implementation(project(":providers"))
    implementation(project(":workspace"))
    implementation(project(":runtime"))
    implementation(project(":tools"))
    implementation(project(":sessions"))
    // Model comparison runs the same eval suite as :evals:liveEval inside the app.
    implementation(project(":evals"))
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
            macOS.iconFile.set(project.file("icons/Chiller.icns"))
            windows.iconFile.set(project.file("icons/Chiller.ico"))
            linux.iconFile.set(project.file("src/main/resources/chiller-icon.png"))
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

// Explicit native check, isolated from check/test and configured provider credentials.
tasks.register<JavaExec>("keychainSmoke") {
    group = "verification"
    description = "Opt-in macOS Keychain create/reopen/update/delete with one disposable synthetic credential."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.chiller.app.di.KeychainSmokeKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Explicit UI acceptance fixture; excluded from default checks and all provider integrations.
tasks.register<JavaExec>("richResultsPreview") {
    group = "verification"
    description = "Render synthetic tables/file results in both themes; -PrichResultsInteractive opens an isolated viewer fixture."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.chiller.app.ui.RichResultsPreviewKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    if (providers.gradleProperty("richResultsInteractive").isPresent) args("--interactive")
}

tasks.register<JavaExec>("pasteBenchmark") {
    group = "verification"
    description = "Paste preparation benchmark: image encoding, text storage and vision reuse, p50/p95 and heap, into build/reports/paste-benchmark.md."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.chiller.app.ui.PasteBenchmarkKt")
    jvmArgs("-Xmx2g", "-Djava.awt.headless=true")
}
tasks.register<JavaExec>("turnResultPreview") {
    group = "verification"
    description = "Render the turn result card (clean and with issues, both themes) into build/reports/turn-result. Synthetic data."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.chiller.app.ui.TurnResultPreviewKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// README screenshots from the scripted demo through the real loop; no provider or key.
tasks.register<JavaExec>("readmeShots") {
    group = "documentation"
    description = "Render README screenshots of the app (scripted demo, offscreen) into docs/images."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.chiller.app.ui.ReadmeShotsKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    workingDir = rootProject.projectDir
}
