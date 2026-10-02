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

> **Historical (before 2026-09-14).** Written before the server path in §6. The statements below
> that no production `SkinRetouchProvider` exists and that the sheet shows 준비 중 describe that
> earlier state; the provider, sheet and server now exist (§6, D083). Items 1, 4, 5 and 6 — the
> photo set, detection quality, working tone corrections and licensing — are still open.

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

## 6. Server path (D083, 2026-09-14)

`server/retouch/`, deployed as user units on this host (T4 shared with MonetGPT). Engines:
`blemish/acne-yolov8m-640@18fad8c5+migan512@6f1f3530/c1` (detector + MI-GAN, CUDA EP),
`shine/tone@1`, `dark_circles/tone@1`, `shaving_shadow/tone@1` (classical Lab/frequency-split tone
corrections written for this task; no model).

### 6.1 Real-photo smoke (mechanical masks, not app masks)

Photo: NASA official portrait `jsc2013e079278` (public domain, user-approved for server/phone
processing; kept in `~/retouch-eval/nasa`, never committed). ROI 2700×3100 (8.4 MP), allowed
regions are hand-placed rectangles (`~/retouch-eval/smoke/region_6.json`), **not** the app's
contour masks, so this is a contract/cost/behaviour smoke and not the SR1 gate.

```bash
V=server/retouch/.venv/bin/python
$V server/retouch/scripts/make_smoke_case.py --image ~/retouch-eval/nasa/orig_6_jsc2013e079278.jpg \
  --region ~/retouch-eval/smoke/region_6.json --out ~/retouch-eval/smoke/case_6 --id nasa6
$V server/retouch/scripts/smoke_client.py --base-url http://127.0.0.1:8094 \
  --manifest ~/retouch-eval/smoke/case_6/manifest.json --all-kinds --negative-auth --repeat 3 \
  --out ~/retouch-eval/smoke/out_6      # exit 0, contract checks OK, 401/403 OK
```

| Kind | outcome | round trip p50 / max (n=3, via proxy, loopback) | observation (`~/retouch-eval/smoke/compare_6.jpg`) |
|---|---|---|---|
| blemish | no_change | 480 / 564 ms (decode ~300 ms, engine ~145 ms) | freckles and moles preserved; nothing detected on this face |
| shine | corrected, 608,801 px | 4,071 / 6,260 ms | **severe**: whole forehead darkened with a visible hard boundary, freckles and texture flattened |
| dark_circles | corrected, 241,838 px | 3,520 / 3,525 ms | **severe**: brightens cheeks rather than under-eye tone, blotchy dark-spot residue |
| shaving_shadow | corrected, 7,272 px | 3,786 / 3,802 ms | edits the jaw contour against the background (region too loose), no stubble reduced |

Four kinds together ≈ 11.9 s (sum of p50) for an 8.4 MP ROI. GPU: server 894 MiB beside MonetGPT
8,068 MiB and two other processes (total ≈ 11.2 / 15.36 GiB) — coexistence OK. Service restart:
`000 → 503 loading → 200` in 5 s.

**Consequence:** the three tone engines fail §3 on the first real face and are not loaded by the
deployment. Blemish has no positive evaluated, so it has not passed either. Until 2026-09-15 it was
enabled on the test deployment; since the review fix (D084) no kind is qualified
(`server/retouch/app/config.py` `QUALIFIED_KINDS = ()`), the deployment loads blemish with
`RETOUCH_EVALUATION_KINDS=blemish`, and health reports `supported_kinds: []` — the app offers no
kind until a gate passes.

### 6.2 Gate table update (`specs/skin_retouch_validation.md` §2)

| 기능 | 후보/버전 | 위치 | 양성 성공/전체 | 음성 보존/전체 | 심각한 변형 | p50/p95 | peak memory | 판정 |
|---|---|---|---|---|---|---|---|---|
| Blemish (auto, SR1-B) | blemish/…/c1 | server T4 | 미측정 (양성 사진 없음) | 1/1 (mechanical, 1 photo) | 0 | 480 / 564 ms (8.4 MP ROI) | 894 MiB GPU | 미판정 — 평가 전용 load, 앱 미지원 |
| Shine (SR1-C) | shine/tone@1 | server | 미측정 | 0/1 | 1 | 4.1 / 6.3 s | — | **FAIL**, 미load |
| DarkCircles (SR1-C) | dark_circles/tone@1 | server | 미측정 | 0/1 | 1 | 3.5 / 3.5 s | — | **FAIL**, 미load |
| ShavingShadow (SR1-C) | shaving_shadow/tone@1 | server | 미측정 | 0/1 | 1 | 3.8 / 3.8 s | — | **FAIL**, 미load |

