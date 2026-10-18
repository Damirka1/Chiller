import java.security.MessageDigest

// Official 14.1.1 release archives; checksums pinned from upstream .sha256 assets.
val hashes = mapOf(
    "aarch64-apple-darwin" to "24ad76777745fbff131c8fbc466742b011f925bfa4fffa2ded6def23b5b937be",
    "x86_64-apple-darwin" to "fc87e78f7cb3fea12d69072e7ef3b21509754717b746368fd40d88963630e2b3",
    "x86_64-pc-windows-msvc" to "d0f534024c42afd6cb4d38907c25cd2b249b79bbe6cc1dbee8e3e37c2b6e25a1",
    "x86_64-unknown-linux-musl" to "4cf9f2741e6c465ffdb7c26f38056a59e2a2544b51f7cc128ef28337eeae4d8e",
    "aarch64-unknown-linux-gnu" to "c827481c4ff4ea10c9dc7a4022c8de5db34a5737cb74484d62eb94a95841ab2f",
)
val os = System.getProperty("os.name").lowercase()
val arch = when (System.getProperty("os.arch").lowercase()) {
    "aarch64", "arm64" -> "aarch64"
    "amd64", "x86_64" -> "x86_64"
    else -> error("Unsupported ripgrep architecture")
}
val target = when {
    os.contains("mac") -> "$arch-apple-darwin"
    os.contains("windows") && arch == "x86_64" -> "$arch-pc-windows-msvc"
    os.contains("linux") -> if (arch == "x86_64") "$arch-unknown-linux-musl" else "$arch-unknown-linux-gnu"
    else -> error("Unsupported ripgrep platform: $os/$arch")
}
val extension = if (os.contains("windows")) "zip" else "tar.gz"
val archiveName = "ripgrep-14.1.1-$target.$extension"
val output = layout.buildDirectory.dir("bundled-resources/common/tools")
tasks.register("prepareRipgrep") {
    inputs.property("archive", archiveName)
    inputs.property("sha256", hashes.getValue(target))
    outputs.dir(output)
    doLast {
        val archive = layout.buildDirectory.file("downloads/$archiveName").get().asFile
        fun digest() = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
        if (!archive.exists() || digest() != hashes.getValue(target)) {
            archive.parentFile.mkdirs()
            val temporary = File(archive.parentFile, "$archiveName.part")
            java.net.URI("https://github.com/BurntSushi/ripgrep/releases/download/14.1.1/$archiveName").toURL().openConnection().apply {
                connectTimeout = 30_000; readTimeout = 60_000
            }.getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
            temporary.copyTo(archive, overwrite = true); temporary.delete()
        }
        check(digest() == hashes.getValue(target)) { "ripgrep archive checksum mismatch" }
        val tree = if (extension == "zip") zipTree(archive) else tarTree(resources.gzip(archive))
        project.sync {
            from(tree) {
                include("**/rg", "**/rg.exe", "**/COPYING", "**/LICENSE-MIT", "**/UNLICENSE")
                eachFile { path = name }
                includeEmptyDirs = false
            }
            into(output)
        }
        val binary = output.get().file(if (os.contains("windows")) "rg.exe" else "rg").asFile
        check(binary.isFile) { "ripgrep binary missing from archive" }
        if (!os.contains("windows")) check(binary.setExecutable(true, false)) { "Cannot set ripgrep executable permission" }
    }
}
