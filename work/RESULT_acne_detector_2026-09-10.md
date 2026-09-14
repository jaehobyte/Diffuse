# Result

## Status

PARTIAL — the detector is ported, exported, verified against PyTorch, wired into the Python
evaluation harness and an Android adapter, and was **run on a real device (9/9 instrumentation
tests, Galaxy S26 Ultra)** before this review pass. Two acceptance criteria are not met: detection
quality on real photographs is unmeasured, because the licensed photo set does not exist; and the
device cost figures now on record came from a **harness this pass corrected**, and no device is
available to re-run it. The device run also produced a **negative cost result** that has to be
resolved before production — 2.0 s and (at rest, so a floor) 261 MB per face ROI. None of that is
claimed as done anywhere.

`work/REVIEW.md`'s five blocking findings are addressed. R1 and R2 were already fixed in the
working tree when this pass started, and each has gained the regression test the review asked for —
R1's needed a bitmap that is genuinely stride-padded, which `Bitmap.createBitmap(w, h, ALPHA_8)` is
not. R3, R4 and R5 are new work in this pass. Details below under **Review fix pass**.

## Changed

### The model contract, first (requirement 1)

`Tinny-Robot/acne` is an **Ultralytics YOLOv8m detection** checkpoint — `nc = 1`,
`names = {0: 'acne'}`, `train_args.imgsz = 640`, strides `[8,16,32]`, saved by Ultralytics 8.0.85
on 2023-04-23. Pinned at revision `d1f64f86f6a89f3988c70aec67eb07492507feba`,
`sha256:2cef23fe…a0a6c`, 52,001,952 B, matching HuggingFace's own LFS digest. Read off the file,
not off the card: the card's `model.detect_acne(...)` is not an Ultralytics API and the
`transformers` tag is HuggingFace's automatic guess. The 8.0.85 module layout no longer exists;
Ultralytics' own `torch_safe_load` remaps it, so no other weights were substituted. Weights live
in `~/.cache/vibe-retouch/`, outside git.

### Export and manifest (requirement 2)

`acne_model.py export` writes `acne_640_fp32.onnx` (103,589,423 B, `sha256:18fad8c5…f5ffa`) and
its manifest together. batch 1, static 640×640, FP32, opset 17, no embedded NMS — the evaluation
**starting** configuration, with 640 the only value taken from the checkpoint itself. Graph:
`images float32 [1,3,640,640]` → `output0 float32 [1,5,8400]`. `onnx.checker` and a CPU session
load both run on the shipped file. The manifest fixes I/O names and shapes, box format and units,
class ids, preprocessing, decode rules, thresholds and the model digest; a model that disagrees
with its manifest is an error in the Python wrapper and in the Android adapter alike.

The export is reproducible in **content**, not byte-identical — Ultralytics stamps a `date` into
graph metadata. Verified: two exports have equal graph bytes once metadata is stripped.

### Python reference and parity (requirements 3, 5, 6, 7)

`detector.py` is pure NumPy and weight-free: letterbox with an explicit odd-remainder rule,
half-pixel-centre bilinear written out rather than delegated to OpenCV, RGB/FP32/NCHW, transparent
pixels composited over a fixed background, decode with no objectness and no second sigmoid, NMS
exactly once, class allowlist `{0}`, deterministic tie-break, inverse letterbox with clipping, and
box→mask as a separate pure transform limited to `allowedMask ∩ alpha>0`.

`acne_model.py parity` passes on both repository fixtures: raw box rows ≤ 9.8e-4 model px, raw
score rows ≤ 1.2e-6, and identical detection counts, classes, boxes (≤2.9e-4 ROI px) and scores
(≤5.8e-7) after NMS. Raw and post-NMS tolerances are fixed separately, and the raw tensor's box
rows and score rows get separate tolerances because they live on different scales.

### Android contract and adapter (requirements 4, 8, 9)

- `core/ai/src/main/.../retouch/`: `BlemishDetector` — `suspend detect(faceRoi): Result<BlemishDetections>`,
  ROI-pixel `xyxy` boxes, confidence, the model's **original** class id, and a detector version.
  Empty detections is a success, told apart from missing / corrupt / incompatible / failed. Plus
  the model-agnostic halves: letterbox + tensor, decoder + NMS, box→mask. Nothing here touches
  `Operation`, the renderer or the editor's session.