Still needed for SR1: the ≥24-photo set with human per-kind annotations (positives with acne,
visible shine, dark circles, stubble; skin-tone/lighting spread), app-contour masks rather than
rectangles, and replacement shine/dark-circle/shaving algorithms.

## 7. 피부 보정 인계 기록 (2026-09-15 `work/RESULT.md`에서 이동)

`work/RESULT.md`가 멀티샷 작업(2026-09-29) 인계로 바뀌면서, 피부 보정의 마지막 인계 내용 — 상태, 검증, 실기기 기록,
배포·운영 정보, Known Issues — 을 여기에 그대로 옮겼다. 내용은 수정하지 않았고 제목 단계만 한 단계 낮췄다.
`work/REVIEW.md`의 피부 리뷰(CHANGES_REQUESTED)는 재리뷰 전이며, 이 기록은 그 수정 보고다.

### Status

PARTIAL — 서버·배포·Android 연결·편집/저장 코드와 자동 검증은 완료. **품질 gate를 통과한 종류가 없어 앱이 제공하는 종류는 0개이고, Corrected 실기기 E2E·성능 측정은 미완료.**

2026-09-15 리뷰 수정(`work/REVIEW.md`): R1–R4 수정, R6 회귀 테스트 추가, N1 문서 정리. R5(실기기 Corrected 경로·성능)는 미해결 — 아래 Known Issues.

| 영역 | 상태 |
|---|---|
| T1 환경·엔진·품질 | PARTIAL — 실제 모델 로드·실사진 smoke 완료. 톤 엔진 3종은 실사진에서 심각한 변형으로 **FAIL**, blemish는 양성 없음으로 미판정. 사람 주석 24장 세트 없음 → **gate 통과 종류 0** |
| T2 서버·wire 계약 | DONE — `server/retouch/`, 계약 v1(pipeline §8.1), 서버 81 tests + Android 계약 테스트 |
| T3 기동·포트·외부망 | DONE(범위 제한) — user unit 2개 active/enabled, linger=yes, SG 8094 규칙은 휴대폰 망 egress `/32`만 허용. 배포는 blemish를 평가 전용으로 load(`supported_kinds: []`) |
| T4 Android 연결·편집·저장 | DONE(자동 검증) — provider/Hilt, 설정, 시트, 세션, `Operation.SkinRetouch`, 저장/렌더 |
| T5 실기기 검증 | PARTIAL — 2026-09-14 SM-S948U에서 설정 → 진입 → 강도 → 미리보기(공개 URL 실제 요청) → NoChange 적용 차단, Back/취소 무변경, 서버 중단·복구, draft 중 내보내기 차단까지 실행(당시 blemish가 앱에 활성화된 배포). **적용·Undo/Redo·재열기·export·offline·회전/process 복구·12MP 성능은 미실행.** 리뷰 수정 후 APK 재설치·기기 재검증은 하지 않음 |

### Changed

#### 리뷰 수정 (2026-09-15)

