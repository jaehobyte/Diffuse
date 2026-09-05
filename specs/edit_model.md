# specs/edit_model.md — Non-destructive edit model

Owner tasks: T08, T13–T16 (ops), T17 (persistence), T29–T32 (v2 Mask/CutOut)
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

    // v2 (T29–T32)
    data class Mask(override val id: String, val maskRef: ImageRef /* ALPHA_8 PNG, working-resolution size */, val points: PointPrompt) : Operation
    data class CutOut(override val id: String, val maskId: String) : Operation
}

enum class AdjustKind {
    Exposure, Contrast, Highlights, Shadows,      // Light
    Temperature, Tint, Saturation, Vibrance,      // Color
    Sharpen, Vignette                             // Detail
}

@JvmInline value class ImageRef(val path: String)   // file in app storage
```
`Operation` is a sealed interface; v2 added `Mask` and `CutOut`. Future ops (`AiResult`, `Text`) follow the same pattern.

## Rules
- Value ranges: zero-centered kinds (Exposure … Vibrance) in `[-1, 1]`; Sharpen and Vignette in `[0, 1]`. 0 is neutral for every kind. The renderer maps to real math.
- **One live `Adjust` per `AdjustKind`**: setting a kind that already exists updates that entry in place (same list position). History still records the change.
- At most one `Crop`. A new crop replaces the old one.
- `Crop.rect` and `angleDeg` are relative to the **un-cropped, un-rotated source**, so re-opening the crop tool shows the current crop on the full image.
- Removing an `Adjust` (value back to 0) deletes the entry rather than storing a no-op.
- **One live `Adjust` per `(AdjustKind, maskId)` pair** (v2): a masked Exposure and an unmasked Exposure may coexist.
- `Mask` ops change no pixels by themselves. `activeMaskId` must reference an existing `Mask` op or be null; undo that removes the referenced `Mask` also resets `activeMaskId` (the history snapshot carries both).
- `CutOut.maskId` must reference an existing `Mask` op. Multiple `CutOut`s are allowed; each further restricts alpha.
- `EditDocument.hasAlpha` (computed): `source.hasAlpha || operations.any { it is CutOut }`.

## Serialization
- kotlinx.serialization JSON, root field `v: Int = 1`.
- Unknown operation types or `AdjustKind` names are dropped with a log warning; the document still loads.

## Edge cases
- Empty `operations` is valid.
- `Crop` with `rect = 0,0,1,1` and `angleDeg = 0` is a no-op and is deleted like a zero Adjust.

## Tests
- Round-trip equality with every `AdjustKind`, a `Crop`, a `Mask`, a `CutOut`, and a masked `Adjust`.
- `activeMaskId` pointing to a missing op fails validation on load (`Unsupported`).
- In-place update keeps list position.
- Zero-value Adjust is removed; full-frame Crop is removed.
- Unknown kind in JSON is dropped, document loads.
