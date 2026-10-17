# Chiller — working agreement

## Current scope

The user authorized a focused implementation pass on 17 October 2026: make the tool loop faster and more model-friendly, keep the desktop UI beautiful, and validate it against DeepSeek V4.1 Flash. Read `docs/STATUS.md` first, then `docs/RUN_REPORT.md` for history. The integration branch is `master`.

Live DeepSeek checks are allowed for this pass with small, explicit budgets. The key comes from the user's local `.env` (`API_TOKEN` or `DEEPSEEK_API_KEY`) or the environment. Never print it, never stage `.env`, never copy it into tests, docs or subagent prompts. Gemma on vLLM is out of scope until the server is available; do not claim its integration verified.

## Project decisions

- Kotlin/JVM, Compose Desktop, custom Chiller design system; Focus and Studio are two layouts of one app.
- DeepSeek V4.1 Flash is the development provider. API model ID: `deepseek-flash`.
- Read `README.md`, `docs/STATUS.md`, `docs/MODULES.md`, `docs/CONTRACTS.md` and the assigned BACKLOG items before implementation. Read `docs/DESIGN.md` for UI work and `docs/PROVIDERS.md` for provider work.
- `contracts/src/main/kotlin/dev/chiller/contracts/Contracts.kt` is the frozen source of truth; draft API sketches in older docs lose to it.

## Collaboration during an authorized implementation run

- One coordinator plus at most three concurrent workers. Workers must not spawn other agents.
- Shared contracts, root build configuration, dependency versions and composition root have one owner: the coordinator.
- Each worker edits only its assigned paths. Request contract changes from the coordinator; do not add substitute shared types or copy other modules into yours.
- Only the coordinator runs Gradle. Concurrent builds of the same checkout race; workers report changes and the coordinator verifies them.
- Only the coordinator stages and commits. Use conventional commits (`feat:`, `fix:`, `docs:`, `test:`, `refactor:`), one logical change per commit.
- No mass reformatting or unrelated refactors. Preserve other workers' and the user's changes.
- Workers report files changed, tests they could not run, assumptions, unresolved issues and next dependencies. Never describe unexecuted checks as passed.

## Implementation invariants

- Core contracts must not depend on Compose, Ktor wire DTOs or concrete persistence.
- Never execute partial/invalid tool arguments or silently fuzzy-apply an edit.
- Snapshot validation, structured tool errors, bounded outputs, cancellation and session journaling are part of the vertical slice.
- Plan mode must not execute processes or write project files. The app can persist its own chat/settings data.
- Process cwd/path checks are not an OS sandbox. Keep that distinction explicit.
- Do not replay a side-effecting operation automatically after an unknown crash outcome. Retrying a stateless model request is not a side effect and is allowed.
- Every run journals a `run.summary` with rounds, tool calls, failures, duration and provider-reported usage when available.
- Reasoning required by the provider protocol is stored separately from optional visible reasoning; see `docs/PROVIDERS.md`.

## Verification

- Default checks: `./gradlew check :evals:offlineSmoke`. They must stay green and use no network.
- Opt-in live check: `./gradlew :evals:liveDeepSeekSmoke` with a configured key. Bounded rounds/output; never part of `check`.
- Packaging: `./gradlew :app-desktop:createDistributable`.
- Run checks meaningful to the change. Report skipped and blocked checks separately from passed checks.
- Do not disable failing checks, ship TODO implementations as working features, or increase scope just to keep agents busy.

## Secrets and external actions

- Do not search the user's machine for API keys. Never print keys, auth headers, environments or credential-store contents.
- Do not push, publish, create remote repositories, deploy, add recurring automations or change account settings unless requested.
- Keep dependency and license choices recorded. Use stable compatible versions, not unverified latest/nightly combinations.
