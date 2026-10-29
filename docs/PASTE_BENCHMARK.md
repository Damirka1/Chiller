**English** · [Русский](PASTE_BENCHMARK.ru.md)

# Paste benchmark, October 18, 2026

Run: `./gradlew :app-desktop:pasteBenchmark` (report in `app-desktop/build/reports/paste-benchmark.md`). Acceptance from the overnight audit: 30 repeats at 1080p/4K/8K for photo, noise and transparency, text of 5 KiB/1 MiB/8 MiB, p50/p95 and memory, reuse of the image for vision.

## What the benchmark changed

The first run showed that poorly compressible images took seconds to prepare: the PNG was encoded whole, came out larger than 4 MiB and was shrunk in fixed 25% steps up to six times. Now the size is estimated from a central 512×512 fragment before the first full encode, and on a miss the image is shrunk in proportion to the overshoot.

| Case | p50 before | p50 after |
|---|---:|---:|
| 8K noise | 5352 ms | 269 ms |
| 4K noise | 1324 ms | 116 ms |
| 8K photo | 1265 ms | 666 ms |
| 1080p noise | 248 ms | 119 ms |
| others | unchanged | unchanged |

All encoding runs off the UI thread: the "preparing…" card shows up at once, and these milliseconds are the time until the attachment is ready, not a frozen window. Reading the system clipboard and the first paint of the card are not measured here: that needs a native clipboard.

## Latest run

Mac OS X aarch64, JDK 26.0.1, 10 CPUs · 30 repeats per case · encoding off the UI thread (as in the app) · "heap peak" is the largest growth of used memory during a case, including garbage not yet collected: an upper bound, not live data.
Not measured: reading the system clipboard and the first paint of the card (needs a native clipboard).

| Image | p50 / p95 / max | Heap peak | Result |
|---|---|---|---|
| 1080p photo | 55 / 57 / 58 ms | 21 MiB | 886 KiB, 1920×1080 |
| 1080p noise | 119 / 139 / 145 ms | 185 MiB | 3148 KiB, 1409×792, downscaled |
| 1080p alpha | 52 / 53 / 60 ms | 38 MiB | 478 KiB, 1920×1080 |
| 4K photo | 201 / 220 / 233 ms | 154 MiB | 3544 KiB, 3840×2160 |
| 4K noise | 116 / 121 / 123 ms | 200 MiB | 3147 KiB, 1409×792, downscaled |
| 4K alpha | 172 / 174 / 177 ms | 96 MiB | 958 KiB, 3840×2160 |
| 8K photo | 666 / 693 / 699 ms | 1887 MiB | 3265 KiB, 3206×1803, downscaled |
| 8K noise | 269 / 273 / 276 ms | 1449 MiB | 3091 KiB, 1340×754, downscaled |
| 8K alpha | 454 / 462 / 472 ms | 1846 MiB | 2599 KiB, 5333×3000, downscaled |

| Text and vision | p50 / p95 / max | Heap peak |
|---|---|---|
| text 5 KiB (4 KiB UTF-8) | 1 / 1 / 2 ms | 2 MiB |
| text 1 MiB (929 KiB UTF-8) | 3 / 3 / 5 ms | 106 MiB |
| text 8 MiB (7429 KiB UTF-8) | 19 / 19 / 21 ms | 1261 MiB |
| vision 4K: first round / next 5 | 112.4 ms / up to 0.09 ms | — |