- **R1** 서버: `QUALIFIED_KINDS`(코드 상수, 현재 `()`)에 든 종류만 `RETOUCH_ENABLED_KINDS`로 활성화 가능하고 기본값도 이것뿐. 미통과 종류는 `RETOUCH_EVALUATION_KINDS`로 load → health `evaluation_engines`에만 표시, `/v1/retouch`는 평가 도구용으로 받음, `supported_kinds`/`engines`에는 없음. smoke client가 평가 engine을 사용. 배포 env를 `RETOUCH_ENABLED_KINDS=` / `RETOUCH_EVALUATION_KINDS=blemish`로 바꾸고 `retouch-server` 재시작 → 공개 proxy health `supported_kinds: []`. 앱 client는 기존대로 `supported_kinds`만 사용(평가 engine 무시 테스트 추가). D084, pipeline §8.1, README 갱신.
- **R2** 서버: 인증·Content-Length 검사 직후 body를 읽기 전에 `Runtime.admit()`(실행+대기 = 1+4) → `Ticket`을 수신·파싱·디코딩·engine·enforce·인코딩 끝까지 보유하고 모든 경로에서 `finally` 반납. 504/연결 종료 뒤 실행 중인 engine thread는 끝날 때까지 ticket을 유지. GPU slot(semaphore)은 별도. 429는 body를 읽지 않으므로 `request_id` 없음(계약 문서 반영). Caddy는 `request_buffers` 미설정이라 body를 버퍼링하지 않고 스트리밍하므로 proxy 쪽 추가 제한은 두지 않음.
- **R3** `SkinRetouchController`: Default dispatcher는 픽셀(`draftPixelsOf`)만 만들고, Main 복귀 후 세션·revision을 확인한 결과만 `publishDraft`로 renderer transient에 등록. 취소·stale 작업은 아무것도 등록하지 않음. ref에 revision이 들어 늦은 정리가 새 draft를 지우지 않음.
- **R4** `SkinRetouchController`: 로컬 얼굴 분석을 `analysisJob`으로 분리. 서버 설정 저장은 요청 `job`만 취소하고 분석은 계속 진행. 얼굴 변경/재시도/닫기는 분석 job도 취소.

- **서버** `server/retouch/`: FastAPI + ONNX Runtime(CUDA EP). `GET /health`(load+warm-up 전 503), `POST /v1/retouch` multipart. Bearer 401/403, 413(body 90 MiB·IHDR 픽셀 선검사), 422, 429(실행 1/대기 4), 503, 504(55 s), 계약 보장 중앙 강제, 로그는 request id/kind/status/단계 ms만. 엔진: blemish(acne 검출기 → MI-GAN 복원, 255=보존 반전, 1회 feather), shine/dark_circles/shaving_shadow(톤 보정). 배포 코드에는 fake 선택 경로 없음.
- **wire 계약**: `specs/skin_retouch_pipeline.md` §8.1과 `server/retouch/README.md`에 part 이름·채널·mask 극성·no_change 형태·오류표·상한 계산·timeout(앱 health 5 s, 추론 connect 10 s / call 60 s, proxy 65 s, 서버 55 s)을 고정.
- **core:ai**: `RetouchServerClient`(엄격 PNG 코덱, 응답 echo/형태/binary 검증, support ∩ allowed ∩ alpha>0 재적용), `RetouchServerSkinRetouchProvider`(probe/refresh, 저장 시 재확인, health의 `supported_kinds`만 지원, engine version 고정, 빈 allowance는 업로드 없이 NoChange), `RetouchServerSettings`(개인 기본값 없음, override 우선), `SkinAllowedMask`(ML Kit contour로 종류별 허용 영역, 눈·눈썹·입술·콧구멍·타인 제외), Hilt binding. `SkinRetouchProvider`에 `checking`/`refresh()`와 `StateFlow` `supportedKinds` 추가.
- **core:imaging / core:data**: `Operation.SkinRetouch` + `SkinRetouchSettings`, JSON `skinRetouch`, 참조/강도 검증, outpaint guard, 첫 Adjust 앞 삽입(Mask와 한 변경), Crop 없는 prefix base, RGB만 support 안에서 교체·alpha 보존 렌더, `SkinRetouchComposite`(§5 수식), renderer transient ref, 원자적 `saveSkinRetouch`/`discardSkinRetouch`, load 시 누락 파일 거부, duplicate 경로 재작성(새 op만).
- **feature:editor**: `SkinRetouchController`(세션/얼굴/요청 세대, 늦은 응답·저장 후 commit 직전 재검사, old finally가 new busy를 못 끔, 저장 중 취소 시 파일 정리), 시트(처리 위치·서버 주소, 상태별 문구, 다시 확인/서버 설정, 얼굴 썸네일, 서버·얼굴이 허용한 종류만 slider, 미리보기, 준비 후 강도는 로컬 합성), draft는 실제 renderer로 표시, 적용 1 commit, 문서 변경 시 세션 종료, draft 중 내보내기 차단, 서버 설정 시트에 피부 보정 주소/토큰.
- **문서**: D083, `specs/skin_retouch.md`·`skin_retouch_pipeline.md` 상태/§8.1, `work/retouch_evaluation.md` §6.

### Files

