# Skin retouch server

A standalone inference server for the app's 피부 보정 tool. The app prepares a face ROI and a
per-kind allowed mask; this server runs the **actual defect detection and correction** for one
kind per request and returns a strength-100 candidate plus a binary change support.

It is a separate service with its own token, port and venv. It shares nothing with MonetGPT or
SAM 3. Wire contract v1 below is the same contract as `specs/skin_retouch_pipeline.md` §8.1
(D083); if the two ever disagree, the spec wins and this file is a bug.

**Quality status: no kind has passed the SR1 quality gate** (`specs/skin_retouch_validation.md`
§3, `work/retouch_evaluation.md`). Running the server proves the contract and the mechanics, not
that a correction looks right on faces.

## Layout

| Path | What it is |
|---|---|
| `app/main.py` | `create_app(settings, engines_factory)`: auth, `/health`, `/v1/retouch`, admission, deadline |
| `app/wire.py` | multipart in/out, strict PNG header checks before decode, metadata validation |
| `app/engines/base.py` | engine protocol and `enforce`, the central §8.1 guarantees |
| `app/engines/blemish.py` (+ `detector.py`, `patches.py`) | acne detector → defect mask → context patches → MI-GAN → guard → feather |
| `app/engines/shine.py`, `dark_circles.py`, `shaving_shadow.py` | tone engines (detection + low-frequency correction) |
| `app/serve.py`, `app/__main__.py` | deployment entry point (`python -m app`); always real engines |
| `tests/` | weight-free tests with injected fake engines; `test_model.py` is marked `model` |
| `scripts/check.sh` | compile + `pytest -m "not model"`; no GPU, no models |
| `scripts/smoke_client.py` | real-model smoke over HTTP with client-side contract validation |
| `scripts/make_smoke_case.py` | builds an ROI + allowed mask + manifest from an image and a rect/polygon |
| `deploy/` | env example, systemd user units, Caddy HTTP test proxy, operations README |

## Wire contract v1

**Roles.** App: orientation normalisation, canonical working base, face selection/geometry (ML Kit),
ROI crop, per-kind allowed mask (eyes, brows, lips, nostrils, other people excluded), final support
intersection and compositing. Server: per-kind **real defect detection and correction** (blemish
detector + restoration model; shine / dark-circle / shaving-shadow detection and tone correction),
region limiting and exactly one feather. The server does no face detection or eligibility.

**Kind wire names.** `blemish`, `shine`, `dark_circles`, `shaving_shadow`.

**Auth.** Every endpoint requires `Authorization: Bearer <token>`. No header or not the Bearer form
→ `401` (`WWW-Authenticate: Bearer`); well-formed but wrong token → `403`. The token belongs to this
server only and is never shared with MonetGPT / SAM 3.

**`GET /health`.** `200` only after every enabled engine has loaded and run a warm-up inference:

```json
{"contract_version":1,"status":"ready","supported_kinds":["blemish"],"engines":{"blemish":"<engine version>"},"evaluation_engines":{}}
```

While loading or after a failure: `503`, `status` is `loading` | `failed`, `supported_kinds` is `[]`,
`engines` and `evaluation_engines` are `{}`. Process start alone never reports ready.
`supported_kinds` (with `engines`) lists only kinds whose SR1 quality gate has passed
(`app/config.py` `QUALIFIED_KINDS`, changed in code with the evidence) and that the operator
enabled; the app offers exactly these. `evaluation_engines` lists kinds loaded for evaluation
(`RETOUCH_EVALUATION_KINDS`): `/v1/retouch` accepts them so the smoke and evaluation tools can run,
but the app never offers them. Omitting the settings never enables an unqualified engine.

**`POST /v1/retouch` request.** `multipart/form-data`, each part name exactly once:

