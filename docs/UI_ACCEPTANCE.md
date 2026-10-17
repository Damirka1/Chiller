# Chiller — UI finishing acceptance, 17 October 2026

Implementation: docs/UI_FINISHING_RUNBOOK.md. Model authorship checkpoint: 3338839.
All development checks use fixtures; this iteration makes no remote paid API requests.

## Implemented and verified

| Card | Result and evidence |
|---|---|
| M1/M2 | Stored MODEL/APPLICATION/OFFLINE origin; streaming author; terminal reset; legacy fallback; full-answer Copy; origin export. Restart/wire/export unit fixtures pass. Native window shows DeepSeek Flash, fixture gemma-4, legacy Ассистент and Chiller · offline. |
| T1/T2 | Activity.toolCallId, ordered projection and legacy unlinked activities; grouped known successful reads/writes, visible failures and actual run status. Projection/core tests and native expansion of two-file read group checked. |
| C1 | Receipt after durable user append; request-ID dedup; draft cleared only for matching accepted snapshot; late ack cannot clear edited/other-session draft; return text on failure. Core/controller tests plus native offline Cmd+Enter produced one response; missing-workspace refusal kept the text. |
| C2 | Per-endpoint remembered IDs, model popup/manual settings, new-chat selection, explicit draft transfer; endpoint change clears credential. Controller fixtures and native selection between two synthetic IDs preserve draft. |
| H1 | Rename events and restart projection; Today/Yesterday/Earlier in local time; full-title hints and explicit-target export. Controller/storage/date fixtures pass; native rename changes row and header. |
| S1 | Persistent theme/density/layout/width/reduced motion; atomic preferences; 10dp divider, resize cursor and accessible width buttons; narrow panel switch, selected file/diff summary. Preferences tests and native dark/compact Apply+restart and narrow panel/chat switch checked. |
| P1 | Explicit single bounded check, max output128, timeout30s, no tools/files/retries; response <=64KiB and valid protocol; safe errors; snapshot/revision/cancel protection. Loopback tests include stale result, malformed/oversized response, timeout and cancellation. Native button produced Verified from one synthetic local response; captured payload had required bounds and no tools. |
| K1 | Shared action routing with pending-send guard; multiline TextFieldValue/composition gate; normal Enter newline; native Cmd+N/Cmd+Enter and settings Escape verified in actual preview. Focus borders/semantic names retained. |
| D1–D3 | Compact composer: 84 dp empty block (`10 + 26 + 6 + 32 + 10`), text field grows to 144 dp then scrolls, measured toolbar with mode + content-sized model control (inline chevron, 5 dp gap, ellipsis + full-ID tooltip), Send/Stop only on the right, wrap instead of smaller type, sidebar capped at 1.2× font scale, top bar grows. Compiled and launched from the current source; **no window/screenshot acceptance was performed for these values**. Process-permission toggle moved into the Changes popup (still in Settings). |

## GUI checks actually performed

- Actual ChillerPreview with isolated /private/tmp/chiller-ui-finishing-review data.
- Light Focus and dark/compact Studio, history date sections and rename.
- Plain Enter remains multiline; Cmd+Enter accepts one offline message and clears field after receipt.
- Cmd+N opens new chat; Escape closes settings while draft remains intact.
- Full answer Copy, grouped tool disclosure, error restoration/return-to-field and read-only interrupted question.
- 1200-message synthetic session: normal scrolling and Jump to latest reached message1199 without an observed freeze. This is qualitative, not a benchmark.
- Narrow 960x720: Studio toggle reaches changes and returns to the same draft; model popup/settings are usable. Changing model through settings and popup preserves draft.
- Local-only HTTP checker GUI path tested with synthetic key; one captured POST, no project context/tools, max_tokens128, protocol-shaped OK response. No claim of live provider tool support.
- Primary full check and macOS package passed with 81 tests before final concurrency/UI polish; final rerun result is recorded in RUN_REPORT.

## Limits and remaining manual acceptance

- Native dark titlebar does not yet track app-selected dark theme; supported appearance integration remains a platform task.
- Real IME composition and VoiceOver need a human/platform pass; composition gating is implemented but actual CJK input was not exercised.
- Pointer dragging of Studio divider did not visibly resize under CUA. The draggable implementation and persisted bounds exist; width buttons provide a keyboard-accessible adjustment. Do not describe pointer-drag acceptance as passed.
- macOS only. Windows/Linux and signing/distribution release gates are not verified.
- The compact composer (D1–D3) is compiled and the app launches, but its measured 84 dp height, chevron spacing, wrap at ~740 dp, popup placement and the moved process toggle were not visually reviewed. Do not describe them as accepted.
- Connection probe authenticates/model-access only. DeepSeek/Gemma live tool loop is outside this UI test run; the separate live smoke is recorded in RUN_REPORT.

## Repeatable review data

```sh
python3 scripts/create-ui-fixtures.py --data-dir /tmp/chiller-new-ui-review
./gradlew :app-desktop:createDistributable -PchillerPreview=true
CHILLER_DATA_DIR=/tmp/chiller-new-ui-review ./app-desktop/build/compose/binaries/main/app/ChillerPreview.app/Contents/MacOS/ChillerPreview
```

Use a new/empty directory. Fixtures explicitly identify synthetic content and never apply their sample diffs.
Preview supports CHILLER_PREVIEW_WIDTH/HEIGHT and CHILLER_PREVIEW_FONT_SCALE (1..2) for manual layout checks. These do not affect the regular Chiller build.


### Readiness-fix native pass, 17 October

Latest offline preview: full scripted demo with real tools/Python completed; explicit question answered; completed-demo transition; text composer and Send; light/dark draft survival; blank-model preset apply/catalog entry; restored Studio file list and expanded README diff verified in native UI. Screenshots: reports/local/fixes-ui (ignored). No remote requests or real Keychain operations. Narrow/font-scale/IME/VoiceOver acceptance remains outstanding.
