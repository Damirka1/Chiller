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
    // Ktor logs through slf4j-api; without a binding SLF4J prints a startup warning. Logging stays off.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
}
tasks.register<JavaExec>("offlineSmoke") {
    group = "verification"
    description = "Runs a deterministic agent against real disposable files and a real test process."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.chiller.evals.OfflineSmokeKt")
}
tasks.register<JavaExec>("liveDeepSeekSmoke") {
    group = "verification"
    description = "Opt-in live multi-round tool loop. Needs DEEPSEEK_API_KEY/API_TOKEN and a small budget; never part of check."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.chiller.evals.LiveDeepSeekSmokeKt")
}
tasks.register<JavaExec>("liveVisionSmoke") {
    group = "verification"
    description = "Opt-in: one request with an image of a random number; passes only if the model reads it. Needs a key; never part of check."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.chiller.evals.LiveVisionSmokeKt")
    workingDir = rootProject.projectDir
}
tasks.register<JavaExec>("liveEval") {
    group = "verification"
    description = "Opt-in live evaluation on the eval task set with a report in reports/local/evals. Needs a key (or a loopback endpoint); never part of check."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.chiller.evals.LiveEvalKt")
    workingDir = rootProject.projectDir
}
tasks.register<JavaExec>("liveImageSmoke") {
    group = "verification"
    description = "Opt-in: one 1024x1024 generation and one edit through the endpoint's image model. Needs a key; never part of check."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.chiller.evals.LiveImageSmokeKt")
    workingDir = rootProject.projectDir
}
