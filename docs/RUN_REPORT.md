# Chiller — ночная реализация 16–17 октября 2026

- Baseline: 79f5950; branch master.
- User authorized implementation, economical subagents, overnight continuation until approximately 10:00 Asia/Almaty.
- Hourly thread heartbeat created: chiller-10-00; ends 17 October 2026 05:00 UTC.
- Goal: N0, then N1/alpha. Live DeepSeek pending explicitly configured key and finite budget. Gemma server unavailable.
- Implemented first-pass source in all ten modules: shared contracts; agent loop/policy; SSE and DeepSeek thinking replay; snapshot workspace edits/undo/recovery; twelve tool wrappers; process supervision; JSONL/artifacts; Focus/Studio UI; actual desktop wiring; offline smoke.
- Toolchain verified by compilation: Gradle 9.4.0, Kotlin 2.3.21, Compose 1.10.3, JVM target 21, current build JDK 26. Dependencies coroutines 1.10.2, serialization 1.9.0, Ktor 3.4.1.
- Shared checkout, coordinator owns build/contracts/integration and Git; workers own assigned modules. Workers use gpt-6-luna medium, no recursive delegation.

## Checks actually performed

- `./gradlew check :evals:offlineSmoke`: passed with the original 33 tests (zero skipped). Includes desktop Kotlin compilation, real filesystem/version/encoding/recovery tests, process timeout/cancellation/flood tests, provider loopback tests, and core/policy tests.
- Offline smoke passed: search → read → snapshot edit → actual three Python greeting assertions → final → history restart. Separate external edit caused STALE_SNAPSHOT and remained untouched.
- Final full `./gradlew check :evals:offlineSmoke` passed: **37 tests, 0 failures, 0 errors, 0 skipped**, plus successful offline smoke. Additional provider tests cover interleaved calls, oversized frames, malformed arguments and truncated valid-JSON calls.
- `./gradlew :app-desktop:run`: first attempt timed out downloading native artifacts; retry succeeded in starting the JVM app. Coordinator deliberately terminated that test JVM before packaging (exit143 is expected cleanup, not a product crash).
- `./gradlew check :evals:offlineSmoke :app-desktop:createDistributable`: **passed** after the custom UI rewrite. macOS .app exists at app-desktop/build/compose/binaries/main/app/Chiller.app. jpackage needs positive first version component, so bundle version is 1.0.0 while product remains alpha0.1.
- Standalone Chiller.app launched with CHILLER_DATA_DIR pointing to project .chiller/local and empty DEEPSEEK_API_KEY. CUA bound it successfully after a long initial native app lookup.
- GUI actually checked: Focus and Studio layouts, light/dark toggle, custom provider settings open/close, suggestion fills composer, offline Send creates visible conversation and history, New chat button returns to welcome. App left open in light Focus. No API key entered or live request made.
- Native keyboard check via CUA super+n did not trigger New chat (including after focusing input); Escape did not dismiss settings, Close button did. Keyboard/native menu binding is a known fix priority, not marked passed. IME/full accessibility/long sessions remain unverified.
- Windows/Linux: unverified. DeepSeek/Gemma live: skipped (no configured key/budget; Gemma server unavailable).
- `git diff --check`: passed. Only project-generated files are staged by coordinator; no secrets/runtime data included.

## Implemented behavior and remaining limits

- Source contracts are a deliberately small v0.1 in a single Kotlin file, with String IDs and typed requests/results. Draft per-package/value-class API remains future refinement; do not generate a second competing API from docs.
- Plan filtering is enforced both in coordinator and tool executor. Partial/length tool calls and repeated call IDs never execute. Processes require explicit UI permission.
- LocalWorkspace reads are bounded to 200 lines/6000 characters and observe only returned lines. Edits preserve UTF-8 BOM, EOL/EOF and POSIX executable permissions; malformed/stale/overlapping/unobserved edits fail.
- Checkpoints journal before-images and reconcile pending file operations by hashes. Restart undo is supported for committed file changes. Unknown operations never replay automatically.
- No-clobber create/move currently requires hard links; move limited to the same directory. ACL/xattrs not copied; parent sync support recorded. Portable NIO cannot guarantee CAS against an external writer.
- Process termination is best-effort descendants, not process groups/job containment. Logs capped at 1 MiB/process, pages at 16 KiB. Native credentials/PTY/screenshots remain future work.
- Session logs have critical append fsync and recovery for a truncated final record. Index currently derives by scan. Markdown/JSON export and preview implemented; retention UI not implemented.
- Context selects whole turns under conservative character/round/time bounds; no LLM summarization or exact provider token accounting. Reasoning replay stored privately; raw token deltas are not persisted individually.
- Ask-user works during an active run; durable pending-question resume needs completion. Per-operation unknown-outcome ledger needs further hardening beyond call IDs and journal reconciliation.
- Profile changes start a new conversation. Keys are session-only UI input or explicit environment reference; never saved to settings.
- UI was rewritten by coordinator after user rejected Material-looking first screen. Uses custom Foundation controls and explicit desktop typography. Focus/Studio/theme/settings/offline-chat were reviewed in actual window. Keyboard shortcuts/IME, long-scroll behavior and native titlebar dark appearance still need fixes/review.

## Next work in priority order

**User steering at midnight:** first Material-looking UI was rejected. Coordinator replaced it with custom Foundation controls, neutral selection, explicit desktop typography, compact navigation and a unified welcome/composer, then reviewed both layouts in native app. Preserve this direction; do not reintroduce standard Material buttons, switches, segmented controls or decorative demo cards. Further UI correctness/polish takes priority over feature expansion. The revised direction has not yet received user feedback.

