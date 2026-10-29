**English** · [Русский](USAGE.ru.md)

# Chiller: usage details

The overview, screenshots and quick start are in the [README](../README.md). This page collects the details: keys, data, building, providers, attachments, history and limitations.

## Keys and local data

Never put an API key into the repository, the documentation, test fixtures or subagent messages. The first opt-in live test expects the `DEEPSEEK_API_KEY` variable, then the OS secret store or a key typed into the app for the session. Shared tests run without a key through FakeProvider and fixtures. A missing key must not block building or UI development.

## Building and running

JDK 21+ (currently checked on JDK 26), Gradle 9.4.0 through the wrapper, Kotlin 2.3.21, Compose 1.10.3. File search needs `rg` on PATH; the end-to-end offline smoke also uses Python 3. The first run downloads the JVM/Compose dependencies, including the native runtime of the current OS.

```sh
./gradlew check :evals:offlineSmoke
./gradlew :evals:liveDeepSeekSmoke   # opt-in, needs a key and a small budget
./gradlew :app-desktop:run
```

In the app, choose a project folder and set the URL/model/API key. A key typed in the UI lasts until exit; the "Remember key" button in the connection settings explicitly saves it to the Keychain (macOS) or the Windows Credential Manager. Without a key there is a clearly labeled offline mode; it does not perform the user's tasks on files. Plan is the default. File changes are allowed in Workspace edit, running commands by a separate switch; this is not an OS sandbox.

By default the app's data is stored in `~/.chiller`; `CHILLER_DATA_DIR` chooses a separate folder. Settings on disk hold the URL/model/workspace and references to the origins of saved keys, but not the keys themselves. For an isolated look:

```sh
CHILLER_DATA_DIR="$PWD/.chiller/local" ./gradlew :app-desktop:run -PchillerPreview=true
```

The shared `check` does not use a remote model. `offlineSmoke` runs a scripted model loop with real file operations, Python checks and journal recovery. DeepSeek passed bounded live checks at an earlier checkpoint; new features were checked offline. Gemma 4 26B A4B on vLLM was checked with small live runs (see the README). CI checks unit tests on three OSes and the DMG/MSI build; this does not replace manual acceptance of installation and the interface.

The sidebar works from the keyboard: ↓ from search moves into the list, ↑↓ move through projects and dialogs, Enter opens, → and ← expand and collapse a project.

## Command palette

Ctrl+Shift+P (⌘⇧P) opens all app commands: menu items (new dialog, export, Focus/Studio, model comparison and others), settings sections, going to a project and a new dialog in it, "Plan"/"Changes" mode, edit confirmation, process commands, the theme. It matches word beginnings in any order ("new dia", "the"); ↑↓ select, Enter runs.

## Search

Ctrl+K (⌘K) searches dialog titles and, from two characters on, the text of messages, both yours and the model's replies as they are shown (tool output and reasoning are not searched). Message matches come in a separate section with the line around the match; a click opens the dialog at the first message with that text. The search runs over the journals on this computer; the text is kept in memory and reread only for dialogs that changed.

## Edit confirmation

The "Changes" mode menu has a "Confirm edits" switch. With it, every write to project files (`replace_text`, `edit_file`, `create_file`, `append_file`, move, delete, a new folder or image) waits for a decision: a card with the proposed edit appears in the conversation. "Apply" performs it, "Decline" returns a refusal to the model (`DECLINED_BY_USER`) and the file does not change, and "Apply the rest in this turn" lets the next edits of the turn through without asking. The preview is built from the call's arguments, not from the file on disk; the edit still goes through the exact snapshot check as usual. Decisions are written to the journal (`tool.approval`). A background dialog waiting for confirmation is marked in the list and sends a notification. Stopping the task while waiting closes the edit as "not performed".

## MCP tools

"Settings → MCP tools" connects Model Context Protocol servers: GitHub, databases, trackers and other services. Local servers with the stdio transport are supported: Chiller starts the program itself (without a shell, in the home folder) and stops it on exit. A server is added with a form (name, command, environment variables) or by importing the `mcpServers` block from another client's configuration; servers by address (`url`) are skipped.

Keys are never written to settings: in a server's environment variables write `${NAME}`, and the value is substituted from the environment or `.env` at startup. If a value looks like a key, the form warns you.

Every dialog gets the tools of ready servers under the names `mcp__<server>__<tool>`. A tool the server marked as reading (`readOnlyHint`) is available in "Plan" mode too; the others only in "Changes" mode. An enabled server means consent to calls of its tools; they are journaled like ordinary calls. Required arguments and simple types are checked before a call; the response is limited to 32 KiB and the call to 120 seconds, and stopping the task cancels the call on the server. Images and audio from the response are not passed to the model.

## Projects and dialogs without a folder

