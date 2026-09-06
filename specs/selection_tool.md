# specs/selection_tool.md — "선택" tool

Owner tasks: T30 (tool), T31 (merging), T32 (masked adjustments), T33 (cut-out)
Module: `feature/editor/tools/select`, `feature/editor/canvas` (gesture mode + overlay)
Depends on: ai_provider.md, segmentation.md, prompt_input.md, edit_model.md, canvas.md, DESIGN.md §4

## 1. What the user sees
1. Tool strip "선택", with the 6dp accent AI dot. Greyed when `availability` is `Unavailable`;
   tapping it then shows a Korean snackbar with the reason.
2. Tapping it opens the sheet. `open()` runs once per editor session behind the progress overlay
   "이미지를 분석하는 중" with a cancel button (DESIGN.md §4 State display, §7).
3. Sheet contents, top to bottom: title "선택" (`headingLg`) → [추가 | 빼기] segmented toggle →
   [반전] [지우기] → the prompt bar (prompt_input.md) → pinned [취소 | 적용].
4. Tapping the photo adds a point and a mask appears: everything outside the selection darkened by a
   `#000000` 60% scrim, a 1dp `accent` outline along the edge, an 8dp dot where they tapped.
5. Typing or speaking a phrase segments every instance of it and merges the result the same way.
6. Undo removes the last merge. 적용 keeps the selection for other tools; 취소 discards it.

## 2. Gesture mode
While the sheet is open, `CanvasViewport.gestureMode = SelectPoint`:
- one-finger **tap** (< 200 ms, < 8dp movement) → foreground point
- one-finger **long-press** (≥ 400 ms) → background point
- one-finger **drag** → pan (unchanged), no point added
- two fingers → zoom / pan (unchanged)
- a tap outside the image rect is ignored

Points are stored normalized (0…1) in image coordinates via `LocalCanvasTransform`.

## 3. State
```kotlin
data class SelectionState(
    val session: SegSession?,        // null until open() completes
    val accumulated: Bitmap?,        // ALPHA_8, working-image size; null = nothing selected
    val mode: MergeMode,             // Add | Subtract, default Add
    val pointPrompt: PointPrompt?,   // the point run currently being refined; null after a mode switch
    val inverted: Boolean,
    val lowConfidence: Boolean,      // last result's score < 0.3
    val busy: Boolean,
    val hint: Hint?,                 // NotFound | LowConfidence | null
)
```
Lives in `EditorViewModel` as sheet state, not in the document, until 적용.

## 4. Merging (T31)
The tool owns exactly one accumulated mask. Every prompt — a point run or a phrase — produces one
incoming mask, which is merged into it:

```
Add       →  acc[i] = max(acc[i], new[i])
Subtract  →  acc[i] = min(acc[i], 255 - new[i])
```
Alpha stays strictly binary (0 or 255) at every step; nothing feathers in v2.

- **Points refine on the server.** Taps accumulate into one `PointPrompt` sent whole to `byPoints`,
  so foreground and background points behave the way SAM expects. The *result* of that run is what
  merges into `accumulated`.
- **Switching the [추가 | 빼기] mode starts a fresh point run** (`pointPrompt = null`). The two
  mechanisms — background points inside a run, and subtracting a whole result — must never fight
  over the same tap. Subtract mode taps therefore build a new positive selection which is then
  subtracted.
- **A phrase merges the union of its instances.** `byText` returns N masks; union them first, then
  merge that single mask by the current mode.
- 반전 flips `accumulated` only, never an individual result.
- 지우기 clears `accumulated` and `pointPrompt`, and keeps the session.
- Each merge is one entry on the tool's own deque. While the sheet is open the top-bar Undo/Redo
  drive that deque, not the document; on 적용 the deque is dropped.
- If a prompt is in flight, only the latest is queued — earlier pending prompts are dropped, not
  chained.

## 5. Canvas overlay (T30)
Drawn above the bitmap, inside the image rect, respecting zoom and pan:
- **scrim**: the full image rect at `#000000` 60%, with the mask cut out (`BlendMode.DstOut`
  against the accumulated mask)