1. Full offline check is green (47 tests). Preserve this integration baseline while addressing remaining items.
2. Fix keyboard shortcuts with proper window/native menu handling; verify focused/unfocused Cmd/Ctrl+N and Cmd/Ctrl+Enter, Escape in settings. Review native titlebar appearance for dark theme. Keep source UI style custom.
3. Initial implementation checkpoint is being committed to master. Standalone test .app remains open (not a Gradle task); current terminal session97162 belongs to that process. Reuse CUA app path for inspection; old unbundled net.java.openjdk.java could not bind.
4. Complete pending-question journaling/resume, context/budget telemetry, Markdown/JSON export and packaging. Keep API-key live checks opt-in.
5. Finish first N0 desktop acceptance then proceed N1/alpha; do not start MCP/worktrees/LSP before integrated alpha is reliable.

## Build ownership

All three workers completed their assigned source/test changes. Coordinator owns next build slot and Git. Do not run concurrent Gradle builds in this checkout. If a local run task is still resolving runtime dependencies, inspect/finish it before launching another Gradle build.

## Checkpoint: context, export and desktop integration

- Added ContextPlanner: preserves system policy and newest whole user turns, tool/result pairing and provider replay. Oversized current turn fails explicitly; earlier turns can be omitted. Character-based estimate with output/schema reserve, not exact provider token accounting or LLM summarization. Five new tests. Footer exposes estimate/rounds; three repeated identical tool errors stop the loop.
- Added Markdown/JSON session export with preview and native save picker. Known credential fields/patterns redacted, replay excluded; arbitrary user text still needs review. Artifact references only, not a portable blob backup. Three new tests.
- Added native File/Conversation/View menus and macOS folder picker. Actual native menu Send produced an offline reply; File → Markdown opened the correct session preview, Close returned to conversation. Latest keyboard checks via CUA Cmd+Enter and Escape still did not change the window; keyboard acceptance remains open. Do not claim it fixed solely because handlers compile.
- Found offline history without workspace attempted to construct a workspace engine. Fixed view-only restoration without engine, and added a desktop controller regression test. Coordinator/controller command guards now cover engine setup and running jobs to avoid simultaneous send/session changes.
- `./gradlew check :evals:offlineSmoke :app-desktop:createDistributable` passed after controller/context fixes. 45 tests passed before the new desktop regression test; its targeted result is recorded in the following checkpoint. Updated .app built successfully. GUI ran the immediately preceding build, so the latest controller restoration fix is covered by unit verification, not yet a new native window check.
- Latest first implementation commit: 6961b00. These changes form the next local checkpoint on master. No live API calls, publishing or pushes.
- Next: durable pending-question/unknown-operation restoration, native keyboard diagnosis and offline history GUI recheck. Alpha is still partial.

### Verified recovery checkpoint

- Full command `./gradlew check :evals:offlineSmoke :app-desktop:createDistributable` passed after recovery changes: **47 tests, 0 failures/errors/skipped**. Includes desktop offline-history regression, context/export tests and interrupted tool/question recovery. Real smoke and macOS packaging passed.
- Recovery recognizes unresolved tool calls in order and unfinished runs. Saved question prompt/options restore as read-only in interrupted UI; there is no live deferred after restart. Guidance requires a new chat. This is durable inspection, **not automatic continuation**. Unknown side effects are never replayed.
- Native GUI actually verified menu Send and Markdown preview/close. Settings closed by button; Escape still did not dismiss under CUA. The latest recovery/question UI has compilation/unit coverage but not a fresh visual check.
- Next build slot is free; worker resume_hardening finished. Local .app may still be running an earlier build in settings/conversation. Relaunch the rebuilt bundle for latest source.
- Startup: `./gradlew :app-desktop:run`; isolated offline: `CHILLER_DATA_DIR="$PWD/.chiller/local" DEEPSEEK_API_KEY= ./gradlew :app-desktop:run`.

## Morning checkpoint — 17 October

- Source baseline 1cb6aff; branch master. No new agents or live calls needed for this pass.
- Corrected modifier ordering: preview key handlers precede focusable nodes in main canvas/settings. This fixes event scope structurally but **does not close keyboard acceptance**: after rebuilding and relaunching, CUA Cmd+N and Escape still left the visible state unchanged, including after raising the window. Needs manual keyboard diagnosis; menu/button actions remain usable.
- Rebuilt and launched actual bundle with explicit empty DEEPSEEK_API_KEY and project-local CHILLER_DATA_DIR. Clicked historical offline session: transcript restored correctly with no workspace error. This closes the earlier history GUI regression.
- `./gradlew check :evals:offlineSmoke :app-desktop:createDistributable`: passed. 47 tests; actual search/read/edit/Python/restart/stale-rejection smoke passed. Live API tests skipped, not implied by offline results.
- Latest test process session22027. CUA subsequently reported the user changed the app, so further UI actions stopped to avoid stale targeting.
- **Delivery status: working desktop prototype, partial alpha.** Remaining alpha acceptance: keyboard/IME/accessibility and long-session GUI testing, durable answer continuation/steering (currently read-only interrupted question recovery), retention/schema migration, exact provider capabilities/budgets and live DeepSeek/Gemma validation. Broader planned eval corpus, filesystem watcher and platform matrix are not complete.
- Run: `./gradlew :app-desktop:run`. Bundle: `app-desktop/build/compose/binaries/main/app/Chiller.app`. No push/publication.

## User-authorized design refinement and DeepSeek smoke