리뷰 수정에서 변경: `server/retouch/app/{config,main}.py`, `app/engines/__init__.py`, `scripts/smoke_client.py`, `deploy/retouch-server.env.example`, `README.md`, `deploy/README.md`, `tests/{conftest,test_auth_health,test_concurrency}.py`; `SkinRetouchController.kt`, `SkinRetouchToolTest.kt`, testShared `FakeFaceRegionAnalyzer.kt`(분석 gate), `RetouchServerClientTest.kt`; `specs/skin_retouch_pipeline.md` §8.1, `work/decisions.md`(D084), `work/retouch_evaluation.md`(§5 과거 표시, §6), `work/RESULT.md`. 저장소 밖: `deploy/retouch-server.env`(git-ignore, 종류 2줄).

전체 작업:

- `server/retouch/**` (신규; `.venv`, `deploy/*.env`, 로그는 git-ignore)
- core:ai: `SkinRetouchProvider.kt`, `AiModule.kt`, `SkinAllowedMask.kt`, `retouch/server/{RetouchPng,RetouchServerConfig,RetouchServerSettings,RetouchServerClient,RetouchServerSkinRetouchProvider}.kt`, `build.gradle.kts`(RETOUCH BuildConfig), testShared `FakeSkinRetouchProvider.kt`·`FakeFaceRegionAnalyzer.kt`, tests `SkinAllowedMaskTest`, `retouch/server/*Test`
- core:imaging: `model/{Operation,EditDocument,EditDocumentJson}.kt`, `render/{Renderer,SkinRetouchOp,SkinRetouchComposite}.kt` + tests
- core:data: `ProjectRepository.kt`, `DefaultProjectRepository.kt`, `file/ProjectFiles.kt` + tests
- feature:editor: `EditorAi.kt`, `EditorViewModel.kt`, `EditorRoute.kt`, `tools/ToolSheetHost.kt`, `tools/retouch/{SkinRetouchController,SkinRetouchState,SkinRetouchSheet}.kt`, `tools/select/{Sam3SettingsSheet,SelectionController,SelectionState}.kt`, `res/values/strings.xml`, tests(`tools/retouch/*`, EditorAi/Renderer/Repository fake 갱신 파일들), feature:browse/export 테스트 fake
- `specs/skin_retouch.md`, `specs/skin_retouch_pipeline.md`, `work/decisions.md`, `work/retouch_evaluation.md`, `work/RESULT.md`
- 저장소 밖: `~/.config/systemd/user/retouch-{server,public}.service`, AWS SG 규칙

### Validation

| Command | Result |
|---|---|
| **리뷰 수정 후** `server/retouch/scripts/check.sh` | exit 0, 86 passed, 4 deselected(`model`) — 신규: 평가 종류 비지원·미통과 활성화 거부, body 수신 전 429(동시 6요청 중 디코딩 1·parse 1), 인코딩 중 자리 유지, 400/422/수신 중 연결 종료 후 용량 복구 |
| **리뷰 수정 후** `scripts/check.sh` | exit 0 (lint, detekt, 전체 unit 1,177 tests / 0 fail / 2 skip 기존, Roborazzi verify, dependencyGuard) |
| **리뷰 수정 후** `:feature:editor … SkinRetouchToolTest` | 21 passed (신규 2: 설정 저장 중 분석 유지, Main 복귀 전 취소된 합성의 transient 0) |
| 변이 확인: R3 옛 등록 위치 / R4 설정 저장이 분석 취소 | 각각 새 테스트 fail(transient 2→4개, 분석 Analyzing 고정) → 복원 후 pass |
| **리뷰 수정 후** `:core:ai … retouch.*` | 0 fail (신규: `evaluation engines are never supported kinds`) |
| **리뷰 수정 후** 배포 health (loopback 8084, proxy 8094) | 200, `supported_kinds: []`, `evaluation_engines: {blemish}` |
| **리뷰 수정 후** `smoke_client.py --base-url http://127.0.0.1:8094 --manifest ~/retouch-eval/smoke/case_6/manifest.json --kind blemish --negative-auth` | exit 0, 401/403 OK, `no_change` 580 ms, "evaluation engine, not offered to the app" |
| `scripts/check.sh` (lint, detekt, 전체 unit, Roborazzi verify, dependencyGuard) | **exit 0**. unit 1,174 tests, 0 fail, 2 skip(기존 core:imaging) |
| `:core:ai … retouch.* / SkinAllowedMaskTest / FakeSkinRetouchProviderTest` | 49 passed |
| `:feature:editor … tools.retouch.*` | 29 passed (SkinRetouchToolTest 19, SkinRetouchSheetTest 10) |
| 변이 확인: 컨트롤러의 늦은 응답/busy 가드 제거 | SkinRetouchToolTest 3 fail → 복원 후 pass |
| `:core:imaging` / `:core:data` unit | 215 (2 skip, 기존) / 25 passed |
| `server/retouch/scripts/check.sh` | exit 0, 81 passed, 4 deselected(`model`) |
| `pytest -m model` (서버 에이전트 실행) | 4 passed |
| `smoke_client.py --base-url http://127.0.0.1:8094 … --all-kinds --negative-auth --repeat 3` (NASA 초상 8.4 MP ROI) | exit 0, 계약 검사 OK, 401/403 OK. 수치는 `work/retouch_evaluation.md` §6 |
| `:app:assembleDebug :app:assembleRelease` | exit 0 (release unsigned 18,435,211 B, ONNX runtime 없음, provider 포함) |
| `git diff --check` | clean |
| localhost health: 토큰 없음/틀림/정상 | 401 / 403 / 200 ready |
| `systemctl --user restart retouch-server` | 000 → 503 → 200, 5 s |
| 기기 셸 `curl http://44.233.156.159:8094/health` (토큰 없음) | 401, proxy 로그 remote_ip 210.94.41.89 |
| `:core:ai:connectedDebugAndroidTest`, 새 피부 E2E instrumentation | **미실행/미작성** |
| 앱 UI 실기기 조작 (SM-S948U) | 설정·진입·미리보기·NoChange·Back·서버 중단/복구·내보내기 차단·PSS 실행(아래 표). 적용·Undo/Redo·재열기·export·offline·slider p50 **미실행** |

