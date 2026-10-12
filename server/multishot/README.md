# Multishot server

A standalone HTTP service: **3–6 photos in, one multishot PNG out.** The order of the photos in the
request is the time order. The **last** photo is the background and the hero (the newest moment,
kept sharp); every earlier photo contributes only its extracted subject, placed on the hero's height
and drawn semi-transparently. Subjects are extracted by the existing SAM 3 service over HTTP.

The contract is `specs/multishot_api.md` (if this file and the spec disagree, the spec wins). The
layout and compositing reproduce the app's multishot (`specs/multishot.md` §4.3–§6, D088): same slots,
anchors, opacity profile, feather, source-over and hero protection. Nothing is generated; no
alignment, inpainting, tracking, collage or job queue. It has its own token, port, venv and spool,
and shares nothing with `server/retouch/`.

## Layout

| Path | What it is |
|---|---|
| `app/main.py` | `create_app(settings, extractor_factory)`: auth, `/health`, `/v1/multishot`, admission, deadline, disconnect |
| `app/upload.py` | streamed multipart parsing into a per-request spool; repeated `images` kept in part order |
| `app/options.py` | strict `metadata` JSON |
| `app/images.py` | real-decoder validation, EXIF/ICC/RGBA normalisation, ≤ 4096 / ≤ 1080 px resizes, PNG out |
| `app/sam.py` | `Extractor` seam and `Sam3Extractor` (upload → text/points → DELETE, 410 replay once) |
| `app/compose.py` | D088 layout, feather, bilinear premultiplied source-over, hero protection |
| `app/pipeline.py` | one request in the worker thread: validate all → extract in order → composite → encode |
| `app/serve.py`, `app/__main__.py` | `python -m app`; always the real SAM 3 adapter |
| `tests/` | weight-free tests: fake extractor (service level), mocked HTTP wire (adapter level) |
| `scripts/check.sh` | compile + every test; no GPU, no SAM 3, no downloads |
| `scripts/smoke_client.py` | sends images in order, checks the response contract, saves the PNG and metadata |

## Order: time vs. place

Calling the API confirms the order: the `images` parts as they appear in the body, 0-based
`input_index`. File names, EXIF capture time and finish order are never used; the same file twice is
two images. Time order (opacity, drawing order) is input order. Place is D088's slots: the hero keeps
the middle slot `k = ⌊(N−1)/2⌋` and the others fill the remaining slots in input order:

| request | picture, left → right |
|---|---|
| `[A, B, C]` | `A  C  B` (C is the hero) |
| `[A, B, C, D, E, F]` | `A  B  F  C  D  E` (F is the hero) |

So the picture is **not** simply the inputs left to right: the hero sits in the middle.

## Environment

| Variable | Default | Meaning |
|---|---|---|
| `MULTISHOT_AUTH_TOKEN` | — (required) | this server's bearer token |
| `MULTISHOT_SAM3_URL` | — (required) | SAM 3 base URL, e.g. `http://127.0.0.1:8080` |
| `MULTISHOT_SAM3_TOKEN` | — (required) | SAM 3's bearer token |
| `MULTISHOT_HOST` / `MULTISHOT_PORT` | `127.0.0.1` / `8086` | bind address |
| `MULTISHOT_MAX_RUNNING` / `MULTISHOT_MAX_QUEUED` | `1` / `0` | admission; beyond it `429 overloaded` + `Retry-After` |
| `MULTISHOT_DEADLINE_S` | `300` | whole request, upload included → `504 timeout` |
| `MULTISHOT_SAM3_CONNECT_TIMEOUT_S` / `_READ_TIMEOUT_S` | `10` / `60` | per SAM 3 call, capped by the remaining deadline |
| `MULTISHOT_TMP_DIR` | system temp | where per-request spools go (removed after each request) |

Clients never pass a URL or a server path. The request limits are fixed defaults in `app/config.py`
(tests build smaller ones): **20 MiB per image, 125 MiB per body (counted on bytes read, with or
without Content-Length), 40,000,000 pixels per input (from the header, before decoding), 7 multipart
parts** (six images + metadata; a seventh image is `invalid_image_count`), 64 KiB metadata. Inputs are
normalised to ≤ 4096 px on the long side; SAM 3 receives ≤ 1080 px.

## Install, check, run