Each dialog has its own folder or none at all. In the sidebar, dialogs are grouped by project: a project can be expanded, pinned at the top, shown in Finder/Explorer or removed from the list (the folder and dialogs stay; opening the folder brings the project back). Ctrl+P (⌘P) jumps to a project, Ctrl+1…9 starts a new dialog in a project by its place in the list; Enter in the jump starts a new dialog in the chosen project.

A project remembers how you worked in it: "Plan" or "Changes" mode, permissions for process commands and web search, the model of the last message. When you go to a project, the mode and permissions come back; a new dialog in it takes its model if it is on the same provider with the same key, and Chiller does not switch to another provider by itself. Dialogs already started keep their model. A dialog in a separate copy remembers all this for its project. Web search is turned on in the mode menu by the input field.

The folder of the next message is shown by the chip by the input field. While the dialog has no turns, or a dialog without a folder has had no file changes yet, the folder can be changed or removed in place. After the first turn the folder is fixed to the dialog: snapshots, undo and the edit journal belong to it. Another folder then opens a new dialog.

A dialog without a folder is a conversation: the model answers, searches the web (if allowed), draws and looks at window snapshots, but does not see project files or run commands. Generated images are stored in Chiller's data (`chats/<id>`) and deleted with the dialog. A folder can be attached to such a dialog as long as it had no file changes.

A task keeps running in the background if you go to another dialog or open a new one. The dialog then has a pulsing dot in the list, and one waiting for an answer says "waiting for answer". It can be stopped from the row menu without opening it. At most three tasks run at once, and only one per folder: two agents in one folder would get in each other's way. The check after edits (auto-verify) also works for a background dialog: if it fails, the output goes to the agent in the same dialog, and the result is visible when the dialog is opened again. Notifications in the top right tell you about background dialogs: a task finished or was interrupted, a check passed or not, the agent is waiting for an answer. A click opens the dialog; a question notification stays until read, the others hide by themselves. When the Chiller window is not focused, the same news arrives as system notifications; a click on them or on the tray icon brings the window back with the right dialog. They and the tray icon are turned off in "Settings → Appearance → System notifications".

### Separate copy (git worktree)

To run two tasks in one project at once, a new dialog in a git project can move to a separate copy: the folder menu by the input field → "Work in a separate copy". Chiller runs `git worktree add` from the last commit (HEAD) in its own data (`worktrees/<id>`) on the `chiller/<id>` branch; uncommitted project changes do not go into the copy. The dialog works in the copy like in an ordinary folder, the project does not change, and the "one task per folder" rule does not apply to the copy. In the list such a dialog stays under its project.

"Move to project" collects the copy's changes into one patch and first checks it with `git apply --check`: the patch applies exactly or not at all, with no fitting. If the same places in the project were already changed, the project is left untouched, and the copy and branch remain for a manual merge. "Delete copy" removes the copy's folder (`git worktree remove`) and returns the dialog to the project; the branch stays in the repository. Deleting a dialog deletes its copy too. Line endings follow the git settings (`core.autocrlf`), as in an ordinary checkout.

If the project folder was moved or renamed, the dialog is shown from the journal with the note "Project folder not found". "Set the new location…" moves all the project's dialogs to the new folder; the journal records the new binding, and nothing is copied or repeated.

The "File" menu has Markdown/JSON export with a preview and choosing the working folder through the system dialog. Export hides known secret fields and private replay content; arbitrary text should be checked by hand. Artifact files are represented by links, not embedded copies.

The context is bounded by whole user turns, keeping tool call/result pairs together. Under pressure, old large tool results are replaced with a short placeholder instead of being dropped with their turn. The token estimate is approximate and accounts for the script of the text, tool JSON and a reserve for images. After a reply, the indicator shows the actual input of the last request if the provider reported it (without the ≈ sign); before a reply, the estimate. It is not the total spent in the chat. The actual total usage of a turn is written to `run.summary`.

The tools are designed for a minimum of rounds: `read_file` returns a `snapshot_id`, and successful `create_file`, `edit_file`, `replace_text` and `append_file` return the new version's `snapshot_id` as their first field, so repeated edits of one file need no re-reading, and a long file is written in parts (`create_file`, then `append_file`); one read shows about 5% of the model's context window (200 lines at 32k, about 740 at 128k), and a cut read reports the lines shown and `next_start_line`; the long output of a finished process comes as its start, error lines from the middle and its end; independent READ calls run in parallel, and effects strictly in order; `search_text` returns compact `path:line` with optional context. `run.summary` in the journal records rounds, the number and errors of tool calls, time and tokens; speed is measured by it, not by feel.