- User approved the refinement plan and implementation. Origin configured to https://github.com/Damirka1/Chiller.git, master retained; no push.
- Reworked Focus composition: fixed bottom composer for empty/conversation states, common 760 dp content axis, centered smaller welcome, compact 248 dp sidebar with one New action, no permanent bottom API/status strip. Neutral light/charcoal dark palettes, legible labels and keyboard focus borders.
- Studio has a bounded draggable divider; code blocks have language/copy and horizontal scrolling; diff scrolls horizontally. Settings scroll and apply permission/motion toggles only after Apply, not on Cancel. Per-session in-memory drafts survive switching.
- User explicitly created root .env with API_TOKEN. Added bounded UTF-8 dotenv resolver and four synthetic tests; no interpolation/execution/logging. Explicit DEEPSEEK_API_KEY/API_TOKEN environment precedes root working-directory .env. .env is ignored and not staged. UI keys remain memory-only; Keychain not implemented. Finder cwd discovery is not automatic; use Gradle from repository root.
- User explicitly approved ONE request to deepseek-flash, no tools/files, max_tokens=128. Executed once: HTTP 200, exact OK, finish_reason stop; usage 9 prompt + 1 completion = 10 tokens. No retries. This verifies authentication/model access only; live multi-round agent/workspace loop remains unverified. No secret printed.
- UI says key present/unverified rather than falsely connected. External smoke does not change profile state inside a running app.
- Full check + offlineSmoke + primary macOS package passed with **51 tests, zero failures/errors/skips**, including dotenv parser fixtures.
- Added optional chillerPreview Gradle property: separate ChillerPreview app/bundle ID, offlinePreview JVM flag suppresses credential autoload. This lets us inspect UI without closing the user's running Chiller (which may hold a key only in memory).
- GUI verified actual light Focus/welcome and dark Studio/conversation, settings dialog open/close; final preview with isolated synthetic [Макет] session: restored transcript, code Copy changed to Copied, diff expanded in Studio. Synthetic fixture explicitly states no changes/tests executed. Own fixture files under /private/tmp/chiller-design-review only.
- Sandbox GUI process launch aborted without opening a window (exit134); launching own preview with escalation worked. CUA fallback initially used default app data; subsequent final preview explicitly uses isolated temporary data and no credentials. No live calls from preview.
- Manual/remaining checks: keyboard/IME and native dark titlebar, full narrow/scale matrix. Divider drag is implemented but CUA did not visually confirm width change; do not mark resize acceptance passed. Keychain and connection-test UI deferred.

## Planning checkpoint — message authors and UI finishing

User requested a more detailed implementation plan suitable for a smaller model. Added docs/UI_FINISHING_RUNBOOK.md: six stages with concrete file ownership, proposed backward-compatible metadata contracts, prerequisites, steps, tests and acceptance checks. Includes model authorship/export, tool-call activity mapping/grouping, send acknowledgment/drafts, model selector, history rename/export, Studio/preferences, connection states/probe and keyboard/accessibility. Contains a bounded ready-to-use prompt for stage I. This checkpoint changes documentation only; none of these newly planned tasks are claimed implemented. No builds/live calls required or executed for this documentation change.

## UI finishing — stage I

M1/M2 implemented: backward-compatible stored message origin, run model telemetry with terminal reset, historical/streaming author labels and full-answer Copy, origin-aware Markdown/JSON export. Offline and legacy labels remain distinct. Provider wire excludes local metadata and preserves replay. Targeted agent-core/providers/sessions/app-desktop tests passed after correcting a cross-module Kotlin smart-cast compile error. Sessions export was rechecked after worker handoff. No live calls. Independent UI preferences helper prepared but not integrated yet; subsequent stages remain in progress.

## UI finishing — integrated stages II–VI

- Implemented T1/T2: activity IDs link to tool calls, ordered per-round projection, legacy unlinked section, compact read/write groups, visible failures and event-based run indicators. Interrupted questions remain read-only and now retain their choices visibly. Failed/cancelled terminal state restores without provider replay.
- Implemented C1/C2: durable-append acceptance callback, bounded request-ID dedup cache, SendReceipt, snapshot-matched draft clearing, rejected-send preservation and return-to-field; model picker and per-endpoint remembered IDs create new chats while preserving draft. Endpoint-origin changes clear the key; no secret is carried to an unrelated host.
- Implemented H1/S1: rename journal projection, date groups/full title hints/explicit-session export, persisted atomic UI preferences, system/light/dark, normal/compact, reduced motion, saved layout/width, narrow Studio switch and selected-file diff stats. Divider has cursor/semantic name plus accessible width buttons. Pointer-drag acceptance remains unconfirmed under CUA.
- Implemented P1: explicit injectable connection checker with one request,128 output cap,30s timeout,no retries/tools/project text; streaming transport lifetime,64KiB body bound, protocol validation, endpoint checks, sanitized Russian errors, atomic revisions and profile/key snapshot under commands mutex. Late or cancelled results cannot verify another profile. No new remote paid calls.
- Native GUI: stored model/legacy/offline labels, grouped tools/Copy, rename updating header/list, Cmd+N, Cmd+Enter one offline message, normal Enter multiline, Escape closing settings with draft intact; dark/compact Apply persisted after restart; failed run restore and Return text; read-only question; scroll/jump in1200-message history; 960x720 Studio/chat switch and draft survival; model selection through settings/popup and missing-workspace rejection preserved text.
- Explicit GUI probe was against ONE local synthetic HttpServer request, using a synthetic key: max_tokens128, streamfalse, one message, no tools; valid fixture response produced Verified. This is UI/transport verification, not live DeepSeek/Gemma validation.
- Full command `./gradlew check :evals:offlineSmoke :app-desktop:createDistributable` passed with **81 tests,0 failures/errors/skipped** after correcting initial smart-cast/return compile errors and two asynchronous fixture ordering issues. No checks disabled. Offline smoke and primary macOS packaging passed. Extra preview packaging initially hit a write-permissions error in its generated runtime dylib; adding owner write to the generated preview bundle and rebuilding resolved it.
- Reusable synthetic GUI generator: scripts/create-ui-fixtures.py, new/empty output directory only. Preview supports explicit width/height/font-scale test variables. User's normal Chiller process and real credentials were not touched by these preview interactions.
- Detailed acceptance and remaining platform checks: docs/UI_ACCEPTANCE.md. Native dark titlebar, true IME/VoiceOver and exhaustive cross-platform acceptance remain unverified; these are not reported as passed.

