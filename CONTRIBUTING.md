**English** · [Русский](CONTRIBUTING.ru.md)

# Development

You need JDK 21+, ripgrep (`rg`) on PATH and Python 3 for the offline smoke. Use the Gradle wrapper; the first run downloads dependencies.

```sh
./gradlew check :evals:offlineSmoke
./gradlew :app-desktop:run
./gradlew :app-desktop:createDistributable
```

On Windows use `gradlew.bat`; the end-to-end smoke needs a working `python3` command. CI runs unit tests on Linux/macOS/Windows, the offline smoke on Linux/macOS, and builds DMG/MSI. Native UI acceptance is done separately.

[Architecture](docs/ARCHITECTURE.md) · [Providers](docs/PROVIDERS.md) · [Design](docs/DESIGN.md) · [Dependencies](docs/DEPENDENCIES.md)

Make small changes and run the checks that fit them. Preserve other people's work; do not run several Gradle builds of one checkout at the same time. Behavior changes need a regression check. Say which checks were run, skipped or blocked.

Secrets, user chats, traces and local reports are never committed. `.env` and `reports/local/` are ignored. Run live API tests only with a separately approved budget; they are not part of the regular check. Do not put real keys into fixtures.

Releases are published by the workflow on a `vX.Y.Z` or `vX.Y.Z-rc.N` tag (a release candidate is published as a pre-release; the text comes from the matching section of `CHANGELOG.md`). The workflow first checks the tagged commit on Linux, macOS and Windows, then builds the installers. Before tagging, check the exact commit and CI; a local test report does not vouch for the published binary.

Documentation is kept in English (`*.md`) and Russian (`*.ru.md`), with a language switch at the top of each file. Update both versions together.

Before DMG/MSI, `:app-desktop:verifyBundledRipgrep` runs: the bundled ripgrep in the finished app must find a file with Cyrillic in its path with an empty PATH. `:app-desktop:prepareThirdPartyNotices` puts a list of third-party libraries with licenses from their POMs and LICENSE/NOTICE files into the app; it is a list for review, not a legal opinion. Unit tests still use `rg` from PATH.
