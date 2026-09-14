# Skin retouch engine evaluation

`specs/skin_retouch_validation.md` §2. What has actually been run, and what has not.

**Status after SR1: no engine is selected.** What is recorded below is the MI-GAN ONNX pipeline's
mechanical contract and its cost on a desktop CPU (§3), and the acne detector's port, export,
PyTorch↔ONNX parity, desktop cost and **on-device behaviour and cost** (§3A). The quality gate
(§3), **detection quality**, the other three corrections (SR1-C) and every MI-GAN Android
measurement are unmeasured, and `specs/skin_retouch_pipeline.md` §1 says selection without that
evidence is not finished. The one device measurement that does exist is a **negative** result on
cost (§3A.7): the detector runs correctly and does not fit the latency or memory budget as
configured.

## 1. Environment

| | |
|---|---|
| Date | 2026-09-09 |
| Repository | §3 measured at `4c9c147`; §3A (SR1-B) at `21df4ca` plus this change |
| Machine | Intel Xeon Platinum 8259CL @ 2.50 GHz, 16 vCPU, 62 GB RAM, Linux 6.14.0-1018-aws |
| Runtime (§3) | Python 3.12.3, onnxruntime 1.24.3 (CPUExecutionProvider), OpenCV 4.13.0, NumPy 2.4.6 |
| Runtime (§3A) | `~/.venvs/acne-export`: Python 3.12.3, torch 2.5.1+cpu, ultralytics 8.3.155, onnx 1.17.0, onnxruntime 1.24.3, OpenCV 4.11.0.86, NumPy 1.26.4 |
| Device | §3 and §3A.1–§3A.6: none. §3A.7: **Samsung SM-S948U**, Snapdragon SM8850, arm64-v8a, Android 16 (SDK 36), reached over an SSH-forwarded adb server |

## 2. Artifacts

| Candidate | Artifact | Obtained | License |
|---|---|---|---|
| MI-GAN 512 Places2 | `migan_pipeline_v2.onnx`, 28,079,181 B, `sha256:6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b` | yes, from `huggingface.co/andraniksargsyan/migan` (the URL the official README gives) | repository code is MIT (Picsart AI Research, 2024). **The weight file carries no license metadata on HuggingFace and the repository has no separate weights license** — unresolved. |
| MI-GAN 256 FFHQ | — | **no.** No ONNX is published; the checkpoint is a `.pt` on the authors' Google Drive and has to be exported by hand with the repository's `scripts/create_onnx_pipeline.py`. | unresolved, and the FFHQ training data is itself non-commercial — check before adopting a face-trained checkpoint |
| OpenCV inpaint (Telea / Navier-Stokes) | in `opencv-python` | yes | Apache 2.0 |
| StyleRetoucher | — | **no.** Neither the paper page nor the author page links runnable code or weights (checked 2026-09-09). | n/a |
| LaMa | — | not attempted at SR1 | n/a |
| Acne detector (SR1-B) | `acne.pt`, 52,001,952 B, `sha256:2cef23fe…a0a6c`, revision `d1f64f86` | yes, from `huggingface.co/Tinny-Robot/acne` at the pinned revision | **unresolved.** `config.json` says `apache-2.0`; the repository has **no `LICENSE` file** despite the card linking one, and no training-data provenance. The graph exported from it carries Ultralytics' `AGPL-3.0` stamp (§3A.1) |
| Acne detector, exported | `acne_640_fp32.onnx`, 103,589,423 B, `sha256:18fad8c5…f5ffa` | yes, `scripts/retouch/acne_model.py export` (§3A.2) | as above |

The weights live in `~/.cache/vibe-retouch/`, are never committed, and are not bundled in the APK.

## 3. What was measured

Every number below is the **adapter** measured on synthetic masks that `scripts/retouch/make_cases.py`
draws over the repository fixture `fixtures/photo_512.png` (512×384, and it contains no face). It is
not a quality sample and cannot become one; §3 of the validation spec needs the photo set §2 fixes.

### 3.1 Reproducing it