## Pause and evening plan — 17 October

User paused further implementation and requested a smaller composer and a remaining-development plan. Saved docs/EVENING_PLAN.md. User selected continuation 15:00–20:00 Asia/Almaty today. No new product edits or paid requests after this steering.

Important verification boundary: the last full green build above preceded a small ChillerApp.kt change to font-scale-aware sidebar width, compact-header selection and bounded model control width. That final patch has not been compiled or visually accepted. A preview at fontScale=2 exposed clipping/overcrowding before that patch; do not report scale acceptance complete. The next run must validate the exact checkpoint first. Studio pointer drag is still unconfirmed. Current changes are being saved as a local implementation checkpoint, not declared finished alpha. The latest own preview uses isolated synthetic data at /private/tmp/chiller-ui-finishing-review; the user's normal Chiller was not closed.

## Early restart — 17 October, planning gate

User explicitly resumed after limits reset. Read-only audit began at14:43 Asia/Almaty; preserve the requested30-minute planning phase, product implementation may start after15:13. Deadline20:00 remains. The active coordinator has already completed initial module reviews and three gpt-6-luna read-only audits; do not create duplicate audits/workers on the15:00 heartbeat. Current handoff: docs/IMPLEMENTATION_AUDIT_2026_10_17.md. Existing uncommitted UI receiver qualification was present before this run and remains untouched. No builds/live calls/product edits during this planning phase. Baseline native screenshot saved to reports/local/evening-ui/before-composer.png (ignored local artifact); it reflects the previously built preview, not verification of current source. Next: publish shared contracts and hand off D1/M1/A1 after the gate.

## Tool-loop speed pass and compact composer — 17 October

- User asked for a faster, more model-friendly tool loop, the approved compact-composer design and live DeepSeek validation. Coordinator plus three workers on disjoint paths; only the coordinator ran Gradle and committed.
- Implemented: snapshot lineage after `edit_file` (further edits of the same file reuse the returned `snapshotId`; observed lines are re-mapped and stale hashes still fail); concurrent READ tool calls with effectful calls kept ordered; `read_file` clamping past EOF; compact `path:line` search with optional `context`; `list_files` returns plain paths and applies globs after ripgrep so ignore rules survive; stable per-round system prefix plus pruning of old large tool results for context pressure; provider `usage` parsed and summed; `run.summary` journaled for every run; bounded 429/5xx/transport retry only before the first delta; atomic `settings.json` replace; journal append no longer re-parses history (size-validated tail cache) and session listing is cached; `maxRounds`/`maxOutputTokens` configurable.
- UI: composer is 84 dp when empty and grows to a ~200 dp block before the field scrolls; measured toolbar with mode + content-sized model control (chevron inline, truncation with full-ID tooltip) and Send/Stop only; sidebar growth capped; top bar grows instead of clipping; the project row above the composer is gone and the process-permission toggle moved into the Changes popup (still in Settings). Send receipts, pending/IME guards and draft behaviour untouched.
- `./gradlew check :evals:offlineSmoke` passed: **105 tests, 0 failures, 0 errors, 0 skipped**, offline smoke passed. `:app-desktop:createDistributable` passed. The app started from the current source with isolated data and an empty key; no exception in the log. Visual/keyboard/IME acceptance remains manual and is not claimed.
- New opt-in task `:evals:liveDeepSeekSmoke` (key from `DEEPSEEK_API_KEY`/`API_TOKEN`, bounded rounds/output, never part of `check`). It ran twice on the final code against `deepseek-flash`: status completed, 5 rounds, 6 tool calls, 0 failed calls, 9.1–9.8 s, prompt ~10.8k, completion 576–634, cached 8 192–9 600; an independent `python3 checks.py` after the run exited 0. The model actually used list/read/edit/process tools, not a scripted provider.
- Commits: `feat(workspace)`, `feat(agent)`, `feat(providers)`, `test(workspace)`, `docs`, `feat(ui)`.
- Not verified: visual acceptance of the new composer, keyboard/IME/VoiceOver, Windows/Linux, Gemma/vLLM, model-catalog discovery, subagents, read-only Git tools. Local vLLM without a credential is still unreachable.

### Model catalog and popup follow-up

- The composer popup covered the toolbar and the input: menus are now anchored to the composer width, open 6 dp above the toolbar with elevation, and no longer hide the mode/model controls. Widths are 224/300 dp with font-scale caps.
- The model menu now shows discovered API models, remembered/manual ids and the selected one with source tags and exact-ID tooltips, plus explicit refresh with loading/failed/stale states.
- New `ModelCatalogClient` + `KtorModelCatalogClient`: one bounded `GET <base>/models` (no redirects, 20 s timeout, 1 MiB body, 500 models, sanitized errors). `DesktopController` owns a revision-guarded refresh, clears the cache on endpoint/credential change and keeps the last list marked stale on failure.
- `./gradlew check :evals:offlineSmoke` passed with **121 tests, 0 failures, 0 skipped**; offline smoke passed. No live request was made for discovery; the provider fixtures are loopback-only.

## Harness pass — instructions, tools, traces, evals — 17 October

- New tools: `replace_text` (exact text, match count must equal `expected_count`, applied as whole-line edits through the existing snapshot/undo path) and `update_plan` (model checklist, no project effect, allowed in plan mode). `WorkspaceService.replaceText` added with a default `UNSUPPORTED`.
- Project instructions (`AGENTS.md`, `CLAUDE.md`, `.chiller/instructions.md`) become a separate stable system message; sources are journaled as `context.instructions`. The system prompt now describes the read → replace/edit chain, planning and verification honesty.
- Per-round traces via `RunTraceSink`; desktop writes bounded JSONL per session, the Studio run tab opens it. A key-value scan of the live traces found no credential.
- `:evals:liveEval` with eight tasks and independent checks; offline smoke proves each check fails before and passes after a reference solution. Provider profiles may opt out of the key; the eval runner does so only for loopback.
- `./gradlew check :evals:offlineSmoke` passed: **138 tests, 0 failures, 0 errors, 0 skipped**.
- Live `deepseek-flash`, one attempt per task: **8/8 passed**, 32 rounds, 38 tool calls, 0 failed calls, 55.9 s, prompt 75 299 / completion 3 798 / cached 61 440 tokens. Tools used: read_file 7, list_files 6, search_text 5, run_process 4, replace_text 4, edit_file 1, create_file 1; `update_plan` was not used on these short tasks.
- Not verified: Gemma/vLLM (no server), keyless endpoints in the desktop app, harder or longer tasks, repeat variance, UI click-through of the new run-tab items.


