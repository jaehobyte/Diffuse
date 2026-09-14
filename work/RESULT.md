# Result

## Status

PARTIAL — T1 (자동 보정 connection) is DONE in code, tests and spec, and the server path is verified
on the host. Phone end-to-end is **not** verified: no device was attached, and the MonetGPT service
has no route a phone can reach (operator action, below). T2/T3 (피부 보정) are **BLOCKED** and
unchanged; the sheet still shows 준비 중. Neither problem is declared solved on the phone.

The previous result (acne detector, 2026-09-10) is preserved as
`work/RESULT_acne_detector_2026-09-10.md`. Review fix pass (2026-09-14): `work/REVIEW.md` R1 and R2
are fixed (below); the review's device/server/skin limits remain open and are unchanged here.

## Changed

### T1 — root cause (reproduced on the host, not on a phone)

1. **The `install.sh` APK points at the phone's own loopback.** `.env` has
   `MONET_BASE_URL=http://127.0.0.1:8082`; `-Pdiffuse.localCreds` compiles it into `BuildConfig`
   (versionName `0.6.0-local`). On a phone that address is the phone. Every probe fails.
2. **The service is reachable only from the server itself.** `uvicorn` listens on
   `127.0.0.1:8082`; no Caddy (or other proxy) is running — `~/bin/caddy` exists but no process,
   and `ss -ltn` shows neither `8082` public nor `8090` (SAM 3's test port, which is down too).
   A downloaded APK (blank default) therefore has no address that could work either.
3. **The app made it unrecoverable.** `health()` collapsed every failure to `false` →
   `AppError.Unavailable`; the probe ran only on a `StateFlow` emission, so a recovered server, or
   re-saving the same settings (equal value, no emission), never re-probed; the tap only showed
   "연결하지 못했어요"; the initial state was "unreachable" before the first probe answered; a saved
   override of an untouched field pinned a build's default forever; a malformed saved URL would
   throw in `Request.Builder.url`.

Server side is healthy: authenticated `GET /health` → `200 {"status":"ready"}`, no/wrong token
→ `401`, and the three-style app-shaped smoke passes (below). No MonetGPT code defect was found, so
`/home/jaeho/monetGPT` was **not modified**. SAM 3 and other GPU services were not touched.

### T1 — fix

- `MonetClient.health(config)` returns `Result<Unit>` with the reason: blank → `Invalid(no server
  address)`, malformed → `Invalid(invalid server address)` (checked before building a request, both
  calls), `404` → `Invalid` (wrong service), `401/403` → `Unauthorized`, `429/5xx` → `Unavailable`,
  refused/timeout → `Io`. Dedicated 5 s connect/read/call timeout; generation keeps 10 s / 120 s.
- `AutoEnhanceProvider` gained `checking` and `refresh()`. `MonetAutoEnhanceProvider`: one probe per
  settings (a refresh for the same settings joins), a probe for replaced settings is cancelled (HTTP
  call cancelled) and its late answer is discarded by job ownership; every save — including an
  identical one, via `MonetSettings.saves` — re-probes; a generation failing with Io/503/401
  triggers one re-probe. No polling, no re-upload.
- `MonetSettings`: one normal form (trim, trailing `/` and `/v1` removed) for saves and old
  overrides; a save equal to the build default removes the override.
- `AutoController`: tap during probe → `auto_checking`; blank/malformed/401 → message + 서버 설정;
  503/unreachable → `auto_rechecking` + re-probe of the same settings + 서버 설정, regardless of any
  earlier success (R1); the answer to that re-check is announced (`auto_ready`, `auto_not_ready`,
  …); the next tap runs. Generations carry a run id: answers after cancel, close, a newer chip, a
  서버 설정 save, or a document change are discarded, and an old run cannot clear a newer run's
  `busy`. A document change (undo/redo/reset) ends the whole session — input bitmap, plan, open
  sheet, `selectedTool`, `sheetBaseline` — without restoring the baseline, so a later chip or 적용
  cannot use the old photograph (R2). A chip failing on an unchanged document keeps the result.
- 서버 설정 sheet: MonetGPT URL shows a loopback/USB hint, marks a malformed URL and disables 저장.
- `scripts/install.sh` warns when `.env` holds a loopback `SAM3_BASE_URL`/`MONET_BASE_URL`.
- `specs/auto_enhance.md` §4/§5/§6/§7/§8: "probe failed → no action" replaced with the retry /
  settings table and race rules; normal result sheet, local 강도, per-style call, one Apply/Undo and
  cancel = unchanged are kept. `work/decisions.md` D081.

### T2 / T3 — 피부 보정: BLOCKED, nothing changed

Checked, not assumed:

- **Quality gate data does not exist.** `specs/skin_retouch_validation.md` §2 needs ≥24 licensed
  face photos with per-kind positives/negatives and annotations; `fixtures/` holds no face photo and
  none can be sourced by the agent. SR1-A/B/C cannot be judged for any kind, so no kind can pass and
  none may be enabled (§1, and this task's rule against enabling unfinished UI).
- **Licensing unresolved** for all candidate weights (MI-GAN weights: no licence metadata; acne
  detector: card says Apache-2.0, no LICENSE file, export carries Ultralytics AGPL-3.0 stamp).
- **Only on-device measurement is negative**: detector 2.0 s p50 and 261 MB PSS at rest per ROI
  on SM8850 (`work/retouch_evaluation.md` §3A.7) vs 5 s all-kinds / 250 MB editor budgets; MI-GAN
  never measured on a device; no production delivery path (debug-only ONNX runtime, adb-pushed model).
- Consequently T3 (production `SkinRetouchProvider` binding, `Operation.SkinRetouch`, codec, render,
  atomic storage, sheet) was not started: T3 connects only verified kinds. No budget was relaxed and
  no blur/fake/external API substitute was added.

## Files

- `core/ai/src/main/kotlin/com/diffuse/core/ai/AutoEnhanceProvider.kt`
- `core/ai/src/main/kotlin/com/diffuse/core/ai/monet/{MonetClient,MonetConfig,MonetSettings,MonetAutoEnhanceProvider}.kt`
- `core/ai/src/testShared/kotlin/com/diffuse/core/ai/FakeAutoEnhanceProvider.kt`
- `core/ai/src/test/kotlin/com/diffuse/core/ai/monet/MonetClientTest.kt`, new `MonetAutoEnhanceProviderTest.kt`
- `feature/editor/src/main/kotlin/com/diffuse/feature/editor/tools/auto/AutoController.kt`
- `feature/editor/src/main/kotlin/com/diffuse/feature/editor/EditorViewModel.kt`
- `feature/editor/src/main/kotlin/com/diffuse/feature/editor/tools/select/Sam3SettingsSheet.kt`
- `feature/editor/src/main/res/values/strings.xml`
- `feature/editor/src/test/kotlin/com/diffuse/feature/editor/tools/auto/AutoToolTest.kt`
- `feature/editor/src/test/kotlin/com/diffuse/feature/editor/tools/select/Sam3SettingsSheetTest.kt`
- `scripts/install.sh` (pre-existing untracked file; warning block added)
- `specs/auto_enhance.md`, `work/decisions.md` (D081), `work/RESULT.md`,
  `work/RESULT_acne_detector_2026-09-10.md` (copy of the previous result)

Pre-existing uncommitted changes (detector work, `app/`/`core/ai` Gradle, `libs.versions.toml`,
`specs/architecture.md`, `work/tasks.md`, …) were preserved and not edited.

## Validation

| Command | Result |
|---|---|
| baseline `:core:ai … monet.*` + `:feature:editor … tools.auto.*` (before changes) | pass |
| `./gradlew :core:ai:testDebugUnitTest --tests 'com.diffuse.core.ai.monet.*'` | 38 passed (MonetClientTest 25, MonetAutoEnhanceProviderTest 9, MonetOperationsTest 4), 0 failed |
| `./gradlew :feature:editor:testDebugUnitTest --tests '…tools.auto.*' --tests '…tools.select.*'` | pass, 0 failures (AutoToolTest 27 after the review fix) |
| review fix: R1/R2 fix reverted in `AutoController` with the new tests kept | 4 of 29 auto tests fail (both R1 tests, the R2 undo test, never-reached); fix restored → all pass |
| review fix: `scripts/check.sh` | **exit 0** |
| review fix: `git diff --check` | clean |
| Race tests with the run-id guard removed | **5 of 26 fail**; guard restored → all pass |
| `scripts/check.sh` | **exit 0** (lint, detekt, all unit tests, Roborazzi verify, dependencyGuard) |
| `./gradlew :app:assembleDebug :app:assembleRelease` | exit 0 |
| `git diff --check` | clean |
| host: `curl /health` no token / wrong token / right token (token read from env file, not printed) | 401 / 401 / 200 `ready` |
| host: `monetGPT/scripts/smoke_client.py --base-url http://127.0.0.1:8082 --all-styles` on `fixtures/photo_512.png` | **exit 0** — balanced 82.7 s / 7 ops, retro 73.2 s / 11, vibrant 71.4 s / 7, strict contract OK |
| `:core:ai:connectedDebugAndroidTest`, phone auto/skin flows, clean install, offline, server stop/recover, wrong token, old override on device | **not run — no device attached** (`adb devices` empty) |
| monetGPT `scripts/check.sh`, GPU tests | not re-run: no server file changed |

Host smoke success ≠ phone end-to-end success; the latter is unverified.

APKs (version `0.6.0`, versionCode 12, built without `-Pdiffuse.localCreds`, so no address/token):

| APK | Size | SHA-256 |
|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 132,234,727 B | `23b7ab77705b6ec57b9d94a91392cdd89d162876b75dc63fdde812deaebcfaf6` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` (unsigned) | 18,356,403 B | `6045add10dcddf7f4f26786fa3afcbbd80919728ed41dac4f20f5e4772b37da9` |

Install / runtime setup:

1. Install the debug APK (`adb install -r app/build/outputs/apk/debug/app-debug.apk`; an update keeps
   existing projects and settings).
2. The MonetGPT service needs a phone-reachable URL first (see Known Issues 1). Then 편집 → AI → 자동;
   if 서버 설정 opens, enter the **server root** (no `/v1`) and the service token, 저장.
3. USB development only: `adb reverse tcp:8082 tcp:8082` with the adb server on the MonetGPT host,
   URL `http://127.0.0.1:8082`. This is not the downloaded-app connection.
4. A device that already has a wrong saved address (e.g. `127.0.0.1:8082` from a `-local` build):
   tap 자동 → the sheet opens → correct the URL → 저장 (saving the same URL also re-checks).

## Review Notes

- `AutoEnhanceProvider` is a public interface change (`checking`, `refresh()`); spec §5 updated.
- Provider ownership check uses `coroutineContext[Job]` identity under a lock with a LAZY job so the
  owner is registered before it can finish. The cancelled-late-answer path is covered by the real
  cancellation test; a cancellation-ignoring *client* is not injectable, so that exact interleaving
  is argued, not tested.
- Document-change invalidation is keyed on `EditorUiState.document` changes (history only); the busy
  overlay does not consume touches, which is why this path is reachable.
- `enhanceCount` in the fake now also counts failed calls.
- R1: `Io` and `503` now always open 서버 설정 beside the re-check (no `everReady`). Chosen over a
  snackbar action because the existing `ToolTap.OpenSettings` path needs no new UI primitive; the
  cost is that a loading server's tap shows the sheet, which the user closes.
- R2: session end is keyed on the controller holding an input bitmap. Test
  `undo under an open result sheet ends the session` covers chip in flight → undo → other chip →
  적용 → late answer; a chip after the session ended calls nothing (the UI has no sheet to tap it).

## Known Issues

1. **No public route to MonetGPT (external action required).** Options: TLS via
   `monetGPT/deploy/Caddyfile` with `MONETGPT_PUBLIC_HOST` (needs a DNS record and an open 443), or
   a SAM3-style plain-HTTP test port (token and photos in the clear; needs a security-group-open
   port). Starting a public listener was not done: it is an outward-facing operator decision, and
   without a device it could not be verified anyway. `.env`'s `MONET_BASE_URL` must then be updated
   by the owner.
2. **GPU coexistence.** The T4 currently holds MonetGPT (≈8.0 GB) + two other services (≈2.2 GB);
   monetGPT's own record says SAM 3 + MonetGPT do not fit together. SAM 3 is not running now. Running
   both needs another GPU host or card; nothing was stopped here.
3. **Phone verification pending** for every item in requirement 17 (auto three styles→강도→적용/취소/
   Undo, skin flows, clean install in a separate environment, offline, server stop/recover, wrong
   token, old-override update).
4. **피부 보정 BLOCKED** on: licensed evaluation photo set with annotations; weight licensing; an
   on-device configuration within budget (quantisation/accelerator) and a production model delivery
   path. Until then no kind passes its gate and the sheet stays disabled.
5. The release APK is unsigned (as before); the debug APK is the installable artifact.