### 실기기 검증 (2026-09-14 22:00~22:15, 마지막 기기 검증)

당시 배포는 blemish를 앱 지원 종류로 보고했다(R1 수정 전). 리뷰 수정 후에는 같은 흐름에서 네 종류 모두 "미지원"으로 보여야 하며, 이것은 기기에서 재확인하지 않았다.

- 기기: Samsung SM-S948U `R3CYA0AVX2E`, Android 16, `ADB_SERVER_SOCKET=tcp:127.0.0.1:15038`(SSH 전달, 제어용). Wi-Fi `sWave_works`, egress 210.94.41.89. `adb reverse`는 `tcp:8082`(MonetGPT)만 있고 8094용은 없음 → 앱 데이터는 공개 URL로 전송.
- APK: 최신 코드로 재빌드한 debug `app-debug.apk` SHA-256 `38123fe9…0033c`, versionCode 12 / 0.6.0 / `com.diffuse`, 신규 설치(`install -r`, 기존 앱 없음).
- 사진: NASA 초상 `jsc2013e079278`(원본 5412×6765), 사진 선택기로 앱에서 직접 가져옴. 조작은 `adb shell input` + `uiautomator dump`(`~/retouch-eval/device/ui.sh`, 증거 `~/retouch-eval/device/`).

| 시나리오 | 결과 |
|---|---|
| 기본값 없는 APK | 시트에 "설정에서 피부 보정 서버 주소를 입력해주세요" + 서버 설정 버튼, 네 종류 미지원 |
| 설정 저장 | "피부 보정 서버에 연결됐어요", 서버가 켠 잡티만 slider, 나머지 3종 "미지원". proxy 로그: 210.94.41.89 `okhttp/4.12.0` `/health` 200 |
| 설정 저장·시트 진입·강도 변경 | `/v1/retouch` 요청 0건 (무단 업로드 없음), "미리보기를 눌러 보정을 준비해주세요" |
| 미리보기 | 요청 1건 `request_id=5e75389b-4af1-4988-b053-e8e99e6a3526`: 기기 로그 NoChange 4,128 ms ↔ 서버 로그 blemish 200 no_change total 3,474 ms(수신 3,153 ms, engine 145 ms), 업로드 6,707,442 B |
| NoChange에서 적용 | 시트 유지, commit 없음 |
| 시트 가장자리 드래그가 시스템 Back으로 처리됨 | 시트 닫힘, Undo 비활성, 문서 무변경 |
| 서버 중단 후 미리보기 | 기기 로그 `/v1/retouch -> 502` → Unavailable, 자동 1회 `/health` 재확인 → "서버가 준비 중이에요" + 다시 확인/서버 설정 |
| 서버 재기동 후 "다시 확인" | 앱 재시작 없이 "연결됐어요", 강도 50 유지. 재요청 `542ab62e-7f71-490c-8354-41253ff1ef5e` 기기↔서버 일치, 왕복 5,264 ms |
| 시트 열린 상태에서 내보내기 | 편집기·시트 유지(내보내기로 이동 안 함). 스낵바 문구는 캡처하지 못함 |
| 미리보기 중 앱 PSS(1 s 간격 12회 샘플) | 최대 353,171 kB — **editor 250 MB 예산 초과**(debug 빌드, 36 MP 원본 → working 4096) |
| 4인 단체 사진 | 인물 판정이 안 되어 일반 메뉴가 열리고 AI 그룹에 피부 보정이 없음 → 다중 얼굴/작은 얼굴 흐름 **진입 불가** |