```bash
cd scripts/retouch
python3 make_cases.py --fixture ../../fixtures/photo_512.png --out ~/retouch-cases/synthetic
python3 evaluate.py --manifest ~/retouch-cases/synthetic/manifest.json \
  --engine migan-onnx:$HOME/.cache/vibe-retouch/migan_pipeline_v2.onnx \
  --out ~/retouch-out/sr1a-migan --repeat 7
python3 evaluate.py --manifest ~/retouch-cases/synthetic/manifest.json \
  --engine opencv-telea --out ~/retouch-out/sr1a-opencv --repeat 7
```

The two engines are run separately, not as two `--engine` flags on one command, because
`peak_rss_kb` is the whole process: one run would report the larger of the two for both. `--repeat 7`
is the repetition condition for every latency figure here: 7 applications of the same case in one
process, after the session is loaded, reported as `total_ms_p50` / `total_ms_p95` (with 7 samples
p95 is the maximum — `evaluate.py` says so rather than interpolating).

Every cell below cites the field it came from in the `metrics.json` those two commands write. The
graph I/O row is read straight off the model:

```bash
python3 -c "import onnxruntime as ort; s = ort.InferenceSession('$HOME/.cache/vibe-retouch/migan_pipeline_v2.onnx', providers=['CPUExecutionProvider']); print([(t.name, t.type, t.shape) for t in s.get_inputs() + s.get_outputs()])"
```

The cases `make_cases.py` writes:

| Case | What it is |
|---|---|
| `polarity` | 128×128 crop, one r=10 disc defect. Planned patch 64×64 |
| `polarity_inverted` | the same crop with the mask turned over — what the model is asked to restore if the app's 255=change mask is passed through without `to_model_mask` |
| `patch64` / `patch129` / `patch258` / `patch384` | one square defect each, sized so the planned patch is exactly that many pixels square |
| `twopatch` | four defects — two 15 px apart, one far away, one entirely inside a protected rectangle — plus an allowed mask that also protects the right half of the far one |

### 3.2 Adapter mechanics (MI-GAN 512 Places2 ONNX pipeline)

| Check | Result | Where it comes from |
|---|---|---|
| Graph I/O | `image uint8 [batch,3,height,width]`, `mask uint8 [batch,1,height,width]`, `result uint8 [·,3,·,·]`, spatial dims dynamic — matches `specs/skin_retouch_pipeline.md` §1.1 | the `onnxruntime` one-liner above |
| Mask polarity | confirmed **255 = keep, 0 = restore**. Correct polarity changed **311** px, all inside the 317 px defect; the un-inverted mask changed **16,042** px, i.e. the whole crop except the blemish | `polarity` vs `polarity_inverted`, `changed_pixels` |
| Empty defect mask | never reaches the model — the adapter returns before the call | `test_retouch.py::test_an_empty_defect_mask_never_reaches_the_model` |
| Wrapper post-processing | the pipeline's own blending changes pixels **outside** the mask, and more of them the bigger the patch: 16 / 149 / 246 / 407 px at 64² / 129² / 258² / 384², and 214 px over the two-patch case | `overreach_pixels_before_guard` |
| Protection guard | 0 violations everywhere, including a defect drawn entirely inside a protected rectangle and one with its right half protected | `protection_violations` |
| Patch planning | `twopatch`: 4 defects → **2 patches** (165×64 and 64×90). The two 15 px apart merged, the far one was cut down to its unprotected half, the fully protected one produced no patch at all — the support is `defect ∩ allowed`, so it never reached the model | `patches`, `patch_sizes` |
| Engine output size | the pipeline returns the patch at the size it was given; a differently sized output is rejected rather than resized into place | `restore.check_engine_output`, `test_retouch.py::test_an_engine_output_of_the_wrong_size_is_rejected` |
| Input immutability | the patch handed to the engine is a copy, so an engine that writes into its input cannot reach the original | `test_retouch.py::test_an_engine_that_writes_into_its_input_cannot_reach_the_original` |

### 3.3 Cost, desktop CPU only