- `core/ai/src/debug/.../retouch/`: `OnnxBlemishDetector` and the model store. Reads an ONNX plus
  manifest **explicitly installed** into app-private files, verifies the digest, cross-checks the
  session's graph against the manifest, and keeps one reused session. File work on `io`, pixels and
  inference on `default`; runs serialised so the input buffer is never shared and `close` cannot
  free native memory under a run; the shared `OrtEnvironment` is never closed; cancellation
  propagates and is re-checked after the native call returns. Failures are `AppError` with class
  names only — no photograph and no tensor in any message.
- `core/ai/src/androidTest/`: `AcneDetectorDeviceTest`, the device harness — 9 tests covering the
  model/manifest match, ROI→boxes→mask, input immutability, non-square ROIs, repeated and
  concurrent requests, cancellation, closing mid-run, transparent pixels, and cost. It skips when
  no model is installed, so `connectedDebugAndroidTest` on a clean device is green rather than red.
- Nothing in `AiModule` binds any of this, and the runtime is `debugImplementation` only.

### On the device (requirements 8, 9, 11)

Everything above was then run on a Galaxy S26 Ultra (SM-S948U, Snapdragon SM8850, `arm64-v8a`,
Android 16 / SDK 36), with the ONNX pushed into the test app's private files by hand. All 9 tests
pass; the graph loaded on the device is byte-identically the one the manifest describes, boxes
stay inside the ROI, the candidate mask stays inside a partial allowance, the input ROI is
unmodified, four concurrent requests return identical boxes, a cancelled request delivers nothing,
and closing during a run in flight is safe.

The cost is the finding: **2,046 ms p50 / 2,065 ms p95** per 512×512 ROI and **261 MB peak PSS**.
`work/retouch_evaluation.md` §3A.7 has the full table and what it means for the budgets.

### Evaluation (requirements 10, 11)

`detect_eval.py` is a second CLI beside `evaluate.py`, not a flag on it: manual masks stay ground
truth and are never fed to the detector. It records defect-level precision/recall (greedy 1:1,
IoU 0.5), how much normal skin the box mask covers, protection intrusion, the detection cap, and
stage timings — then runs the existing `RestoreRun` twice over the same ROI, allowance, engine and
patch settings, once per mask source, as separate rows. A case with no `allowed_mask` is skipped
rather than run against an all-permissive allowance.

### Review fix pass (`work/REVIEW.md`, 2026-09-10)

**R1 — `ALPHA_8` rowBytes.** Already fixed in the working tree: `candidateMask` reads through
`byteCount`/`rowBytes` and converts at both bitmap boundaries. What the review asked for and was
missing is a test that actually exercises a padded stride — `Bitmap.createBitmap(301, 173, ALPHA_8)`
comes back with `rowBytes == 301`, so the existing odd-width test passes on stride-blind code too.
`CandidateMaskTest` now also builds the allowance with `extractAlpha()`, which gives `rowBytes 304`
for a 301-pixel row (asserted, so the coverage cannot lapse silently), and checks the empty-detection
call, a partial allowance, the alpha exclusion and input immutability on it.

**R2 — semi-transparent parity.** Already fixed: `flatten_alpha` composites in float32 and no longer
quantises to `uint8` before the resize, and the shared fixture carries a `left_half_semi_transparent`
case with `a = 128` under an interpolating resize. The fixture's note records why that case uses flat
white — Android stores `ARGB_8888` premultiplied, and 255 is what survives `createBitmap` →
`getPixels` exactly, so the case compares the compositing arithmetic and not the platform's storage
rounding. Verified rather than taken on trust: both suites assert the same file, at `1e-5`.

**R3 — the manifest's execution semantics are now verified, both sides.** The values live once, in
`PREPROCESS_SEMANTICS` and `DECODE_SEMANTICS` in `scripts/retouch/detector.py`; `acne_model.py
export` writes them, and both consumers require them back:

