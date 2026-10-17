dependencies {
    implementation(project(":contracts"))
    implementation(project(":workspace"))
    implementation(project(":runtime"))
}

// Explicit public-web verification; never part of offline/default checks.
tasks.register<JavaExec>("webSmoke") {
    group = "verification"
    description = "Opt-in bounded public search and HTML/plain/JSON fetch; no credentials or project content."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.chiller.tools.WebSmoke")
}
