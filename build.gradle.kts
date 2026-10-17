import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.serialization") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.compose") version "1.10.3" apply false
}

// One version for the app, jars and installers. CI release builds pass -PchillerVersion from the git tag.
val chillerVersion = providers.gradleProperty("chillerVersion").get()
require(Regex("""^[1-9]\d*\.\d+\.\d+$""").matches(chillerVersion)) { "chillerVersion must be MAJOR.MINOR.PATCH with MAJOR > 0 (installer rule): $chillerVersion" }
allprojects { group = "dev.chiller"; version = chillerVersion }
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }
    tasks.withType<JavaCompile>().configureEach { sourceCompatibility = "21"; targetCompatibility = "21" }
    dependencies {
        add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        add("implementation", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        add("testImplementation", kotlin("test-junit5"))
        add("testImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher:1.12.2")
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
tasks.register("check") { dependsOn(subprojects.map { "${it.path}:check" }) }