## macOS title bar and logging binding — 17 October

- macOS window uses full-window content with a transparent, untitled title bar (`apple.awt.fullWindowContent`, `apple.awt.transparentTitleBar`, `apple.awt.windowTitleVisible`). The traffic lights float over a 30 dp draggable strip at the top of the sidebar; with the sidebar hidden the toolbar leaves 78 dp for them. Empty toolbar space drags the window (Compose `WindowDraggableArea`). The build stamp moved from the title to the Settings footer.
- Dependency: `org.slf4j:slf4j-nop:2.0.17` (MIT) as runtimeOnly in app-desktop and evals, silencing the SLF4J no-binding warning from Ktor; logging stays off.
- Not verified: the window appearance itself (no screen-capture permission in this session); double-click-to-zoom on the hidden title bar is not implemented.

## Autonomous pass — subagents, Studio, providers, settings, CI — 17 October (evening)

- Subagents per the audit design (contracts/Subagents.kt, SubagentSupervisor, SubagentTools): read-only, depth one, path-scoped, mailbox, cursor wait, cancel wins, orphan stop, restore as interrupted; per-child limits; helpers' usage reported separately. Not done: a shared parent+children token budget.
- Studio rebuilt: changes grouped by file with per-change diff and undo, helper cards with progress and stop, run metrics with helpers total.
- Providers: 12 presets, keys per origin (memory only; env/.env by variable name), loopback without a key in the desktop app. Only DeepSeek verified live.
- Settings page replaces the modal; AgentOptions persisted (rounds, output, helpers, instructions).
- Versioning `chillerVersion=1.1.0`; DMG/MSI formats; About shows version/build/commit. `packageDmg` built Chiller-1.1.0.dmg locally.
- CI/CD: ci.yml (Linux/macOS/Windows tests, DMG+MSI artifacts), release.yml (tag → installers → GitHub Release). Not run on GitHub: nothing was pushed.
- Composer @-mentions, user-message edit/copy, attention request when a background run ends.
- Checks: `./gradlew check` 154 tests, 0 failures, 0 skipped (full rerun); offline smoke passed with 9 meaningful eval checks.
- Live deepseek-flash: full eval 9/9, 0 tool errors; delegate-research ×2 → 2/2 with both helpers completed each time.
- Not verified: Windows/Linux, GitHub workflows, other providers, Gemma/vLLM, @-mention popover and settings page by hand (offscreen renders only for the settings page and Studio).


## Readiness fixes and Focus polish — 17 October

- Continued from 48a7503 on master after the user's approval of the readiness-fix plan. Three bounded gpt-6-luna workers; only coordinator ran Gradle/Git. No remote or paid API calls.
- Provider setup now accepts an endpoint/key without a model; sending and the paid connection check require a selected model. Catalog choices retain friendly name plus exact ID. Existing origin-scoped credential and revision guards preserved.
- Lifecycle calls are ordering barriers between parallel READ batches. Supervisor inbox drain/round advance is atomic. Important state is appended before publication; failed spawn/message acceptance rolls back. Cancel and parent finish join child jobs, including terminal snapshots with jobs still settling.
- Added RunBudgetProvider shared by parent/helpers in the desktop composition root: 48 logical model streams and 32768 output tokens by default, adjustable in Agent settings. Atomic reservations clamp each request; only successful measured responses refund unused output, failed/unknown responses keep their reserve. Input tokens, transport retries and money are not bounded by this counter. Synthetic demo has no model spend and bypasses this wrapper.
- New-session/send/reset wait for terminal-run cleanup instead of silently ignoring commands. The former flaky demo-return test now also requires actual completion.
- macOS CredentialStore uses JNA 5.6.0 and Security.framework, explicit save/delete by origin, settings persist only origin references. Tests and preview inject None/fake gateways, no real Keychain entries were read/written in this pass. Other OS platforms retain session/env credentials. Keychain native permissions and real store operations remain unverified.
- Focus: text-driven composer height, folder in toolbar, completed action groups collapsed, failed/question rows visible, completed/stopped demo panel, simpler project card and expanded Studio diff. Offline preview now defaults to its own temp data directory rather than ~/.chiller.
- First integration build caught a missing native-store size constant; first new storage test had a wrong expected origin (omitted normalized :443). Both corrected. A full rerun `./gradlew check :evals:offlineSmoke :app-desktop:createDistributable --rerun-tasks` passed:171 tests,0 failures/errors/skipped. Native GUI then exposed demo spending conservative unknown-usage reservations; demo was excluded from model budget and completion asserted in its regression test. Following check/offlineSmoke/preview package passed again.
- Native GUI on latest preview: fresh demo completed17 rounds/24 actions, Python exit0, two helper results, one intentional NO_MATCH; answered README question; demo completion panel and new normal dialog; compact text composer and send control; dark/light switch preserved draft; blank-model OpenAI preset Apply enabled and applied, catalog action available afterward (no network request); restored demo in Studio and expanded README diff. Screenshots under ignored reports/local/fixes-ui/.
- Not accepted here: real Keychain prompt/storage, paid-provider regressions, narrow-window/large-font/IME/VoiceOver, Windows/Linux and Gemma. No push/publication.


