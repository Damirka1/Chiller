import java.nio.file.Files
import java.util.concurrent.TimeUnit

plugins { id("org.jetbrains.compose"); id("org.jetbrains.kotlin.plugin.compose") }
apply(from = rootProject.file("build-support/ripgrep.gradle.kts"))
apply(from = rootProject.file("build-support/notices.gradle.kts"))

dependencies {
    // JNA 5.18.1 (Apache-2.0/LGPL-2.1 dual license): macOS Security.framework credential storage.
    implementation("net.java.dev.jna:jna:5.18.1")
    // JetBrains Runtime API 1.10.1 (Apache-2.0): native custom title bar on Windows when running on the JetBrains Runtime;
    // on any other JDK it reports itself unavailable and the standard window frame is used.
    implementation("org.jetbrains.runtime:jbr-api:1.10.1")
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
    // Compose UI test (same Compose 1.10.3, Apache-2.0): settings forms are exercised with real typing.
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}
compose.desktop {
    application {
        mainClass = "dev.chiller.app.MainKt"
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED", "-Dapple.awt.application.name=Chiller")
        if (providers.gradleProperty("chillerPreview").isPresent) jvmArgs += "-Dchiller.offlinePreview=true"
        nativeDistributions {
            appResourcesRootDir.set(layout.buildDirectory.dir("bundled-resources"))
            val preview = providers.gradleProperty("chillerPreview").isPresent
            packageName = if (preview) "ChillerPreview" else "Chiller"
            macOS.bundleID = if (preview) "dev.chiller.preview" else "dev.chiller.app"
            packageVersion = project.version.toString()
            // Shown as the publisher in Windows "Installed apps" and as the copyright in the macOS bundle.
            vendor = "Damirka1"
            copyright = "© 2026 Damirka1. Apache License 2.0."
            description = "Chiller — coding agent harness"
            licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
            macOS.iconFile.set(project.file("icons/Chiller.icns"))
            macOS.infoPlist {
                extraKeysRawXml = "<key>NSScreenCaptureUsageDescription</key><string>Chiller captures only the window you select to attach a screenshot to your conversation.</string>"
            }
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
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    workingDir = rootProject.projectDir
    // A dev run is a bare `java` process, so the Dock would say "java". -Xdock is macOS-only (other JVMs reject it),
    // which is why it is set here and not in application.jvmArgs, which also go into the Windows installer's launcher.
    // An argument provider, because the Compose plugin replaces this task's jvmArgs list when it runs.
    if (System.getProperty("os.name").lowercase().contains("mac")) jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Xdock:name=Chiller") })
}

// Version and build metadata shown in Settings → About. Release builds pass the full tag (1.2.0-rc.2) as
// chillerDisplayVersion, since installers only accept the numeric version; CI adds its run number.
val versionResources = layout.buildDirectory.dir("generated/version")
fun git(vararg args: String): Provider<String> = provider {
    runCatching { providers.exec { commandLine("git", *args); workingDir = rootDir; isIgnoreExitValue = true }.standardOutput.asText.get().trim() }.getOrDefault("")
}
val writeVersion by tasks.registering {
    val version = project.version.toString()
    val displayVersion = providers.gradleProperty("chillerDisplayVersion").orElse(version)
    val commit = providers.environmentVariable("GITHUB_SHA").orElse(git("rev-parse", "HEAD")).map { it.ifBlank { "local" } }
    val commitDate = git("log", "-1", "--format=%cs")
    val buildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orElse("")
    inputs.property("version", version)
    inputs.property("displayVersion", displayVersion)
    inputs.property("commit", commit)
    inputs.property("commitDate", commitDate)
    inputs.property("buildNumber", buildNumber)
    outputs.dir(versionResources)
    doLast {
        val file = versionResources.get().file("chiller-version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$version\ndisplayVersion=${displayVersion.get()}\ncommit=${commit.get().take(12)}\ncommitDate=${commitDate.get()}\nbuildNumber=${buildNumber.get()}\n")
    }
}
sourceSets.named("main") { resources.srcDir(versionResources) }
tasks.named<ProcessResources>("processResources") {
    dependsOn(writeVersion)
    from(rootProject.file("LICENSE")) { into("META-INF") }
}

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

// Only app execution/packaging downloads the pinned native tool; offline unit checks do not.
tasks.matching { it.name in setOf("prepareAppResources", "run") }.configureEach { dependsOn("prepareRipgrep", "prepareThirdPartyNotices") }
// Compose does not notice files our own tasks put into appResourcesRootDir; without an explicit input a local package
// could keep stale notices or a stale ripgrep.
tasks.matching { it.name in setOf("prepareAppResources", "createDistributable") }.configureEach {
    inputs.dir(layout.buildDirectory.dir("bundled-resources")).withPropertyName("bundledResources")
}
// jpackage copies resource files without their executable bit. Restore it in the app image before installers run.
tasks.matching { it.name == "createDistributable" }.configureEach {
    doLast {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            val binaries = fileTree(layout.buildDirectory.dir("compose/binaries/main/app")) { include("**/resources/tools/rg") }.files
            check(binaries.size == 1) { "Expected one bundled ripgrep in the application image" }
            binaries.forEach { check(it.setExecutable(true, false)) { "Cannot make packaged ripgrep executable" } }
        }
    }
}

val verifyBundledRipgrep by tasks.registering {
    group = "verification"
    description = "Search Unicode paths using the packaged ripgrep with PATH empty."
    dependsOn("createDistributable")
    doLast {
        val binaries = fileTree(layout.buildDirectory.dir("compose/binaries/main/app")) { include("**/resources/tools/rg", "**/resources/tools/rg.exe") }.files
        check(binaries.size == 1) { "Missing or ambiguous packaged ripgrep" }
        val binary = binaries.single()
        val fixture = Files.createTempDirectory("chiller поиск ").toFile()
        try {
            File(fixture, "данные с пробелом.txt").writeText("chiller-bundled-search\n")
            val command = ProcessBuilder(binary.absolutePath, "--no-config", "--files", "--", ".").directory(fixture).redirectErrorStream(true)
            command.environment()["PATH"] = ""
            val process = command.start()
            check(process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); "Bundled ripgrep timed out" }
            val text = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            check(process.exitValue() == 0 && text.contains("данные с пробелом.txt")) { "Packaged ripgrep cannot list Unicode files: $text" }
            val search = ProcessBuilder(binary.absolutePath, "--no-config", "--fixed-strings", "chiller-bundled-search", ".").directory(fixture).redirectErrorStream(true)
            search.environment()["PATH"] = ""
            val child = search.start()
            check(child.waitFor(15, TimeUnit.SECONDS)) { child.destroyForcibly(); "Bundled search timed out" }
            check(child.exitValue() == 0 && child.inputStream.bufferedReader().readText().contains("chiller-bundled-search")) { "Bundled search failed" }
            println("Packaged ripgrep: list/search passed with empty PATH and Unicode paths")
        } finally { fixture.deleteRecursively() }
    }
}
tasks.matching { it.name in setOf("packageDmg", "packageMsi") }.configureEach { dependsOn(verifyBundledRipgrep) }