- Python `DetectorContract.from_manifest` checks `manifest_version`, the whole `preprocess` block,
  the whole `decode` block (layout, box format, box units, objectness, class activation, embedded
  NMS) and the class allowlist, raising `ManifestContractError`; `from_graph` now also requires
  `tensor(float)` on every input and output.
- Android `DetectorManifest` gained a `preprocess` section and the missing `decode` fields, and
  `contract()` compares every one of them plus the I/O dtype. `graphContract` now compares the
  input's batch and channel axes and the output's batch axis — it checked neither — and the
  session's element types.
- Refusal tests on both sides change **one** semantics field with the digest and shapes untouched:
  BGR, NHWC, nearest, a different pad value or transparent background, `scale_up: false`,
  `scale: 1.0`, `xyxy`, normalised box units, objectness, embedded NMS, a second sigmoid, FP16.
  Omitting a field is refused too, one field at a time, so a later addition cannot become optional
  by accident.
- The `postprocess_defaults` thresholds stay overridable, because a threshold sweep is the point of
  requirement 11 — `acne_onnx.py` records which ones differ, and `metrics.json` carries
  `setting_overrides` beside the numbers they produced.

**R4 — the evaluation CLI keeps the alpha.** `detect_eval.load_rgba` reads each case with
`IMREAD_UNCHANGED` and returns `(rgb, alpha)`; `evaluate.load_rgb` is untouched, so SR1-A and
`RestoreRun` keep their RGB contract. The alpha is then used three times: composited into the
detector's input (requirement 5), intersected into the candidate mask (requirement 7), and
intersected into the allowance **both** restoration rows use, so the manual/auto pair stays
comparable (requirement 10). `metrics.json` gained `transparent_pixels` and
`candidate_on_transparent`. New weight-free integration tests (`test_detect_eval.py`) drive the CLI
end to end with a transparent PNG, a deterministic fake detector and the OpenCV baseline engine;
they fail on the old code path (verified by reverting the alpha and watching two of them go red).

**R5 — the device cost harness measures what it claims.** `i_cost_on_this_device` now asserts every
result is a `Success` before aggregating it, so a failing run cannot be recorded as a fast one; it
reports model load, preprocess, inference, decode+NMS, candidate mask, detect total and detect+mask
with p50/p95/min/max each (`OnnxBlemishDetector` exposes `lastTimings` and `loadMs` for the stages);
and it samples PSS on a background thread **during** the runs, reporting `resting_`,
`observed_peak_` and `after_close_total_pss_kb` separately, with the sampling interval, the sample
count and an explicit note that a poll is a lower bound. The old single post-`close()` reading is
gone. The sampler and the session are released in a `finally`, so a failing assertion no longer
leaves a native session alive for the rest of the class.

**Tests Missing — cancellation and close are synchronised with the real run.** `OnnxBlemishDetector`
takes an `onRunEntered` callback invoked on the inference thread immediately before `session.run`
(default no-op; nothing in production constructs this class). `f_` and `g_` wait on a latch it
signals, so the cancel and the `close` land while the model is running — which `job.cancel()`
straight away and `delay(1)` did not establish. **Not re-run:** no device.

**N1 — the evaluation document's stale references.** §3's "15 MB APK budget" now says what
`specs/architecture.md` §8 actually says (< 1000 MB, no bundled models) while keeping the SR1-A
history intact, and §5's "the tool stays out of the menus" is replaced with the current product
state: the 피부 보정 entry exists and its sheet shows 준비 중 with Apply disabled, so the follow-on
condition is that the sheet stops saying 준비 중. §3A.7's device table is annotated in place —
the numbers are kept as the record of what was run, with the memory figure relabelled as
after-close and the latency figures marked as unasserted whole calls, and a 미측정 table for the
corrected harness beside it.

## Files

Added:

- `scripts/retouch/acne_model.py`, `detector.py`, `acne_onnx.py`, `detect_eval.py`, `test_detector.py`,
  `test_detect_eval.py` (added in the review pass, for R4)
