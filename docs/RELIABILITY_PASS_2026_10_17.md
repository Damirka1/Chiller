# Reliability and developer tools — 17 October 2026

User-approved continuation of the readiness plan, on master. This report supersedes older statements that Git tools, continuation cursors and cross-directory moves are absent. Verification results belong in RUN_REPORT; new code is not evidence of live model compatibility.

## Changes

- Run-owned background processes terminate when the parent run completes, fails or is cancelled. Close rejects new admissions and waits for cleanup. Admission is synchronized; at most eight live processes. Output retention is bounded to 64 completed records; expired output must not be recovered by blindly rerunning a side-effecting command.
- Context compaction occurs before fitting the current tool loop. Older tool output can be replaced while preserving call/result pairs, protocol replay and error codes. The compaction stub never fabricates success and warns against repeating effects to recover output. Context estimates remain character-based.
- list_files/search_text accept continuation cursors scoped to the original query. Captures are immutable, bounded and expire after two minutes; continuation is not a filesystem watcher. A capture exceeding its own bound requires a narrower query.
- move_file supports existing directories within one workspace with no overwrite, source validation, journaling and conflict-aware undo/recovery. Link/unlink is not an atomic multi-file transaction; ambiguous crash states are conflicts.
- Read-only git_status/git_diff/git_log/git_show use fixed arguments, no external diff/textconv/fsmonitor, bounded output/deadline, and require the repository root to equal the workspace root. They do not commit, stage, switch branches or fetch. Git is a required local executable.
- find_symbols is lexical navigation, not LSP/AST. read_symbol has conservative Kotlin block extraction and refuses unsupported/ambiguous syntax. These tools do not prove semantic references or safe rename.
- read_diagnostics extracts Kotlin/Java/Python file locations from actual process output with raw fallback, partial-parser marking and observed exit/state; an empty diagnostic list is not proof of success.
- History cleanup is explicit: preview selected inactive session data, confirm deletion, revalidate snapshot, detach before deleting. Project source files are outside maintenance roots. No automatic retention policy or destructive schema migration is enabled.
- Working-chat progress distinguishes preparing, waiting for a model, streaming, running a tool, waiting for the user, and terminal states. Settings explain that shared request/output caps do not bound input, transport retries or monetary cost.
- Eval corpus expanded from 9 to 14 tasks, adding multi-module work, preservation of user edits, Unicode/CRLF and diagnosis/navigation cases. Repeated live reports include per-task success rate and time/usage. Independent Python checks isolate imports from stale bytecode caches.
- An explicit keychainSmoke task exercises a synthetic disposable account only; it never discovers or reads configured provider accounts. It is excluded from default checks.

## Further gates

- Native create/reopen/update/delete passed on a disposable synthetic Keychain entry after replacing incompatible JNA 5.6.0 with 5.18.1. Provider-account ACL prompts in the packaged app remain a separate acceptance item.
- New paid model regressions require a separately agreed finite budget. No live requests are implicit in this pass.
- Gemma/vLLM requires the actual server; other provider presets and Windows/Linux need their native acceptance runs.
- Exact tokenizers, input/money/HTTP-attempt budgets, semantic LSP navigation, OS process containment and PTY remain separate engineering work.
- Writable helpers require isolated worktrees, a reviewable patch import and crash/restart rules. The current helpers remain read-only; a file write capability must not be added by merely changing their tool allowlist.
- Multi-file atomic editing needs a grouped write-ahead protocol and fault-injection matrix. Individual journaled writes/moves are not advertised as such a transaction.
- MCP/browser/web integrations need a concrete use case and their own permission/output boundaries; none is implicitly enabled.

## Files, web, recovery and history follow-up

- File creation makes missing parents with no-follow validation and journals the directory intent. Explicit create_directory is idempotent. Undo removes the created file, leaving directories rather than recursively deleting potentially user-owned content.
- NETWORK and SCREEN_CAPTURE are separate permission classes. web_search/web_fetch are absent from the advertised catalog until enabled; public-address checks, redirected-target revalidation, time/byte/output bounds apply. HTML is extracted, plain text/JSON preserved. This is best-effort network admission, not a DNS-rebinding-proof sandbox. DuckDuckGo may block automated queries.
- show_file returns typed presentation metadata. UI previews bounded PNG/JPEG and reveals files without executing them. Selected-window capture is macOS-only, explicit picker + recording permission; model cannot select another window. Capture tests use a fake native gateway; real capture remains unverified.
- Paste/drop imports are app-private session artifacts: >5120 UTF-8 bytes becomes a text attachment. Send is gated during imports, owner session is passed explicitly across the file picker, and cross-session references are rejected. Binary metadata is model-readable, pixels are not a vision request.
- Repeated-error stopping journals the complete batch before stopping. Explicit recovery inserts UNKNOWN receipts instead of replaying effects. Interrupted activities use a neutral terminal state rather than displaying forever-running controls.
- Whole-turn delete and atomic replace_turn preserve provider message pairing. Saving an edit never calls the provider. View/export activity ownership uses message sequence + operation identity, not a provider call ID that can repeat across runs. Raw JSON journals retain logically deleted records; this is documented, not secure erasure.
- The application icon is original project artwork, derived from the existing ChillerMark geometry. scripts/generate-app-icons.swift creates the RGBA master, scripts/package-app-icons.py packages native sizes. Committed ICNS/ICO/PNG are consumed directly by packaging, so CI does not require Swift or iconutil on Windows.
