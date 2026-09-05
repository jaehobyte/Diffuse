# specs/selection_tool.md — "선택" tool (tap-to-segment)

Owner tasks: T29 (model), T30 (tool), T31 (masked adjustments), T32 (cut-out)
Module: `feature/editor/tools/select`, `feature/editor/canvas` (gesture mode + overlay)
Depends on: ai_provider.md, segmentation.md, edit_model.md (Mask, CutOut), canvas.md, DESIGN.md §4

## 1. What the user sees
1. Tool strip "선택" (AI dot). Greyed when `availability` is `Unavailable`; tapping a greyed tool shows a snackbar with the reason.
2. Tap → progress overlay "이미지를 분석하는 중" while `prepare()` runs (first time per editor session). Sheet opens: title "선택", buttons row [반전] [지우기], and — once a mask exists — a primary pill "배경 지우기". Bottom row Cancel / Apply.
3. Tap on the photo → a mask appears within ~100ms: everything outside it darkened by a `#000000` 60% scrim, a 1dp `accent` outline on the mask edge, an 8dp accent dot where they tapped.
4. Tap again elsewhere → mask grows to include it. Long-press → white dot, mask excludes that region.
5. Undo removes the last point. Apply keeps the selection for other tools; Cancel discards it.

## 2. Gesture mode
While the sheet is open, `CanvasViewport.gestureMode = SelectPoint`:
- one-finger **tap** (< 200ms, < 8dp movement) → foreground point
- one-finger **long-press** (≥ 400ms) → background point
- one-finger **drag** → pan (unchanged), no point added
- two fingers → zoom/pan (unchanged)
Points are stored normalized (0…1) in image coordinates via `LocalCanvasTransform`.

## 3. State
```kotlin
data class SelectionState(
    val embedding: ImageEmbedding?,       // null until prepare completes
    val points: PointPrompt?,             // null = nothing selected yet
    val mask: SegMask?,                   // latest result
    val inverted: Boolean,
    val lowConfidence: Boolean,           // mask.score < 0.3
    val busy: Boolean,                    // segment in flight
)
```
Lives in `EditorViewModel` as part of the sheet state, not in the document, until Apply.

## 4. Behavior
- Each new point: `history.push(doc /* unchanged */, coalesceKey = "select")`? **No.** Points are not document operations before Apply. The tool keeps its own `pointHistory: ArrayDeque<PointPrompt>`; the top-bar Undo/Redo, while this sheet is open, operate on that deque (buttons relabelled by state only, no visual change). On Apply the deque is dropped.
- After each point change → `segment()` with the full prompt (all points). Conflate: if a segment is in flight, queue only the latest prompt.
- 반전: flips `inverted`; the preview scrim and outline swap sides. Applied at Apply time by inverting the alpha.
- 지우기: clears points and mask; embedding kept.
- Apply: write `mask_<id>.png` (ALPHA_8, inverted if set), push `Operation.Mask(id, ref, points)` to history (one entry), set `activeMaskId = id`, close the sheet. If no mask → Apply is disabled.
- Cancel / system back: discard state, keep embedding until the editor is left (so re-opening is instant).
- Low confidence: show a `bodySm` hint under the buttons: "선택이 불확실해요. 점을 더 추가해보세요." No blocking.

## 5. Canvas overlay (T30)
Drawn above the bitmap, inside the image rect, respecting zoom/pan:
- scrim: full image rect at `#000000` 60%, with the mask **cut out** (`BlendMode.DstOut` against the mask bitmap)
- outline: `accent`, 1dp regardless of zoom (`stroke = 1.dp / scale`), traced from the mask via `Path` from the alpha edge (Marching squares is overkill — use `Bitmap.extractAlpha` + `Path` from `Region` boundary; cache per mask)
- points: 8dp circles, `accent` for fg, `#FFFFFF` for bg, 1dp `editHairline` stroke, fixed screen size
- while `busy`, the previous mask stays visible; no spinner on the canvas

## 6. Consumers
### 6.1 Masked adjustments (T31)
- When `activeMaskId != null`, `AdjustSheet` shows a switch "선택 영역에만" (default on). Off → the `Adjust` gets `maskId = null`.
- `Operation.Adjust.maskId: String?`; renderer: `out = lerp(in, op(in), maskAlpha)`.
- The active mask is shown on the canvas as the scrim while an adjustment sheet with the toggle on is open, so the user sees where it will apply. Toggle off → scrim hidden.

### 6.2 Cut-out (T32)
- Primary pill "배경 지우기" in the selection sheet (enabled when a mask exists). Tapping it applies the mask (as Apply does) **and** pushes `Operation.CutOut(maskId)` in the same history entry, then closes the sheet.
- Renderer: `alpha = min(alpha, maskAlpha)` for the whole image. `SourceImage.hasAlpha` for export purposes becomes true if any `CutOut` exists (`EditDocument.hasAlpha` computed property).
- Undo removes both ops (one entry). Redo restores both.

## 7. Edge cases
- Image changed since `prepare` (crop applied, then selection opened): the tool always prepares on the **current rendered preview at working resolution**, not the source, so masks align with what the user sees. Re-prepare when `operations` containing `Crop` changed since the last embedding.
- Tap outside the image rect → ignored.
- Process death mid-selection → selection lost (not persisted). Applied masks are persisted (`mask_<id>.png`).
- Rotation of the canvas transform (T24 live rotate) is never active at the same time as `SelectPoint`; tools are exclusive.

## 8. Tests (all with `FakeSegmentationProvider` except where noted)
- UI: open tool → prepare called once; second open → not called again.
- UI: tap adds fg point and shows scrim + dot; long-press adds bg point (white dot); undo removes the last point; 지우기 clears; 반전 toggles; Apply pushes exactly one `Mask` op and sets `activeMaskId`; Cancel leaves the document untouched.
- UI: greyed tool when `availability = Unavailable` → snackbar.
- Render goldens: `exposure_+0.5_masked` (left-half mask), `cutout_render` (circle mask on `photo_512.png`).
- Screenshot goldens: `select_sheet_open`, `select_mask_preview` (fake circle mask), `select_low_confidence`.
- `MaskOutlineTest`: outline path bbox equals mask bbox ± 1px.
