**English** · [Русский](PROVIDERS.ru.md)

# Providers

Chiller uses the OpenAI-compatible Chat Completions API. A matching format does not guarantee that a particular server supports tools, vision or reasoning. The main live checks were done on DeepSeek (`deepseek-flash`) and on Gemma 4 26B A4B (vLLM 0.31 behind an HTTPS gateway).

## Connecting

Set the base URL, the exact model ID and the key in settings. A local server usually uses `http://localhost:8000/v1`; DeepSeek directly uses `https://api.deepseek.com`. The `models` and `chat/completions` paths are appended to the base URL. A gateway's key needs that gateway's address. The address can be HTTP or HTTPS, without a login in the URL, a query or `#`. A corporate proxy is configured separately, see [USAGE](USAGE.md#proxy).

The model catalog loads at startup and when the connection changes, and it can be refreshed by hand. A remote endpoint needs a configured key; loopback can work without one. The last list is kept per endpoint; on an error it stays available and is marked stale. Changing or deleting a key clears the cache for its origin. A catalog response does not prove that generation or tools work. The paid connection check runs only when you ask.

Keys are bound to their origin. The "Remember key" button explicitly saves a key to the system store: the Keychain on macOS, the Credential Manager on Windows (entry `Chiller/<origin>`, only for the current user of this computer); otherwise there is process memory and the configured environment variables/.env. Do not keep keys in extra JSON request fields. Details are in [USAGE](USAGE.md).

## vLLM and gateways

From the `/models` response Chiller reads:

| Field | Meaning |
|---|---|
| `id` | the required model ID |
| `name` | the name in the interface; used by the "Auto" profile when the ID says nothing about size |
| `context_window`, `context_length`, `max_model_len` | the context window (the first one found); without them, 32 768 |
| `max_output_tokens` | a confirmed reply limit; a missing field means "unknown" |
| `input_modalities` (or `architecture.input_modalities`) | `image` turns on vision in "Auto" mode |
| `capabilities` | `vision` when there are no `input_modalities`; `reasoning` enables the reasoning switch through the chat template; `chat_completions`, `image_generation`, `image_editing` |
| `output_modalities`, `supported_endpoints` | a model without text output or without `/chat/completions` is not offered for chat; `/images/generations` and `/images/edits` declare generation and editing |

Missing fields stay unknown; the ID heuristic is used only then.

vLLM reasoning arrives in `reasoning` (older versions and SGLang use `reasoning_content`) and is shown as visible reasoning, step by step in the conversation. For a model with `reasoning` in the catalog it is sent back with its messages in `reasoning_content`. Gemma 4 requires reasoning to be kept between tool calls within one turn. The Gemma 4 and Qwen 3 templates keep it only after the last user message and drop the reasoning of earlier turns themselves. Without this the model lost its plan after every tool result. For such a model the switch sends `chat_template_kwargs: {"enable_thinking": true|false}` both ways, since Qwen templates think by default. Your own `chat_template_kwargs` in the extra request fields replaces this one.

Chiller always sends `max_tokens`: the reply limit is part of the turn budget and of recovery after a cut-off, even if a gateway suggests not passing it.

The server certificate is checked against the JDK store and the system store (Windows-ROOT, the macOS keychain), so a corporate CA trusted by the OS works without configuring the JVM. Verification is never turned off; a TLS error arrives as `PROVIDER_TLS_ERROR` without retries.

A `429` with `Retry-After` (seconds or an HTTP date) is waited out up to 120 seconds, and the turn shows "Retrying in N s"; a longer interval ends the turn with the server's number. An error that arrives as an SSE `error` event after the reply started is reported in the provider's own words (`PROVIDER_STREAM_ERROR`).

A gateway should answer an exceeded quota with `429` and `Retry-After` for `stream: true` as well: Chiller treats `401` as a rejected key and does not retry it.

## Images

The image model is chosen in "Settings → Agent → Image model": "Auto" takes the first catalog model with `image_generation` at the chosen address, "Off" removes the tool. "Image provider" in the same place lets images be drawn through another connected provider, for example chat on llama.cpp and images through a company gateway; the list shows providers whose saved catalog has an image model. Requests use the OpenAI Images API at that address with that address's key: `POST /images/generations` (JSON) and `POST /images/edits` (multipart with the image). Chiller asks for `b64_json` and never downloads images from a link in the response; it accepts PNG, JPEG and WebP up to 64 MiB. A quota or server error before the response is retried up to three attempts, respecting `Retry-After`; a timeout (180 s) is not retried.

`generate_image` is not available in Plan mode, writes only a new file (never overwrites), with undo, and at most four images per turn. A reference is a project image or a user attachment by `artifact_id`; no other files are sent to the image address.

## Your own provider

"Settings → Connection → Add your own provider" saves a name, an address, an environment/`.env` variable with the key, a default model and, optionally, reasoning and a profile applied with it. The entry is stored in `~/.chiller/settings.json` and never goes into the repository or the installer. The key is read from the variable at startup and on save and bound to the origin of that address; a key typed in the app is not replaced by the variable.

## Profiles and protocol

The full/compact profile changes the tool set and how the agent works, not what the server can do. Automatic selection by ID is a heuristic; the settings can override it.

Sampling: the compact profile asks for temperature 0.2, the full one leaves it to the server. For a family with a known recommendation from its authors, that recommendation replaces the profile's temperature: Gemma (by ID or by name from the catalog) gets `temperature: 1.0`, `top_p: 0.95`, `top_k: 64`. The temperature chosen in settings and "Extra request fields (JSON)" are applied on top; a `null` value removes a default field, for example `{"top_k": null}` for a server that does not accept `top_k`. The request fields of each live eval are written to its `report.md`. Vision sends prepared images to models that support it; the support flag does not replace checking the server.

The SSE adapter assembles tool arguments until the reply is complete. A partial call is never executed. A bounded retry of a stateless request on a temporary error is allowed before the first delta; tool effects are never retried automatically.

Protocol reasoning/replay is stored separately from visible reasoning: hiding text in the UI must not break the history the provider needs. Usage is saved as the provider reported it; missing numbers stay unknown.

## Reply length and cut-offs

The requested `max_tokens` never exceeds half of the context window. If the provider answers that the value is out of range (DeepSeek, OpenAI and vLLM formats), Chiller remembers the limit for the address + model pair and retries the same stateless request once. Model reasoning spends the same limit. A cut-off tool call is not executed; the model gets the limit size and a way to write the file in parts. Cut-off text is kept, and the model continues where it stopped.

## Checks

`./gradlew check :evals:offlineSmoke` uses fixtures and local services. The first Gradle run may download dependencies.

The app has its own task money budget (Settings → Agent → "Money budget"): a limit in USD, prices per 1M input and output tokens, a price per image and a per-request input token ceiling. Every attempt, including retries, helpers and images, reserves the worst case before it is sent; a reply with known usage replaces the reservation with the actual cost, and an unknown outcome keeps the reservation. Prices are bound to the address, chat model and image model they were saved for: with another model the budget refuses, and an empty price refuses too. The input token ceiling also bounds the request context. The total is written to `run.summary` (`cost`). It is an estimate at your prices, not the provider's bill.

The RC live checks went through a local gateway with a money ceiling: before every request the worst cost at peak prices without a cache discount is reserved, an image counts as 16 384 input tokens, after the reply the reservation is replaced with the actual cost, and an unknown outcome keeps the reservation. The gateway itself is a local test tool and is not part of the app.

The live tasks `:evals:liveDeepSeekSmoke`, `:evals:liveEval`, `:evals:liveVisionSmoke`, `:evals:liveImageSmoke` run separately with an explicitly configured key. They spend balance. Round and output limits do not provide an overall dollar ceiling, including retries and helpers; set an external budget before running a large set.
