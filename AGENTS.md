# Chiller — working agreement

## Current scope

The user authorized implementation and overnight continuation on 16 October 2026. Read docs/RUN_REPORT.md first and continue the existing implementation instead of restarting from the plan. Night heartbeat chiller-10-00 continues this thread until 17 October 2026 10:00 Asia/Almaty. Keep the branch master. Use economical gpt-6-luna workers for bounded independent tasks; no more than three workers and no recursive delegation. Follow docs/AGENT_RUNBOOK.md with the actual frozen source contracts in contracts/src/main/kotlin/dev/chiller/contracts/Contracts.kt taking precedence over draft API sketches.

## Project decisions

- The primary integration branch is `master`, as explicitly requested by the user.
- Kotlin/JVM, Compose Desktop, custom Chiller design system; Focus and Studio are two layouts of one app.
- DeepSeek V4.1 Flash is the first available development provider. API model ID: `deepseek-flash`.
- Gemma 4 26B-A4B on vLLM remains a target. Do not mark its live integration verified without access to that server.
- Read README.md, docs/MODULES.md, docs/CONTRACTS.md, and the assigned BACKLOG items before implementation. Read docs/DESIGN.md for UI work and docs/PROVIDERS.md for provider work.

## Collaboration during an authorized implementation run

- Use a coordinator and at most three concurrent workers with the currently available slots. Workers must not recursively spawn other agents.
- Shared contracts, root build configuration, dependency versions and composition root have one owner: the coordinator.
- Each worker may edit only the paths assigned to it. Request contract changes from the coordinator; do not add substitute shared types or copy other modules into yours.
- In a shared checkout, only the coordinator stages/commits and runs full Gradle checks. Coordinate targeted Gradle tests as well; multiple builds of the same outputs can race.
- No mass reformatting or unrelated refactors. Preserve other workers' and the user's changes.
- Workers report files changed, tests actually run, assumptions, unresolved issues and next dependencies. Never describe fake-backed or unexecuted checks as live verification.

## Implementation invariants

- Core contracts must not depend on Compose, Ktor wire DTOs or concrete persistence.
- Never execute partial/invalid tool arguments or silently fuzzy-apply an edit.
- Snapshot validation, structured tool errors, bounded outputs, cancellation and session journaling are part of the vertical slice.
- Plan mode must not execute processes or write project files. The app can persist its own chat/settings data.
- Process cwd/path checks are not an OS sandbox. Keep that distinction explicit.
- Do not replay a side-effecting operation automatically after an unknown crash outcome.
- For DeepSeek tool requests, preserve protocol-required reasoning separately from optional visible reasoning history; see PROVIDERS.md.

## Secrets, external actions and verification

- Do not search the user's machine for API keys. Never print keys, auth headers, environments or credential-store contents.
- Live API tests are opt-in, require an explicitly configured local key and finite budget, and are excluded from default checks. Keep fixture payloads synthetic or sanitized.
- Do not push, publish, create remote repositories, deploy, add recurring automations or change account settings unless requested.
- Run checks meaningful to the change. Report skipped and blocked checks separately from passed checks.
- Do not disable failing checks, ship TODO implementations as working features, or increase scope just to keep agents busy.
- Keep dependency and license choices recorded. Use stable compatible versions, not unverified latest/nightly combinations.