When run from the repository root, the app reads `API_TOKEN` from a local `.env` (`DEEPSEEK_API_KEY` is supported too). Environment variables take priority over the file. `.env` is excluded from Git. The file is read at startup; restart the app after changing it. When started from Finder the working folder may differ: for `.env`, use the Gradle command from the project root. A key from the UI stays in memory; a separate button explicitly saves it to the Keychain (macOS) or the Windows Credential Manager.

The `-PchillerPreview=true` flag builds a separate `ChillerPreview.app` and fully turns off automatic key loading at startup, for safe visual checks. The ordinary build: `./gradlew :app-desktop:createDistributable`.

Replies keep their model author; old messages have a neutral label, and offline ones a separate one. There are model and history menus, renaming, export of the selected dialog, normal/compact density and persistent view settings. The connection check button makes one bounded request, only when pressed; having a key does not by itself mean a checked connection. When the provider address changes, the key of that address is used (if set), and a key of another address is never sent there.

## Providers and keys

"Settings → Connection" has ready-made providers (compatible with OpenAI Chat Completions): DeepSeek, OpenAI, OpenRouter, Google Gemini, Groq, Mistral, xAI, Together, Fireworks and the local Ollama, LM Studio, vLLM. A key is kept separately for each address and goes only there; at startup environment variables or `.env` are picked up: `DEEPSEEK_API_KEY`/`API_TOKEN`, `OPENAI_API_KEY`, `OPENROUTER_API_KEY`, `GEMINI_API_KEY`, `GROQ_API_KEY`, `MISTRAL_API_KEY`, `XAI_API_KEY`, `TOGETHER_API_KEY`, `FIREWORKS_API_KEY`. Local servers on `localhost` work without a key. The address can be HTTP or HTTPS: a server on your local network (for example, llama.cpp) is connected with `http://`; over HTTP the key travels in plain text. Only DeepSeek was checked live.

The model menu by the input field has an "Other providers" section: providers with a saved key or models used before. A model picked there continues the current dialog on the other provider from the next message, and the whole history goes to the new server; the switch is written to the journal. Changing the provider in settings still starts a new dialog.

### Proxy

"Settings → Connection → Proxy" is for corporate networks: "System" (the OS settings), "Manual" (`http://host:port`, HTTPS goes through a CONNECT tunnel) or "No proxy". Chat, the model list, images, the connection check and web search go through the proxy; `localhost` and exceptions (`*.company.local`, `.corp`, `10.0.*`, `<local>`) go direct. Proxies with a login and password (including NTLM/Kerberos), PAC scripts and SOCKS are not supported yet.

## Versions, CI and releases

The version is set by one line, `chillerVersion` in `gradle.properties` (format `MAJOR.MINOR.PATCH`; `MAJOR > 0` is required by the installers). It goes into the jar files, DMG/MSI and "Settings → About" together with the build's commit.

- `.github/workflows/ci.yml`: on every push to `master` and every pull request, `check` on Linux, macOS and Windows (the offline smoke on Linux/macOS, it needs `python3`), then DMG and MSI as build artifacts.
- `.github/workflows/release.yml`: a `v1.2.3` tag builds installers with that version and attaches them to a GitHub Release.

```sh
git tag v1.2.0-rc.1 && git push origin v1.2.0-rc.1   # release candidate: pre-release
git tag v1.2.0 && git push origin v1.2.0             # stable release
```

The installers are not signed yet: on macOS, open the app the first time with right-click → "Open"; on Windows, SmartScreen may ask for confirmation.

Visual check rules: [DESIGN.md](DESIGN.md).