| Measurement | MI-GAN 512 ONNX | OpenCV Telea |
|---|---|---|
| Session / model load | 422 ms | 0 ms |
| One patch, end to end p50, at 64² / 129² / 258² / 384² | 349 / 351 / 295 / 299 ms | 21 / 22 / 26 / 32 ms |
| Two patches (`twopatch`), p50 / p95 | 583 / 612 ms | 22 / 23 ms |
| Pre-process + composite per patch | ≤ 1.0 ms (largest observed, 384² patch) | ≤ 0.4 ms |
| Process peak RSS | 868 MB | 60 MB |

MI-GAN's latency is **flat in patch size and linear in patch count**: the pipeline resizes to its own
resolution internally, so a 384² patch costs what a 64² one costs, and two patches cost twice one.
A face with a dozen scattered blemishes is a dozen inferences unless the merge rule groups them,
which makes that rule a performance parameter as well as a quality one. OpenCV, which works at the
patch's real size, behaves the opposite way.

None of this predicts Android. `specs/skin_retouch_pipeline.md` §1.1 requires on-device tensor
compatibility, the accelerator path, load + pre/post-processing latency, and runtime and model
size against the APK budget, and none of those were measured. (This paragraph originally named a
15 MB budget. `specs/architecture.md` §8 now reads **< 1000 MB, no bundled models** — ADR-008 was
retired — so the constraint on a 28 MB restoration model is delivery and integrity, not a budget
it overflows. The SR1-A measurements above are unchanged; only this reference is.)

## 3A. SR1-B: the acne detector port

`specs/skin_retouch_validation.md` §1.1 step 2. **The detector is ported and verified; its
detection quality is unmeasured.** What follows is the model contract, the export, PyTorch↔ONNX
parity and desktop cost. Precision, recall and the Android numbers need the photo set §2 fixes and
a device, and neither was available (§5).

### 3A.1 Provenance

