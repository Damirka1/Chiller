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
tasks.register<JavaExec>("liveEval") {
    group = "verification"
    description = "Opt-in live evaluation on the eval task set with a report in reports/local/evals. Needs a key (or a loopback endpoint); never part of check."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.chiller.evals.LiveEvalKt")
    workingDir = rootProject.projectDir
}
