# specs/adjust_light.md — Light tool

Owner task: T13
Module: `core/imaging/ops` (math), `feature/editor/tools/light` (sheet)
Depends on: render.md §Operation math, edit_model.md, history.md, DESIGN.md §4 Slider/Bottom sheet

## Sheet
Title: `light_title` ("빛"). Four `AdjustSlider`s in this order, each zero-centered, range −1…1, step 0.01, displayed as −100…100 integer:

| Slider | AdjustKind | Label key |
|---|---|---|
| 노출 | Exposure | `light_exposure` |
| 대비 | Contrast | `light_contrast` |
| 하이라이트 | Highlights | `light_highlights` |
| 그림자 | Shadows | `light_shadows` |

## Behavior
- Slider `onChange` → `history.push(doc.withAdjust(kind, value), coalesceKey = "adjust:$kind")` → conflated preview render.
- `onChangeFinished` → `history.commitCoalesce()`.
- Double-tap on a slider → value 0 → the `Adjust` entry is removed (edit_model.md rule).
- Opening the sheet shows current values from the document, not zeros.
- Cancel/Apply per editor_shell.md sheet lifecycle.

## Math (implemented in `Ops.kt`, referenced by render.md)
- Exposure: `rgb × 2^(v × 2)`
- Contrast: `(c − 0.5) × (1 + v) + 0.5`
- Highlights: `rgb × 2^(v × w_h)` where `w_h = smoothstep(0.6, 1.0, luma)`
- Shadows: `rgb × 2^(v × w_s)` where `w_s = 1 − smoothstep(0.0, 0.4, luma)`
- luma = `0.2126R + 0.7152G + 0.0722B` in linear 0…1. Clamp per channel.

## Tests
- Goldens: `exposure_+0.5`, `exposure_-0.5`, `contrast_+0.5`, `contrast_-0.5`, `highlights_+0.5`, `highlights_-0.5`, `shadows_+0.5`, `shadows_-0.5` (fixture `photo_512.png`, tolerance per testing.md §4).
- Property test: value 0 → output identical to input for every kind.
- Highlights +0.5 must not change a pixel with luma 0.2 by more than 1/255.
- UI: drag → one history entry after release; opening the sheet shows persisted values; double-tap resets.
- Golden: `light_sheet_open`.
