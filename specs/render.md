# specs/render.md — Render pipeline

Owner tasks: T10, T13–T16 (op math), T20 (export), T49 (in-order walk), T59 (GenerativeFill),
T63 (Outpaint)
Module: `core/imaging/render`

## Purpose
Turn an `EditDocument` into pixels: fast previews while editing, full resolution on export. Correctness is defined by golden image tests.

## API
```kotlin
interface Renderer {
    suspend fun preview(doc: EditDocument, targetLongEdgePx: Int): Bitmap
    suspend fun full(doc: EditDocument, onProgress: (Float) -> Unit = {}): Bitmap
}
```
Both run on `Dispatchers.Default` and check cancellation between operations.

## Pipeline order
1. Decode `source` at the requested size (preview) or full size.
2. **If `operations[0]` is an `Outpaint`** (T63): allocate the expanded canvas, draw its `resultRef` scaled to fill it, then draw the decoded source into the interior rect over the top, with an alpha ramp of `OUTPAINT_BLEND_PX` at the boundary. The original pixels therefore survive at full resolution and only the invented border comes from the model. See outpaint.md §4.
3. Walk `operations` **once, in list order** (T49), dispatching `Adjust`, `GenerativeErase`, `GenerativeFill` and `CutOut` as they are met. `Mask` and `Outpaint` contribute no pixels here.
4. Apply `Crop` last, regardless of list position, so adjustments are visible inside the crop. Saved order is not changed.

## Operation math (v1, CPU; all in one `Ops.kt`)
- Exposure: RGB × `2^(value × 2)`.
- Contrast: `(c − 0.5) × (1 + value) + 0.5`.
- Highlights / Shadows: luminance-masked exposure; mask = smoothstep on luma (highlights above 0.6, shadows below 0.4), strength `value × 1.0` EV.
- Temperature: R += `value × 0.1`, B −= `value × 0.1`. Tint: G −= `value × 0.1`.
- Saturation: `ColorMatrix.setSaturation(1 + value)`.
- Vibrance: saturation boost weighted by `(1 − currentSaturation)`.
- Sharpen: unsharp mask, radius 1px at preview scale (scaled proportionally at full res), amount `value`.
- Vignette: radial darkening from 70% radius to corners, max `value × 0.6` EV.
- Crop: rotate by `angleDeg` about center, then crop `rect`; expand canvas as needed so no black corners inside the rect (the crop tool guarantees the rect stays inside the rotated image).
- `GenerativeErase` and `GenerativeFill`: `out = lerp(in, result, maskAlpha)`. One composite each, no per-pixel math; the two share a branch shape and differ only in which file they load.
- `Outpaint`: the composite in Pipeline order step 2. It is not an in-list operation and has no math here.

Every op clamps to `[0, 1]` per channel. Keep the math in `Ops.kt` so it can be ported — the AGSL backend is specs/gpu_render.md (T66), which is gated on a minSdk bump and a benchmark number and takes these goldens as its judge.

## Caching
- Preview cache keyed by `(doc.source, doc.operations, targetLongEdgePx)`, 3 entries.
  The source belongs in the key: the `Renderer` is a singleton shared by every
  project, and two documents with the same operations — an empty list, for every
  freshly imported project — otherwise collide and serve each other's pixels.
- Base decode cache keyed by `(source, size)`, 2 entries.
- A new preview request cancels the in-flight one for the same document.

## Performance budget
- `preview`, 4096px source → 1080px target: < 100ms p50 on a Pixel 6a-class device. CI benchmark asserts < 250ms (emulator).
- `full`, 4096px with 8 ops: < 2s; progress reported at least once per op.

## Edge cases
- Cancelled mid-render: throws `CancellationException`; no partial bitmap escapes.
- Missing source file: `RenderException.MissingSource`; caller shows a snackbar.
- Preview target larger than source: never upscale; return source size.

## Tests
- Golden image per `AdjustKind` at +0.5 and −0.5 (Sharpen/Vignette at 0.5 only). Tolerance 2/255 per channel, 99.9% of pixels. **This line predates the 24 HSL kinds; adjust_hsl.md §10 is the rule for those**, and the eight goldens it names are the coverage there rather than 48 more files.
- `generative_fill_render` and `outpaint_render`, plus the outpaint case asserting the interior is the **source's** pixels well inside the ramp (outpaint.md §8).
- Crop 1:1 and straighten 15° goldens.
- Order test: Exposure→Contrast ≠ Contrast→Exposure.
- Cancellation test.
- Benchmark excluded from `check`, run via `scripts/bench.sh`.
