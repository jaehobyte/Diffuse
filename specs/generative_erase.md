# specs/generative_erase.md — Generative eraser

Owner tasks: T39 (the key), T40 (`GeminiEraseClient`), T41 (`WhiteFill`), T42 (`GeminiEraseProvider`),
T43 (tool copy), T50–T51 (the margin and the instruction rewrite)
Modules: `core/ai/gemini`, `feature/editor/tools/erase`, `core/imaging` (op + render — already built)
Decisions: ADR-011 (the device calls Gemini directly). **Supersedes ADR-010**, the sam3-server proxy.
Depends on: ai_provider.md, segmentation.md, selection_tool.md, edit_model.md

This replaces the T37/T38 proxy design. §9 (the tool) and §10 (the document model) are carried over
from it unchanged and are already implemented; everything between the tool and the wire is new.
§13 lists exactly what T42 deletes.

## 1. What it does
Removes whatever the active selection covers and fills the hole with plausible surroundings. The
user picks a region with the 선택 tool, switches to 지우기, and taps once.

## 2. Where the model runs, and why that changed
The model is `gemini-2.5-flash-image` ("nano banana"). ADR-010 put the call on `~/sam3-server` so
that no API key would ever sit in the app. That endpoint was never implemented — `POST
/v1/edit/erase` returns 404 today — and the proxy bought less than it looked like it did: the key
still had to live somewhere, and the server half became a second codebase to keep in step with a
feature that is entirely an Android feature.

ADR-011 moves the call onto the device and pays for it by **never shipping a key at all**:

- No `GEMINI_KEY` in `BuildConfig`, in `local.properties`, or in any committed file. A published
  APK contains no credential, so decompiling it yields nothing.
- The key is typed into the settings sheet at runtime and persisted in `SharedPreferences`,
  exactly as the SAM 3 token already is (segmentation.md §6). Same trust model, same storage, one
  more field.
- The `.env` at the repo root is a **human's notepad**, not a build input. Nothing in Gradle reads
  it. Its `GEMINI_KEY` line is the value a developer pastes into the sheet, and the file already
  says as much about the SAM 3 values sitting next to it.

Honest cost: each user brings their own key, and quota and billing follow that key. On a rooted
device or through an unlocked `adb backup`, `SharedPreferences` is plaintext — the same exposure the
SAM 3 token has carried since T28, and the same one accepted then.

`~/sam3-server` keeps doing exactly one job: segmentation. It needs no change for this feature and
`POST /v1/edit/erase` is struck from the prerequisites.

## 3. The flow

```
선택 tool  ─ SAM 3, over HTTP ─▶  SegMask (ALPHA_8, working size, binary)
지우기 tap
  └─ GeminiEraseProvider                                    (all of this on the device)
       1. downscale image + mask to ≤ 1024 long edge        §7
       2. paint every masked pixel #FFFFFF over a copy      §4  ← "하얗게"
       3. JPEG q90 → base64
       4. POST …/gemini-2.5-flash-image:generateContent     §5
       5. decode the returned image, scale to working size  §7
  └─ Operation.GenerativeErase(maskId, resultRef)           §10, already built
  └─ Renderer: out = lerp(in, result, maskAlpha)            §10, already built
```

The provider is the only layer that knows any of this. `EraseProvider.erase(image, mask, hint)` is
unchanged from ai_provider.md §3, so `EraseController`, `FakeEraseProvider` and the
`generative_erase_render` golden all survive the swap untouched.

## 4. Painting the hole white (T41)
`gemini-2.5-flash-image` takes images and text. It has **no mask parameter**, so the mask has to be
expressed in the pixels themselves. The app composites before it sends:

```kotlin
internal object WhiteFill {
    /** [mask] is ALPHA_8 at [image]'s size. Returns a new ARGB_8888 copy; [image] is untouched. */
    fun apply(image: Bitmap, mask: Bitmap): Bitmap
}
```
- Every pixel whose mask alpha is non-zero becomes opaque `#FFFFFFFF`. Everything else is copied
  verbatim.
- The mask is binary (segmentation.md §1), so there is no partial blend and no feathering. A soft
  edge would hand the model an ambiguous boundary, which is the opposite of the point.
- Implemented with `Canvas.drawBitmap(mask, …)` under a `PorterDuff.SRC_IN` paint filled white, or
  a plain pixel loop — whichever reads more simply. It runs once per erase on a ≤ 1024px bitmap, so
  it is not a hot path.