- **outline**: `accent`, `1.dp / scale` so it stays 1dp on screen at any zoom; traced from the mask
  alpha and cached per mask instance
- **points**: 8dp circles, `accent` for foreground and `#FFFFFF` for background, 1dp `editHairline`
  stroke, fixed screen size
- while `busy`, the previous mask stays visible — no spinner on the canvas

## 6. Apply and cancel
- **적용**: write the accumulated mask (inverted if set) as `mask_<id>.png` (ALPHA_8, working
  resolution), push `Operation.Mask(id, maskRef)` as **one** history entry, set `activeMaskId`,
  close the sheet. Disabled while `accumulated` is null.
- **취소 / system back**: discard the selection state, keep the session so re-opening is instant.
- Leaving the editor closes the session (`close()`).

## 7. Hints
Non-blocking `bodySm` text under the buttons. Never a snackbar, never a dialog.
- score < 0.3 → "선택이 불확실해요. 점을 더 추가해보세요."
- `byText` returned nothing → "찾지 못했어요. 다른 단어로 해보세요."

## 8. Consumers
### 8.1 Masked adjustments (T32)
- When `activeMaskId != null`, every `AdjustSheet` shows a toggle "선택 영역에만" (default on).
  Off → the `Adjust` is written with `maskId = null`.
- Renderer: `out = lerp(in, op(in), maskAlpha)`.
- The active mask is drawn as the scrim while such a sheet is open with the toggle on, so the user
  sees where the change will land. Toggle off → scrim hidden.

### 8.2 Cut-out (T33)
- Primary pill "배경 지우기" in the selection sheet, visible only once a mask exists. It applies the
  mask exactly as 적용 does **and** pushes `Operation.CutOut(maskId)` in the same history entry,
  then closes the sheet.
- Renderer: `alpha = min(alpha, maskAlpha)` over the whole image.
- `EditDocument.hasAlpha` becomes true, so export auto-selects PNG by the rule already in export.md.
- Undo removes both ops as one step; redo restores both.

### 8.3 Generative erase (T38)
Specified separately in generative_erase.md. It consumes `activeMaskId` and nothing else from here.

## 9. Edge cases
- **The tool always prepares on the current rendered preview at working resolution**, not on the
  source, so masks align with what the user sees. Re-open the session when the operation list has
  changed in a way that changes geometry (any `Crop` added, removed, or edited) since the last
  `open()`.
- Process death mid-selection loses the selection; it is not persisted. Applied masks are.
- The T24 live-rotate transform is never active at the same time as `SelectPoint` — tools are
  exclusive.
- A second `Mask` op replaces `activeMaskId`; the older `Mask` stays in the list for undo.

## 10. Tests (fakes only, per ai_provider.md §6)
- `open()` runs once on first open and not again on the second open in the same editor session.
- Tap adds a foreground point and shows the scrim and the dot; long-press adds a background point
  (white dot); undo removes the last merge; 지우기 clears; 반전 toggles.
- `MaskMergeTest` (pure, no Android view layer): add then subtract on overlapping circles gives the
  expected coverage; invert is its own inverse; alpha stays binary; union of a `byText` result
  merges as one step.
- Mode switch clears the point run — a subtract-mode tap does not append a background point.
- 적용 pushes exactly one `Mask` op and sets `activeMaskId`; 취소 leaves the document untouched.
- Greyed tool when `availability = Unavailable` → snackbar.
- A prompt failing with `Unavailable` leaves `accumulated` untouched and shows a snackbar.
- Render goldens: `exposure_+0.5_masked` (left-half mask), `cutout_render` (circle mask on
  `photo_512.png`).
- Screenshot goldens: `select_sheet_open`, `select_mask_preview`, `select_mask_merged`,
  `select_low_confidence`, `select_prompt_result`.
- `MaskOutlineTest`: the outline path's bbox equals the mask bbox ± 1px.