- `core/ai/src/main/kotlin/com/diffuse/core/ai/retouch/{BlemishDetector,DetectorPreprocess,DetectionDecoder,CandidateMask}.kt`
- `core/ai/src/debug/kotlin/com/diffuse/core/ai/retouch/{DetectorModel,OnnxBlemishDetector}.kt`
- `core/ai/src/test/kotlin/com/diffuse/core/ai/retouch/{DetectorPreprocess,DetectorPreprocessParity,DetectionDecoder,CandidateMask}Test.kt`
- `core/ai/src/testDebug/kotlin/com/diffuse/core/ai/retouch/{DetectorModelStore,OnnxBlemishDetector}Test.kt`
- `core/ai/src/androidTest/kotlin/com/diffuse/core/ai/retouch/AcneDetectorDeviceTest.kt`
- `core/ai/src/test/resources/retouch/preprocess_parity.json`

Modified:

- `core/ai/build.gradle.kts` — `debugImplementation(onnxruntime-android)`, an instrumentation
  runner, androidTest dependencies
- `gradle/libs.versions.toml` — `onnxruntime-android 1.24.3` (debug, core:ai only) and
  `androidx.test:runner 1.7.0`. **Authorised by the task's Scope → Modify**; the file's header
  otherwise freezes it
- `scripts/retouch/README.md` — the SR1-B section: provenance, pinned export environment, every
  command, and the device install steps
- `work/retouch_evaluation.md` — new §3A (including §3A.7, the device measurements), plus the
  artifacts table, the result table row, §1 and §5. The review pass added §3A.7's correction note
  and the 미측정 table beside it, and refreshed §3's APK-budget reference and §5's closing
  paragraph (N1)

Touched again by the review pass: `scripts/retouch/{detector,acne_model,acne_onnx,detect_eval}.py`,
`core/ai/src/{main,debug,test,testDebug,androidTest}/.../retouch/` and `scripts/retouch/README.md`
(the manifest-semantics and alpha contracts).

Not touched: `specs/`, `DESIGN.md`, `work/decisions.md`, `work/REVIEW.md`, `scripts/check.sh`, the
tool menu, `SkinRetouchSheet`, `SkinRetouchProvider`, `Operation`/render/save/history, and the
existing SR1-A scripts. The uncommitted changes already in `specs/architecture.md` and
`work/tasks.md` are untouched.

## Validation

Executed in this change; every line is a run that happened.

### The review pass

| Command | Result |
|---|---|
| `python3 -m pytest scripts/retouch` | **83 passed** (was 59), no weights, no network |
| `./gradlew :core:ai:testDebugUnitTest --tests '*retouch*'` | **61 passed, 0 failed** (was 58) across six classes |
| `scripts/check.sh` | **exit 0** — lint, detekt, all modules' unit tests (**1,049**, 0 failures), `verifyRoborazziDebug`, `dependencyGuard` |
| `./gradlew :app:assembleDebug :app:assembleRelease` | exit 0 |
| `./gradlew :core:ai:assembleDebugAndroidTest` | exit 0 — the rewritten device harness compiles; **it was not run, no device** |
| `git diff --check` | clean |
| R1's new test against the old code | Verified the padded case is real: `extractAlpha()` on 301×173 gives `rowBytes 304`, asserted in the test so the coverage cannot lapse |
| R4's new tests against the old code path | Reverted the alpha in `detect_eval` and **2 of 5 failed**; restored, all 5 pass. The regression tests bite |
| `DetectorContract.from_manifest` on the **real** `acne_640_fp32.manifest.json` | **accepted unchanged** — the stricter reader needs no re-export |
| `OnnxAcneDetector` on the real ONNX with one manifest field mutated | **refused** `decode.box_format=xyxy`, `preprocess.color=BGR`, `decode.embedded_nms=true`, `preprocess.pad_value=[0,0,0]`, each with the field named; the unmodified manifest still loads. Same digest, same graph, so this is the check a digest cannot make |
| `detect_eval.py` on the RGBA case (`~/retouch-cases/rgba`, outside the repo), `--confidence 0.1 --engine opencv-telea` | `transparent_pixels 65536`, **`candidate_on_transparent 0`**, `protection_intrusion 0`; both restore rows `violations 0`; `setting_overrides {"confidence": {"manifest": 0.25, "used": 0.1}}`; stages `preprocess 52.6 / inference 242.1 / decode 0.8 ms`. **Desktop, not Android** |