**Why white and not mid-grey.** ADR-010's server painted mid-grey. White is the stronger signal: it
is out of gamut for almost every natural scene, so the model cannot mistake it for content it should
preserve, and a flat maximum-luminance patch is the most legible "this is a hole" cue the format
offers. The one photo where this is weakest — a white wall, snow, an overexposed sky — is also the
easiest possible inpainting job, so the failure mode is benign.

It lives in `core/ai/gemini` rather than `core:imaging`: it is a detail of how one provider talks to
one model, not a rendering operation. `core:ai` reaches into `core:imaging` for `AdjustKind` and
nothing else (ai_provider.md §2), and a pixel operation is exactly the "nothing else".

**`WhiteFill` is shared, not copied.** 채우기 sends the identical whitened image and differs only in
the instruction (generative_fill.md §2), and 확대 whitens a *border* rather than a region through a
sibling object, `WhitePad` (outpaint.md §5). Three features, one idea: the mask is expressed in the
pixels because the model has no mask parameter. A second implementation of white-painting in any of
them is a signal the design drifted.

## 5. The Gemini call (T40)
`GeminiEraseClient`, OkHttp + kotlinx.serialization. One class, one request shape.

```
POST {baseUrl}/v1beta/models/gemini-2.5-flash-image:generateContent
  x-goog-api-key: <the key from GeminiSettings>
  Content-Type: application/json
```
```json
{
  "contents": [{
    "role": "user",
    "parts": [
      { "inlineData": { "mimeType": "image/jpeg", "data": "<base64 of the whitened image>" } },
      { "text": "<the instruction below>" }
    ]
  }],
  "generationConfig": { "responseModalities": ["IMAGE"] }
}
```

- **The key goes in a header, never a query parameter.** URLs end up in logs, crash reports and
  `MockWebServer` recorded requests; headers are easier to keep out of all three.
- `baseUrl` is injectable and defaults to `https://generativelanguage.googleapis.com`. Tests point
  it at `MockWebServer` on localhost; nothing else about the client changes between the two.
- camelCase (`inlineData`, `mimeType`, `generationConfig`) on both the request and the response.
  The API accepts snake_case on input but answers in camelCase, and one convention is cheaper than
  two.
- Timeouts: connect 10 s, read 60 s. Generation is slow and this is the one call where that is
  expected. Cancellable — cancelling closes the call and discards the partial response.

**The instruction is a code constant, in English, and is not user-facing.** DESIGN.md §9's "user
strings are Korean and live in `strings.xml`" governs what a person reads; this is wire payload.
English because the model's instruction following is most reliable there.

```
The image contains a solid pure-white region. Replace that region with photorealistic content
that continues the surrounding scene: match its lighting, texture, perspective, focus and grain
so the result looks like a single unedited photograph. Do not introduce any new object, person,
text or watermark. Do not alter anything outside the white region. Return only the edited image.
```
When `hint` is non-blank, one sentence is appended saying the thing was **removed** and must not be
drawn again (T51 rewrote it; the earlier wording could be read as an instruction to paint the thing
back in). `EraseController` passes `null`; `PlanRunner` passes the most recent `Select` phrase.

**The instruction is supplied by the caller, not owned by the client (T60).** `GeminiEraseClient`
exposes `edit(image, instruction)` and `GeminiEraseProvider` passes `ERASE_INSTRUCTION`; the fill
and outpaint providers pass their own (generative_fill.md §3, outpaint.md §5). One class knows how
to talk to `gemini-2.5-flash-image`; three providers know what to ask it for. D10 is no longer
deferred — it is generative_fill.md.

**Reading the response.** `candidates[0].content.parts` is scanned for the first part carrying
`inlineData`; its `data` is base64 and its `mimeType` is `image/png` or `image/jpeg`. Text parts
are ignored, not treated as an error — the model is allowed to narrate.

## 6. Error mapping
Onto the `AppError` cases in architecture.md §9. **No new case is added.** The error body is
`{"error": {"code": …, "message": …, "status": …}}`.

| Condition | `AppError` |
|---|---|
| `400 INVALID_ARGUMENT` | `Invalid(message)` |
| `400 FAILED_PRECONDITION` (billing/region) | `Unavailable` |
| `401`, `403` — `UNAUTHENTICATED` / `PERMISSION_DENIED` | `Unauthorized` |
| `404 NOT_FOUND` (model name retired) | `Unsupported` |
| `413`, or the encoder cannot fit the request | `TooLarge` |
| `429 RESOURCE_EXHAUSTED` | `Unavailable` |
| `500 INTERNAL`, `503 UNAVAILABLE`, `504 DEADLINE_EXCEEDED` | `Unavailable` |
| `200` with `promptFeedback.blockReason`, or `finishReason` `SAFETY` / `PROHIBITED_CONTENT` / `IMAGE_SAFETY` | `Invalid("blocked:<reason>")` |
| `200` with no `inlineData` part | `Unsupported` |
| transport, or the returned bytes do not decode | `Io(cause)` |

