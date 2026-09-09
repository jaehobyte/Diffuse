# Skin retouch engine evaluation

`specs/skin_retouch_validation.md` §2. What has actually been run, and what has not.

**Status after SR1: no engine is selected.** What is recorded below is the MI-GAN ONNX pipeline's
mechanical contract and its cost on a desktop CPU. The quality gate (§3), the automatic detector
(SR1-B), the other three corrections (SR1-C) and every Android measurement are unmeasured, and
`specs/skin_retouch_pipeline.md` §1 says selection without that evidence is not finished.

## 1. Environment

| | |
|---|---|
| Date | 2026-09-09 |
| Repository | `4c9c147`, evaluation tool `scripts/retouch/` (this change) |
| Machine | Intel Xeon Platinum 8259CL @ 2.50 GHz, 16 vCPU, 62 GB RAM, Linux 6.14.0-1018-aws |
| Runtime | Python 3.12.3, onnxruntime 1.24.3 (CPUExecutionProvider), OpenCV 4.13.0, NumPy 2.4.6 |
| Device | **none** — no Android device or emulator was available |

## 2. Artifacts

| Candidate | Artifact | Obtained | License |
|---|---|---|---|
| MI-GAN 512 Places2 | `migan_pipeline_v2.onnx`, 28,079,181 B, `sha256:6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b` | yes, from `huggingface.co/andraniksargsyan/migan` (the URL the official README gives) | repository code is MIT (Picsart AI Research, 2024). **The weight file carries no license metadata on HuggingFace and the repository has no separate weights license** — unresolved. |
| MI-GAN 256 FFHQ | — | **no.** No ONNX is published; the checkpoint is a `.pt` on the authors' Google Drive and has to be exported by hand with the repository's `scripts/create_onnx_pipeline.py`. | unresolved, and the FFHQ training data is itself non-commercial — check before adopting a face-trained checkpoint |
| OpenCV inpaint (Telea / Navier-Stokes) | in `opencv-python` | yes | Apache 2.0 |
| StyleRetoucher | — | **no.** Neither the paper page nor the author page links runnable code or weights (checked 2026-09-09). | n/a |
| LaMa | — | not attempted at SR1 | n/a |

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
size against the 15 MB APK budget, and none of those were measured.

## 4. Result table (`specs/skin_retouch_validation.md` §2)

| 기능 | 후보/버전 | 위치 | 양성 성공/전체 | 음성 보존/전체 | 심각한 변형 | p50/p95 | peak memory | 판정 |
|---|---|---|---|---|---|---|---|---|
| Blemish (manual mask, SR1-A) | MI-GAN 512 Places2 `migan_pipeline_v2.onnx@6f1f3530` | desktop CPU | 미측정 | 미측정 | 미측정 | 583 / 612 ms (`twopatch`, 2 patches, synthetic, 7 runs) | 868 MB (process) | 미판정 |
| Blemish (manual mask, SR1-A) | MI-GAN 256 FFHQ | — | 미측정 | 미측정 | 미측정 | 미측정 | 미측정 | 미실행, 가중치 미확보 |
| Blemish (manual mask, SR1-A) | OpenCV Telea / NS 4.13.0 | desktop CPU | 미측정 | 미측정 | 미측정 | 22 / 23 ms (`twopatch`, 2 patches, synthetic, 7 runs) | 60 MB (process) | 미판정 |
| Blemish (auto detect, SR1-B) | 검출기 미구현 | — | 미측정 | 미측정 | 미측정 | 미측정 | 미측정 | 미실행 |
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
3. **An Android measurement**: onnxruntime (or another runtime) on a real device — tensor
   compatibility, accelerator path, end-to-end latency with pre/post-processing, peak memory
   against the 250 MB editor budget, and the runtime + model size against the 15 MB APK principle
   (`specs/architecture.md` §8). A 28 MB model is far past what may be bundled, so delivery is its
   own decision.
4. **A blemish detector** for SR1-B, with detection precision/recall — moles and freckles counted
   as false positives — reported apart from restoration quality.
5. **Shine, dark circles and shaving shadow paths** for SR1-C. Blemish restoration says nothing
   about them.
6. **Weights licensing**, for both checkpoints, before either could ship.

Until 1–3 are answered for blemish, `SkinRetouchProvider` stays without a production
implementation and the tool stays out of the menus (`work/tasks.md` SR1.7).