| part | Content-Type | content |
|---|---|---|
| `metadata` | `application/json` | `{"request_id":"<[A-Za-z0-9_-]{1,64}>","contract_version":1,"kind":"blemish","expected_engine_version":"<value from health>","width":W,"height":H}` |
| `image` | `image/png` | 8-bit **RGBA** (colour type 6), W×H, straight alpha, orientation-normalised ROI |
| `allowed_mask` | `image/png` | 8-bit **grayscale** (colour type 0), W×H, values 0 or 255 only. **255 = may change**, 0 = keep |

**`200` response.** `multipart/form-data`, same part-name rule:

| part | when | content |
|---|---|---|
| `metadata` | always | `{"request_id","contract_version":1,"kind","engine_version","outcome":"corrected"\|"no_change","width","height","timing_ms":{...}}` |
| `candidate` | `corrected` only | 8-bit RGBA PNG W×H. Strength 100, region-limited and feathered once. Alpha equals the input |
| `change_support` | `corrected` only | 8-bit grayscale PNG W×H, 0/255. 255 = the candidate may differ there |

`no_change` carries **only** the `metadata` part. Server guarantees: support ⊆ allowed ∩ (input
alpha > 0); candidate RGBA equals the input outside the support; `corrected` implies a non-empty
support. An empty allowed mask or an empty detection gives `no_change` without calling a model.
Client checks: a mismatched `request_id` / `contract_version` / `kind` / `engine_version` / size /
channels / non-binary support is a failure; the client intersects the support again with
allowed ∩ original alpha > 0 and restores the input outside it and in alpha.

**Errors.** Body `{"error":"<code>","request_id":"<if known>"}`; never images, masks or tokens.

| status | code | meaning | app |
|---|---|---|---|
| 400 | `invalid_request` | missing/duplicate part, JSON/ID error, PNG decode failure, channel/size mismatch, non-binary mask | Invalid |
| 400 | `unsupported_contract` | contract_version ≠ 1 | Invalid |
| 401 / 403 | `unauthorized` / `forbidden` | no token / wrong token | Unauthorized → server settings |
| 409 | `engine_mismatch` | expected_engine_version ≠ current | re-check health, explicit retry |
| 413 | `too_large` | body > 90 MiB, a side > 4096, W×H > 16,777,216 | TooLarge |
| 422 | `unsupported_kind` | kind not enabled | Unsupported |
| 429 | `overloaded` | more than 1 running + 4 waiting requests, with `Retry-After`; no `request_id` (the body is not read) | Unavailable, explicit retry |
| 503 | `not_ready` | loading / failed | Unavailable |
| 504 | `timeout` | wait + run over 55 s | Unavailable |

**Limits.** Working long edge 4096 → largest ROI 4096×4096 = 16,777,216 px. Worst-case RGBA PNG
(incompressible, deflate stored blocks): 4·W·H + H (filter bytes) + 5 B per 65,535 B of stored
blocks + chunk overhead ≈ 67,125,000 B; grayscale mask ≈ 16,790,000 B; multipart/JSON < 2 KB →
worst case ≈ 83.9 MB (80.0 MiB). Request/response body limit **90 MiB (94,371,840 B)**, the same at
the proxy. Decoded pixels are checked per part from the PNG IHDR before decoding.

**Timeouts, concurrency, cancellation.** App health connect/read/call 5 s; inference connect 10 s,
call 60 s. Proxy read/write 65 s. Server job deadline 55 s (wait + run), 1 running, 4 waiting.
When the client disconnects, a waiting job is removed and a running job's result is discarded. A
GPU run cannot be interrupted, but each engine does a bounded number of patches/operations, so a
slot is never held indefinitely. Request data is processed in memory only; no temporary files.

**Logs.** Only request ID, kind, status (and outcome) and per-stage milliseconds. Bodies, images,
masks and Authorization are never logged, written to files, or used for training. The deployed
server loads real engines only; fake engines exist only in test code.

### Implementation notes on the contract

- Order of checks: auth → declared `Content-Length` → admission (429) → streamed body size →
  multipart shape → metadata → readiness (503) → kind (422) → engine version (409) → PNG headers
  (413/400) → decode → mask values → empty allowance (`no_change`) → GPU slot → deadline (504).
