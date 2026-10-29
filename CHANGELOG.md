**English** · [Русский](CHANGELOG.ru.md)

# Changelog

All notable changes to Chiller. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versions follow [SemVer](https://semver.org/). A release candidate (`-rc.N`) is a build for checking before the stable version; its installers get the number without the suffix (`1.2.0`), as DMG and MSI require.

## [Unreleased]

### Added

- **Interface in English and Russian:** English by default, Russian in Settings → Appearance → Language; the switch applies at once. Statuses, errors and the demo follow the chosen language.
- **Another provider from the model menu:** an "Other providers" section in the menu by the input field; the chosen model continues the current dialog on another provider from the next message, and the switch is written to the journal.
- **Images through another provider:** "Settings → Agent → Image provider", for example chat on llama.cpp and `generate_image` through a company gateway, each with its own key.
- **Proxy for corporate networks:** "Settings → Connection → Proxy": system, manual or none, with exceptions; chat, the model catalog, images, the connection check and web search go through it.
- **HTTP provider addresses:** not only `localhost`, for example llama.cpp on your local network.
- **Reasoning per step:** every model step has its own collapsible "Reasoning" block before its reply and tools; while the model thinks, a "Thinking" row shows the latest thought. Reasoning is saved in the journal and is not sent back to the model.
- **Time per task:** 10 minutes by default, 30, 60, 120 or no limit ("Settings → Agent → Run limits").
- **A project remembers its settings:** "Plan"/"Changes" mode, permissions for process commands and web search, the model (for new dialogs on the same provider); the web switch is in the mode menu by the input field.
- **Keyboard sidebar:** ↑↓ through projects and dialogs, Enter opens, →/← expand a project.
- **Command palette:** Ctrl+Shift+P runs any app action by part of its name.
- **Message search:** Ctrl+K finds text in dialog messages, shows the line with the match and opens the dialog at that place.
- **Edit confirmation:** a switch in "Changes" mode; every write to files waits for "Apply" or "Decline" with a preview of the edit; the rest of the turn's edits can be applied at once.
- **MCP tools:** local Model Context Protocol servers (stdio) in settings, with `mcpServers` import; all dialogs get their tools, and in "Plan" mode only those marked as reading. Keys come through `${NAME}` from the environment or `.env`.
- **Medeo font:** Sans in the interface and Mono in code, paths and commands instead of system fonts; the zero is slashed.
- **Projects in the sidebar:** dialogs grouped by folder, a colored project monogram, the git branch, pinning, "Show in Finder/Explorer" and "Remove from list". Ctrl+P jumps to a project, Ctrl+1…9 starts a new dialog in a project.
- **Dialogs without a folder:** a conversation with web search and images, without access to files and commands. Its images are stored in Chiller's data.
- **The folder is bound to the dialog:** while the dialog has no turns, the folder chip by the input field changes or removes it in place; after the first turn another folder opens a new dialog.
- A moved project folder can be set again for all its dialogs.
- **Background tasks:** a task keeps running when you go to another dialog. The list shows that it is running or waiting for an answer, and it can be stopped from the menu. Up to three tasks run at once, one per folder.
- **Background auto-verify:** the check after edits runs for a background dialog too; on failure, the fix goes to the same dialog.
- **Separate copy (git worktree):** a dialog can work in its own copy of the project, in parallel with other tasks in that project; changes move to the project as an exact patch after `git apply --check`, or not at all.
- **Continue in another folder:** a new dialog in the chosen folder with a draft from the previous one's history.
- Dragging a folder into the sidebar opens it as a project; expanded projects are remembered between launches; dialogs with an unread result are highlighted; Shift+Enter in Ctrl+P opens the project's last dialog.
- **System notifications:** when the window is not focused, news from background dialogs comes through the system (tray icon); turned off in the appearance settings.
- **Notifications:** about completion, an error, a check result and an agent question in a dialog that is not open right now; a click opens the dialog.

### Changed

- Editing your message immediately starts a new turn with the corrected text.
- A dialog's title is the first sentence of the request, with the full text on hover; long paths in the conversation and in "Changes" are shortened in the middle so the file name stays visible.
- A new settings layout with provider search and a new app icon.
- One model step may last up to 60 minutes instead of 10; a stalled stream still ends after 2 minutes of silence.

### Fixed

- A dialog no longer gets stuck after a step interrupted by a stop or the time limit: a new message closes the unfinished calls as UNKNOWN, repeats nothing and continues the turn. The "Check and continue" button hides once the interrupted step is closed.
- In Studio, expanding a new image opens the image viewer instead of the text "Binary files differ".
- The time in the dialog list ("23 min") was cut off and shifted to the left.
- A task stopped during an agent question is shown as stopped on opening, not as interrupted.
- On Windows the full path was shown instead of the folder name; "Open in Finder" in settings is called "Show in Explorer" on Windows.

## [1.2.1] — 2026-10-21

Tool work for small models based on live measurements on Gemma 4 26B A4B (vLLM, compact profile, with reasoning). Reasoning is now sent back to the model within the turn. Edit results and errors suggest the next step, reads adapt to the context window, and the reasoning headroom is larger. Added the `tool-*` and `hard-*` eval tasks.

### Added

- **`append_file`:** a long file is written in parts, `create_file` and then `append_file` with the `snapshot_id` of the previous step; existing lines do not change. `create_file` now returns a `snapshot_id`.
- `append_file`, `delete_file`, `move_file` and `create_directory` in the compact profile: before, the model deleted and moved files through the command line, without undo and only with process permission.
- **Gemma sampling:** Gemma models (by ID or by name from the catalog) get Google's recommended temperature 1.0, top_p 0.95, top_k 64 instead of the compact profile's 0.2. The temperature chosen in settings and extra fields take priority; `null` in extra fields removes a default field.
- `tool-*` eval tasks (an edit at the end of a file, escaping, tabs, replacing all occurrences, a chain of edits, a large file, long output, delete and move, writing in parts) and `CHILLER_EVAL_EXTRAS` for A/B tests of request fields; the request fields are written to the report.
- **Hard `hard-*` eval tasks** for models that pass the short ones. Six tasks: a feature across six modules from a spec, two related bugs among decoy files, eight differently written date formats in a 450-line file, an implementation from a spec with hidden cases, tests that must catch seven mutants, and a setting traced through three configuration layers. They have a round limit of 24, as in the app.

### Fixed

- **vLLM reasoning is sent back to the model within the turn.** Before, Gemma 4 and Qwen 3 with reasoning forgot after every tool call what they were going to do, although Gemma 4 requires keeping its thoughts between calls of one turn. Now reasoning goes with the model's messages in `reasoning_content`; chat templates drop the reasoning of earlier turns themselves. On 18 tasks with Gemma 4 (compact profile, with reasoning) all tasks passed both before and after. Generated tokens dropped by 45%, time by 37%, and rounds spent only on `update_plan` halved.
- A ban on running processes now suggests what to do instead: read the files and give the user the command to check.
- **Snapshots after edits.** The result of `create_file`, `replace_text`, `append_file` and other edits starts with `snapshot_id`, like `read_file`, and the hashes were removed from it. Before, the model took the edit's `id` or `afterHash` instead of `snapshotId`, which came last. An unknown or foreign `snapshot_id` now gets the file's current snapshot in reply when there is one, instead of a request to reread the file.
- **Window-sized reads.** One `read_file` shows about 5% of the model's context window: 200 lines at 32k, about 740 at 128k. Before, Gemma read a 471-line file in pieces of 200 and spent two rounds on every edit.
- `create_file` over an existing file suggests what to do instead; when the only difference is whitespace on empty lines, the error names those lines. If a long `old_text` differs from the file, the error names the first mismatching line and shows both versions.
- **12288 tokens of reasoning headroom instead of 4096.** On a task with a complex spec, Gemma's reasoning was cut 8 times in 4 attempts, losing 80–115 s each time. With the new headroom there are no cuts, and an attempt takes about 150 s instead of 300–600. It is an upper bound: short replies do not get longer.
- `replace_text` did not find an `old_text` copied to the end of the file together with the last line break.
- Edit errors explain what to fix: when only empty lines differ, edit in parts; with double escaping, write `\n` once; with `12 | ` prefixes, remove them; `MATCH_COUNT_MISMATCH` explains what `expected_count` means, and `UNOBSERVED_RANGE` names the lines to read.
- Lines read under different snapshots of the same content count together: an edit after reading a file in parts needs no re-reading.
- Argument errors name the argument and the expected type; calling a tool that does not exist gives `UNKNOWN_TOOL` with the list of available ones.
- The long output of a finished process comes as its start, error lines from the skipped middle and its end, not the first 16 KB.
- A cut `read_file` reports the lines shown and `next_start_line`; `search_text`/`list_files` and the edit parameters got descriptions (the model no longer passes `expected_count` needlessly).
- If reasoning took the whole reply limit and nothing came out, the empty reply is not saved, and the next step runs without reasoning.
- The second identical failed call gets a warning before the third one ends the turn; the hint after a cut-off reply names a tool that exists in the profile.
- A final answer together with a closing `update_plan` ends the turn without an extra round, as does a closing `update_plan` right after a plan reminder when all steps are done.

### Known limitations

- The measurements were made on one gateway (Gemma 4 26B A4B, vLLM 0.31) in small runs; other models on vLLM were not checked live.
- The compact profile and Gemma sampling are chosen automatically by model ID or by name from the catalog. An alias without "Gemma" in its name gets the full profile: choose compact in "Settings → Agent → Model profile".
- The installers are not signed: on macOS, open the app the first time with right-click → "Open"; on Windows, SmartScreen may ask for confirmation.
- Path and process working-folder checks are not an OS sandbox.

## [1.2.0] — 2026-10-19

The stable 1.2 version. Checked live on Gemma 4 26B A4B (vLLM behind an HTTPS gateway): image generation and editing, your own provider with a key in the system store, a task money budget, long-dialog memory and protection from loops and made-up data.

### Added

- **The model catalog tells more:** the context window from `max_model_len` (as vLLM reports it), vision from `input_modalities`/`capabilities`, reasoning from `capabilities`. "Auto" vision trusts the catalog before the ID, and the "Auto" profile looks at the name when the ID says nothing about size: the alias `quasar` named "Gemma 4 26B A4B" gets the compact profile and vision.
- **vLLM reasoning:** the `reasoning` field is shown as visible reasoning and is not returned to the history; for a model with `reasoning` in the catalog, the reasoning switch passes `chat_template_kwargs.enable_thinking`.
- The live eval reads the catalog, skips tasks without the tools the profile needs (SKIP) and marks provider failures as ERROR, not as a model failure.
- **Image generation and editing:** the `generate_image` tool through the provider's image model (OpenAI Images API: generations and multipart edits). The image is saved as a new file with a preview, undo and a journal entry; the reference is a project image or an attachment. The model is chosen in "Settings → Agent → Image model" (Auto from the catalog, Off or a specific one). The `:evals:liveImageSmoke` live check and the `generate-image` eval task.
- **Your own provider:** a name, an address, a key variable, a model, default reasoning and profile; stored only in local settings, and the key from `.env` is bound to the provider's address.
- With reasoning on, the reply gets 4096 tokens of headroom over the limit: reasoning spends the same `max_tokens`.
- **Task money budget:** a USD limit at your prices; a worst-case reservation before every attempt (retries, helpers, images), prices bound to the model, a refusal instead of counting at someone else's prices, the total in `run.summary`.
- **Long-dialog memory:** when the context is compressed, constraints from all user messages (with the message number), open plan steps and the original request are kept.
- **Helpers search the web** if the turn allows it; `wait_agents` waits for a helper to finish, not for each of its steps.
- **The plan is carried through:** a final answer with open steps gets up to two reminders; a `skipped` status with a reason; an unclosed plan is visible in the turn result.
- The model is told what it can do in this session (images, the web, helpers, processes), today's date and the user's system with hints for running commands.
- The window remembers its size and maximized state between launches.
- Eval: multi-turn scenarios (changed requirements, a user edit, a restart, context compression), tasks on closing the plan and on refusing to make up data.
- **Keys on Windows persist between launches:** the "Remember key" button puts the key into the Windows Credential Manager (entry `Chiller/<origin>`, only for the current user of this computer), like the Keychain on macOS. The `:app-desktop:windowsCredentialSmoke` check creates, reads, updates and deletes a one-time entry.

### Fixed

- **Corporate CA:** the certificate is checked against the JDK store and the system one (Windows-ROOT, the macOS keychain). Before, a server trusted by Windows gave "Provider connection failed" with three retries; now it works, and a TLS error is called `PROVIDER_TLS_ERROR` and is not retried.
- **`Retry-After`** was honored only up to 2 seconds, and a gateway quota (`429`, `Retry-After: 60`) broke the turn in two seconds. Now the wait is up to 120 seconds with a "Retrying in N s" notice; a longer interval ends the turn with the server's number.
- An error that arrives as an SSE event in the middle of a reply is reported in the provider's words, not as a broken stream.
- **Made-up data:** `web_fetch` marks pages that are drawn by scripts and contain no data; an empty search without a "nothing found" mark is a `SEARCH_UNAVAILABLE` error; search results have snippets again; the prompt requires taking facts only from tool results.
- **Loops:** repeats are counted by tool, arguments and result; an identical result gives a reminder, then a round without tools with an honest answer; the last allowed step answers too instead of breaking with an error.
- A missing program gives `EXECUTABLE_NOT_FOUND` with a hint for the OS (on Windows, `cmd /c start`), not a faceless `TOOL_FAILED`.
- An image model from the catalog can no longer be chosen for chat: model selection shows only models with text output and `/chat/completions`.
- Gradle on a JDK older than 21 says right away that JDK 21+ is needed, instead of failing with `UnsupportedClassVersionError` when running tests or evals.

## [1.2.0-rc.3] — 2026-10-18

The last candidate before 1.2.0: switching the model within a dialog and settings polish.

### Changed

- **Switching the model no longer starts a new dialog.** A model of the same provider chosen in the menu continues the current dialog from the next message; `session.model` is written to the journal. Protocol reasoning is sent back only to the model that wrote it, so the previous model's replies go to the new one as plain text. Changing the provider address in settings still starts a new dialog, so the history does not go to another service without an explicit decision.
- The connection status no longer hangs at "not checked" when everything works: the model's first reply confirms the connection without a separate request, a received model list shows "server responds", and before that the status says "configured".
- The settings page appears with a smooth slide together with the sidebar; a status dot is shown next to "Connection", and the version and the theme switch are at the bottom of the panel; ↑/↓ switch sections.
- Settings navigation opens in the sidebar instead of the dialog list, with a smooth slide; the settings page gets the full width. Without the sidebar (a narrow window) the section list stays on the page.

## [1.2.0-rc.2] — 2026-10-18

Fixes after checking 1.2.0-rc.1.

### Added

- **Bundled ripgrep 14.1.1** in the installers: official archives with SHA-256 checks and their licenses; search works without `rg` on the system. Before building DMG/MSI, the finished app is checked by searching paths with Cyrillic with an empty PATH.
- **Search inside large attachments and saved results:** `read_artifact` takes a `query` and returns matching lines with numbers and offsets. Before, a large log had to be paged through blindly 16 KB at a time.
- **Viewing the task memory:** when a long turn is shortened, you can see which excerpts stayed in context.
- **Environment diagnostics** in "About": ripgrep (bundled or from PATH), Git and Python 3 via `--version`, without the network; "Connection" shows the key, the model catalog and the generation check separately.
- **Custom window title bar on Windows:** the system strip with the title and menu is gone, and the interface starts at the top edge, as on macOS. The minimize/maximize/close buttons, window snapping (Snap), dragging and double-click stay native. The menu moved to the "≡" button in the toolbar; the shortcuts Ctrl+N, Ctrl+O, Ctrl+, , Ctrl+. and Ctrl+Enter work as before. It needs the JetBrains Runtime, which the Windows installer is now built with; on another JVM the ordinary window frame stays.
- **"About":** the full version with its suffix (`1.2.0-rc.2`) and a pre-release mark, the CI build number and the commit date, a link to the commit, the author and the license, a "Copy details" button for bug reports (without the data path). The installers name the publisher (Damirka1) and the copyright.
- **Third-party component information** inside the app: licenses from each library's POM, LICENSE/NOTICE files from their JARs and the Apache 2.0 text.

### Changed

- After a reply is cut off by the limit, the agent gets the exact limit size and a way to write the file in parts; the task stops only after two cut-offs in a row without progress.
- Token usage is saved even for a cut-off reply, and incomplete totals are marked; known usage returns the unused budget reservation.
- A long current turn is shortened by whole tool exchanges, keeping the original request, the last exchange and unknown outcomes. A one-off lack of context is no longer remembered as the model's limit.
- The model's finished plan is shown inside the turn result card, not as a separate card.
- A stop at the 10-minute task limit is called that, not "cancelled by the user".
- The release workflow first checks the tagged commit on Linux, macOS and Windows and only then builds the installers.
- When run from source on macOS, the Dock shows "Chiller", not "java".

### Fixed

- Shortcut hints on Windows and Linux show Ctrl, not ⌘.
- Restoring the session status after `run.truncated`.
- The local package build did not notice changes in prepared resources and could leave outdated notices or ripgrep.

### Known limitations

- A task is limited to 10 minutes even with "No limit" for steps and budget; after it stops, it can be continued.
- Installation on a clean Windows was not checked; the current packaging does not support Windows ARM64.
- The installers are not signed or notarized; the RC → stable update was not checked.
- Gemma on vLLM was not checked live.

## [1.2.0-rc.1] — 2026-10-18

The first release candidate of the 1.2 branch: profiles for small models, a verifiable turn result, vision and work with long replies.

### Added

- **Model profiles.** "Full" (33 tools) and "Compact" (13 tools, a step-by-step prompt, one call per step) for 4–30B models; "Auto" chooses by model ID. The compact profile has the project map in context and definition search with `find_symbols`. Measured on DeepSeek V4.1 Flash, 14 tasks: rounds 67 → 56, prompt ~235k → ~146k tokens with the same result.
- **Turn result from facts.** The card at the end of a turn is built from the journal: changed files, commands with exit codes, presented files. A failed check that was not rerun, an unread result, an edit that did not apply and a "tests passed" answer without any checks are highlighted separately.
- **Changes outside the agent's tools.** Files changed during the turn by commands or by you are shown separately: undoing the turn does not bring them back.
- **Undo a whole turn** with one button, restoring files exactly; repeated undo and an app restart take it into account.
- **Auto-verify.** After edits, the project's check command runs; failed output goes back to the agent, and the number of attempts is configurable.
- **Model comparison** on the built-in eval tasks right in the app: success, time, tokens, repeated reads and failed edits.
- **Vision.** Images from the clipboard and files go to models that understand them (checked live on DeepSeek V4.1 Flash): they are downscaled to 1568 px and sent only with the latest messages.
- **"Model maximum"** for the reply length: Chiller learns the model's limit from the provider's response (384k for DeepSeek Flash) and remembers it. "No limit" for the task budget and the number of steps (with a 500-step safety net).
- **A live plan checklist** under the messages; **Shift+Tab** switches "Plan" and "Changes".
- **Instant paste:** the attachment card appears at once, encoding runs in the background; large text becomes a file.
- **Copying tables** from a reply: they paste into Excel/Numbers/Google Sheets as a table and into text as columns.
- Waste metrics in eval reports, live vision checks, automatic loading of the provider's model catalog, the Apache 2.0 license.

### Changed

- Agent settings apply from the next message (before, from the next dialog); the history is kept.
- A reply cut by the length limit no longer breaks the task: the cut call is not executed, and the agent continues, writing a large file in parts. After a stop at the step limit there is a "Continue" button.
- Provider errors with code 400 show the provider's own message, not just "HTTP 400".
- Encoding large pasted images is 10–20 times faster (8K "noise": 5.4 s → 0.27 s), and prepared images are not recomputed every round.

### Fixed

- Symbol search and the project map worked only in projects up to ~1500 files.
- Undoing a deleted file failed with `NoSuchFileException`.
- The chat jumped when scrolling to an image already shown; a wide image overlapped its caption; the loading bar went over the sidebar.
- Model comparison lost partial results on stop and "hung" if stopped right after the start.
- An auto-verify result could land in another dialog after switching projects.
- Six tests silently did not run; flaky tests on Windows and Linux were fixed.

### Known limitations

- Gemma on vLLM was not checked live yet; the measurements were made on DeepSeek V4.1 Flash.
- The installers are not signed: on macOS, open the app the first time with right-click → "Open"; on Windows, SmartScreen may ask for confirmation.
- Path and process working-folder checks are not an OS sandbox.

## [1.1.0] — 2026-10-17

The first public preview: a desktop app with Focus and Studio modes, code tools with file snapshots and undo, processes, subagent helpers, presets for OpenAI-compatible providers, a settings page and DMG/MSI builds in CI.

[1.2.1]: https://github.com/Damirka1/Chiller/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/Damirka1/Chiller/compare/v1.2.0-rc.3...v1.2.0
[1.2.0-rc.3]: https://github.com/Damirka1/Chiller/compare/v1.2.0-rc.2...v1.2.0-rc.3
[1.2.0-rc.2]: https://github.com/Damirka1/Chiller/compare/v1.2.0-rc.1...v1.2.0-rc.2
[1.2.0-rc.1]: https://github.com/Damirka1/Chiller/compare/v1.1.0...v1.2.0-rc.1
[1.1.0]: https://github.com/Damirka1/Chiller/releases/tag/v1.1.0
