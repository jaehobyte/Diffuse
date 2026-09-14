# Skin retouch engine evaluation (SR1)

Reproducible harness for the engine comparison `specs/skin_retouch_validation.md` §1.1 asks for.
It measures; it does not decide. Quality is judged by a human on the images it writes (§3), and
the results go in `work/retouch_evaluation.md`.

Two halves, kept apart on purpose (§1.1): **SR1-A** restores hand-annotated defect masks, and
**SR1-B** is the automatic detector that produces those masks by itself. A missed blemish and a
badly repainted one must not end up inside one number, so the manual masks stay ground truth and
are never fed to the detector as input.

Nothing here runs in `scripts/check.sh`: it needs model weights and photographs, and neither is in
this repository.

## Layout

| File | What it is |
|---|---|
| `patches.py` | defect mask → context patches (merge rule, context ratio, minimum size) |
| `restore.py` | the adapter contract: mask polarity, patch loop, protection guard, stage timings |
| `engines.py` | MI-GAN through its official ONNX pipeline, and the OpenCV inpaint baseline |
| `make_cases.py` | deterministic synthetic cases over a repository fixture, for the recorded adapter measurements |
| `evaluate.py` | SR1-A CLI: run engines over a manifest, write results and `metrics.json` |
| `test_retouch.py` | the adapter mechanics, weight-free (`specs/skin_retouch_validation.md` §4) |
| `acne_model.py` | SR1-B: inspect the acne checkpoint, export the ONNX + manifest, PyTorch↔ONNX parity, and the preprocessing fixture |
| `detector.py` | the reference detector — letterbox, decode, NMS, box→mask. Pure NumPy, no weights |
| `acne_onnx.py` | the exported detector on desktop ONNX Runtime, behind one `detect(roi)` |
| `detect_eval.py` | SR1-B CLI: detection metrics, and the same restoration run on the manual and the automatic mask |
| `test_detector.py` | the detector mechanics, weight-free |

## Requirements

`numpy`, `opencv-python`, and — for MI-GAN and for the acne detector — `onnxruntime`. Both test
files need neither onnxruntime nor weights:

```bash
python3 -m pytest scripts/retouch
```

## Weights

Never committed, never bundled into the APK, and not downloaded by any of these scripts.

**MI-GAN 512 Places2, official exported ONNX pipeline** — the one pre-converted artifact the
authors publish:

```bash
mkdir -p ~/.cache/vibe-retouch
curl -L -o ~/.cache/vibe-retouch/migan_pipeline_v2.onnx \
  https://huggingface.co/andraniksargsyan/migan/resolve/main/migan_pipeline_v2.onnx
sha256sum ~/.cache/vibe-retouch/migan_pipeline_v2.onnx
# 6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b  (2026-09-09, 28,079,181 bytes)
```

**MI-GAN 256 FFHQ** has no published ONNX. It is a `.pt` checkpoint on the authors' Google Drive,
and comparing it means downloading that by hand and running the repository's own
`scripts/create_onnx_pipeline.py` to export it. Until that is done the FFHQ/Places2 comparison
`specs/skin_retouch_validation.md` §1.1 requires is not finished — see `work/retouch_evaluation.md`.

## Cases

A case is a photograph, a hand-annotated defect mask, and optionally the allowed-skin mask:

```json
{"cases": [
  {"id": "p01", "image": "p01.png", "defect_mask": "p01_defect.png", "allowed_mask": "p01_skin.png"}
]}
```

Masks are 0/255 grayscale at the image size. In `defect_mask` 255 means "this is a blemish"; in
`allowed_mask` 255 means "this pixel may change". The polarity MI-GAN itself wants is the opposite
one, and `restore.to_model_mask` is the only place that conversion happens.

Keep the manifest and the photographs **outside this repository**. `specs/skin_retouch_validation.md`
§2 fixes the set at 24 licensed photos with the stated spread of skin tones, lighting and features,
and forbids committing the originals.

## Running

```bash
cd scripts/retouch
python3 evaluate.py --manifest ~/retouch-cases/manual.json \
  --engine opencv-telea \
  --engine migan-onnx:$HOME/.cache/vibe-retouch/migan_pipeline_v2.onnx \
  --out ~/retouch-out/sr1a --repeat 5
```

Per case and engine it writes the corrected image, a changed-pixel map, and a row in
`metrics.json` carrying: patch count and sizes, changed pixels, pixels the engine changed outside
the support before the guard reverted them, protection violations after it (must be 0), per-stage
timings, p50/p95 wall clock, model load time, and process peak RSS.

