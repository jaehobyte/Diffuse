# specs/edit_model.md — Non-destructive edit model

Owner tasks: T08, T13–T16 (ops), T17 (persistence)
Module: `core/imaging/model`

## Purpose
Represent an edit session as source + ordered operations so any state can be re-rendered, undone, serialized, and exported at full resolution.

## Types
```kotlin
data class EditDocument(
    val id: String,
    val source: ImageRef,
    val operations: List<Operation>,
    val createdAt: Long,
    val updatedAt: Long,
)

sealed interface Operation {
    val id: String
    data class Adjust(override val id: String, val kind: AdjustKind, val value: Float) : Operation
    data class Crop(override val id: String, val rect: RectF /* normalized 0..1 */, val angleDeg: Float) : Operation
}

enum class AdjustKind {
    Exposure, Contrast, Highlights, Shadows,      // Light
    Temperature, Tint, Saturation, Vibrance,      // Color
    Sharpen, Vignette                             // Detail
}

@JvmInline value class ImageRef(val path: String)   // file in app storage
```
`Operation` is a sealed interface so v2 can add `Mask` and `AiResult` without touching existing ops. Do not pre-add them.

## Rules
- Value ranges: zero-centered kinds (Exposure … Vibrance) in `[-1, 1]`; Sharpen and Vignette in `[0, 1]`. 0 is neutral for every kind. The renderer maps to real math.
- **One live `Adjust` per `AdjustKind`**: setting a kind that already exists updates that entry in place (same list position). History still records the change.
- At most one `Crop`. A new crop replaces the old one.
- `Crop.rect` and `angleDeg` are relative to the **un-cropped, un-rotated source**, so re-opening the crop tool shows the current crop on the full image.
- Removing an `Adjust` (value back to 0) deletes the entry rather than storing a no-op.

## Serialization
- kotlinx.serialization JSON, root field `v: Int = 1`.
- Unknown operation types or `AdjustKind` names are dropped with a log warning; the document still loads.

## Edge cases
- Empty `operations` is valid.
- `Crop` with `rect = 0,0,1,1` and `angleDeg = 0` is a no-op and is deleted like a zero Adjust.

## Tests
- Round-trip equality with every `AdjustKind` and a `Crop`.
- In-place update keeps list position.
- Zero-value Adjust is removed; full-frame Crop is removed.
- Unknown kind in JSON is dropped, document loads.