```bash
cd server/multishot
uv venv --python 3.12 .venv
uv pip install --python .venv/bin/python -r requirements-test.txt   # requirements.txt for serving only
scripts/check.sh                                                     # from the repo root: server/multishot/scripts/check.sh

export MULTISHOT_AUTH_TOKEN=...   MULTISHOT_SAM3_URL=http://127.0.0.1:8080   MULTISHOT_SAM3_TOKEN=...
.venv/bin/python -m app
```

Keep tokens in the environment or an untracked env file; never commit them or real photos.

## Examples

```bash
T="Authorization: Bearer $MULTISHOT_AUTH_TOKEN"
URL=http://127.0.0.1:8086

# Health: 200 {"contract_version":1,"status":"ready"} or 503 {"status":"unavailable"}.
curl -s -H "$T" $URL/health

# Three photos: the same field name repeated, in time order; c.jpg is the background and hero.
curl -s -H "$T" -F images=@a.jpg -F images=@b.jpg -F images=@c.jpg $URL/v1/multishot -o response.multipart

# Six photos with narrower spacing (see "Clipping").
curl -s -H "$T" -F images=@1.jpg -F images=@2.jpg -F images=@3.jpg -F images=@4.jpg -F images=@5.jpg \
  -F images=@6.jpg --form-string 'metadata={"spacing":0.8}' $URL/v1/multishot -o response.multipart
```

The 200 body is `multipart/form-data` with a `metadata` JSON part and an `image` PNG part. To check
the contract and save the PNG and metadata, use the smoke client (same order rules):

```bash
MULTISHOT_AUTH_TOKEN=... .venv/bin/python scripts/smoke_client.py --base-url $URL \
  --out smoke-out/three a.jpg b.jpg c.jpg                     # → smoke-out/three/result.png, metadata.json
MULTISHOT_AUTH_TOKEN=... .venv/bin/python scripts/smoke_client.py --base-url $URL \
  --out smoke-out/six --metadata '{"spacing":0.8}' 1.jpg 2.jpg 3.jpg 4.jpg 5.jpg 6.jpg
```

### No subject, several people, and picking one with a point

Each photo needs exactly one subject. Without a point the server asks SAM 3 for `person`:

```bash
curl -s -H "$T" -F images=@a.jpg -F images=@empty.jpg -F images=@c.jpg $URL/v1/multishot
# 422 {"error":"subject_not_found","request_id":"…","image_index":1}
curl -s -H "$T" -F images=@crowd.jpg -F images=@b.jpg -F images=@c.jpg $URL/v1/multishot
# 422 {"error":"ambiguous_subject","request_id":"…","image_index":0}
```

Nothing is guessed (no biggest/first person, no union) and no photo is skipped. Retry with one point
per photo — `null` keeps text extraction — as fractions of the EXIF-upright photo:

```bash
curl -s -H "$T" -F images=@crowd.jpg -F images=@b.jpg -F images=@c.jpg \
  --form-string 'metadata={"subject_points":[[0.42,0.55],null,null]}' $URL/v1/multishot -o response.multipart
```

A point sends one foreground point with `multimask:false`; an empty or multi-mask answer fails the
same way. Whether the subjects are the same person, and golf clubs as separate objects, are not checked.

### Clipping

Subjects are never clamped, shrunk or moved to another slot; whatever lands off the canvas is cut and
reported: `"warnings":[{"code":"subject_clipped","input_index":4}]`. With six photos, a centred hero
(`hx = 0.5`) and `spacing = 1`, the last slot's anchor is exactly `x = 1` — that subject is half cut.
`spacing = 0.8` puts it at 0.9; `spacing = 0` stacks every anchor on the hero's (intended overlap).
Wide subjects in a portrait canvas are cut even at moderate spacing.

## Operational notes

- One request runs at a time and none wait (`429 overloaded`, `Retry-After: 5`). A worker that is still
  running after its request timed out or disconnected keeps the slot until it returns; it starts no
  further SAM 3 call, and its result is dropped.
- SAM 3 sessions are deleted after every image (`finally`, 5 s timeout). A 410 allows one re-upload and
  replay for that image; other upstream failures are not retried.
- SAM 3 rate limits count this server as one client. The reference deployment allows **6 uploads per
  60 s**: a six-photo request spends all six, and a request that runs into the limit fails
  `503 segmentation_unavailable`. Space requests out or raise SAM 3's `SAM3_UPLOAD_RATE`.
- The same normalised photos and masks give the same bytes; SAM 3 inference itself is not promised to
  be bit-identical across runs.
- Logs: one line per request — request id, status, error code, image count, total ms. No image data,
  masks, tokens or upstream bodies.
