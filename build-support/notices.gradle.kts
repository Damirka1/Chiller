import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact

// Third-party notices shipped inside the app: the licenses each dependency declares in its Maven POM, every
// LICENSE/NOTICE/COPYING file found inside its JAR, and one Apache-2.0 text when any dependency uses it.
// This is an inventory for review, not a legal certification.
val notices = layout.buildDirectory.dir("bundled-resources/common/third-party")
val runtime = configurations.named("runtimeClasspath")
tasks.register("prepareThirdPartyNotices") {
    inputs.files(runtime)
    inputs.file(rootProject.file("LICENSE"))
    outputs.dir(notices)
    doLast {
        val directory = notices.get().asFile
        directory.deleteRecursively(); directory.mkdirs()
        val artifacts = runtime.get().resolvedConfiguration.resolvedArtifacts
            .filter { it.file.extension == "jar" && it.moduleVersion.id.group != "dev.chiller" }
            .sortedBy { it.moduleVersion.id.toString() }
        val componentIds = artifacts.mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }.distinct()
        // Licenses as the POMs declare them (name and URL); resolved from the same repositories as the build.
        val pomLicenses = dependencies.createArtifactResolutionQuery().forComponents(componentIds)
            .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute().resolvedComponents
            .associate { component ->
                val pom = component.getArtifacts(MavenPomArtifact::class.java).filterIsInstance<org.gradle.api.artifacts.result.ResolvedArtifactResult>().firstOrNull()?.file
                component.id.displayName to (pom?.let(::declaredLicenses) ?: emptyList())
            }
        var usesApache = false
        val undeclared = mutableListOf<String>()
        val lines = artifacts.map { artifact ->
            val id = artifact.moduleVersion.id.toString()
            val destination = File(directory, id.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            var count = 0
            ZipFile(artifact.file).use { zip ->
                zip.entries().asSequence().filter { entry ->
                    val name = entry.name.substringAfterLast('/').uppercase()
                    !entry.isDirectory && (name.startsWith("LICENSE") || name.startsWith("NOTICE") || name.startsWith("COPYING"))
                }.forEach { entry ->
                    destination.mkdirs()
                    val output = File(destination, "${count++}-${entry.name.substringAfterLast('/')}")
                    zip.getInputStream(entry).use { input -> output.outputStream().use { input.copyTo(it) } }
                }
            }
            val licenses = pomLicenses[id].orEmpty()
            if (licenses.any { it.contains("Apache", ignoreCase = true) && it.contains("2.0") }) usesApache = true
            if (licenses.isEmpty() && count == 0) undeclared += id
            val declared = licenses.joinToString("; ").ifEmpty { "лицензия не объявлена в POM" }
            "- $id — $declared" + if (count > 0) " · файлов уведомлений из JAR: $count" else ""
        }
        if (usesApache) rootProject.file("LICENSE").copyTo(File(directory, "Apache-2.0.txt"), overwrite = true)
        val inventory = buildList {
            add("# Сторонние компоненты Chiller"); add("")
            add("Лицензии взяты из POM каждой библиотеки; рядом лежат файлы LICENSE/NOTICE/COPYING, найденные внутри её JAR.")
            if (usesApache) add("Текст Apache License 2.0 — в `Apache-2.0.txt`.")
            add("Модули самого Chiller (`dev.chiller:*`) распространяются под Apache-2.0, см. LICENSE. Встроенный ripgrep и его лицензии — в `tools/`.")
            add("Это перечень для проверки, а не юридическое заключение."); add("")
            addAll(lines)
            if (undeclared.isNotEmpty()) { add(""); add("Требуют ручной проверки (нет ни лицензии в POM, ни файла в JAR): ${undeclared.joinToString()}") }
        }
        File(directory, "INVENTORY.md").writeText(inventory.joinToString("\n") + "\n")
        logger.lifecycle("Third-party notices: ${lines.size} components, ${undeclared.size} need manual review")
    }
}

/** License names (with URLs) declared in a POM's <licenses>; parent POMs are not followed. */
fun declaredLicenses(pom: File): List<String> = runCatching {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    val document = factory.newDocumentBuilder().parse(pom)
    val nodes = document.getElementsByTagName("license")
    (0 until nodes.length).mapNotNull { index ->
        val element = nodes.item(index) as org.w3c.dom.Element
        fun child(name: String) = element.getElementsByTagName(name).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        val name = child("name") ?: return@mapNotNull null
        child("url")?.let { "$name ($it)" } ?: name
    }.distinct()
}.getOrDefault(emptyList())