The detection numbers in that last row (`tp 0, fp 9` on a synthetic case) are not a finding about
acne — the fixture has none, and the confidence was lowered so the path would produce boxes at all.

### Earlier in this change (before the review)

| Command | Result |
|---|---|
| `python3 -m pytest scripts/retouch` | **59 passed**, no weights, no network |
| `./gradlew :core:ai:testDebugUnitTest --tests '*retouch*'` | **58 passed, 0 failed** across the six new classes |
| `scripts/check.sh` | **exit 0** — lint, detekt, all modules' unit tests, `verifyRoborazziDebug`, `dependencyGuard` |
| `./gradlew :app:assembleDebug :app:assembleRelease` | exit 0 |
| `git diff --check` | clean |
| `acne_model.py inspect` | the contract in "Changed" above |
| `acne_model.py export` | ONNX + manifest; `onnx.checker` and CPU session load both pass |
| `acne_model.py parity` × 2 fixtures | **PASS**, tolerances in `work/retouch_evaluation.md` §3A.3 |
| `detect_eval.py --engine opencv-telea --repeat 7` | detection p50/p95 252/278 ms, peak RSS 458 MB; manual and auto restoration rows written separately |
| `:core:ai:connectedDebugAndroidTest` | **BUILD SUCCESSFUL, 9/9 passed, 0 failed**, run **twice**, on SM-S948U (Snapdragon SM8850, arm64-v8a, Android 16 / SDK 36). **This was the pre-R5 harness**; see Known Issues 7 |

Release APK: **18,336,271 B before and after** — built from a worktree at `HEAD` for the
comparison. Zero ONNX Runtime entries and no model in it; the compressed content grew 12,624 B
(four unused `core:ai` classes) and the alignment padding absorbed it. The debug APK grows
25,136,785 → 132,283,722 B, of which 106,939,560 B is `libonnxruntime.so` for four ABIs — that is
what the debug-only runtime costs, and the first size figure any production delivery has to answer.

`dependencyGuard` needed no change: it already guards `debugImplementation`, and
`com.microsoft.onnxruntime` is not in `core:ai`'s forbidden prefixes. Nothing was weakened.

Detekt found 24 real issues in the first draft (magic numbers, complexity, nesting, return count).
All were fixed **in the code**; the detekt config was not edited.

## Review Notes

Added by the review pass, worth a careful look:

- **The execution semantics are now duplicated in two languages on purpose**, and that is the risk
  this fix carries. `PREPROCESS_SEMANTICS` / `DECODE_SEMANTICS` in `scripts/retouch/detector.py`
  are the definition; `DetectorModel.kt` restates the same strings as private constants and
  compares them. There is no cross-language fixture pinning *these* the way
  `preprocess_parity.json` pins the tensor, so a typo on one side shows up as a Kotlin adapter
  refusing a manifest Python accepts — loudly, but only when a model is installed. Pinning them to
  one generated file would be the stronger design; it is a bigger change than this review asked
  for, and I did not take it. Worth a decision.
- **`OnnxBlemishDetector` gained a constructor parameter that only tests use** (`onRunEntered`,
  invoked immediately before `session.run`). It is the only way to make `f_` and `g_` actually
  test cancelling and closing *during* a native run rather than around one. Default is a no-op,
  the class is `src/debug` and nothing in production constructs it — but it is a seam, and if the
  reviewer would rather have a weaker test than a seam, that is a legitimate call.
- **`lastTimings` is read after the run returns, outside the lock.** Safe as written because runs
  are serialised and the harness reads it between its own calls; it would not be safe for a caller
  issuing overlapping requests, which nothing does. `@Volatile` on a whole record, not a counter.
- **The PSS sampler reports a lower bound and says so.** It polls at 100 ms on a daemon thread and
  logs the interval and the sample count beside the figure, so a reader can see how much a spike
  could have been missed by. It is not an assertion and nothing gates on it.
