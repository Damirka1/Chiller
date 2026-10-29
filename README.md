<div align="center">

# Chiller

**English** · [Русский](README.ru.md)

**A desktop coding agent for local and cloud models.**

Working preview: features are evolving quickly, and changes need review.

Kotlin · Compose Desktop · OpenAI-compatible APIs · macOS and Windows

[Download a release](https://github.com/Damirka1/Chiller/releases) · [Quick start](#quick-start) · [Why Chiller](#why-chiller) · [Details](docs/USAGE.md)

<img src="docs/images/focus-light.png" alt="Chiller: Focus mode, light theme" width="900">

</div>

Chiller is a desktop app in which a model works on your project through precise tools: it searches, reads fragments, edits against file snapshots, runs checks and reports back. The main goal is for a **4–30B model on your own vLLM / Ollama / LM Studio** to solve real tasks reliably and fast, not only large cloud models.

> The app interface is currently in Russian. The documentation is available in English and Russian.

## Why Chiller

Chiller lets you choose a full or a compact tool set and bound the context. The aim is fewer wasted reads and an easier job for small models. The effect depends on the model and the task.

### Faster: fewer rounds and tokens

| What | How | Effect (live measurements, DeepSeek V4.1 Flash) |
|---|---|---|
| **Compact profile** for small models | 17 core tools in the current version (including append, delete and move), a short step-by-step prompt, one call per step, temperature 0.2; for Gemma, Google's recommended temperature 1.0, top_p 0.95, top_k 64 | In an early 11-tool profile: 13/13 tasks with **~146k** prompt tokens versus **~235k** for the full profile, **38% fewer** |
| **Project map** in context from the first round | files by folder and their top-level declarations, within 3000 characters | 14/14 in both cases; rounds **67 → 56 (−16%)**, tool calls 78 → 61, time 120 → 108 s; "find where in the code…" tasks take 2 rounds instead of 4 |
| **Edits without re-reading** | `edit_file` returns a new `snapshot_id`, so later edits of the same file skip another `read_file` | fewer rounds on multi-step edits |
| **Parallel reads** | independent READ calls run at once, changes run strictly in order | several files in one round |
| **Stable prefix** | the system part of the request does not change between rounds | provider cache: in measurements up to 80% of the prompt came from cache |
| **Reading in fragments** | `find_symbols` gives the definition line, `read_file` reads a range, `search_text` returns compact `path:line` | the context gets the relevant piece, not the whole file |

The numbers above come from separate small runs on DeepSeek. They are not a statistical guarantee and not a test of local models. The profile's tool set and version changed between runs.

### More reliable: tools that are hard to misuse

- **Snapshots instead of blind writes.** An edit of an existing file refers to the `snapshot_id` of the version that was read. If the file changed, the tool returns `STALE_SNAPSHOT` instead of overwriting someone else's changes.
- **No "approximate" application.** `replace_text` needs an exact match and the expected number of occurrences; partial or invalid arguments are not executed.
- **Structured errors with a hint.** Every error has a code and a `recovery` hint, and three identical errors in a row stop the loop instead of repeating forever.
- **Checks before "done".** With auto-verify and process permission on, the project's configured command runs after edits and returns the output of failing tests to the agent.
- **Turn results from facts, not from the model's words.** The card at the end of a turn is built from the journal: changed files, commands with exit codes, presented files. It separately highlights a failed check that was not rerun, an unread result, an edit that did not apply, and the absence of confirmed check commands.
- **Undo as you go.** Changes made by file tools can be rolled back during the dialog when the current state allows restoring. Files changed during the turn by commands or by you are listed separately: undo does not bring them back, and detecting such changes is bounded by the snapshot size and the files checked.
- **Measured waste.** The built-in eval tasks count not only the pass rate but also repeated reads, failed edits and the amount of tool output in context, so improvements are backed by numbers.

## Screenshots

<table>
<tr>
<td width="50%"><img src="docs/images/focus-dark.png" alt="Focus, dark theme"><br><sub><b>Focus</b>: the dialog, grouped tool calls, a live plan checklist under the messages.</sub></td>
<td width="50%"><img src="docs/images/studio-dark.png" alt="Studio"><br><sub><b>Studio</b>: the inspector with diffs, processes, helpers, tokens and rounds.</sub></td>
</tr>
<tr>
<td width="50%"><img src="docs/images/settings-light.png" alt="Agent settings"><br><sub><b>Model profile</b>: auto by ID, tool set, vision, temperature and request fields for vLLM.</sub></td>
<td width="50%"><img src="docs/images/focus-light.png" alt="Focus, light theme"><br><sub><b>Light theme</b>: Chiller's own design system on shared components.</sub></td>
</tr>
</table>

The screenshots are rendered from the real app and the real agent loop on a demo project: `./gradlew :app-desktop:readmeShots`.

## Features

- **OpenAI-compatible providers**: presets for vLLM, Ollama, LM Studio, DeepSeek, OpenAI, OpenRouter, Gemini, Groq, Mistral, xAI, Together, Fireworks. A key is bound to its origin; you can explicitly save it to the macOS Keychain or the Windows Credential Manager. Compatibility of each preset has not been confirmed separately.
- **Model profiles**: "Full" for large models, "Compact" for small ones; "Auto" chooses by ID (for example, `gemma-4-26b-a4b` → compact) and, for gateway aliases, by the name in the catalog (`quasar` named "Gemma 4 26B A4B" → compact). Gemma by ID or name gets the recommended sampling (temperature 1.0, top_p 0.95, top_k 64); a temperature you choose and extra request fields take priority, and `null` in them removes a field the server does not accept. If the catalog does not name the model (an alias named like "Quasar"), choose the profile and temperature by hand.
- **Vision**: images from the clipboard and from files go to a model that understands them, downscaled and only with the latest messages.
- **Image generation**: if the provider's catalog has an image model (for example, Flux 2 Klein), the agent gets `generate_image`. It draws a new picture or edits an attached or project picture and saves the result as a new file with a preview and undo. Images can be drawn through a different connected provider than the chat.
- **Your own provider**: a company gateway or a home server (HTTP or HTTPS, for example llama.cpp on your local network) is added in settings with a name, an address and a key variable from `.env`; it is stored only in this computer's settings. A model of another provider can be picked right in the model menu, and requests can go through a corporate proxy.
- **Background preparation of attachments**: a card shows the preparation while encoding runs in the background; text over 5 KiB becomes a file attachment. System clipboard latency depends on the OS and the format; the [measurements](docs/PASTE_BENCHMARK.md) do not include the time to the first paint.
- **Helpers**: read-only subagents for parallel investigations with a shared budget of requests and tokens.
- **Modes**: Plan (no writes or processes), Changes, separate permissions for processes, the web and window snapshots.
- **Model comparison** inside the app: the same tasks, pass rate, time, tokens and waste side by side.
- **Journal**: every action is written to an append-only journal; `run.summary` records rounds, calls, errors, time and provider usage. After a crash, side effects are never repeated automatically.
- **Export** to Markdown/JSON, dialog history, renaming, editing and undoing turns.

## Quick start

Ready-made DMG and MSI installers are on the [releases](https://github.com/Damirka1/Chiller/releases) page; they need no separate JDK. Ripgrep is included in the installer, so there is nothing else to install.

To run from source you need JDK 21+ and Git; Gradle prepares a verified ripgrep for the current platform:

```bash
git clone https://github.com/Damirka1/Chiller.git
cd Chiller
./gradlew :app-desktop:run
```

1. Choose a project folder.
2. In "Settings → Connection" pick a preset (for example, vLLM `http://localhost:8000/v1`) and a model.
3. Write the task. Switch to "Changes" mode for edits, and turn on "Process commands" for tests.

A demo without a model or a key: "Demo scenario" on the start screen shows a scripted run on real files.

### Gemma on vLLM

Start vLLM with the OpenAI-compatible API and tool calling on: `--enable-auto-tool-choice` and a `--tool-call-parser` that matches your model's chat template (see the vLLM documentation). In Chiller, set the address `http://<server>:8000/v1` and the model ID; the "Auto" profile turns on compact mode and vision. Chiller reads the context window, vision and reasoning from the `/models` catalog (`max_model_len`, `input_modalities`, `capabilities`; the fields are listed under [providers](docs/PROVIDERS.md#vllm-and-gateways)). For a model with `reasoning` in the catalog, the "Model reasoning" switch passes `chat_template_kwargs.enable_thinking`. The reasoning of such a model is sent back to it within the turn, as Gemma 4 requires. On 18 Gemma tasks this halved the generated tokens and cut the time by a third. Gemma requests get the recommended sampling: temperature 1.0, top_p 0.95, top_k 64. vLLM structured output can be passed through "Extra request fields (JSON)".

Live check on October 19, 2026: Gemma 4 26B A4B (MoE, ~4B active parameters) on vLLM 0.31 behind an HTTPS gateway, the `:evals:liveEval` task set.

**Short tasks** (fixes, renaming, a large file, navigation, CRLF/Unicode, user edits, helpers, images, plan, honesty):

| Run | Passed | Notes |
|---|---|---|
| Compact, no reasoning, 14 tasks × 1 | 12/13, `delegate-research` skipped (no helpers) | `locate-code` miss: answered by the wrapper's name without reading its body |
| Full, no reasoning, 14 × 1 | 13/14 | the same `locate-code` |
| Compact + reasoning, 14 × 1 | **13/13** | ~255k prompt tokens, ~5 min |
| Full + reasoning, 14 × 1 | **14/14** | ~371k prompt tokens on the same 13 tasks, ~6.7 min |
| Full + reasoning, 17 tasks × 2 | **31/34 (91%)** | all three failures hit the 14-step limit, no wrong results; `plan-closure` 2/2, `generate-image` 2/2 |
| `honest-missing-data` (the source has no data) × 2 | **2/2** | numbers were never made up; before the "no tools" round the model looped and scored 0/2 |

**Long scenarios** (full profile + reasoning, 2 repeats each):

| Scenario | Passed | What it checks and what we saw |
|---|---|---|
| `conversation-context-pressure`, 10 turns in a 16k window | **2/2** | the context was compressed; three constraints from the first message (`canary`, `kz`, `retries: 2`) reached the last turn |
| `conversation-requirements`, 3 turns: fix → changed requirements → user edit and restart | 1/2 | restart from the journal and a foreign file edit are preserved; in the second run the model changed `checks.py` despite a ban in the first message |
| `multifile-retry-policy` | 1/2 | the task gets solved, but with 1.5–2.4k reasoning tokens per round (~27–35 tokens/s) a turn may not fit into 10 minutes |

Also: `liveVisionSmoke` passed (an image with a number was read, ~1.3 s); `liveImageSmoke` with Flux 2 Klein generated and edited 1024×1024 (~40 and ~65 s). With reasoning, the reply gets 12288 tokens of headroom over the limit, because reasoning spends the same `max_tokens`.

**October 20, 2026**, compact profile with reasoning, Gemma sampling:

| Set | Passed | Notes |
|---|---|---|
| Short and long tasks, 18 × 1 | **18/18** | after returning the model's reasoning: generated tokens 53.6k → 29.6k, time 825 → 522 s |
| `tool-*` probes, 10 × 2 | **20/20** | it was 18/20 without reasoning |
| Hard `hard-*`, 6 × 2 | 9/12 | `hard-iso-dates` 0/2 → 2/2 after window-sized reads; failures are `hard-duration-spec` (reasoning was cut by the limit) and `hard-mutation-tests` (the model does not check withdrawing the whole balance) |
| `hard-duration-spec` and `hard-mutation-tests` with 12288 reasoning headroom, × 3 | **6/6** | cuts 8 → 0, a `hard-duration-spec` attempt takes ~150 s instead of 300–600; `hard-mutation-tests` had no cuts before either, so it is more likely noise |

What we saw from the model: with reasoning it is noticeably more reliable (`locate-code` passes) but slower; it sometimes breaks a constraint it can see in context; in a multi-step edit `edit_file` may insert lines instead of replacing them. These are small runs, not a statistical evaluation.

## Quality evaluation

```bash
./gradlew check :evals:offlineSmoke                       # no remote model; needs dependencies and Python 3
CHILLER_EVAL_PROFILE=compact ./gradlew :evals:liveEval     # a paid run of 19 tasks; needs a configured key
CHILLER_EVAL_BASE_URL=http://localhost:8000/v1 CHILLER_EVAL_MODEL=gemma-4-26b-a4b-it ./gradlew :evals:liveEval
```

Each task is checked independently (real Python tests and files), and the checks are validated themselves: they fail on the untouched project and pass on the reference solution. The `report.md` report has the pass rate, rounds, tokens and waste for every task.

## Structure

Ten modules: `contracts` (frozen contracts), `agent-core` (the agent loop, profiles, context), `providers` (Chat Completions + SSE), `tools`, `workspace` (snapshots, edits, undo), `runtime` (processes), `sessions` (journal), `evals`, `design-system`, `app-desktop`. More in [architecture](docs/ARCHITECTURE.md), [providers](docs/PROVIDERS.md), [design](docs/DESIGN.md), [development](CONTRIBUTING.md) and [usage](docs/USAGE.md).

On every push, CI builds and tests on Linux, macOS and Windows and produces DMG and MSI installers; a `vX.Y.Z` tag publishes a release.

## Known limitations

- Path and process working-directory checks are not an OS sandbox.
- Gemma/vLLM was checked live on one server (Gemma 4 26B A4B, vLLM 0.31 behind a gateway), with results in "Gemma on vLLM"; symbol search is lexical, not LSP.
- The installers are not signed: on macOS, open the app the first time with right-click → "Open".
- The task money budget (Settings → Agent) uses prices you enter and reserves the worst case in advance; it is not the provider's bill, and without a budget the request and token limits do not cap the amount spent.

## License

Chiller is distributed under the [Apache License 2.0](LICENSE). Third-party component licenses are kept separately; see [dependencies](docs/DEPENDENCIES.md).
