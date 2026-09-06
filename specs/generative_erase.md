# specs/generative_erase.md — Generative eraser

Owner tasks: T37 (`EraseProvider` and the proxy client), T38 (the tool)
Modules: `core/ai/erase`, `feature/editor/tools/erase`, `core/imaging` (op + render), `core/data`
Decisions: ADR-010 (generative editing through the sam3-server proxy)
Depends on: ai_provider.md, selection_tool.md, edit_model.md

## 1. What it does
Removes whatever the active selection covers and fills the hole with plausible surroundings. The
user picks a region with the 선택 tool, switches to 지우기, and taps once.

## 2. Why a proxy, not a direct Gemini call
The model is `gemini-2.5-flash-image` ("nano banana"), and it needs an API key. A key shipped in an
APK is extractable by anyone who downloads it, so the app never holds one. Instead `~/sam3-server`
— which the app already talks to and already authenticates against — exposes one endpoint and keeps
`GEMINI_KEY` in its own environment.

The app's entire knowledge of generative editing is therefore:

```
POST /v1/edit/erase        Authorization: Bearer <the same SAM 3 token>
  multipart/form-data
    image  — the working image, JPEG
    mask   — PNG at the image's size whose **alpha channel** carries the mask; opaque pixels
             are the region to erase. (`Bitmap.compress` cannot write ALPHA_8 usefully, so an
             ARGB PNG with the mask in its alpha is what actually goes on the wire — the same
             shape core:imaging's MaskIo writes to disk.)
    hint   — optional short phrase (text field)
  → 200 image/png, the edited image at the uploaded size
```
Error bodies and status codes follow the same table as every other `/v1/` route
(segmentation.md §4). The upstream contract lives in `~/sam3-server/specs/api.md`; that file wins.

## 3. Why the mask is painted, not passed through
`gemini-2.5-flash-image` has no mask parameter. The server composites before it calls the model: it
paints the mask region a neutral mid-grey over the original and asks the model to fill the painted
area from its surroundings. One image in, one image out — unambiguous about *what* to remove, and
it avoids asking the model to reason about the relationship between two separate images.

This is the server's business. The app sends the original and the mask and never learns how the
prompt is built. If the server later switches to a different model or technique, no Android code
changes.

## 4. `EraseProvider` (T37)
Interface in ai_provider.md §3. `Sam3EraseProvider` implements it over `Sam3EraseClient`:
- 60 s read timeout — generation is slow, and this is the one call where that is expected.
- Cancellable: cancelling closes the call and the partial response is discarded.
- Errors map exactly as segmentation.md §4 does. There is no session and therefore no 410 path.
- `availability` mirrors `Sam3SegmentationProvider`'s: the same base URL and token, the same
  `/healthz` probe. A separate probe would be a second reason for the same answer.
- `FakeEraseProvider` fills the mask region with the mean colour of a 4px band just outside it —
  deterministic, so `generative_erase_render` is a stable golden.

## 5. The tool (T38)
- Tool strip "지우기", with the 6dp accent AI dot.
- Disabled, with a snackbar reason on tap, when `activeMaskId == null` ("먼저 영역을 선택해주세요.")
  or `availability` is `Unavailable`.
- No sheet. Tapping the tool runs it: the progress overlay shows "지우는 중" in `accent` with a
  cancel button (DESIGN.md §4 State display, §7).
- Cancelling leaves the document byte-for-byte untouched.
- On success the tool closes and the canvas shows the result. Failure → Korean snackbar; the
  selection survives so the user can retry.

## 6. Staying non-destructive
The document model is source + operations, and a generative result is new pixels — the one place
where those two ideas meet. The result becomes an operation that *carries* its pixels:

```kotlin
data class GenerativeErase(
    override val id: String,
    val maskId: String,
    val resultRef: ImageRef,   // erase_<id>.png in the project folder, working-resolution size
) : Operation
```
Renderer: `out = lerp(in, result, maskAlpha)`.

Consequences, all of which fall out for free:
- Pixels outside the mask keep their original values, so a later crop or adjustment still composes.
- Undo is a single op removal. Redo restores it.
- Ops added *after* the erase apply on top of it, in list order, like any other op.
- Autosave, persistence, history, and export take no special case — only the file store learns to
  keep `erase_<id>.png` alive as long as the op references it.
- Multiple erases stack. Each references its own mask.

`GenerativeErase.maskId` must reference an existing `Mask` op, validated on load like `CutOut.maskId`.

## 7. Resolution
The erase runs at working resolution (≤ 4096, and ≤ 2048 after the upload downscale in
segmentation.md §3). The result is scaled back to the working size bilinearly. **Export therefore
does not re-run the model** — it composites the stored result, exactly as the preview does. Running
generation again at export time would produce different pixels than the user approved.

## 8. Tests
- `Sam3EraseClientTest` on `MockWebServer`: multipart field names and order, auth header, PNG
  decoding, timeout, 503, cancellation. Localhost only.
- `FakeEraseProvider` determinism: the same input twice gives identical bytes.
- Round-trip: a document with a `GenerativeErase` serializes, loads, and re-renders identically;
  `erase_<id>.png` survives autosave and is deleted when the op is garbage.
- Validation: `maskId` pointing at a missing op fails load with `Unsupported`.
- UI: disabled without a selection, with the right snackbar; run → canvas updates and one history
  entry is pushed; undo restores; cancel mid-flight is a no-op.
- Render golden `generative_erase_render` (fake provider, circle mask on `photo_512.png`).
- The pixels outside the mask are asserted **equal to the input**, not merely similar.
