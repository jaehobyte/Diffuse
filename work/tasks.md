# tasks.md — Ralph loop task queue (v1.1 fixes + v2 on-device selection)

Completed v1 tasks (T01–T21) live in `tasks/v1_done.md`. Read it once per iteration to know what already exists; never re-implement anything listed there.

Rules for the agent: unchanged — see CLAUDE.md "Ralph loop rules". One task per iteration, `scripts/check.sh` is the only verdict, only edit checkboxes in this file.

Legend: `[ ]` todo · `[x]` done · `[!]` blocked · `[H]` human-only, loop must skip

---

## Phase 5 — v1.1 fixes (start immediately)

- [ ] T22 Reset to original
  spec: specs/editor_shell.md §Top bar (amend), history.md
  deps: —
  done when:
    - top bar gains a reset icon button between Redo and Compare (Material Symbols `history` or `restart_alt`)
    - tap → `history.push(doc.copy(operations = emptyList()))` with no coalesce key, so it is a single undoable step; **no confirmation dialog** (undo covers it)
    - disabled when `operations.isEmpty()`
    - after reset the canvas refits (dimensions may change if a Crop was removed)
    - UI test: reset then undo restores every operation; golden `editor_shell_default` updated (it is named here, so re-recording is allowed)
  touches: feature/editor, specs/editor_shell.md (append the one new row to the Top bar table only)

- [ ] T23 Crop preset aspect is wrong (16:9 renders as ~1:1)
  spec: specs/crop.md §Interaction, §Model
  deps: —
  done when:
    - **first** a failing test reproduces it: `CropGeometryTest.presetAspectMatchesInPixels` builds the preset rect on a 4000×3000 source and asserts `(rect.width × srcW) / (rect.height × srcH)` equals the preset within 0.5%, for all five presets and for both orientations
    - root cause fixed (likely: aspect enforced in normalized 0…1 space without multiplying by the source aspect; verify before assuming)
    - existing goldens `crop_1x1`, `crop_straighten_15`, `crop_overlay` still pass unchanged; if `crop_overlay` was recorded with the bug, it is named here and may be re-recorded once
  touches: feature/editor/tools/crop, core/imaging/ops (only if the render side is also wrong)

- [ ] T24 Live rotate / straighten preview in the crop tool
  spec: specs/crop.md §Interaction (amend), canvas.md
  deps: T23
  done when:
    - while the crop sheet is open, moving the straighten slider or tapping 90° rotates the image **immediately on the canvas** — no Apply needed
    - implementation: the canvas applies a rotation transform to the drawn bitmap about the image center (`overlayTransform` in `CanvasViewport`); **no re-render** through `Renderer` during the drag — this is a canvas-level transform for performance
    - the crop rect stays screen-fixed and auto-shrinks per crop.md while the image rotates under it
    - Apply commits `Crop(rect, angleDeg)` exactly as before; Cancel removes the transform
    - `CanvasTransformTest` gains a case for the rotation transform; goldens `crop_live_rotate_15`, `crop_live_rotate_90`
  touches: feature/editor/canvas, feature/editor/tools/crop, specs/crop.md (append to §Interaction only)

## Phase 6 — v2 prerequisites

- [H] T25 Human decisions and assets (loop must skip this task)
  - ADR-007: on-device segmentation with EdgeTAM via ExecuTorch (XNNPACK); Apache-2.0; add to architecture.md §10
  - ADR-008: model delivery — choose one: (a) bundle both `.pte` in `assets/` and raise the APK budget to 50MB, or (b) download on first use into `filesDir/models/` with a progress UI. Write the choice into specs/segmentation.md §Delivery
  - add `org.pytorch:executorch-android` to `libs.versions.toml`
  - place `edgetam_encoder_xnnpack_fp32.pte` and `edgetam_decoder_xnnpack_fp16.pte` under `fixtures/models/` for tests (git-lfs) and, if (a), under `app/src/main/assets/models/`
  - write `specs/ai_provider.md`, `specs/segmentation.md`, `specs/selection_tool.md`; extend `specs/edit_model.md` with `Operation.Mask` and `Operation.CutOut`
  - run the loop again only after these exist

## Phase 7 — Model layer

- [ ] T26 `core:ai` module and `SegmentationProvider` interface
  spec: specs/ai_provider.md, architecture.md §6
  deps: T25
  done when:
    - new module `core:ai` (depends on `core:common`, `core:imaging` for `ImageRef` only); `feature:editor` depends on it; dependency-guard updated
    - `interface SegmentationProvider { suspend fun prepare(image: Bitmap): Result<ImageEmbedding>; suspend fun segment(emb: ImageEmbedding, prompt: PointPrompt): Result<SegMask> }`
    - `PointPrompt(points: List<Point>, labels: List<Boolean>)` in normalized image coords; `SegMask(bitmap: Bitmap /* ALPHA_8, image size */, score: Float)`
    - `FakeSegmentationProvider`: returns a circle of radius 0.2 around the first point, deterministic
    - tests for the fake; Hilt binding chooses fake in tests, real in app
  touches: core/ai, settings.gradle.kts (only to add the module — this one exception is pre-approved), feature/editor build file