| | |
|---|---|
| Source | [`Tinny-Robot/acne`](https://huggingface.co/Tinny-Robot/acne), revision `d1f64f86f6a89f3988c70aec67eb07492507feba`, file `acne.pt` |
| Checkpoint | `sha256:2cef23fe3587b0154cd3598cae54f8c0d8076acebb545286e8904c11a9ee0a6c`, 52,001,952 B — matches HuggingFace's own LFS digest for that revision |
| What it is | Ultralytics **YOLOv8m object detection**, `yolov8m.yaml` scale `m`, saved by Ultralytics **8.0.85** on 2023-04-23, `task=detect` |
| Classes | `nc = 1`, `names = {0: 'acne'}` — read off the checkpoint, not off the card |
| Training input | `train_args.imgsz = 640`, strides `[8, 16, 32]`, data `/kaggle/input/acneda1/acne.yaml` |
| Obtained | yes, by `curl` at the pinned revision. Kept in `~/.cache/vibe-retouch/`, never committed, never bundled |

Three things about the card are **not** evidence and were not treated as such:

- the repository's `transformers` / `AutoModel` tags are HuggingFace's automatic guess from
  `config.json`; this is an Ultralytics checkpoint and there is no Transformers model behind it;
- the card's `model.detect_acne(image_path=...)` example is not an Ultralytics API and does not
  run. The execution contract below came from loading the file;
- `config.json` says `"license": "apache-2.0"` and the card's text links a `LICENSE` file — **the
  repository contains no `LICENSE` file** (its whole file list is `.gitattributes`, `README.md`,
  `acne.pt`, `assets/README.md`, `config.json`, `requirements.txt`). The stated licence has no
  accompanying licence text, and no training-data provenance is given.

**Unresolved, and blocking for production rather than for evaluation:** the exported graph carries
`license = AGPL-3.0 License (https://ultralytics.com/license)` in its own metadata, stamped by the
exporter. Ultralytics' YOLOv8 code is AGPL-3.0; the weights are declared Apache-2.0 by a card with
no licence file. Whether this model may ship in a proprietary application is a question about the
model *and* about running it through Ultralytics-derived code, and it is not answered here.

### 3A.2 Export

Reproduce with the pinned environment in `scripts/retouch/README.md` (SR1-B → "The export
environment"). The checkpoint's 2023 module layout no longer exists; Ultralytics' own
`torch_safe_load` remaps it, so no other weights were substituted.

```bash
V=~/.venvs/acne-export/bin/python
$V scripts/retouch/acne_model.py export --checkpoint ~/.cache/vibe-retouch/acne.pt \
  --out ~/.cache/vibe-retouch/acne_640_fp32.onnx
```

| | |
|---|---|
| Exporter | `ultralytics.YOLO.export`, ultralytics 8.3.155, torch 2.5.1+cpu, onnx 1.17.0, onnxruntime 1.24.3, Python 3.12.3 |
| Options | `format=onnx, imgsz=640, opset=17, batch=1, dynamic=False, half=False, simplify=False, nms=False` |
| Artifact | `acne_640_fp32.onnx`, **103,589,423 B**, `sha256:18fad8c553d3936c4233840d3fefd2ca1b1706486a50d881c12ab1825e7f5ffa` |
| Graph I/O | `images float32 [1,3,640,640]` → `output0 float32 [1,5,8400]` |
| Checks run | `onnx.checker.check_model` and a CPU `InferenceSession` load, both on the file that is shipped to the device |

`[1, 5, 8400]` is `4 + nc` rows by 8400 anchors, `cxcywh` in model-input pixels, class scores
already activated in the graph, no objectness row and no embedded NMS. The decoder reads that off
the manifest and refuses a graph shaped otherwise instead of assuming it.

The export is **reproducible in content, not byte-identical**: two exports of one checkpoint differ
only in the `date` Ultralytics stamps into `metadata_props`, and their graph bytes are equal once
metadata is stripped (verified). The SHA-256 therefore identifies one exported *file* — which is
what the adapter checks — and the `export` block of the manifest is the configuration.

Every option above is the **evaluation starting** configuration, not a claim about training. The
one figure that came from the checkpoint is 640, which is its own `train_args.imgsz`.

### 3A.3 PyTorch ↔ ONNX parity

`acne_model.py parity` runs both on the same preprocessed tensor. Tolerances are fixed separately
for the raw tensor and for the detections, because they are different questions.

| Fixture | raw box rows | raw score rows | detections | box after NMS | score after NMS | verdict |
|---|---|---|---|---|---|---|
| `fixtures/photo_512.png` (512×384) | 9.77e-4 model px | 7.30e-7 | 17 = 17, same class | 7.3e-5 ROI px | 5.5e-7 | PASS |
| `fixtures/photo_12mp.jpg` (3000×4000) | 5.26e-4 model px | 1.18e-6 | 17 = 17, same class | 2.9e-4 ROI px | 5.8e-7 | PASS |

Tolerances: raw box rows ≤ 1e-2 model px, raw score rows ≤ 1e-5, post-NMS box ≤ 1 model px
(converted to ROI px per fixture), post-NMS score ≤ 1e-3. The raw output's two halves get separate
tolerances rather than one loose number: rows 0–3 are coordinates in 0..640, where one float32 ULP
is already ~6e-5, and rows 4+ are scores in 0..1.

Both runs are at `--confidence 0.05`, stated because it is below the 0.25 evaluation default: these
fixtures are landscape and studio photographs with no acne in them, so at 0.25 the comparison would
be 0 detections against 0 detections and would exercise none of the box path. **The 17 detections
are not findings about acne.** They are the same 17 low-confidence boxes out of both runtimes.

### 3A.4 Cost, desktop CPU only

512×384 ROI, ONNX Runtime 1.24.3 CPUExecutionProvider, 7 repeats, same machine as §1.

```bash
$V scripts/retouch/detect_eval.py --manifest ~/retouch-cases/synthetic/manifest.json \
  --detector ~/.cache/vibe-retouch/acne_640_fp32.manifest.json \
  --engine opencv-telea --out ~/retouch-out/sr1b --repeat 7
```

| Measurement | Value | `metrics.json` field |
|---|---|---|
| Session load | 332 ms | `detector.load_ms` |
| Pre-process | 32.9 ms | `stage_ms.preprocess_ms` |
| Pure inference | 133.7 ms | `stage_ms.inference_ms` |
| Decode + NMS | 0.13 ms | `stage_ms.decode_ms` |
| Detect, end to end p50 / p95 | 252 / 278 ms | `total_ms_p50`, `total_ms_p95` |
| Process peak RSS | 458 MB | `peak_rss_kb` |
| Detection cap reached | no | `hit_max_detections` |

**This is not an Android measurement.** §3A.7 is.

### 3A.7 Cost and behaviour on a real device

`:core:ai:connectedDebugAndroidTest`, **9/9 passed**, on hardware:

| | |
|---|---|
| Device | Samsung SM-S948U (`m3q`), Snapdragon **SM8850**, `arm64-v8a` only, 8 cores, 10.7 GB RAM |
| OS | Android 16, SDK 36, `S948USQT1AZC7` |
| Runtime | `com.microsoft.onnxruntime:onnxruntime-android:1.24.3`, **CPU execution provider** |
| Model | the same `acne_640_fp32.onnx`, `sha256:18fad8c5…`, 103,589,423 B, read from app-private files |
| Graph as loaded on device | `images [1,3,640,640]` → `output0 [1,5,8400]` — identical to the manifest |

```bash
adb push ~/.cache/vibe-retouch/acne_640_fp32.onnx /data/local/tmp/blemish-detector/
adb push ~/.cache/vibe-retouch/acne_640_fp32.manifest.json \
  /data/local/tmp/blemish-detector/detector.manifest.json
./gradlew :core:ai:connectedDebugAndroidTest && adb logcat -d -s AcneDetectorDevice:I
```

Two independent runs of the whole suite, both 9/9, reported as `run 1 / run 2` so the spread is
visible rather than averaged away:

| Measurement | Value | logcat key |
|---|---|---|
| Cold first call — SHA-256 of 103 MB, session creation, then one detection | **2,275.5 / 2,230.4 ms** | `cold_first_call_ms` |
| Warm detect, 512×512 ROI, p50 | **2,046.5 / 2,002.8 ms** | `warm_total_ms_p50` |
| Warm detect, p95 | **2,065.3 / 2,024.5 ms** | `warm_total_ms_p95` |
| Warm detect, min / max over 20 runs | 2,013.2–2,066.1 / 1,833.7–2,026.6 ms | `warm_total_ms_min/max` |
| Model load + session, by difference | ≈ 230 ms | — |
| Process total PSS, ~~peak~~ **after the session was closed** | 267,711 / 279,941 kB ≈ 261–273 MB | ~~`peak_total_pss_kb`~~ |
| Repeats | 20 per run, after the session is warm | `repeats` |

> **These figures were produced by a harness that has since been corrected, and they are kept as
> the record of what was run rather than re-labelled.** Two of them are weaker than they read:
>
> * the memory figure was a **single `getProcessMemoryInfo` call made after `close()`**, so it is
>   the process at rest and not a peak — the true peak during a run can only have been higher;
> * the latency figures are whole `detect` calls that were **not asserted to have succeeded**, and
>   the candidate mask was outside them.
>
> `AcneDetectorDeviceTest.i_` now aggregates successful runs only, splits model load / preprocess /
> inference / decode+NMS / mask / total with p50 and p95 each, and samples PSS on a background
> thread during the runs — reporting `resting_total_pss_kb`, `observed_peak_total_pss_kb` (a
> sampled **lower bound**, with its interval and sample count), and `after_close_total_pss_kb`
> separately. **The corrected harness has not been run: no device is available.** The replacement
> table below is therefore 미측정, and the conclusions in this section rest on the old figures,
> which are bad enough to stand — 2 s per ROI is a latency measurement the correction does not
> flatter, and a *resting* 261 MB is a lower bound on the peak either way.

| Measurement (corrected harness) | Value | logcat key |
|---|---|---|
| Model load / session creation | 미측정 | `model_load_ms` |
| Preprocess p50 / p95 | 미측정 | `preprocess_ms_p50/p95` |
| Inference p50 / p95 | 미측정 | `inference_ms_p50/p95` |
| Decode + NMS p50 / p95 | 미측정 | `decode_nms_ms_p50/p95` |
| Candidate mask p50 / p95 | 미측정 | `mask_ms_p50/p95` |
| Detect total, and detect + mask | 미측정 | `detect_total_ms_*`, `detect_plus_mask_ms_*` |
| Resting / observed peak / after-close PSS | 미측정 | `resting_`, `observed_peak_`, `after_close_total_pss_kb` |

**Two of these are product problems, not just numbers:**

1. **2.0 s per face ROI, on a current flagship, for blemish detection alone** — and the stage
   split that would say *where* it goes is 미측정.

   specs/skin_retouch_validation.md §5's target is a p95 of 5 s for a *cold prepare of one face
   with all four kinds active* — and that budget is 40% spent before any restoration runs. MI-GAN
   then costs one inference per patch on top (§3.3). CPU EP with an FP32 YOLOv8m is not a viable
   production configuration at this size; quantisation, a smaller checkpoint or an accelerator
   path is required, and each is its own measurement.
2. **261–273 MB total PSS in a bare instrumentation process** — no document, no working bitmap,
   no renderer — against §5's 250 MB editor budget for a 12 MP import at working size 4096. A
   103 MB FP32 model's weights and arena dominate this, and both runs are over the budget on
   their own. Read this as a **floor**: it was measured with the session already closed, so the
   peak while running was higher by an unmeasured amount.

Latency is extremely stable — p95 within 1% of p50 in both runs, and the two runs agree to 2% —
so these are the configuration's cost, not noise.

Behaviour verified on the device, all passing:

| Test | What it showed |
|---|---|
| `a_` | the installed model's digest and graph match its manifest; the version string carries the digest |
| `b_` | ROI → boxes → mask end to end: every box inside the ROI, non-degenerate, class 0; mask strictly 0/255 and a subset of a **partial** allowance (7,356 of 172,032 allowed px) |
| `c_` | the input ROI is byte-identical afterwards |
| `d_` | a non-square 301×173 ROI works and its boxes stay inside it |
| `e_` | repeated and 4-way concurrent requests return identical boxes — the reused session and shared input buffer are safe |
| `f_` | a cancelled request delivers nothing, and the detector still works afterwards — **as run, the cancel was not synchronised with the native call**, so it showed only that a request cancelled around the run delivers nothing; the harness now waits on a latch signalled immediately before `session.run` and has not been re-run |
| `g_` | closing during a run in flight is safe, and afterwards requests report `Unavailable` — same caveat: `delay(1)` did not establish that a run had started, and the latch replaced it |
| `h_` | a part-transparent ROI is handled, and every transparent pixel is out of the mask |
| `i_` | the cost table above |

Two of these tests were **added or corrected because the device found real defects**, and they are
the reason the suite is worth running rather than a formality: the candidate mask used
`width * height` as an `ALPHA_8` buffer size, which throws on any width that is not stride-aligned
(a face ROI is any width at all); and the Python and Kotlin preprocessors disagreed on
**partially** transparent pixels, because Python quantised the composite back to 8 bits before
resampling and Kotlin did not. Both are fixed, the parity fixture now carries a semi-transparent
case, and `CandidateMaskTest` now round-trips a 301×173 allowance. `work/RESULT.md` → Review Notes
has the full list.

The detection **counts** in `b_` (100, `hit_detection_limit=true`) and `d_` (21) are **not quality
figures**. The ROI is a procedurally generated oval with regular dark spots — the harness makes it
so the path can be exercised with the repository's own pixels — and the model firing 100 times on
it says only that the cap works and is reported. Detection quality remains §5.4.

### 3A.5 The automatic mask, joined to the existing restoration harness

The same command runs `RestoreRun` twice over one ROI, allowance, engine and patch settings —
once on the manual annotation and once on the detector's own mask — as two rows:

| Row | patches | changed px | overreach before guard | protection violations | p50 |
|---|---|---|---|---|---|
| `twopatch`, mask = **manual** | 2 | 1,243 | 0 | 0 | 22.0 ms |
| `twopatch`, mask = **auto** | 0 | 0 | 0 | 0 | 0.12 ms |

The auto row is an **empty** candidate mask, and an empty mask calls the restoration model zero
times — which is the contract §1.1 states, observed end to end rather than asserted. It is not a
quality result: `twopatch` is `make_cases.py` painting rectangles on `fixtures/photo_512.png`,
which contains no face and no blemish, so its `recall = 0/4` is a statement about a synthetic
fixture and nothing else. Every case in that manifest without an `allowed_mask` is **skipped**
rather than run against an all-permissive allowance; six of the seven were skipped for that reason.

### 3A.6 What was verified about the port itself

| Property | How |
|---|---|
| Pre-processing is identical in Python and Kotlin | one fixture, `core/ai/src/test/resources/retouch/preprocess_parity.json`, asserted by `test_detector.py` **and** `DetectorPreprocessParityTest`. Letterbox geometry, sampled tensor values (≤1e-5) and the tensor sum (≤1e-6 rel), over square, wide, tall, odd-sized and part-transparent ROIs |
| Odd padding, non-square ROI, inverse transform, clipping | `test_detector.py` and `DetectionDecoderTest`, both sides |
| No objectness, no second sigmoid, NMS exactly once | `nms_is_applied_once_and_only_here`, both sides |
| Class allowlist | `{0}` from the checkpoint's own `names`; an id the model does not have is a configuration error, not an empty result |
| NaN / Inf / wrong shape / wrong `4+nc` | dropped or rejected; a box at infinity never becomes a mask over the whole face |
| Candidate mask ⊆ allowance ∩ `alpha > 0` | `CandidateMaskTest`; inputs are never modified |
| Empty detections, empty allowance | normal empty mask, not a failure and not "all of the face" |
| Model absent / digest mismatch / corrupt manifest | `Unavailable` vs `Invalid`, three separate outcomes (`DetectorModelStoreTest`, `OnnxBlemishDetectorTest`) |
| Release APK | **no ONNX Runtime and no model.** 18,336,271 B before and after this change; the compressed content grew 12,624 B (four unused `core:ai` classes) and the file size is unchanged because the zip's alignment padding absorbed it |
| Debug APK, for comparison | 25,136,785 B → 132,283,722 B, **+102 MiB**, of which 106,939,560 B is `libonnxruntime.so` for four ABIs (arm64-v8a alone is 25,831,632 B). This is what "the runtime is debug-only" costs and is also the first size figure any production delivery has to answer |

Not verified, because it needs a device: real inference on Android, on-device latency and memory,
and the boxes an actual ARM build produces. `AcneDetectorDeviceTest` exists and was **not run**
(§5).

## 4. Result table (`specs/skin_retouch_validation.md` §2)

| 기능 | 후보/버전 | 위치 | 양성 성공/전체 | 음성 보존/전체 | 심각한 변형 | p50/p95 | peak memory | 판정 |
|---|---|---|---|---|---|---|---|---|
| Blemish (manual mask, SR1-A) | MI-GAN 512 Places2 `migan_pipeline_v2.onnx@6f1f3530` | desktop CPU | 미측정 | 미측정 | 미측정 | 583 / 612 ms (`twopatch`, 2 patches, synthetic, 7 runs) | 868 MB (process) | 미판정 |
| Blemish (manual mask, SR1-A) | MI-GAN 256 FFHQ | — | 미측정 | 미측정 | 미측정 | 미측정 | 미측정 | 미실행, 가중치 미확보 |
| Blemish (manual mask, SR1-A) | OpenCV Telea / NS 4.13.0 | desktop CPU | 미측정 | 미측정 | 미측정 | 22 / 23 ms (`twopatch`, 2 patches, synthetic, 7 runs) | 60 MB (process) | 미판정 |
| Blemish (auto detect, SR1-B) | `Tinny-Robot/acne` YOLOv8m → `acne_640_fp32.onnx@18fad8c5` (검출기), 복원 엔진 미결합 | **on-device** SM-S948U CPU EP (desktop 병기) | 미측정 | 미측정 | 미측정 | **2046 / 2065 ms** (검출만, 512×512 ROI, 20회, 기기) · 252 / 278 ms (desktop, 512×384, 7회) | **261 MB** (기기 peak PSS) · 458 MB (desktop process) | 미판정 — 포팅·기기 검증 완료, **비용 예산 초과**, 품질 미측정 |
| Shine (SR1-C) | 후보 미평가 | — | 미측정 | 미측정 | 미측정 | 미측정 | 미측정 | 미실행 |
| DarkCircles (SR1-C) | 후보 미평가 | — | 미측정 | 미측정 | 미측정 | 미측정 | 미측정 | 미실행 |
| ShavingShadow (SR1-C) | 후보 미평가 | — | 미측정 | 미측정 | 미측정 | 미측정 | 미측정 | 미실행 |

"미측정" is literal: the run did not happen. No number in this file came from a face photograph,
and the latency columns are adapter cost on a synthetic mask — §3.1 has the commands that produce
them and §3.2/§3.3 the `metrics.json` field behind each one.

## 5. What SR1 still needs

1. **The photo set.** §2 fixes 24 licensed photographs — six positives and six negatives per
   correction, across skin tones, lighting, glasses, makeup, beards, angles and multiple people —
   with hand-drawn defect masks. None exists, and this agent has no rights to source one. Until it
   does, §3's gate (≥80% positives, all negatives preserved, zero severe deformations) cannot be
   evaluated for any engine, and no engine can be adopted.
2. **The FFHQ-256 checkpoint**, so §1.1's "same defects, both models" comparison can be run rather
   than assumed. Manual Google Drive download plus the repository's ONNX export.
3. **A viable on-device configuration.** The detector now *runs* on a device and the
   measurement exists (§3A.7) — and it says the current configuration does not fit: **2.0 s per
   face ROI** on a Snapdragon SM8850 CPU EP against a 5 s p95 budget for all four kinds, and
   **261 MB peak PSS** in a bare test process against a 250 MB editor budget. Quantisation, a
   smaller checkpoint, or an accelerator path (NNAPI / QNN / GPU) each need their own measurement
   and none is attempted here. MI-GAN has still not been measured on a device at all.
   Delivery is separate again: a 28 MB restoration model, a **103 MB** detector and 25.8 MB of
   `arm64-v8a` runtime are all far past what `specs/architecture.md` §8 allows to be bundled.
4. **Detection quality** for SR1-B: precision and recall against the annotated photo set, with
   moles and freckles counted as false positives, reported apart from restoration quality, plus
   how much normal skin a box-shaped candidate mask sweeps in. The detector is ported and its
   mechanics are verified (§3A); nothing is known about what it finds on a face. The checkpoint's
   own card describes training on **African and dark skin tones**, which makes the spread §2 asks
   for a question about this model specifically and not a generic fairness note.
5. **Shine, dark circles and shaving shadow paths** for SR1-C. Blemish restoration says nothing
   about them.
6. **Weights licensing**, for all three artifacts, before any could ship: MI-GAN's weight file
   carries no licence metadata, and the acne checkpoint is declared Apache-2.0 by a card with no
   licence file while its exported graph carries Ultralytics' AGPL-3.0 stamp (§3A.1).

Until 1–3 are answered for blemish, `SkinRetouchProvider` stays without a production
implementation. What that looks like in the product has since changed: the portrait tool menu does
carry a **피부 보정** entry (T79 and the menu work that followed), and tapping it opens a sheet
whose four corrections are all shown as 준비 중 with Apply disabled. So the follow-on condition is
not "the tool appears" but "the sheet stops saying 준비 중" — nothing may run a correction until a
production `SkinRetouchProvider`, the protected allowed-skin mask builder and the SR1-A/SR1-B
quality gates exist (`specs/skin_retouch_pipeline.md` §1.1).
