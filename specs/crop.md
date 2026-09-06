# specs/crop.md — Crop & rotate tool

Owner tasks: T15, T24 (live rotation preview), T58 (the planner's `crop_ratio`)
Module: `core/imaging/ops` (Crop op), `feature/editor/tools/crop` (overlay + sheet)
Depends on: canvas.md (overlay slot, `LocalCanvasTransform`), edit_model.md (`Crop` op), render.md

## What the user sees
- Tool strip "자르기" → canvas refits to show the **full un-cropped source**, and a crop overlay appears: dimmed area outside the rect (`#000000` 50%), rect border 1dp `editInk`, corner handles 24dp L-shapes, rule-of-thirds grid inside while dragging.
- Sheet (max 45%): preset chips row [자유, 1:1, 4:5, 9:16, 16:9], a zero-centered straighten slider −45…45° (shown as degrees, 1 decimal), and two icon buttons: 90° left, 90° right. Cancel / Apply.

## Interaction
- Drag a corner: resizes keeping the opposite corner fixed. With a preset aspect, the rect stays locked to that ratio.
- Drag an edge: resizes one axis (Free) or both (locked aspect).
- Drag inside the rect: moves it. Drag outside: pans the canvas (overlay does not consume).
- Straighten: rotates the image under the fixed rect; the rect is auto-shrunk (aspect preserved) so it stays fully inside the rotated image — no empty corners, ever.
- 90° buttons: rotate the whole source; rect rotates with it; aspect preset swaps (4:5 → 5:4 is displayed as the same chip).
- Min rect size: 10% of the short edge.
- Straighten and the 90° buttons preview **live on the canvas**, with no `Renderer` pass: the canvas rotates the drawn bitmap about the image centre (quarter turns swap the fitted size, the straighten rotates inside the unchanged bounds, matching `CropOp`). The rect stays where it is on screen; Apply commits `Crop(rect, angleDeg)` as before and Cancel drops the transform with the sheet.

## Opened by the planner (T58)
vibe_edit.md §4.1's `crop_ratio` step commits a centred rect at one of the four preset ratios and
then opens this tool with that chip selected and the committed rect loaded — the ordinary
"re-opening the tool shows the existing crop" path, entered from a plan instead of a tap.

Nothing here changes for it. The rect comes from `CropGeometry.applyPreset`, which the chips already
call, so the planner writes **no geometry of its own**; the preset enum on the wire maps onto
`AspectPreset` at the `feature:editor` boundary. `Free` is not one of the wire values: a model
choosing "자유" would be choosing nothing.

## Model
`Operation.Crop(rect: RectF normalized to un-rotated source, angleDeg: Float)` — `angleDeg` includes 90° steps + straighten (e.g. 105°). One Crop max; Apply replaces it.
- Apply with `rect = (0,0,1,1)` and `angle = 0` → Crop removed.
- Re-opening the tool shows the existing crop.

## Render
Per render.md: rotate about center, then crop `rect`. Crop is applied after Adjusts in the pipeline.

## Tests
- `CropGeometryTest`: auto-shrink keeps the rect inside for angles −45, 15, 45 on a 4:3 source; presets keep aspect within 0.5%.
- Goldens (render): `crop_1x1`, `crop_straighten_15`.
- Goldens (UI): `crop_overlay`, `crop_sheet_open`.
- UI: dragging a corner past the min size clamps; 90° rotate twice then Apply → `angleDeg = 180`.