- Admission counts whole requests, not engine runs: a request takes its place before its body is
  read and gives it back after its response is built, on every path (error, disconnect, timeout).
  An engine thread still running after a 504 or a disconnect keeps the place until it returns.
  So at most 1 + 4 bodies and decoded images exist at once; a refused request allocates nothing.
- A part with a `Content-Type` other than the table's is `invalid_request`; a part without one is
  accepted and validated by content. Unknown part names are `invalid_request`. Unknown metadata
  keys are ignored.
- Readiness is all-or-nothing: if any enabled engine fails to load, health is `503 failed` (disable
  that kind from `RETOUCH_ENABLED_KINDS` / `RETOUCH_EVALUATION_KINDS` to serve the others).
- An engine exception is `500 {"error":"internal_error"}` (not in the §8.1 table; it is a server bug,
  not a client condition). A request the client abandoned is answered with 499 to nobody.
- `outcome` is downgraded to `no_change` when the enforced support is empty or no pixel differs.
- A body rejected by Caddy's `request_body` limit is answered by the proxy with the same
  `413 {"error":"too_large"}` (no `request_id`, as the body was never read).

## Engines

Every engine receives private copies of the ROI (H×W×4 uint8) and allowed mask (H×W bool) and
returns `None` or `(candidate, support)`. `enforce` (app/engines/base.py) then applies the
guarantees above to whatever it returned. Parameters are named constants per module; changing them
means bumping that module's `PARAMS_VERSION`, which is part of the engine version string.

| Kind | Version string | Detection | Correction |
|---|---|---|---|
| `blemish` | `blemish/acne-yolov8m-640@18fad8c5+migan512@6f1f3530/c1` | acne detector ONNX, conf 0.25, NMS IoU 0.45, ≤ 100 boxes → ellipse inscribed in each box, grown by 15% of its short side, ∩ allowed ∩ alpha > 0 | context patches (merge gap 16 px, context 1× box, ≥ 64 px) → MI-GAN pipeline with 255 = keep / 0 = restore → per-patch support-only composite in plan order → guard (outside support = original) → one feather (0.3% of long edge, ≥ 1.5 px) |
| `shine` | `shine/tone@1` | Lab; L above a robust local skin estimate (masked blur, 3 passes excluding bright outliers) by 9, chroma ≤ local skin chroma; opened, min area; bounded rim hysteresis | low-frequency L pulled toward the skin estimate (85%, cap 30 L), a/b partly toward skin, high-frequency layer kept; one feather |
| `dark_circles` | `dark_circles/tone@1` | per allowance component: reference = median Lab of pixels ≥ 70th percentile low-frequency L; darker by 5 L, or by 2.5 L and bluer by 3 b*; lashes/eyeliner (very dark + local contrast) excluded with a margin | low-frequency L lift (80%, cap 12) and a/b toward reference (70%, cap 6), texture kept; one feather |
| `shaving_shadow` | `shaving_shadow/tone@1` | per component: reference = brightest warm pixels; darker by 3 L AND lower b* by 3 AND greyer (chroma < 92%); dense dark textured hair (beard) excluded with a margin, conservatively | low-frequency L (60%, cap 8) and a/b (70%, cap 7) toward reference, texture kept; one feather |

The blemish version encodes the first 8 hex digits of the SHA-256 of both model files.

### Model files and provenance

Files live in `RETOUCH_MODEL_DIR` (default `~/.cache/vibe-retouch`), are never committed and never
downloaded by this server. How they were obtained is in `scripts/retouch/README.md`; the facts
below are copied from `work/retouch_evaluation.md` §2 and §3A.