`429` maps to `Unavailable` rather than sleeping on `Retry-After`, matching segmentation.md §4:
the UI shows a snackbar and the user decides whether to retry.

`message` goes into the log and into `Invalid`, never into a user-facing string.

A safety block is deliberately **not** a new `AppError`. It is an `Invalid` with a recognizable
`detail` prefix, which is all §9's contract needs; the tool tells the two apart in §9 below.

## 7. `GeminiEraseProvider` (T42)
```kotlin
@Singleton
class GeminiEraseProvider @Inject internal constructor(
    private val client: GeminiEraseClient,
    private val settings: GeminiSettings,
) : EraseProvider
```

`erase(image, mask, hint)`:
1. `image.size != mask.size` → `Invalid("mask must be the image's size")`. Same guard the proxy had.
2. `GeminiImageCodec.encode` — downscale the longest edge to **1024**, JPEG quality 90, falling back
   to 75 once if the body would exceed 20 MB, then `TooLarge`. The mask is scaled to the same size
   with **nearest neighbour**, because it is binary and must stay binary.
3. `WhiteFill.apply` on the downscaled pair (§4), then compress that.
4. Call §5.
5. Decode, convert to `ARGB_8888`, and scale to the caller's image size **bilinearly**. Return it.

All of it on `DispatcherProvider.io`, with `ensureActive()` before the network call.

**Why 1024 and not segmentation's 2048.** `gemini-2.5-flash-image` returns roughly a megapixel
regardless of what it is given, so sending 2048 only pays to have the model downsample it, and we
would then upscale from its output anyway. Matching the model's own working size keeps one
resampling step out of the round trip.

`availability` is derived from `settings.config`, with **no probe**:

```
key is blank      → Unavailable(Invalid("no api key"))
key is present    → Ready
```
Probing Gemini is not free — the cheapest useful call is a real generation, billed to the user's
key. So availability answers "is this configured", and reachability is discovered by the one call
the user actually asked for. This is a deliberate departure from segmentation.md §7, where
`/healthz` is free.

## 8. The key (T39)
`GeminiSettings`, shaped exactly like `Sam3Settings`:

```kotlin
data class GeminiConfig(val apiKey: String, val baseUrl: String = DEFAULT_BASE_URL)

@Singleton class GeminiSettings @Inject constructor(@ApplicationContext context: Context) {
    val config: StateFlow<GeminiConfig>
    fun update(apiKey: String)
}
```
- `SharedPreferences`, file `gemini_settings`, key `api_key`. Not DataStore — the version catalog
  has no entry and CLAUDE.md freezes it, the same reason `ExportSettingsStore` and `Sam3Settings`
  gave.
- Default is the **empty string**. There is no `BuildConfig` field, no `local.properties` key, and
  no read of `.env` (§2).
- `baseUrl` is not user-editable. It is a constant with a test seam, not a setting; a text field
  for it would be a way to send the user's key to an arbitrary host.
- Separate from `Sam3Settings` — different host, different credential, different lifetime. One
  class holding both would let a SAM 3 change invalidate a Gemini key.

**The sheet.** `Sam3SettingsSheet` gains a third field, `Gemini API 키`, and its title becomes
`서버 설정`. The app has no settings screen, and a second sheet for one field would be worse than
one sheet with three. The 지우기 tool opens the same sheet when the key is missing, the way the
선택 tool already does when the base URL is missing. The key field uses
`PasswordVisualTransformation`, so a shoulder-surfer and a screenshot see dots.

No golden covers this sheet today (`feature/editor/src/test/screenshots` has no entry for it), so
adding the field re-records nothing.

## 9. The tool (T43)
Carried over from T38 and already built. Only the copy changes.

- Tool strip "지우기", with the 6dp accent AI dot (DESIGN.md §4).
- No sheet. Tapping runs it: the progress overlay shows `erase_working` ("지우는 중") in `accent`
  with a cancel button (DESIGN.md §4 State display, §7).
- Cancelling leaves the document byte-for-byte untouched.
- Disabled with a snackbar reason on tap. The reason is now specific:

