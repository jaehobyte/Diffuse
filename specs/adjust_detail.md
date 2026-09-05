# specs/adjust_detail.md — Detail tool

Owner task: T16
Module: `core/imaging/ops`, `feature/editor/tools/detail`

## Sheet
Title: `detail_title` ("디테일"). Two one-sided sliders, range 0…1, shown as 0…100:

| Slider | AdjustKind | Label key |
|---|---|---|
| 선명도 | Sharpen | `detail_sharpen` |
| 비네트 | Vignette | `detail_vignette` |

Same `AdjustSheet` mechanics; `zeroCentered = false`, so no center tick.

## Math
- Sharpen: unsharp mask. `out = in + amount × (in − blur(in))`, `amount = v × 1.5`, blur = 3×3 box at preview scale; at full-res scale the kernel radius by `fullLongEdge / previewLongEdge` rounded (max 5×5 in v1 for cost). Clamp.
- Vignette: `d = distance(pixel, center) / distance(corner, center)`; `w = smoothstep(0.7, 1.0, d)`; `rgb × 2^(−v × 0.6 × w)`.

## Performance
Sharpen is the only convolution in v1. Budget: preview render with Sharpen 1.0 stays < 100ms at 1080px (render.md). If it does not, reduce the kernel, do not skip the test.

## Tests
- Goldens: `sharpen_0.5`, `vignette_0.5`.
- Sharpen 0 and Vignette 0 → identity.
- Vignette 1.0: center pixel unchanged (±1), corner pixel darker.
- Sharpen on a flat gray image → identity (no ringing on flat areas).
- Golden: `detail_sheet_open`.