- [ ] T27 EdgeTAM runtime via ExecuTorch
  spec: specs/segmentation.md
  deps: T26
  done when:
    - `EdgeTamProvider : SegmentationProvider` loads the two `.pte` modules lazily on first `prepare`
    - `prepare`: letterbox-resize to 1024×1024, RGB/255 + ImageNet mean/std normalize, run encoder, keep the three feature tensors in `ImageEmbedding` (≈ 30MB; only one embedding cached at a time)
    - `segment`: map points to 1024-space pixel coords, run decoder, pick the mask with the highest IoU score, sigmoid > 0.5 → ALPHA_8, un-letterbox back to image size with bilinear upsample
    - both run on `Dispatchers.Default`; cancellable between encoder and decoder
    - `EdgeTamProviderTest` on Robolectric with the fixture models: a click on the red patch of `photo_512.png` yields a mask whose bounding box covers ≥ 80% of the patch and ≤ 5% of the gray patch
    - `scripts/bench.sh` gains: encoder time and decoder time on the 12MP fixture (informational)
  touches: core/ai/edgetam, scripts/bench.sh

- [ ] T28 Model delivery (per ADR-008)
  spec: specs/segmentation.md §Delivery
  deps: T27
  done when:
    - (a) assets: models resolved from `assets/models/`; APK size check in `check.sh` updated to the new budget
    - or (b) download: `ModelStore.ensure(): Flow<DownloadState>`; sheet shows progress and size before starting; verified by SHA-256 listed in the spec; failure → snackbar, tool stays disabled
    - either way, `SegmentationProvider` is unavailable (tool greyed with a reason) until models are present
  touches: core/ai, feature/editor/tools/select (availability state only)

## Phase 8 — Selection tool

- [ ] T29 `Operation.Mask` in the model and renderer
  spec: specs/edit_model.md (amended in T25), render.md
  deps: T25
  done when:
    - `Operation.Mask(id, maskRef: ImageRef /* ALPHA_8 PNG in project folder */, points: PointPrompt /* for re-editing */)`
    - a `Mask` op alone changes no pixels; the renderer exposes `resolveMask(doc, maskId): Bitmap?` for consumers
    - masks are saved by persistence as `mask_<id>.png`; round-trip test
    - at most one **active** mask per document (`EditDocument.activeMaskId`); older masks stay for undo
  touches: core/imaging/model, core/imaging/render, core/data

- [ ] T30 "선택" tool: tap-to-segment with darkened preview
  spec: specs/selection_tool.md, DESIGN.md §4
  deps: T26, T29, T24
  done when:
    - tool strip gains "선택" (icon `lasso_select` or similar) with the 6dp accent AI dot; opens a sheet with: [반전] [지우기] and Cancel/Apply
    - on opening: `prepare()` runs once with the progress overlay ("이미지를 분석하는 중"); canvas one-finger tap becomes a **foreground point**, long-press becomes a **background point** (`gestureMode = SelectPoint`)
    - every point → `segment()` → preview: outside the mask darkened (`#000000` 60% scrim), 1dp `accent` outline along the mask edge; points drawn as 8dp dots (accent fg, white bg)
    - each point push is a history entry; undo removes the last point and re-segments
    - Apply → writes `Operation.Mask` and sets `activeMaskId`; Cancel discards
    - works fully with `FakeSegmentationProvider` in tests; UI test covers add fg, add bg, undo, invert, apply
    - goldens: `select_sheet_open`, `select_mask_preview`
  touches: feature/editor/tools/select, feature/editor/canvas (gesture mode + mask overlay draw only)

- [ ] T31 Adjustments limited to the selection
  spec: specs/adjust_light.md / adjust_color.md / adjust_detail.md (amend: masked mode), render.md
  deps: T30
  done when:
    - when `activeMaskId != null`, every `AdjustSheet` shows a toggle "선택 영역에만" (default on); `Operation.Adjust` gains `maskId: String?`
    - renderer blends `out = lerp(in, adjusted, mask)`
    - goldens: `exposure_+0.5_masked` using a fixture mask covering the left half — right half must equal the input
    - existing unmasked goldens unchanged
  touches: core/imaging/ops, core/imaging/render, feature/editor/tools/*

- [ ] T32 Background removal from the selection
  spec: specs/selection_tool.md §CutOut
  deps: T30
  done when:
    - selection sheet gains a primary action "배경 지우기" (visible only when a mask exists)
    - `Operation.CutOut(maskId)`: alpha outside the mask → 0; `hasAlpha` becomes true; checkerboard shows
    - export auto-selects PNG (export.md rule already exists)
    - golden `cutout_render`; UI test: cutout → undo restores alpha
  touches: core/imaging/ops, feature/editor/tools/select, feature/export (only if the auto-PNG rule needs the new flag)

---

## Deferred
- D01 Layers · D02 Text · D03 GPU render (AGSL) · D04 Tablet · D05 Onboarding
- D06 Box prompt for selection (drag a rectangle) · D07 Inpaint with a generative model · D08 Video (EdgeTAM tracking)
