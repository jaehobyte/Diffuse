# Skin retouch engine evaluation (SR1)

Reproducible harness for the engine comparison `specs/skin_retouch_validation.md` §1.1 asks for.
It measures; it does not decide. Quality is judged by a human on the images it writes (§3), and
the results go in `work/retouch_evaluation.md`.

Nothing here runs in `scripts/check.sh`: it needs model weights and photographs, and neither is in
this repository.

## Layout

| File | What it is |
|---|---|
| `patches.py` | defect mask → context patches (merge rule, context ratio, minimum size) |
| `restore.py` | the adapter contract: mask polarity, patch loop, protection guard, stage timings |
| `engines.py` | MI-GAN through its official ONNX pipeline, and the OpenCV inpaint baseline |
| `make_cases.py` | deterministic synthetic cases over a repository fixture, for the recorded adapter measurements |
| `evaluate.py` | the CLI: run engines over a manifest, write results and `metrics.json` |
| `test_retouch.py` | the adapter mechanics, weight-free (`specs/skin_retouch_validation.md` §4) |

## Requirements

`numpy`, `opencv-python`, and — for MI-GAN only — `onnxruntime`. The mechanics tests need neither
onnxruntime nor weights.

```bash
python3 -m pytest scripts/retouch/test_retouch.py
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

## What this harness cannot tell you

- Nothing about **detection**. Every mask here is drawn by a human, which is SR1-A on purpose:
  `specs/skin_retouch_validation.md` §1.1 keeps a missed blemish and a badly repainted one out of
  the same number. Automatic detection is SR1-B.
- Nothing about **Android**. These timings are desktop CPU onnxruntime. Runtime size, accelerator
  compatibility and on-device latency are separate measurements (`specs/skin_retouch_pipeline.md`
  §1.1).
- Nothing about **shine, dark circles or shaving shadow**. Those are separate paths (SR1-C).