## First preview release preparation — user-authorized GitHub publication

- Continued baseline841cd19 on master; economical bounded workers owned workspace/runtime/history, web/screenshot and UI paths; coordinator integrated shared contracts, composition, builds and Git. No new paid model calls, no reading/staging `.env`.
- Completed current reliability pass plus safe nested parent creation/create_directory; permission-gated public search/fetch; show_file/image cards; app-private paste/drop attachments (>5120 UTF-8 bytes becomes file); session-owned imports block Send; cross-session references rejected.
- Added explicit interrupted-run recovery without replay, full repeated-error-batch journaling, whole-turn delete and atomic history.replace_turn. Editing saves without model/process execution. Activity projection binds unique journal message sequence/operation identity, avoiding reused provider-call-ID leaks. JSON raw journal is labelled and retains logical deletions; Markdown shows projected history.
- macOS screenshot tool is explicit selected-window only, with app toggle and preflight OS permission; model cannot choose another window. Fake-backed tests passed; actual selected-window capture remains unverified. Binary image metadata is model-readable, pixels are not model vision input.
- Native GUI before these additions:980×760 Focus/Studio, model waiting, helper panel, actual Python exit0, Stop/question and new-chat flow. Found and fixed lingering running activity after stop. Fresh paste/drop/edit GUI acceptance attempt could not bind isolated Preview through CUA (app inventory showed it, getApp returned invalid/not found); not marked passed. IME/VoiceOver/full platform GUI matrix pending.
- Public `:tools:webSmoke`: first run exposed missing CSS-class null handling in real DDG navigation; fixed with regression fixture. Subsequent search returned3 results; HTML and bounded RFC plain-text fetch passed. GitHub JSON endpoint was unavailable (HTTP/transport failure); JSON preservation and sanitized structured transport failure are fixture-tested. No keys or project content were sent.
- Final local command `./gradlew check :evals:offlineSmoke :app-desktop:keychainSmoke :app-desktop:createDistributable`: **passed**,241 tests,0 failures/errors/skipped. Offline oracle:14 checks fail untouched/pass reference. Native Keychain synthetic create/reopen/update/delete passed with JNA5.18.1. First integrated webSmoke command failed due wrong entrypoint name (WebSmokeKt vs object WebSmoke), corrected; not concealed as a feature failure.
- Original matte mint/snowflake icon generated from code-native ChillerMark geometry, no external art. PNG/ICNS/multi-resolution ICO committed; Main window and platform packages configured. Rebuilt main .app Info.plist points to Chiller.icns; bundled bytes exactly match source. Generator scripts committed; CI uses prebuilt assets.
- User explicitly authorized push and first-release tag after completion. Target is existing private Damirka1/Chiller repo/master; no existing tags/releases at preflight. Version1.1.0 retained, first release marked Preview. Remote CI/release outcome recorded in a following checkpoint once actually executed.
- Run: `./gradlew :app-desktop:run`; normal package `app-desktop/build/compose/binaries/main/app/Chiller.app`. Unsigned installers, Gemma/live-new-tool behavior and native Windows/Linux remain limits. OS sandbox/PTY/LSP/writable helper worktrees/MCP/multi-file transactions are not claimed complete.


### Release gate follow-up

- Synchronous transferable snapshot now captures text/file paths/image reference before native drop completes; async importer never re-reads the expired transferable. One-shot expiring6 KiB transferable regression added. Explicit screenshot/network catalog admission and denied-capture-no-invocation assertions added.
- `./gradlew check :evals:offlineSmoke :app-desktop:createDistributable`: passed again, **243 tests,0 failures/errors/skipped**. Current master was pushed as51755a6; CI39050486193 started, macOS tests+offline passed while other jobs were pending at this checkpoint. Follow-up commit will trigger a fresh full matrix before tagging.


### Published first preview — completed