- **`opaque_allowance` changes the manual restoration row too**, not only the automatic one. That
  is deliberate — requirement 10 wants the mask source to be the only difference between the two —
  but it does mean a manual row from `detect_eval.py` on an RGBA case is not identical to one from
  `evaluate.py`, which has no alpha in its contract at all. `evaluate.py` is untouched.

- **Two implementations of one preprocessing rule** is the main risk this change carries. It is
  pinned by `core/ai/src/test/resources/retouch/preprocess_parity.json`, generated by
  `acne_model.py fixture` and asserted by the Python **and** the Kotlin suites, over square, wide,
  tall, odd-sized and part-transparent ROIs, at tolerances far below one 8-bit level. Changing one
  side without the other fails. Worth confirming that is really symmetric.
- **The reviewer's scratch probe is gone**, removed by the reviewer as `work/REVIEW.md` records.
  Its coverage now lives in `CandidateMaskTest` — including a case built with `extractAlpha()`,
  which is the only way to get a padded `ALPHA_8` stride here — and in the parity fixture's
  semi-transparent case.
- **`src/testDebug`** is a new source set in this repository. It exists so tests that touch the
  debug-only store still compile when the release unit-test variant does. Detekt scans
  `src/main/kotlin` and `src/test/kotlin` only, so `src/debug`, `src/testDebug` and `src/androidTest`
  are linted but not detekt'd — deliberate, but new.
- **`OnnxBlemishDetector.close()` is `suspend`**, not `Closeable.close()`. That is what lets it wait
  for a run in flight instead of blocking a thread or freeing native memory underneath one. It
  means the eventual production owner has to close it from a coroutine.
- **Runs are serialised** on one mutex. One face at a time is what the sheet asks for, and it is
  what makes the reused input buffer safe. If a future caller wants parallel faces, that buffer has
  to stop being shared first.
- **The class allowlist comes from the manifest**, and the manifest's decode block is refused if it
  declares objectness or embedded NMS. That is the guard against pointing this decoder at a
  differently shaped head.
- The `--confidence 0.05` in the recorded parity runs is deliberate and stated: the fixtures
  contain no acne, so at the 0.25 default the comparison would be 0 boxes against 0 boxes. The 17
  matched detections are a numerical result about two runtimes, **not** a finding about acne.
- **Three bugs the device run found, all in the harness, all fixed** — worth reading as evidence
  of what a weight-free suite cannot catch:
  1. `installFromStaging` scraped the model's filename out of the manifest with a regex, and the
     manifest has more than one `"file"` key — it matched the provenance block's `acne.pt` and
     silently installed the manifest without the model. Now parsed with `JSONObject`.
  2. `DetectorModelStore.isInstalled()` checked only the manifest, so that half-installed state
     read as "installed" and the cost test happily timed calls that were failing. It now checks
     the model file the manifest names as well, and `DetectorModelStoreTest` covers both halves.
  3. `AcneDetectorDeviceTest` mutated the ROI with `setPixel`, but `Bitmap.createBitmap(pixels,…)`
     returns an **immutable** bitmap — which is exactly what the contract wants a ROI to be. The
     transparency is now built into the pixel array.
  A fourth, earlier: JUnit 4 rejected the whole class because `log()` returned `Log.i`'s `Int`,
  which made several `= runBlocking { … }` methods non-`void`.
- **Two more that a review probe found in the production code, both fixed and both now covered:**
  1. **`ALPHA_8` row stride.** `candidateMask` treated `width * height` as the pixel buffer size.
     `ALPHA_8` rows are padded to `Bitmap.rowBytes`, so `copyPixelsTo/FromBuffer` throws outright
     on any width that is not aligned — and a face ROI is any width at all. It only ever ran on
     aligned widths here (10, 512), so nothing caught it: the device tests use 512×512 and 301×173
     for the *ROI*, but the *allowance* was always 512-wide. `CandidateMaskTest` now round-trips a
     301×173 allowance and checks it row by row, so a stride mistake shows as drift rather than a
     matching total.
  2. **Semi-transparent alpha parity.** Python composited over the pad value and then rounded back
     to `uint8` before resampling; Kotlin stayed in float. The two agreed on every fully opaque and
     fully transparent pixel and differed by up to half a level on partial alpha — and the parity
     fixture only had alpha ∈ {0, 255}, so it never looked. Python now composites in float (the
     `uint8` round trip had no purpose before a float resize), and the fixture has a
     `left_half_semi_transparent` case. That case uses flat white rather than the colour pattern
     because Android stores `ARGB_8888` **premultiplied**, and 255 is a value that survives the
     round trip exactly — so the case compares the compositing arithmetic and not the platform's
     storage rounding.

