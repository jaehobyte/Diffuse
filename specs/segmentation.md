# specs/segmentation.md — SAM 3 segmentation over HTTP

Owner tasks: T27 (`Sam3Client`), T28 (`Sam3SegmentationProvider`, settings)
Module: `core/ai/sam3`
Decisions: ADR-009. Supersedes the retired EdgeTAM / ExecuTorch plan (ADR-007, ADR-008 — see
tasks.md D09).
Upstream contract: `~/sam3-server/specs/api.md`. **That file wins on any disagreement with this one.**

## 1. Backend
Meta's SAM 3 behind an authenticated FastAPI service. Two-call shape: one upload runs the vision
backbone and caches the inference state; every prompt afterwards reuses it. Three prompt kinds
exist server-side (`points`, `box`, `text`); v2 uses **points** and **text**. `box` is deferred
(D06) — do not write a client for it.

| Setting | Value |
|---|---|
| Base URL | from `Sam3Settings` (§6). No default that points at a public host. |
| Auth | `Authorization: Bearer <token>` on every `/v1/` route |
| Coordinates sent | normalized 0..1 against the uploaded image |
| Geometry received | original-image pixels |
| Mask encoding | always `format = "png"` — base64 8-bit alpha PNG at original resolution |
| Upload limit | 20 MB, JPEG or PNG |
| Session TTL | 600 s server-side, refreshed on each use, LRU-evicted above 8 |

**No COCO RLE decoder is written.** The server offers a PNG encoding of the same mask, so RLE would
be dead code. If a future need appears, add it then.

## 2. `Sam3Client` (T27)
OkHttp + kotlinx.serialization. One class, one `OkHttpClient` instance, no interceptor stack beyond
the auth header.

```kotlin
class Sam3Client(...) {
    suspend fun health(): Result<Unit>                                     // GET /healthz
    suspend fun upload(jpeg: ByteArray): Result<UploadResponse>            // POST /v1/images
    suspend fun points(id: String, p: PointPrompt): Result<List<RawMask>>  // .../segment/points
    suspend fun text(id: String, phrase: String): Result<List<RawMask>>    // .../segment/text
    suspend fun delete(id: String)                                         // DELETE /v1/images/{id}
}
```
- `points` sends `multimask = true` and returns the model's candidates, ordered by score as the
  server ordered them. `text` sends `threshold = 0.5`, `max_instances = 20`.
- `RawMask` carries the decoded `ALPHA_8` bitmap and the score. Decoding runs off the calling thread.
- Timeouts: connect 10 s, read 30 s for prompts, read 60 s for the upload.
- `health` is the only unauthenticated call.

## 3. Encoding an image for upload
1. If the working bitmap's long edge exceeds 2048 px, downscale to 2048 (bilinear). Masks come back
   at the uploaded size and are scaled up to the working size by the provider; a 2048 mask is more
   than enough for a hard-edged selection and keeps the upload small.
2. Compress to JPEG quality 90. If the result still exceeds 20 MB, drop quality to 75 and retry once;
   still too large → `Failure(TooLarge)`.
3. Send as `multipart/form-data`, field `file`.

## 4. Error mapping
| HTTP | `error` | `AppError` |
|---|---|---|
| 400 | `invalid_prompt` | `Invalid(detail)` |
| 401 | `unauthorized` | `Unauthorized` |
| 410 | `session_expired` | absorbed — see §5 |
| 413 | `image_too_large` | `TooLarge` |
| 415 | `unsupported_media_type` | `Unsupported` |
| 503 | `not_ready` / `out_of_memory` | `Unavailable` |
| transport / decode | — | `Io(cause)` |

The error body is always `{ "error": ..., "detail": ... }`. `detail` goes into the log and into
`Invalid`, never into a user-facing string — user strings are Korean and live in `strings.xml`.

## 5. Session expiry is absorbed, never surfaced
api.md states a client must be prepared for `410` at any time. The provider handles it:

```
prompt → 410 → re-upload the same bitmap (once) → replay the same prompt → result
                                                 ↘ 410 again → Failure(Unavailable)
```
The caller sees only a slower call. The re-upload uses the bitmap the provider retained when the
session was opened; if that bitmap is gone, the provider fails with `Unavailable` rather than
guessing. Only **one** replay per prompt — no loop.

## 6. Settings (T28)
`Sam3Settings` exposes `baseUrl: String` and `token: String` as a `StateFlow`.
- Build-time default: `local.properties` keys `sam3.baseUrl` and `sam3.token`, surfaced as
  `BuildConfig` fields on `:app`. `buildConfig` is off globally in `gradle.properties`, so it is
  enabled for `:app` only, in build-logic.
- Runtime override: a settings sheet writes both to `SharedPreferences` — the same choice
  `ExportSettingsStore` already made, since the catalog has no DataStore entry.
- Empty base URL is the default when `local.properties` says nothing. It is not an error state to
  hide; it is what `availability` reports.
- The emulator reaches a host-local server at `http://10.0.2.2:8080`. Say so in the settings sheet
  helper text.

## 7. Availability
```
no base URL configured            → Unavailable(Invalid)
GET /healthz fails or returns 503 → Unavailable(Unavailable)
GET /healthz 200                  → Ready
```
Checked when the provider is first used and whenever settings change; not polled. A failed prompt
does not by itself flip `availability` — one bad request is not a dead server.

## 8. `Sam3SegmentationProvider` (T28)
- `open(bitmap)`: encode per §3, upload, retain the encoded bytes for the §5 replay, build
  `SegSession`. Closes any previous session with `DELETE` first.
- `byPoints`: normalize the points against the **uploaded** size, send, take the highest-scoring
  candidate, scale its alpha to the working image size (nearest-neighbour — the mask is binary),
  return `SegMask`.
- `byText`: send the phrase, scale every returned mask the same way, preserve score order.
- `close`: `DELETE`, then drop the retained bytes.
- All of it on `DispatcherProvider.io`; `ensureActive()` before each network call.

## 9. Tests
`Sam3ClientTest` and `Sam3SegmentationProviderTest`, both on `MockWebServer` (localhost only):
- each route: correct path, method, auth header, and JSON body shape
- normalized coordinates are encoded as sent, not as pixels
- base64 alpha PNG decodes to an `ALPHA_8` bitmap of the advertised size
- every row of the §4 table maps to the stated `AppError`
- the §5 replay: 410 then success replays the prompt exactly once and the caller sees success;
  410 twice yields `Unavailable`
- `open` twice issues a `DELETE` for the first session
- an oversized bitmap is downscaled and re-compressed per §3 rather than failing
- `byText` returning `count: 0` yields an empty list, not a failure
- cancellation mid-call leaves no session leaked (a `DELETE` is still attempted)

`bench.sh` gains upload and prompt wall time against a `MockWebServer` with a fixed delay —
informational only, and it measures our encode/decode cost, not the model's.
