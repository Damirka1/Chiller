package dev.chiller.workspace

import java.nio.file.Files
import java.nio.file.Path

/** Packaged apps always use their own tool; source/eval runs may use PATH. */
object Ripgrep {
    fun executable(resources: String? = System.getProperty("compose.application.resources.dir"), windows: Boolean = System.getProperty("os.name").startsWith("Windows")): String {
        if (resources.isNullOrBlank()) return "rg"
        val binary = Path.of(resources).resolve("tools").resolve(if (windows) "rg.exe" else "rg")
        check(Files.isRegularFile(binary) && (windows || Files.isExecutable(binary))) {
            "Bundled ripgrep is missing or not executable: $binary. Reinstall Chiller."
        }
        return binary.toAbsolutePath().toString()
    }
}