## Known Issues

1. **The device configuration does not fit the budgets.** It runs, correctly, and it costs too
   much (`work/retouch_evaluation.md` §3A.7): **2,046 ms p50 / 2,065 ms p95** per 512×512 face ROI
   on a Snapdragon SM8850 CPU execution provider, against `specs/skin_retouch_validation.md` §5's
   5 s p95 for a cold prepare of **all four kinds**; and **261 MB peak PSS** in a bare
   instrumentation process — no document, no working bitmap, no renderer — against the 250 MB
   editor budget. Latency is stable (p95 within 1% of p50), so this is the configuration, not
   noise. Quantisation, a smaller checkpoint and an accelerator path (NNAPI / QNN / GPU) are the
   obvious next steps and each needs its own measurement; none was attempted, and no budget was
   relaxed to accommodate the current numbers.
2. **Detection quality is unmeasured.** `specs/skin_retouch_validation.md` §2's 24 licensed
   photographs do not exist and cannot be sourced here. Precision, recall, mole/freckle false
   positives, makeup, beards, skin tone and small-lesion behaviour, and how much normal skin a
   box-shaped mask sweeps in, are all `미측정`. The only case run end to end is a synthetic
   rectangle mask on a faceless fixture; its `recall = 0/4` says nothing about the model.
3. **Licensing is unresolved and blocks production, not evaluation.** `config.json` declares
   `apache-2.0`, the repository contains **no `LICENSE` file** although the card links one, no
   training-data provenance is given, and the graph this export produces carries Ultralytics'
   `AGPL-3.0` stamp in its own metadata. Whether these weights and this code path may ship in a
   proprietary app is not answered here.
4. **Size.** The FP32 detector is 103.6 MB and ONNX Runtime is 25.8 MB of `arm64-v8a` native code.
   Neither may be bundled under `specs/architecture.md` §8, and nothing here proposes a delivery
   mechanism, quantisation or an accelerator path — those are follow-up decisions with their own
   measurements, and this change deliberately did not relax a budget or move anything to a server.
   Note the device reports `ro.product.cpu.abilist=arm64-v8a` only, so the other three ABIs the
   debug APK carries are dead weight there.
5. **The detector alone does not make Blemish supported.** Still required before automatic
   correction can be enabled: the protected allowed-skin mask builder, a real restoration provider,
   the SR1-A and SR1-B quality gates, and the save/render/editor session work. `SkinRetouchProvider`
   still has no production binding and the sheet still shows its "준비 중" state — unchanged by this
   diff.
6. `~/.cache/vibe-retouch/acne_640_fp32.onnx` on this machine is the artifact every number above
   refers to. Re-exporting produces an equivalent graph with a different digest, which will not
   match the recorded `sha256`; regenerate the manifest with it (the tooling does this in one step)
   rather than editing the digest. The manifest that is already there was checked against the
   stricter reader and **accepted unchanged**, so R3 does not force a re-export.
7. **The device figures on record came from the pre-R5 harness, and the corrected one has not
   run.** The 9/9 pass and the 2.0 s / 261 MB in `work/retouch_evaluation.md` §3A.7 were produced
   before this review pass: the memory reading was taken after `close()` (so it is the process at
   rest, a floor rather than a peak), the latency figures were whole `detect` calls that were not
   asserted to have succeeded and excluded the candidate mask, and `f_`/`g_` cancelled and closed
   without being synchronised with the native run. All of that is fixed and compiles; **no device
   is available to re-run it**, so every corrected figure is 미측정 and §3A.7 says so in place. The
   two product problems the old numbers raise are not softened by the correction — a latency
   measurement is not flattered by asserting success, and a resting 261 MB bounds the peak from
   below.
