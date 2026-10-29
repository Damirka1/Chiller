**English** · [Русский](ARCHITECTURE.ru.md)

# Chiller architecture

Chiller is a Kotlin/JVM app with Compose Desktop. The source of truth for shared types and ports is [Contracts.kt](../contracts/src/main/kotlin/dev/chiller/contracts/Contracts.kt); tool schemas and validation live next to their implementation in `tools`. This document describes the structure, not a future API.

| Module | Responsibility |
|---|---|
| contracts | Shared types; provider, file, process and journal ports |
| agent-core | The model loop, context, profiles, limits and helpers |
| providers | Chat Completions, SSE, tool call assembly and the model catalog |
| workspace | Reading, search, snapshots, exact edits and restore |
| runtime | Processes, output limits and cancellation |
| tools | Tool schemas and execution, web, diagnostics and the MCP client (stdio) |
| sessions | Journal, artifacts, restore and export |
| design-system | Themes and shared Compose components |
| app-desktop | Screens, controller, settings and dependency packaging |
| evals | Offline fixtures, scenarios and opt-in live checks |

`agent-core` depends on the contracts; implementations are plugged in by desktop/evals. The contracts do not depend on Compose or HTTP DTOs. The main module dependencies are listed in each module's `build.gradle.kts`.

## Invariants

- Only fully assembled and valid tool arguments are executed.
- File edits check the snapshot and exact matches; a conflict is never resolved by fuzzy search.
- Plan forbids writes to the project and processes; the app may still save its own history and settings.
- Independent reads may run in parallel, changes run in sequence.
- The journal records operations and `run.summary`. After a failure, unknown side effects are never repeated automatically.
- Undo of file tools checks the current state. Changes by external commands have no general undo guarantee; detecting them is bounded by the snapshot size and the hashed files.
- Request and output token limits are not an API money limit. Unknown usage is never counted as zero.
- Path and working-directory checks do not isolate processes at the OS level.

Building and checks are described in [CONTRIBUTING](../CONTRIBUTING.md), user-facing features in [USAGE](USAGE.md).