미실행: Corrected 결과가 필요한 적용 1 commit·Undo/Redo·재열기·export·offline 재열기·회전 draft·process 복구(이 사진에서 검출 0건이고, FAIL 판정 톤 엔진을 테스트용으로 켜는 조치는 권한 정책이 거부), slider-to-preview p50.

### 배포·운영

- **공개 URL**: `http://44.233.156.159:8094` (HTTP, 비암호화) → Caddy → `127.0.0.1:8084`. Caddy `admin off`, `persist_config off`.
- **Unit**: `retouch-server.service`, `retouch-public.service` — enabled/active, `Linger=yes`. 부팅 자동 시작은 설정만 확인했고 호스트 재부팅 테스트는 하지 않음.
- **명령**: `systemctl --user {start|stop|restart|status} retouch-server.service retouch-public.service`, 로그 `journalctl --user -u retouch-server.service`. 설치/HTTPS 전환은 `server/retouch/deploy/README.md`.
- **토큰**: `server/retouch/deploy/retouch-server.env`(600, git-ignore)의 `RETOUCH_AUTH_TOKEN`. MonetGPT/SAM 3와 별도. 앱은 서버 설정 시트에서 입력.
- **활성 종류**: 앱 지원 없음(`RETOUCH_ENABLED_KINDS=`, `QUALIFIED_KINDS=()`). `RETOUCH_EVALUATION_KINDS=blemish`(평가 전용). 톤 엔진 3종은 FAIL로 미load. 종류를 앱에 제공하려면 gate 증거와 함께 `app/config.py` `QUALIFIED_KINDS`를 코드로 변경한 뒤 env에 활성화(D084).
- **Ingress**: `sg-09e915674a6bd5375` / `sgr-0b2cf130f609f5c0b`, TCP 8094, `210.94.41.89/32`(휴대폰 사내망 egress). 제거: `aws ec2 revoke-security-group-ingress --group-id sg-09e915674a6bd5375 --security-group-rule-ids sgr-0b2cf130f609f5c0b`. 호스트 방화벽(ufw) 비활성. 8084·ADB는 비공개.
- **마지막 검증 APK·기기**: debug `app-debug.apk` SHA-256 `38123fe9…0033c`, versionCode 12 / 0.6.0 / `com.diffuse`, Samsung SM-S948U `R3CYA0AVX2E`(Android 16, `ADB_SERVER_SOCKET=tcp:127.0.0.1:15038`). 개인 기본값 없음. 리뷰 수정 코드로는 재빌드·재설치하지 않음.
- 이전 시도(참고): 같은 버전의 이전 빌드 SHA-256 `378c5c62…a6076`을 SM-S948N `10.243.100.22:42387`(`ADB_SERVER_SOCKET=tcp:127.0.0.1:15037`)에 설치했으나 공유 기기라 설정 저장 직후 조작을 중단했다.

### Review Notes

