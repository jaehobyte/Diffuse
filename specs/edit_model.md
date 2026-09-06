# specs/edit_model.md — Non-destructive edit model

Owner tasks: T08, T13–T16 (ops), T17 (persistence), T29–T33 (v2 Mask/CutOut), T38 (GenerativeErase),
T59 (GenerativeFill), T63 (Outpaint)
Module: `core/imaging/model`

## Purpose
Represent an edit session as source + ordered operations so any state can be re-rendered, undone, serialized, and exported at full resolution.

## Types
```kotlin
data class EditDocument(
    val id: String,
    val source: ImageRef,
    val operations: List<Operation>,
    val activeMaskId: String? = null,   // v2: the Mask op other tools apply to
    val createdAt: Long,
    val updatedAt: Long,
)

sealed interface Operation {
    val id: String
    data class Adjust(override val id: String, val kind: AdjustKind, val value: Float, val maskId: String? = null) : Operation
    data class Crop(override val id: String, val rect: RectF /* normalized 0..1 */, val angleDeg: Float) : Operation

    // v2 (T29, T33, T38)
    data class Mask(override val id: String, val maskRef: ImageRef /* ALPHA_8 PNG, working-resolution size */) : Operation
    data class CutOut(override val id: String, val maskId: String) : Operation
    data class GenerativeErase(override val id: String, val maskId: String, val resultRef: ImageRef /* PNG, working-resolution size */) : Operation

    // v3 (T59, T63) — see generative_fill.md and outpaint.md
    data class GenerativeFill(override val id: String, val maskId: String, val resultRef: ImageRef, val prompt: String) : Operation
    data class Outpaint(override val id: String, val margins: Margins, val resultRef: ImageRef /* the whole expanded image */) : Operation
}

/** Fractions of the source's width/height added on each side; each in 0f..0.5f (outpaint.md §3). */
data class Margins(val left: Float, val top: Float, val right: Float, val bottom: Float)

enum class AdjustKind {
    Exposure, Contrast, Highlights, Shadows,      // Light
    Temperature, Tint, Saturation, Vibrance,      // Color
    Sharpen, Vignette                             // Detail
}

@JvmInline value class ImageRef(val path: String)   // file in app storage
```
`Operation` is a sealed interface; v2 added `Mask`, `CutOut`, and `GenerativeErase`, and v3 adds `GenerativeFill` and `Outpaint`. Future ops (`Text`) follow the same pattern. **The JSON root `v` stays 1 through all of it**: adding an operation type is backward compatible, because an older build drops what it does not recognise and still loads the document (see Serialization).

`Mask` stores only the resulting alpha, **not** the prompts that produced it. A v2 selection is
built by merging point runs and text phrases (selection_tool.md §4), so no single prompt reproduces
it; storing one would be a lie the re-edit path would then have to honour. Re-opening the selection
tool starts a fresh selection.

## Rules
- Value ranges: zero-centered kinds (Exposure … Vibrance) in `[-1, 1]`; Sharpen and Vignette in `[0, 1]`. 0 is neutral for every kind. The renderer maps to real math.
- **One live `Adjust` per `AdjustKind`**: setting a kind that already exists updates that entry in place (same list position). History still records the change.
- At most one `Crop`. A new crop replaces the old one.
- `Crop.rect` and `angleDeg` are relative to the **un-cropped, un-rotated source**, so re-opening the crop tool shows the current crop on the full image.
- Removing an `Adjust` (value back to 0) deletes the entry rather than storing a no-op.
- **One live `Adjust` per `(AdjustKind, maskId)` pair** (v2): a masked Exposure and an unmasked Exposure may coexist.
- `Mask` ops change no pixels by themselves. `activeMaskId` must reference an existing `Mask` op or be null; undo that removes the referenced `Mask` also resets `activeMaskId` (the history snapshot carries both).
- `CutOut.maskId` must reference an existing `Mask` op. Multiple `CutOut`s are allowed; each further restricts alpha.
- `GenerativeErase.maskId` must reference an existing `Mask` op. It carries its own pixels: the renderer blends `out = lerp(in, result, maskAlpha)`, so it stays composable with every op after it and undo is still a single removal. See generative_erase.md §6.
- `GenerativeFill` follows `GenerativeErase` exactly — same `maskId` validation, same blend, same file lifetime — and adds `prompt`. It is stored because one prompt reproduces this result exactly, which is not true of `Mask` (see the note above); it is display and provenance data and is never re-sent automatically. See generative_fill.md §5.
- **At most one `Outpaint`, and it is always `operations[0]`.** `withOutpaint` inserts at index 0 and replaces any existing one. It is the only op that changes the canvas *size*, so everything after it measures against the expanded canvas and nothing has to ask which era it came from. Committing one **re-normalizes an existing `Crop.rect`** into the expanded space (pure arithmetic on `margins`, outpaint.md §3) and is **refused while any `Mask`, `CutOut`, `GenerativeErase` or `GenerativeFill` op exists**, because those carry pixels or alpha sized to the un-extended canvas. See outpaint.md §3.
- Ops referencing a file (`Mask.maskRef`, `GenerativeErase.resultRef`, `GenerativeFill.resultRef`, `Outpaint.resultRef`) keep that file alive; the file store deletes it only when no op in any history entry references it.
- `EditDocument.hasAlpha` (computed): `source.hasAlpha || operations.any { it is CutOut }`.

## Serialization
- kotlinx.serialization JSON, root field `v: Int = 1`.
- Unknown operation types or `AdjustKind` names are dropped with a log warning; the document still loads.

## Edge cases
- Empty `operations` is valid.
- `Crop` with `rect = 0,0,1,1` and `angleDeg = 0` is a no-op and is deleted like a zero Adjust.

## Tests
- Round-trip equality with every `AdjustKind`, a `Crop`, a `Mask`, a `CutOut`, a `GenerativeErase`, a `GenerativeFill` carrying a prompt, an `Outpaint`, and a masked `Adjust`.
- `activeMaskId`, `CutOut.maskId`, `GenerativeErase.maskId`, or `GenerativeFill.maskId` pointing to a missing op fails validation on load (`Unsupported`).
- A second `withOutpaint` replaces rather than appends and stays at index 0; committing one re-normalizes an existing `Crop`; `withOutpaint` on a document holding a mask-bearing op is refused.
- In-place update keeps list position.
- Zero-value Adjust is removed; full-frame Crop is removed.
- Unknown kind in JSON is dropped, document loads.
