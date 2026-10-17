# Chiller 1.1.0 — first preview release

First packaged preview of Chiller, a Kotlin/Compose desktop coding agent with Focus and Studio layouts. The installer version is 1.1.0, continuing the existing project version; this is the first GitHub release, not a claim that every roadmap feature is complete.

## Included

- Model discovery from configured `/models`, origin-bound credentials, explicit macOS Keychain storage, parent/helper request and output caps.
- Snapshot-bound file edits, exact replacement, safe parent-directory creation, move/undo/recovery and immutable paged search/list results.
- Read-only Git, conservative lexical symbols and process diagnostics; bounded supervised processes and cancellation.
- Permission-gated web search and HTML/plain/JSON fetch; file cards and PNG/JPEG previews; explicit selected-window capture on macOS.
- Paste/drop attachments, automatic text attachment above5 KiB, per-chat draft/import ownership, rename/delete chats and logical edit/delete whole turns.
- Read-only helper agents with mailbox, wait/cancel and Studio progress. Original Chiller native icon.

## Verification boundary

Default offline checks never spend model tokens. The latest local test count and CI/release results are recorded in RUN_REPORT. Previous live DeepSeek tests passed on an earlier checkpoint; the new tools/attachments have fixtures and real filesystem/process coverage, not a fresh paid-model benchmark. Gemma/vLLM is unverified without the server.

The native Keychain synthetic create/reopen/update/delete smoke passed on macOS. Public web search, HTML and plain-text fetch passed; one GitHub JSON endpoint was unavailable in the smoke, while JSON preservation is fixture-tested. Real selected-window capture, drag/drop/clipboard matrix, IME/VoiceOver, Windows/Linux GUI acceptance remain pending. New native UI automation could not bind the Preview window; compilation/unit results are not substituted for this check.

Images are visible to the user; binary pixels are not sent as model vision input. History edits do not undo disk effects and are logical: JSON export includes the raw append-only journal. Processes/DNS checks are not OS sandboxes. Semantic LSP, PTY, writable helper worktrees, MCP and multi-file atomic transactions are outside this preview.

Installers are unsigned. Use on version-controlled workspaces, review requested actions and inspect diffs/tests. macOS first launch may require right-click → Open; Windows may show SmartScreen. Download only assets from this repository's release.

Run from source: `./gradlew :app-desktop:run` (JDK21+, ripgrep, Git; Python3 for Python checks).