Task limits are set in "Settings → Agent" and apply from the next message: model steps, logical requests and output tokens for the whole task (the main agent together with helpers), and the reply length per step. Each has a "No limit" option ("Model maximum" for the reply length: the limit is learned from the provider's response and remembered). This is not a money limit and not a counter of transport retries. The time per task is set there too: 10 minutes by default, 30, 60, 120 or no limit; one model step is limited to 60 minutes, and a stalled stream ends after 2 minutes of silence. Regardless of settings, a task stops after 500 steps; it can be continued with the "Continue" button.

If a reply hits the length limit, the cut-off tool call is not executed: the agent gets the limit size and writes the file in parts. The task stops only after two such cut-offs in a row without progress. For persistent storage on macOS: "Apply", then "Save the current key to Keychain"; an isolated native create/reopen/update/delete smoke with a synthetic key passed on macOS.


## Files, the web and history

- `create_file` safely creates missing parent folders; `create_directory` is available separately. All paths stay in the chosen workspace; symlinks and existing files are never swapped out.
- Cmd/Ctrl+V and drag and drop add text, files or images. Pasted text over **5120 bytes of UTF-8** becomes a `.txt` attachment. Up to eight attachments, a file up to 8 MiB, a pasted PNG up to 4 MiB/16 MP; imports are stored in Chiller's data, not in the project. Sending waits for the import to finish. When you switch chats, an attachment stays with its original dialog.
- The model reads attachment text through `read_artifact`: in parts (`offset`/`limit`, the response reports `next_offset`) or with a `query` search that returns matching lines with numbers and offsets. Images (PNG/JPEG) go to the model as vision input if the model understands them: "Settings → Agent → Model profile → Images" (Auto, by the catalog's `input_modalities`/`capabilities`, otherwise by model ID: DeepSeek Flash, Gemma 3/4, GPT-4o, Qwen-VL and others; On; Off). The image is downscaled to 1568 px on the long side and re-encoded as JPEG; only images from the last two user messages are sent, at most four per request. Otherwise the model gets only the file's name, type and size. Checked live on `deepseek-flash`: `./gradlew :evals:liveVisionSmoke` sends an image with a random number and passes only if the model read it (2/2, 265 input tokens, ~1.3 s). On Gemma 4 26B A4B (vLLM behind a gateway) the check passed too (~1.3 s). If the provider has an image model, `generate_image` draws a picture from a description or edits a project image or an attachment and saves the result as a new file with a preview and undo (only in "Changes" mode, at most four per turn); the image model and the image provider are chosen in "Settings → Agent". `show_file` shows a card of an existing file; PNG/JPEG have a bounded preview, other files can be shown in Finder/Explorer. In Studio, expanding a new or changed image opens the image viewer.
- The web is turned on separately in "Settings → Agent": `web_search` (DuckDuckGo) and `web_fetch` (HTML/text/JSON). The search may get a challenge from the service. Private addresses are blocked by a DNS check, but this is **not an OS network sandbox**.
- On macOS a snapshot is available only for the window explicitly chosen in settings, after allowing screen recording in macOS. The model cannot choose another window by itself; there is no full display snapshot. Windows/Linux capture is not supported yet.
- A dialog can be renamed and deleted from the history menu. Deletion needs a preview/confirmation and does not touch the project; the selected/active dialog has to be closed first. A message is deleted together with its whole turn, so tool call pairs are not broken. Editing removes the later replies and sends the corrected message again as a new turn. This does not undo file changes already made.
- Deleting/editing messages is logical: the original append-only journal is kept. Markdown exports the visible conversation; JSON explicitly exports the original journal, including logically deleted entries. Fully deleting a dialog's data is a separate operation.
- After an interrupted action there is an explicit "Check and continue": uncertain results are marked UNKNOWN; commands are never repeated automatically. The same happens if you simply send a new message: the dialog goes on, and the model checks the state first.

Extra checks run only explicitly: `./gradlew :tools:webSmoke` (fixed public pages, without keys or a project) and `./gradlew :app-desktop:keychainSmoke` (a one-time synthetic entry). The ordinary `check` calls no paid APIs and no internet.

## macOS permission for window snapshots

In the agent settings, turn on "Window snapshots" and press "Refresh" in the window picker: Chiller asks for the system screen recording permission. If access was already denied, enable Chiller in "System Settings → Privacy & Security → Screen & System Audio Recording" (the name depends on the macOS version). Restart the app if needed, refresh the list and choose the window. The OS permission and the snapshot permission inside Chiller are separate settings.

The permission request is triggered by the user choosing a window; the model never triggers the system prompt itself. For a stable permission assignment use the built Chiller.app; a run from Gradle/the terminal may show up in macOS under the name of the launching process.

The "Process commands" choice is kept after restarting the app. It works only together with "Changes" mode; "Plan" mode still forbids processes. When a dialog opens, the conversation starts at the latest messages; scrolling up by hand turns off following new messages.

## History and context reduction

The request budget comes from the chosen model's window in the catalog, at most 1 048 576 tokens. If the window is unknown, a clearly shown fallback of 32 768 is used. The reply reserve, tool schemas and a protocol margin are subtracted from the budget. A number in the catalog does not prove that the server actually allows such a request; the estimate does not replace a tokenizer.

Cancelling a turn that already got tool results does not cut off the earlier plan. Calls with an unknown outcome are closed as UNKNOWN with the next message or with the "Check and continue" button; operations are never repeated automatically.

When space runs short, old large results are replaced with placeholders; if that is not enough, whole old turns are left out. A summary saved in `context.summary` is sent before the excluded history: excerpts of recent requests, the last detailed request and the plan, replies and links to successful file tools. It is rebuilt from the current history, accounting for edits and deletions. It is a bounded automatic excerpt, not an LLM summary: long quotes are cut, semantic completeness is not guaranteed, and there are no extra API costs. The original conversation stays in the journal.

Above the input field you can see the number of excluded messages and shortened results. Estimates, the actual input and the window are saved in the journal and restored when a session opens. If the current turn itself does not fit after shortening old results, Chiller ends the request with a limit error instead of sending an incomplete tool pair.