| State | String | Action |
|---|---|---|
| `activeMaskId == null` | `erase_needs_selection` 먼저 영역을 선택해주세요 | — |
| `availability` is `Unavailable(Invalid)` — no key | `erase_needs_key` 설정에서 Gemini API 키를 입력해주세요 | opens the 서버 설정 sheet |
| failure, `detail` starts with `blocked:` | `erase_blocked` 이 이미지는 편집할 수 없어요 | — |
| any other failure | `erase_failed` 지우지 못했어요 | — |

On success the canvas shows the result. On failure the selection survives, so the user can retry
without re-selecting.

## 10. Staying non-destructive (already built — do not re-implement)
The document model is source + operations, and a generative result is new pixels. The result is an
operation that *carries* its pixels:

```kotlin
data class GenerativeErase(
    override val id: String,
    val maskId: String,
    val resultRef: ImageRef,   // erase_<id>.png in the project folder, working-resolution size
) : Operation
```
Renderer: `out = lerp(in, result, maskAlpha)`.

- Pixels outside the mask keep their original values, so a later crop or adjustment still composes.
- Undo is a single op removal. Redo restores it.
- Ops added after the erase apply on top of it, in list order.
- Autosave, persistence, history and export take no special case; only the file store keeps
  `erase_<id>.png` alive as long as the op references it.
- Multiple erases stack, each referencing its own mask.
- `maskId` must reference an existing `Mask` op, validated on load like `CutOut.maskId`.

## 11. Resolution, and what the model is allowed to change
The erase runs at ≤ 1024 (§7) and the result is scaled back to working resolution. **Export does not
re-run the model** — it composites the stored `erase_<id>.png`, exactly as the preview does. Running
generation again at export time would produce different pixels than the user approved.

`gemini-2.5-flash-image` regenerates the whole frame; the bytes it returns differ from the input
*everywhere*, not only inside the hole. That is fine and is why §10's blend is the right shape: the
renderer takes result pixels **only where the mask is opaque**, so every pixel outside the selection
is still the original, bit for bit. The existing test asserting that stays valid and becomes the
load-bearing guarantee of this design rather than a formality.

Known risk, accepted: if the model ever returns a different aspect ratio, the bilinear scale-back
shifts content *inside* the erased region. Nothing outside it moves, and the model preserves aspect
in practice, so no guard is written. If it starts happening, the fix is a letterbox-aware
scale-back, not a wider tolerance.

## 12. Tests
No test contacts an external host; `GeminiEraseClient`'s `baseUrl` seam is what makes that true.

- `WhiteFillTest`: masked pixels are exactly `0xFFFFFFFF`; unmasked pixels equal the input; the
  input bitmap is not mutated; an all-clear mask returns a pixel-identical copy.
- `GeminiEraseClientTest` on `MockWebServer`: the request path and method; `x-goog-api-key` present
  and the key absent from the URL; the JSON body shape (one `inlineData` part, one `text` part,
  `responseModalities`); a base64 image part decodes; text-only parts are skipped to reach the image
  part; every row of §6; cancellation mid-flight closes the call.
- `GeminiImageCodecTest`: a 4096px bitmap comes back at 1024 on the long edge with its aspect
  preserved; the mask is scaled nearest-neighbour and stays strictly binary.
- `GeminiEraseProviderTest`: a mismatched mask size fails with `Invalid` before any request; the
  bytes on the wire are the *whitened* image, not the original (assert a sampled masked pixel is
  white after decoding the request body); the result is returned at the caller's size; availability
  flips with the key.
- `FakeEraseProvider` determinism: unchanged, still the basis of the golden.
- Round-trip, validation, UI behaviour and the `generative_erase_render` golden: **unchanged from
  T38**. They must pass without edits; needing to touch one means the swap reached further than §13.

## 13. What T42 deletes
Exactly three files, all of them the proxy transport:

```
core/ai/src/main/kotlin/com/diffuse/core/ai/erase/Sam3EraseClient.kt
core/ai/src/main/kotlin/com/diffuse/core/ai/erase/Sam3EraseProvider.kt
core/ai/src/test/kotlin/com/diffuse/core/ai/erase/Sam3EraseClientTest.kt
```
`MaskPng.kt` is the fourth candidate: the Gemini path sends no mask over the wire, so it is
expected to become unreferenced and to go with the three above. If something outside `erase/` still
uses it, move it to `gemini/` instead of deleting it. It is not shared with `core:imaging`'s
`MaskIo`.

Everything else stays: `EraseProvider`, `FakeEraseProvider`, `MaskBitmaps`, `EraseController`,
`Operation.GenerativeErase`, the renderer blend, `erase_<id>.png` persistence, the strings, and
every golden.