- Functional/tag commit: **6fdc7d8c968f615880f30d6c8cec92a6be5fa041**. Annotated tag **v1.1.0** pushed after CI success; branch remains master. Documentation-only final report follows the tag without changing application code.
- [CI39050745646](https://github.com/Damirka1/Chiller/actions/runs/39050745646): **success**. Tests passed on Ubuntu/macOS/Windows; offline smoke passed on Ubuntu/macOS (intentionally not configured on Windows); both DMG/MSI installer jobs passed. POSIX-only tests are skipped on Windows, not presented as native process verification.
- [Release39051514743](https://github.com/Damirka1/Chiller/actions/runs/39051514743): **success**, both installers built and publication job completed. [Chiller v1.1.0 Preview](https://github.com/Damirka1/Chiller/releases/tag/v1.1.0) is published (draft=false, prerelease=true). Prepared feature/limit notes applied after workflow publication.
- Assets verified through GitHub API as uploaded: **Chiller-1.1.0.dmg**,150580881 bytes, Apple Silicon arm64; **Chiller-1.1.0.msi**,136930111 bytes, Windows x64. Runner architectures were confirmed from completed installer logs; macOS package is not universal/Intel. Linux is source-build only. Assets are unsigned.
- GitHub-reported SHA256: DMG `a42fae5828e18dc6fcb1fa8ae7523f3ed6d70598ea46d36eeeaf3574c1db5084`; MSI `24eeff042a0855b624762c279a994b69666a0398a4b893dcdb90f2f0d9dc2eea`.
- Remote CI emitted nonfatal warnings for older Actions runtime/setup-java versions and upcoming runner image changes. Updating/pinning those is follow-up maintenance; no successful check was disabled to publish. Final docs-only commit skips a redundant CI rebuild; application code/tag are the fully checked6fdc7d8.
- Remaining acceptance: real selected-window screenshot permission/capture, clipboard/drop/native keyboard/IME/accessibility matrix, Windows/Linux interactive UI, fresh bounded live eval of new tools/attachments, Gemma/vLLM. Image vision, OS sandbox/PTY/LSP, writable helpers/MCP/multi-file transactions remain outside preview scope. No new paid calls were made in this pass.


## Rich chat results — 17 October 2026

- User-authorized follow-up to exported weather chat: Markdown tables were valid in the journal but unsupported by the UI; show_file had already succeeded before the final answer, while its result was hidden among collapsed activities and displayed as a 96×68 cropped thumbnail.
- Markdown now renders pipe tables with header/alignment, shared bounded column widths, wrapping, horizontal scrolling and inline formatting. Escaped/code-span pipes are parsed; malformed candidates retain literal content. Regression includes the exact seven-row table from the supplied export. This is a supported Markdown subset, not a full GFM implementation.
- PNG/JPEG file cards show the complete image up to 380dp high using Fit, plus dimensions, file metadata and explicit open/reveal actions. Successful show_file/capture results are rendered outside the activity disclosure, deduplicated by path within the group. Tool details remain independently collapsible.
- Click/open launches a resizable image dialog with fit/100%, plus/minus, wheel/pinch zoom, drag pan, Escape/0/+/− controls. Existing bounded image decoding and path-validation callbacks remain in use (4MiB/16MP previews); no automatic remote-image loading. Geometry tests verify fit and pan bounds.
- Restoring a session now uses the matching latest terminal run.summary for duration, rounds, counters and provider usage; later history edits invalidate those metrics. In-memory terminal duration and persisted summary share one value after cleanup. Tests cover restored metrics and stale summaries after history replacement.
- Studio explicitly labels cumulative input/output across model rounds and distinguishes tool errors from observed nonzero process exits/timeouts, deduplicating repeated process reads within the current user turn. Context remains a separate approximate value.
- Model instruction reinforced: present generated results before the final answer, preserve source-data meaning and label approximations (daily extrema do not establish separate day/night intervals). This is prompting, not a claim of newly live-verified model behavior. The user's weather project and exported chat were not modified.
- Verification: `./gradlew check :evals:offlineSmoke :app-desktop:richResultsPreview :app-desktop:createDistributable` **passed**,253 tests,0 failures/errors/skipped. Offline smoke and 14 task oracles passed. Initial targeted compile caught a missing required target in a new fixture test; fixed before the successful gate.
- Explicit `richResultsPreview` task renders a synthetic chart/table in light/dark and 1120/560px widths to app-desktop/build/reports/rich-results. All four PNGs rendered; light-wide/dark-narrow visually inspected. `-PrichResultsInteractive` starts an isolated fixture without providers/credentials/user sessions. Native fixture started without logged errors; CUA listed RichResultsPreviewKt but getApp failed with Invalid app, so click/gesture/Escape native acceptance is **unverified**. The fixture process was stopped afterward. No new API requests or remote publication.
- Updated local application: app-desktop/build/compose/binaries/main/app/Chiller.app. Existing GitHub v1.1.0 assets are not rebuilt by this local command.

## Harness features — profiles, comparison, rewind, auto-verify — 17 October (night)

- Worked in an isolated worktree branch (feat/model-profiles) while another agent changed Markdown, previews and the run tab; master was merged in without conflicts.
- Model profiles (full/compact/auto by model id), ProfiledTools, optional system prompt, provider requestExtras that cannot replace protocol fields; Settings → Agent profile card. liveEval takes CHILLER_EVAL_PROFILE and reads the key from the nearest .env.
- Live deepseek-flash, compact profile, 13 tasks: 13/13 with ~146k prompt tokens versus ~235k for the full profile on the same tasks.
- In-app model comparison on the shared runEvalSuite; live check full vs compact on two tasks: both 2/2, compact 19k vs 55k prompt tokens.
- Turn rewind through per-change undo; fixed a pre-existing bug where undo of delete_file always threw NoSuchFileException (regression test added).
- Auto-verify: per-project check command, argv without shell, only with process permission; live loop failed once (exit 1), the agent fixed the remaining case from the output, the second check passed.
- Checks after merging master: 261 tests, 0 failures/errors/skipped; offline smoke with 14 task oracles passed.
- Not verified: Gemma/vLLM (including structured-output extras), the new pages by hand in the running app (comparison page rendered offscreen; the banner and rewind button only through tests).


## Paste, vision, loss metrics, smart context — 17 October (late night)

- Branch feat/paste-vision-context. Instant paste cards with background encoding (4K image ~160 ms, off the UI thread).
- Vision: image_url parts for the two newest user messages, at most four images per request, downscaled to 1568 px JPEG; Settings → Agent → Изображения (auto by model id / on / off); the composer note says whether the model will see images. Verified live on 18 October with :evals:liveVisionSmoke (see the audit follow-up below); the Gemma server is unavailable.
- Eval loss metrics: repeated reads (same path and range with no write in between), failed file-changing calls and tool output characters, in the CLI line, report.md, summaries and the in-app comparison.
- Compact profile: project map (files by folder with top-level declarations, ≤3000 chars) as a stable system message, find_symbols in the toolbox, prompt for fragment reads.
- Live A/B, deepseek-flash, compact, 14 tasks, n=1 each: without map 14/14, 67 rounds, 78 calls, prompt 154 826, 120 s; with map 14/14, 56 rounds, 61 calls, prompt 143 344, 108 s. Navigation tasks went from 4 to 2 rounds; edit-large-file went from 4 to 5 rounds. Repeated reads and failed edits were 0 in both runs.
- Found and fixed: four tests (ReadOnlyCodeService ×3, RunBudgetProvider ×1) plus two attachment tests were never executed because runBlocking returned a value and JUnit 5 skips non-void tests. All now run and pass; declared and executed counts match (277).
- Demo scenario marks its last plan step done. README rewritten with screenshots rendered from the real app; details moved to docs/USAGE.md.
- Not verified: vision with a real vision model, Gemma/vLLM, the new settings toggle by hand in the running app (rendered offscreen only).

## 18 октября 2026 — однократный ночной аудит

Полный анализ, планы и критерии приёмки: [NIGHT_AUDIT_2026_10_18](NIGHT_AUDIT_2026_10_18.md).
Аудирован master c529eb7; отдельная ветка paste-vision-context 798bbea только просмотрена, без merge/изменений.

- Full live DeepSeek: 14/14; compact на пяти одинаковых задачах: 5/5. Отдельно прошли text-artifact >5 KiB → nested file → show_file, web search/fetch, отмена/restore/recover без повтора процесса; GET /models вернул два ID. Text-only, thinking disabled; Gemma и vision не проверены.
- Единый budget proxy: 112 HTTP-запросов, 112 ответов; консервативный резерв $1.357653 из разрешённых $2.50. Оценка расхода по usage и опубликованным off-peak ценам $0.016121376, не банковское списание. Сохранён ledger без секретов; proxy остановлен.
- Исправлен P1 auto-verify: поздний failed-result после смены проекта больше не отправляет repair в новый чат. Коммит d01041f; controller-регрессия и targeted AutoVerifyTest прошли.
- Финальный `./gradlew check :evals:offlineSmoke :app-desktop:createDistributable --rerun-tasks`: BUILD SUCCESSFUL, 49 executed, 264 теста / 0 failures/errors/skipped. Новые функции не реализовывались.
- Метод master для PNG-вставки блокирует EDT: 4K flat 114–198 мс, 4K noise 617–705 мс с последующим отказом по размеру. Это три измерения каждого fixture, не p95 и не end-to-end Cmd+V. В отдельной ветке есть фоновая подготовка, ещё не интегрированная.
- Заново отрисованы light/dark wide/narrow rich-results; просмотрены light-wide/dark-narrow. Нативная UI-приёмка и clipboard first-paint не выполнены.
- В планах: обязательные read_artifact/show_file для compact, кеш/лимиты vision preparation, durable rewind, частичная история comparisons, продуктовый денежный ledger, lifecycle/нативные regression-сценарии.
- Пользоваться можно как рабочим preview с review diff и тестами. Автономная надёжность на произвольных проектах не подтверждена. Push/release не выполнялись.

Сырые результаты: `reports/local/night-audit-2026-10-18/` и два eval report от 20261018-043226 / 20261018-043727. Это ignored локальные артефакты. Команда обычного запуска: `./gradlew :app-desktop:run`.

Завершение: одноразовая автоматизация `chiller` удалена; остановлен только проверенный собственный `caffeinate` PID 54050, pid-файл удалён. Повторных ночных запусков не назначено.

## Night audit follow-up — 18 October

Done:
- Compact profile keeps `read_artifact` and `show_file` (P1).
- Auto-verify session binding (P1) was already fixed by the audit commit d01041f; merged as is.
- Paste/vision (P1): prepared images cached per stored file (LRU of 8, keyed by size and mtime); at most two pastes prepared at once; non-RGB sources converted directly at the pixel-limited size; the project map never exceeds maxChars. Binding to the original draft already held (the coroutine captures the owner key and its pending-card state); no change.
- Live vision: new opt-in `:evals:liveVisionSmoke` (one request, image with a random 4-digit number, output cap 64). deepseek-flash read it 2/2 (265 prompt tokens, ~1.3 s), so "auto" now enables vision for deepseek-flash.
- Repeated rewind (P2): undone ids come from the session journal (undoOf of workspace commits); the button offers only remaining changes and a second rewind is a no-op, also after restart.
- Comparison (P2): the record is rewritten atomically after every attempt, the stopped side's attempts are kept, ids get a random suffix.

Not done, deliberately:
- Money ledger with reservations across helpers and retries (P2): a product feature of its own; the existing RunBudgetProvider already caps requests and output tokens per task.
- Vision capability stored per endpoint+model: the model-id heuristic plus the manual switch cover the current providers; a per-endpoint probe needs a stored capability record and its own UI.
- Native clipboard snapshot latency before the pending card: the transferable must be read synchronously before it expires; not measured or changed.
- 30-repeat paste latency/heap benchmark, wider live corpus (Kotlin/TypeScript/large repo), 429/SSE-drop scenarios and a native-window soak: measurement work for a separate pass.
- Feature ideas (confirmed-result card, safe return point overview): larger features, left for planning.

## Result card, outside changes, paste benchmark — 18 October (morning)

- Turn result card: built from tool calls and results only. Files with line counts, commands with their last exit code, shown files and issues: a failed check-like command with no later success, an unread check result, an edit that never applied, an answer claiming a check when none ran (sentences with a negation excluded). Collapsed when clean, open with issues; issues lead the collapsed line. Five rule tests; both states rendered offscreen in light/dark.
- Outside changes: metadata snapshot (size, mtime) of non-ignored files before and after an editing turn; paths touched by file tools are subtracted; the rest is shown as "changed by commands or by hand, rewind will not restore". Journaled and restored with the session. End-to-end controller test with a fake loopback model: the tool's file is excluded, the command's file is reported, and the record survives reopening.
- Found while doing it: the internal ripgrep file list shared the 48 KiB model-output cap, so find_symbols failed with OUTPUT_LIMIT and the project map was cut on projects above ~1500 files. The list has its own 2 MiB cap now; regression test with 3001 files.
- Paste benchmark (30 repeats): poorly compressible images took seconds (8K noise 5.4 s). PNG size is now predicted from a centre crop and misses shrink proportionally: 8K noise 269 ms, 4K noise 116 ms, 8K photo 666 ms; screenshots unchanged at 50–200 ms; text up to 8 MiB under 20 ms; vision payload reused after the first round (112 ms → 0.09 ms). Native clipboard read and first paint are not measured.
- Checks: 289 tests, all executed; offline smoke passed. Not verified: the card and the outside-changes section in the running app by hand, Gemma/vLLM.
