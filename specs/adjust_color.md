# specs/adjust_color.md — Color tool

Owner task: T14
Module: `core/imaging/ops`, `feature/editor/tools/color`
Depends on: adjust_light.md (same sheet mechanics), render.md

## Sheet
Title: `color_title` ("색"). Four zero-centered sliders, −1…1, shown as −100…100:

| Slider | AdjustKind | Label key |
|---|---|---|
| 온도 | Temperature | `color_temperature` |
| 색조 | Tint | `color_tint` |
| 채도 | Saturation | `color_saturation` |
| 생동감 | Vibrance | `color_vibrance` |

Behavior identical to adjust_light.md (coalesce, reset, restore). The sheet composable is the same generic `AdjustSheet(kinds)`; do not duplicate it.

## Math
- Temperature: `R += v × 0.1`, `B −= v × 0.1`
- Tint: `G −= v × 0.1`
- Saturation: `ColorMatrix.setSaturation(1 + v)` (v = −1 → grayscale)
- Vibrance: per pixel `s = max(r,g,b) − min(r,g,b)`; apply saturation `1 + v × (1 − s)` — low-saturation pixels move more, already-saturated ones barely.

## Tests
- Goldens: `temperature_±0.5`, `tint_±0.5`, `saturation_±0.5`, `vibrance_±0.5`.
- Saturation −1 → every output pixel has R = G = B (±1).
- Vibrance +0.5 changes the saturated-red patch by less than the gray-ish skin patch (asserts the weighting).
- Golden: `color_sheet_open`.
