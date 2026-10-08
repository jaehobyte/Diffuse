# specs/multishot_api.md — 멀티샷 HTTP API (contract v1)

Owner task: work/tasks.md (2026-10-01, 3–6장 멀티샷 API)
Implementation: `server/multishot/` (standalone FastAPI service; README there)
Depends on: specs/multishot.md §4.2–§6 (the app's math and compositing), specs/segmentation.md,
the SAM 3 service API v1 (`sam3-server/specs/api.md`)
Decision: work/decisions.md D089

This is the **server API** contract. The app's multishot (specs/multishot.md: 2–6 photos in all, its
document model, storage and Undo) is unchanged and does not call this API yet; Android integration is
later work. The API reproduces the app's layout and compositing arithmetic; it does not share code
with the Android renderer and returns pixels, not an edit document.

## 1. What it does
3–6 photos of one motion in, one PNG out. The **last** photo is the background and the hero (the
newest moment, kept sharp). Every other photo contributes only its extracted subject, placed on the
hero's height and drawn semi-transparently over the last photo. Real pixels only: nothing is
generated, aligned, inpainted, tracked or matched.

## 2. Request
`POST /v1/multishot`, `Authorization: Bearer <token>` (the server's own token, from its environment).
`multipart/form-data`:

| part | count | content |
|---|---|---|
| `images` | 3–6, the **same name repeated** | JPEG or PNG still image, any size within §5 |
| `metadata` | 0 or 1 | JSON object, below |

### 2.1 Order
The order in which the `images` parts appear in the body is the time order, 0-based `input_index`.
It is never re-derived from file names, EXIF capture time, or decode/extraction finish order; the
same file name or the same bytes twice are two images. Calling the API confirms the order.

- `images[N−1]` is the background and the hero; the canvas is its normalised size.
- Spatial slots (D088, unchanged): N columns, the hero's slot `k = ⌊(N − 1)/2⌋`, the other inputs
  take slots `0…N−1` without `k` **in input order**. Time order and drawing order are input order;
  left → right on the picture is **not** input order for the hero, which stays in the middle slot.

| input | slots, left → right |
|---|---|
| `[A, B, C]` | `[A, C, B]` — C is the hero |
| `[A, B, C, D]` | `[A, D, B, C]` |
| `[A, B, C, D, E]` | `[A, B, E, C, D]` |
| `[A, B, C, D, E, F]` | `[A, B, F, C, D, E]` |

### 2.2 `metadata`
Optional; `Content-Type` absent or `application/json`. Unknown fields, duplicate keys, a second
`metadata` part, `NaN`/`Infinity`, booleans as numbers, and anything out of range are refused.

| field | type | default |
|---|---|---|
| `spacing` | finite number 0…1 | 1 |
| `subject_points` | array of exactly N items, each `null` or `[x, y]` (finite, 0…1) | all `null` |

A point is a fraction of the **EXIF-upright** photo. `null` = extract `person` by text.

## 3. Processing
### 3.1 Normalisation and validation (before any SAM 3 call)
For each input in order: the real decoder decides the format (MIME type and extension are ignored);
JPEG (incl. camera MPO, frame 0) and PNG stills only; animated PNG, GIF, WebP, BMP, … are refused.
The header's pixel count is checked before decoding. EXIF orientation is applied, an embedded ICC
profile is converted to sRGB, the result is RGBA (a PNG's alpha is kept) shrunk to ≤ 4096 px on its
long side, aspect kept, never enlarged. Normalised photos are spooled to disk and read one at a time.

### 3.2 Extraction (one SAM 3 session per image, input order)
Upload a ≤ 1080 px long-side RGB PNG of the normalised photo, one prompt with `format=png`, then
DELETE in a `finally`. The returned PNG is 8-bit grayscale whose **brightness** (0/255) is the mask;
its size must equal the upload's, other values or modes are an invalid answer. The mask is stretched
nearest-neighbour to working size.

- `subject_points[i]` null → `segment/text` `person`, threshold 0.5, max_instances 20.
- otherwise → `segment/points` one foreground point, `multimask: false`.
- Exactly one non-empty mask is the subject. None → `subject_not_found`; several →
  `ambiguous_subject` (on that `image_index`). No largest/first pick, no union. The hero needs one too.

### 3.3 Layout (specs/multishot.md §4.3, scale 1, rotation 0)
- Anchor: bottom centre of the bounds of every mask pixel, as a fraction of the photo.
- Hero anchor `(hx, hy)`; slot `j` target `x = hx + (j − k)·spacing/N`, `y = hy`.
- Each added photo is contained in the canvas (`s0 = min(W/w, H/h)`) and offset so that its
  **anchor**, not its centre, lands on the target: `offset = target − ½ − s0·(anchor − ½)·(w, h)/(W, H)`.
- Nothing is clamped, shrunk or re-slotted: a subject past the edge is cut there and reported as a
  `subject_clipped` warning (any corner of its transformed bounds off the canvas). With N = 6,
  `hx = 0.5`, `spacing = 1` the last slot is `x = 1`; `spacing = 0.8` keeps it at 0.9. `spacing = 0`
  stacks every anchor on the hero's (intended overlap).

### 3.4 Compositing (specs/multishot.md §5–§6)
- Subject = the photo's RGB with alpha `photoAlpha × feather(mask)`, one radius-2 box feather in
  working pixels (`MultiShotSubject`). Never the whole photo or a rectangle.
- Opacity of added input `i` of `n = N − 1`: `0.25 + 0.45·i/(n − 1)`, applied once (8-bit paint).
- Inputs `0…n−1` are drawn source-over in input order (later on top), bilinear on premultiplied
  pixels, onto the last photo; the canvas keeps its alpha.
- Hero protection: `lerp(composite, hero, heroAlpha)` on premultiplied pixels with
  `heroAlpha = heroPhotoAlpha × feather(heroMask)` — `MultiShotOp.protect`'s integers; weight 255
  returns the hero's pixel exactly. The hero is never moved or drawn twice.
- Same normalised photos and masks → the same output bytes. SAM 3 inference itself is not promised to
  be bit-identical across runs.
- Output: 8-bit RGBA PNG of the canvas size; no EXIF, GPS or other input metadata is copied.

## 4. Responses
### 4.1 `200`
`multipart/form-data`, parts in this order: `metadata` (`application/json`), `image` (`image/png`).

```json
{
  "contract_version": 1,
  "request_id": "9f…",
  "input_count": 3,
  "hero_index": 2,
  "width": 1536, "height": 1920,
  "spacing": 1.0,
  "shots": [
    {"input_index": 0, "slot_index": 0, "anchor": [0.1522, 0.9546], "opacity": 0.25, "is_hero": false},
    {"input_index": 1, "slot_index": 2, "anchor": [0.8189, 0.9546], "opacity": 0.7, "is_hero": false},
    {"input_index": 2, "slot_index": 1, "anchor": [0.4855, 0.9546], "opacity": 1.0, "is_hero": true}
  ],
  "warnings": [{"code": "subject_clipped", "input_index": 0}]
}
```

`shots` is in time (input) order; `anchor` is where the subject's anchor landed, as a fraction of
the canvas. The pixels are the result; `shots` is diagnostic, not an app edit document. There is no
202/job id, no base64 JSON, no partial success and no "original returned" success.

### 4.2 Errors
`application/json` `{"error": "<code>", "request_id": "…"[, "image_index": i]}` — `image_index` only
when one input caused it. No stack trace, token, internal URL or upstream body.

| HTTP | `error` | when |
|---|---|---|
| 400 | `invalid_request` | not multipart / no boundary, malformed or unterminated body, unknown part, second `metadata`, `metadata` with another Content-Type |
| 400 | `invalid_metadata` | metadata not a JSON object, unknown field, bad `spacing` / `subject_points` |
| 401 | `unauthorized` | missing, non-Bearer or wrong token (`WWW-Authenticate: Bearer`) |
| 413 | `too_large` | §5 limits (`image_index` for one image's bytes or pixels) |
| 415 | `unsupported_media_type` | a decodable non-JPEG/PNG, or an animated PNG (`image_index`) |
| 422 | `invalid_image_count` | 0–2 or ≥ 7 `images` |
| 422 | `invalid_image` | empty, truncated or undecodable (`image_index`) |
| 422 | `subject_not_found` / `ambiguous_subject` | §3.2 (`image_index`) |
| 429 | `overloaded` | admission full (`Retry-After`) |
| 502 | `invalid_upstream_response` | SAM 3 answered something outside its contract (bad JSON, mask size/mode/values, unexpected 4xx) |
| 503 | `segmentation_unavailable` | SAM 3 unreachable, 401/403, 429, 5xx incl. OOM/not ready, or a second 410 |
| 504 | `timeout` | the request deadline or an upstream read timeout |
| 500 | `internal_error` | anything else |

Check order: auth → admission → `Content-Length` > limit → streamed body (part rules, byte limits,
seventh image) → image count → metadata → every image in order: format → pixels → decode and
normalise → then extraction, image by image.

## 5. Limits and operation
| limit | default |
|---|---|
| bytes per image part | 20 MiB |
| HTTP body | 125 MiB, counted on bytes actually read (Content-Length absent or wrong) |
| pixels per input, from the header before decoding | 40,000,000 |
| multipart parts | 7 (six images + metadata); a seventh image is `invalid_image_count` unless a size limit hits first |
| metadata part | 64 KiB |
| normalised photo / canvas long side | 4096 px |
| SAM 3 upload long side | 1080 px |
| running / waiting requests | 1 / 0 |
| request deadline, upload included | 300 s |
| SAM 3 connect / read timeout | 10 s / 60 s, each capped by the remaining deadline |
| SAM 3 DELETE / health probe timeout | 5 s |

- Admission is taken before the body is read and held by the worker thread too: a worker still
  running after its request timed out or disconnected keeps the slot until it returns; it is told to
  stop and starts no further extraction or compositing step; its result is dropped.
- A 410 from SAM 3 allows one re-upload and replay of the same prompt for that image; nothing else
  is retried. DELETE failures are logged with the request id and the error kind only and never
  replace the request's outcome.
- The spool directory (uploads, normalised arrays) is removed when the handler and the worker have
  both let go — on success, error, timeout and disconnect. Images and masks are never logged or kept.
- `GET /health` (authenticated): `200 {"contract_version":1,"status":"ready"}` when SAM 3's `/healthz`
  is ok and its token is accepted, else `503 {"status":"unavailable"}`.
- SAM 3 rate limits apply to this server as one client (reference deployment: 6 uploads per 60 s);
  a six-photo request uses six, and a request that hits the limit fails `segmentation_unavailable`.