| File | Digest | Source | Licence |
|---|---|---|---|
| `migan_pipeline_v2.onnx` (28,079,181 B) | `sha256:6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b` (pinned in code) | `huggingface.co/andraniksargsyan/migan`, the URL the official MI-GAN README gives | repository code MIT (Picsart AI Research, 2024). **The weight file carries no licence metadata and the repository has no separate weights licence — unresolved.** |
| `acne_640_fp32.onnx` (103,589,423 B) + `acne_640_fp32.manifest.json` | `sha256:18fad8c553d3936c4233840d3fefd2ca1b1706486a50d881c12ab1825e7f5ffa` (checked against the manifest) | exported by `scripts/retouch/acne_model.py export` from `Tinny-Robot/acne` revision `d1f64f86f6a89f3988c70aec67eb07492507feba`, `acne.pt` `sha256:2cef23fe…a0a6c` (Ultralytics YOLOv8m detection, nc = 1) | **unresolved.** `config.json` says `apache-2.0` but the repository has no `LICENSE` file and no training-data provenance; the exported graph carries Ultralytics' `AGPL-3.0` stamp. Blocking for production, not for evaluation. |

`load` refuses a missing file, a digest mismatch, or a manifest whose preprocessing/decoding
semantics differ from `app/engines/detector.py`; the kind then reports `failed`. There is no fake
or fallback engine in deployment code.

## Install

```bash
cd server/retouch
~/.local/bin/uv venv --python python3.12 .venv
~/.local/bin/uv pip install --python .venv/bin/python -r requirements-test.txt   # or requirements.txt
```

`onnxruntime-gpu` uses the CUDA execution provider when it initialises (logged as
`detector=CUDAExecutionProvider migan=CUDAExecutionProvider`) and otherwise falls back to CPU with a
warning line. `RETOUCH_EXECUTION_PROVIDER=cpu` forces CPU.

## Tests and check

```bash
server/retouch/scripts/check.sh                                         # compile + weight-free tests
server/retouch/.venv/bin/python -m pytest server/retouch/tests          # same tests (model tests deselected)
server/retouch/.venv/bin/python -m pytest server/retouch/tests -m model # real models, needs the files
```

## Run locally

```bash
cd server/retouch
RETOUCH_AUTH_TOKEN="$(cat /path/to/token-file)" RETOUCH_PORT=18084 .venv/bin/python -m app
```

Environment variables: `RETOUCH_AUTH_TOKEN` (required, refuses to start if empty),
`RETOUCH_MODEL_DIR`, `RETOUCH_ENABLED_KINDS` (default: the qualified kinds, currently none; an
unqualified kind refuses to start), `RETOUCH_EVALUATION_KINDS` (default none), `RETOUCH_HOST`
(127.0.0.1), `RETOUCH_PORT` (8084),
`RETOUCH_MAX_RUNNING` (1), `RETOUCH_MAX_QUEUED` (4), `RETOUCH_JOB_DEADLINE_S` (55),
`RETOUCH_EXECUTION_PROVIDER` (`auto`|`cpu`), `RETOUCH_GPU_MEM_LIMIT_MB` (2048, per ONNX session).

## Smoke

A mechanical case from the face-free fixture (expect `no_change` for blemish; the tone engines do
fire on a landscape because the whole rectangle is "allowed" — that says nothing about faces):

```bash
echo '{"roi":[0,0,512,384],"rect":[40,30,472,354]}' > /tmp/region.json
server/retouch/.venv/bin/python server/retouch/scripts/make_smoke_case.py \
  --image fixtures/photo_512.png --region /tmp/region.json --out /tmp/retouch-case --id photo512
RETOUCH_AUTH_TOKEN=... server/retouch/.venv/bin/python server/retouch/scripts/smoke_client.py \
  --base-url http://127.0.0.1:8084 --manifest /tmp/retouch-case/manifest.json --all-kinds \
  --negative-auth --repeat 11 --out /tmp/retouch-smoke
```

`--token-file` may replace the environment variable (the file must be mode 600). The smoke never
prints the token or payloads, validates every response against the contract, prints request ID,
kind, outcome, status, timings and changed-pixel counts, reports p50/p95 per kind, and exits
non-zero on any violation.

## Deploy

See `deploy/README.md`: systemd user units for the backend (`127.0.0.1:8084`) and the Caddy HTTP
test proxy (`:8094`), linger, start/stop/status/recovery, and the HTTPS migration.