`--model-size N` is for a raw fixed-size model. The official ONNX **pipeline** crops and resizes
internally, so it runs without that flag.

## Reproducing what is already recorded

`work/retouch_evaluation.md` §3 was measured without a photograph: `make_cases.py` draws the masks
over a repository fixture, deterministically, and writes the cases outside the repository.

```bash
cd scripts/retouch
python3 make_cases.py --fixture ../../fixtures/photo_512.png --out ~/retouch-cases/synthetic
```

The manifest it writes is an ordinary manifest — `evaluate.py --manifest
~/retouch-cases/synthetic/manifest.json` runs it like any other. The exact commands, the repetition
condition, and the `metrics.json` field behind every recorded number are in that file's §3.1. These
cases measure the adapter only: the fixture contains no face.

## SR1-B: the acne detector

### The checkpoint

[`Tinny-Robot/acne`](https://huggingface.co/Tinny-Robot/acne) is an **Ultralytics YOLOv8m object
detection** checkpoint. Not a segmentation model, not a restoration model, and — despite the
repository's `transformers` tag, which HuggingFace assigns automatically — not a Transformers
model. The card's `model.detect_acne(image_path=...)` example is not an Ultralytics API and does
not run; the contract below was read off the checkpoint instead.

```bash
mkdir -p ~/.cache/vibe-retouch
curl -L -o ~/.cache/vibe-retouch/acne.pt \
  https://huggingface.co/Tinny-Robot/acne/resolve/d1f64f86f6a89f3988c70aec67eb07492507feba/acne.pt
sha256sum ~/.cache/vibe-retouch/acne.pt
# 2cef23fe3587b0154cd3598cae54f8c0d8076acebb545286e8904c11a9ee0a6c  (52,001,952 bytes)
```

The revision is pinned because `main` can move; the digest is HuggingFace's own LFS `sha256` for
that revision. Weights are never committed and never bundled into the APK.

### The export environment

The checkpoint was saved by Ultralytics **8.0.85**, whose module layout (`ultralytics.yolo.utils`)
no longer exists. Ultralytics' own `torch_safe_load` remaps it, so a current release loads the file
— no other weights are substituted, and the 2023 release is not needed:

```bash
python3 -m venv ~/.venvs/acne-export
~/.venvs/acne-export/bin/pip install torch==2.5.1 torchvision==0.20.1 \
  --index-url https://download.pytorch.org/whl/cpu
~/.venvs/acne-export/bin/pip install ultralytics==8.3.155 onnx==1.17.0 onnxruntime==1.24.3 "numpy<2"
```

`onnxruntime` is pinned to the version the Android adapter uses, so the Python reference and the
device are the same runtime.

### Inspect, export, verify

```bash
cd scripts/retouch
V=~/.venvs/acne-export/bin/python

$V acne_model.py inspect --checkpoint ~/.cache/vibe-retouch/acne.pt

$V acne_model.py export --checkpoint ~/.cache/vibe-retouch/acne.pt \
  --out ~/.cache/vibe-retouch/acne_640_fp32.onnx

$V acne_model.py parity --manifest ~/.cache/vibe-retouch/acne_640_fp32.manifest.json \
  --checkpoint ~/.cache/vibe-retouch/acne.pt \
  --image ../../fixtures/photo_12mp.jpg --confidence 0.05
```

`export` writes `acne_640_fp32.onnx` **and** `acne_640_fp32.manifest.json` beside it. The manifest
is the contract every consumer reads: graph I/O, class ids, preprocessing, decode rules, thresholds
and the model's SHA-256. A model whose digest disagrees with its manifest is an error, in the
Python wrapper and in the Android adapter alike.

The digest is only half of it. Both consumers also check what the manifest says the run **means** —
`preprocess` (colour order, dtype, scale, layout, resize, interpolation, pad value and placement,
transparent background) and `decode` (layout, box format, box units, objectness, class activation,
embedded NMS), plus the I/O element type — against what they actually perform, and refuse a
mismatch before inference. Those values live in one place, `PREPROCESS_SEMANTICS` and
`DECODE_SEMANTICS` in `detector.py`; `export` writes them, `DetectorContract.from_manifest` and
`DetectorManifest.contract()` require them back. The failure this prevents is the quiet one: the
same file, the same SHA-256, a manifest that says `BGR` or `xyxy`, and boxes that come out
well-formed and wrong.

The `postprocess_defaults` block is different in kind — a threshold sweep is part of the
evaluation, so `--confidence`, `--nms-iou` and `--max-detections` may differ from it. They are
**recorded** rather than refused: `metrics.json` carries a `setting_overrides` object saying which
settings this run did not take from the manifest.

Two exports of one checkpoint are identical in content and different in SHA-256: Ultralytics stamps
an export timestamp into the graph metadata. The digest therefore identifies **one exported file**,
which is what the adapter checks; the export *configuration* is the `export` block of the manifest.

`parity` compares PyTorch and ONNX Runtime on the same preprocessed tensor, with the raw-tensor and
the post-NMS tolerances fixed separately (`acne_model.py`, `RAW_BOX_TOLERANCE_PX` and below).

### Evaluating detection

```bash
# detection only — no restoration weights needed
$V detect_eval.py --manifest ~/retouch-cases/manual.json \
  --detector ~/.cache/vibe-retouch/acne_640_fp32.manifest.json \
  --out ~/retouch-out/sr1b

# detection, then the same restoration of the manual and the automatic mask, as separate rows
$V detect_eval.py --manifest ~/retouch-cases/manual.json \
  --detector ~/.cache/vibe-retouch/acne_640_fp32.manifest.json \
  --engine migan-onnx:$HOME/.cache/vibe-retouch/migan_pipeline_v2.onnx \
  --out ~/retouch-out/sr1b --repeat 5
```

It reads the same case manifest `evaluate.py` does. `defect_mask` is the annotation and is used
**only** as ground truth; `allowed_mask` is required, and a case without one is skipped rather
than treated as "all of the face may change".

A case image's **alpha is loaded and kept** (`load_rgba`, not `evaluate.load_rgb`, which drops it):
it is composited into the detector's input over the fixed background, intersected into the
candidate mask, and intersected into the allowance both restoration rows use — so a transparent
region is never detected as skin, never restored, and the manual and automatic rows stay
comparable. `metrics.json` records `transparent_pixels` and `candidate_on_transparent`, and the
latter must be 0.

### On a device

The Android side is `core/ai`: a `BlemishDetector` contract with model-agnostic pre/post-processing
in `src/main`, and the ONNX Runtime adapter in `src/debug` — a `debugImplementation` dependency, so
no release variant and no release APK contains the runtime, and no Hilt module binds the adapter.
The model is a file a developer installs by hand:

```bash
adb shell mkdir -p /data/local/tmp/blemish-detector
adb push ~/.cache/vibe-retouch/acne_640_fp32.onnx /data/local/tmp/blemish-detector/
adb push ~/.cache/vibe-retouch/acne_640_fp32.manifest.json \
  /data/local/tmp/blemish-detector/detector.manifest.json

./gradlew :core:ai:connectedDebugAndroidTest
adb logcat -d -s AcneDetectorDevice
```

`AcneDetectorDeviceTest` copies those two files into the test app's private directory
(`/data/data/com.diffuse.core.ai.test/files/blemish-detector/`), which is the only place the
adapter reads from, and prints its measurements as `key=value` lines under the tag
`AcneDetectorDevice`. With no model installed every test **skips**: `connectedDebugAndroidTest` on
a clean device is green and says nothing, rather than red.

### One preprocessing contract, two implementations

`core/ai/src/test/resources/retouch/preprocess_parity.json` is generated by

```bash
$V acne_model.py fixture      # or plain python3; it needs no weights
```

and is asserted against by `test_detector.py` **and** by `core/ai`'s
`DetectorPreprocessParityTest`. Changing either preprocessor without regenerating it fails on that
side; regenerating it without changing the other side fails there. The tolerances are in the file,
and are far tighter than one 8-bit level.

## What this harness cannot tell you

- Nothing about **detection quality**. `detect_eval.py` measures precision and recall against a
  human annotation, and it can only do that on the licensed photo set
  `specs/skin_retouch_validation.md` §2 fixes. A weight-free `pytest` run says the port is wired
  the way its manifest says, and nothing about whether the model finds acne.
- Nothing about **Android** unless `connectedDebugAndroidTest` was actually run against a device.
  The Python timings are desktop CPU onnxruntime and are not a substitute
  (`specs/skin_retouch_pipeline.md` §1.1).
- Nothing about **shine, dark circles or shaving shadow**. Those are separate paths (SR1-C).
