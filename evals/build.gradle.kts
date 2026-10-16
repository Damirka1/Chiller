dependencies {
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
