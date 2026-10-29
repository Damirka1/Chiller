**English** · [Русский](DEPENDENCIES.ru.md)

# Dependencies and distribution

Versions are pinned in the root and module `build.gradle.kts` files, and Gradle in `gradle/wrapper/gradle-wrapper.properties`.

Main components: Kotlin 2.3.21, Compose 1.10.3, coroutines 1.10.2, serialization 1.9.0, Ktor 3.4.1 and JNA 5.18.1. JNA is used for the system key store: the macOS Keychain (Security.framework) and the Windows Credential Manager (Advapi32); the OS provides the system libraries. Tests only: Compose UI Test of the same version 1.10.3 (Apache-2.0), so settings forms are checked with real text input; it is not part of the app. The Chiller icon was made for the project; asset generators are not runtime dependencies.

JetBrains Runtime API 1.10.1 (`org.jetbrains.runtime:jbr-api`, Apache-2.0) provides the custom window title bar on Windows. The Windows installer is built with JetBrains Runtime 21.0.11 b1163.116 (GPLv2 with Classpath Exception, like OpenJDK; a pinned download from cache-redirector.jetbrains.com) and ships it as the runtime; the macOS installer still uses Temurin 21. On a JVM without JetBrains Runtime the API reports that it is unavailable, and the standard window frame is used.

This list is not a full SBOM or a license review of transitive dependencies. Before distributing binaries publicly, check the license/NOTICE set of the libraries and runtime actually included. For JNA the project chose the Apache-2.0 option of its dual license; upstream notices must be preserved.

Chiller is distributed under the [Apache License 2.0](../LICENSE). It does not replace the licenses of third-party components. The Chiller license text is included in the app resources and passed to the installer builder.

## Medeo font

The interface is set in Medeo 1.000-rc4 (release `v1.0.0-rc.4.1` from DNFDEVS/medeo-font, SIL Open Font License 1.1): Medeo Sans for interface text, Medeo Mono for code, paths and commands. It is a release candidate, chosen deliberately; when 1.0 is out, the files are replaced with the same layout. Medeo is based on Geist (Vercel, basement.studio) and Inter (Rasmus Andersson); the copyrights are listed in the license.

The `design-system` resources hold the static styles unchanged: Sans Regular, Italic, Medium, SemiBold and Mono Regular, Medium, SemiBold (about 3.8 MB), next to `OFL.txt` from the release. SHA-256 of the release archive: `3dede94b30924e56644634ae5f4f85d624c8dfc151d2c42a947472eccc2a0e22`. Sans has the OpenType feature `zero` (slashed zero) turned on; in Mono the zero is slashed by design.

## Bundled ripgrep

The installer contains ripgrep 14.1.1 from the official BurntSushi/ripgrep release. The archive is chosen by the build's OS and architecture, and its SHA-256 is pinned in `build-support/ripgrep.gradle.kts`. COPYING, LICENSE-MIT and UNLICENSE from the archive ship with the binary. Chiller uses the MIT option. The app downloads nothing at startup; the binary is updated with a new build. Windows ARM64 is not yet supported by this packaging.