- R1 방식: 지원 판정은 코드 상수 `QUALIFIED_KINDS`, 평가 load는 env. health에 `evaluation_engines` 필드를 추가했다(앱은 모르는 필드를 무시). 평가 종류는 인증된 누구나 `/v1/retouch`로 호출할 수 있다 — 앱은 보내지 않지만 서버가 막지는 않는다.
- R2: 429에서 `request_id`가 빠진 것은 계약 변경이다(앱은 오류 body의 `request_id`를 쓰지 않음). 로딩 중 요청도 자리를 받은 뒤 body를 읽고 503을 받는다.
- 헬스는 활성 엔진 하나라도 load 실패하면 전체 503 `failed`(운영자가 종류를 빼서 복구). §8.1 문구의 다른 해석 가능성 있음.
- 서버에 계약표 외 응답 추가: 500 `internal_error`, 연결 끊김 499(수신자 없음), `corrected`인데 변화 없으면 `no_change`로 강등.
- `SkinRetouchProvider` 공개 인터페이스 변경(`checking`, `refresh()`, `StateFlow` supportedKinds). `EditorAi` 생성자 인자 3개 추가.
- draft는 preview 배율로 합성한 transient 결과를 renderer에 넣어 그리고, 적용 시에는 working 해상도 base를 다시 렌더해 저장한다(세션 동안 full-canvas 후보를 들고 있지 않음).
- `duplicate`는 원래부터 source/erase/fill/outpaint/mask 경로를 원본 폴더로 공유한다(기존 결함). 이번에는 SkinRetouch와 그 mask만 재작성했다.
- 서버 구현 중 로컬 Caddy 테스트가 `~/.config/caddy/autosave.json`을 한 번 덮어썼고(복구 불가, `--resume` 사용 시에만 영향), 18094를 수 초간 전체 인터페이스에 열었다. 이후 설정에 `persist_config off`/loopback bind를 넣었다.

### Known Issues

1. **품질 gate 미통과(전 종류) → 앱 지원 종류 0.** 톤 엔진 3종은 첫 실사진에서 심각한 변형(`~/retouch-eval/smoke/compare_6.jpg`) → 알고리즘 교체 필요. blemish는 양성 사진이 없어 미판정. 필요 자료: 사용 권한 있는 최소 24장, 종류별 양성/음성 6장 이상의 **사람 주석**(여드름·유분광·다크서클·면도자국, 피부톤/조명 분포). NASA 공공 도메인 초상 60장은 `~/retouch-eval/nasa`에 있으나 대부분 보정된 공식 사진이라 양성이 거의 없다.
2. **R5 미해결 — Corrected 실기기 경로와 성능.** 설정·미리보기·NoChange·서버 복구는 SM-S948U에서 실행했다(위 표). 남은 것: 품질 gate를 통과한 양성 사진으로 적용 1 commit → Undo/Redo → 재열기 → export/offline, 다중/작은/가린 얼굴, Crop/회전/Adjust, 요청·저장 중 취소, 회전 draft/process 복구, 새 피부 E2E instrumentation. 이 경로는 통과 종류가 생겨야 앱에서 실행할 수 있다(D084). 성능: 기록된 PSS 최대 353,171 kB는 debug·36 MP 원본 조건이며 요구 조건(12 MP/working 4096, 기존 편집기 대비 peak, 반복 실행 잔류, slider-to-preview p50 <100 ms)은 미측정, 네 종류 왕복 p95는 loopback 3회뿐이다. 기기에는 앱(debug)과 `/sdcard/Pictures/RetouchEval/` NASA 사진 4장이 남아 있다.
3. **Ingress 범위.** `0.0.0.0/0` 규칙은 세션 권한 정책이 거부해 `/32`로 제한했다. 휴대폰 모바일 데이터망이나 독립 외부 호스트에서의 검증은 해당 IP 추가가 필요하다.
4. 새 피부 E2E instrumentation 테스트는 작성하지 않았다. 기존 `AcneDetectorDeviceTest`는 서버 경로를 검증하지 않는다.
5. 서버 성능: 8.4 MP ROI 네 항목 합계 ≈ 11.9 s(표본 3, loopback — 완료 판정 근거 아님). 톤 엔진은 전체 해상도 Lab 처리라 큰 ROI에서 느리다.
6. release APK는 unsigned(기존과 동일).
7. **메뉴 불일치(제품 결정 필요)**: `specs/skin_retouch.md` §2는 일반 profile의 AI 그룹에도 피부 보정을 두지만 현재 메뉴(T79, `ToolStripLevelTest`)는 인물 profile에서만 노출한다. 인물로 판정되지 않는 단체 사진은 진입할 수 없다. 명세와 메뉴 계약 중 무엇을 따를지 정해야 하므로 리뷰 수정에서도 바꾸지 않았다.
8. 잡티 Corrected 경로를 실기기에서 확인하려면 여드름이 보이는 사용 권한 있는 얼굴 사진이 필요하다.
