# tasks.md — Ralph loop task queue (v1: traditional editor, no AI)

Rules for the agent:
- Work top to bottom. Pick the first unchecked task whose dependencies are all checked.
- One task per iteration. Do not start the next task in the same iteration.
- A task is done only when every line under `done when` is true and `scripts/check.sh` exits 0.
- Commit with message `T<NN>: <title>` on success. On failure, revert uncommitted changes.
- Only modify paths listed under `touches`. If you need to change something else, stop and write to `blocked.md`.
- After each iteration, update `progress.md`.
- If the same task fails 3 iterations in a row, mark it `[!]`, log it in `blocked.md`, and move to the next task.
- Only the checkbox of a task may be edited in this file. Never edit task text.

Legend: `[ ]` todo · `[x]` done · `[!]` blocked · `deps` = task ids that must be `[x]` first

---

## Phase 0 — Scaffold & safety net (done by a human before the loop starts)

- [x] T01 Project skeleton compiles
  spec: specs/architecture.md
  deps: —
  done when:
    - modules exist: `app`, `core:ui`, `core:imaging`, `core:data`, `feature:browse`, `feature:editor`, `feature:export`
    - `scripts/check.sh` exits 0
    - `app` launches to an empty Compose screen
  touches: root gradle files, all module `build.gradle.kts`, `settings.gradle.kts`

- [x] T02 Design tokens and theme
  spec: DESIGN.md §2–3, §6
  deps: T01
  done when:
    - `core/ui/theme/Tokens.kt` defines every token in DESIGN.md §2 as a `Color` and §3 as a `TextStyle`
    - `AppTheme(mode = Browse | Edit)` switches light/dark palettes
    - `TokensTest` asserts hex values match DESIGN.md
  touches: core/ui

- [x] T03 Screenshot test harness
  spec: specs/testing.md
  deps: T02
  done when:
    - Roborazzi configured; `verifyRoborazziDebug` runs inside `scripts/check.sh`
    - one golden exists: `theme_swatches` rendering all tokens in both modes
    - `scripts/check.sh` runs lint, detekt, unit tests, screenshot verification, exits non-zero on any failure
  touches: core/ui, scripts/, root gradle

## Phase 1 — Canvas

- [x] T04 Image loading pipeline
  spec: specs/imaging.md
  deps: T01
  done when:
    - `ImageLoader.load(uri): Result<SourceImage>` decodes off the main thread, downsamples to max 4096px on the long edge
    - EXIF orientation applied
    - OOM path returns `Failure(TooLarge)` instead of crashing
    - unit test with the 6000×4000 fixture verifies downsampling
  touches: core/imaging/load

- [x] T05 Canvas composable with fit/zoom/pan
  spec: specs/canvas.md, DESIGN.md §5, §8
  deps: T02, T04
  done when:
    - `EditorCanvas` renders a bitmap centered with ≥16dp `editBackground` margin
    - pinch zoom (0.5×–8× of fit), one- or two-finger pan, double-tap toggles fit ↔ 2×
    - transparency shown as 8dp checkerboard using `canvasCheckerA/B`
    - `CanvasGestureTest` covers zoom clamp, pan clamp, double-tap
    - goldens: `canvas_fit`, `canvas_zoomed`, `canvas_transparent`
  touches: feature/editor/canvas

- [x] T06 Editor screen shell
  spec: specs/editor_shell.md, DESIGN.md §4 (Top bar, Tool strip)
  deps: T05
  done when:
    - layout: top bar 56dp / canvas / tool strip 72dp
    - top bar: back, undo, redo, compare, export pill (wired to no-op or nav)
    - tool strip with Light / Color / Crop / Detail, selected tool shows accent indicator
    - edge-to-edge, status bar color = `editSurface`
    - golden: `editor_shell_default`
  touches: feature/editor

- [x] T07 Bottom sheet component
  spec: DESIGN.md §4 (Bottom sheet)
  deps: T02
  done when:
    - `EditSheet(title, content, onCancel, onApply)` with 24dp top corners, drag handle, max height 45%
    - Cancel/Apply pinned at the bottom, Apply is primary pill
    - goldens: `sheet_expanded`, `sheet_collapsed`
  touches: core/ui/components

## Phase 2 — Edit engine

- [x] T08 Non-destructive edit model
  spec: specs/edit_model.md
  deps: T04
  done when:
    - `EditDocument` = source + ordered `List<Operation>`
    - `Operation` sealed interface with `Adjust(kind, value)` and `Crop(rect, angleDeg)`
    - JSON round-trip test passes; unknown op types are dropped, not crashed
  touches: core/imaging/model

- [x] T09 Undo / redo history
  spec: specs/history.md
  deps: T08
  done when:
    - `HistoryStack` with push/undo/redo, capped at 50
    - coalesces rapid pushes with the same key into one entry
    - `HistoryStackTest` covers cap, coalesce, redo invalidation
    - top bar undo/redo enabled state reflects the stack
  touches: core/imaging/history, feature/editor

- [x] T10 Render pipeline with preview cache
  spec: specs/render.md
  deps: T08
  done when:
    - `Renderer.preview` at canvas resolution and `Renderer.full` at source resolution
    - runs on `Dispatchers.Default`, cancellable between ops
    - preview cache (3 entries) and base decode cache (2 entries)
    - benchmark test present (excluded from `check`, run via `scripts/bench.sh`)
  touches: core/imaging/render, scripts/

- [ ] T11 Compare gesture
  spec: specs/editor_shell.md
  deps: T09, T10
  done when:
    - holding the compare button shows the source; release returns to the preview
    - disabled when the document has no operations
    - UI test verifies both states
  touches: feature/editor

## Phase 3 — Adjustment tools

- [ ] T12 Slider component
  spec: DESIGN.md §4 (Slider)
  deps: T02
  done when:
    - `AdjustSlider(value, range, zeroCentered, onChange, onChangeFinished)`: 4dp track, 20dp thumb, value pinned right in mono, center tick when zero-centered
    - double-tap resets to default
    - goldens: `slider_default`, `slider_zero_centered`
  touches: core/ui/components

- [ ] T13 Light adjustments
  spec: specs/adjust_light.md, specs/render.md
  deps: T07, T09, T10, T12
  done when:
    - Exposure, Contrast, Highlights, Shadows implemented as `Adjust` ops
    - golden image test per kind at +0.5 and −0.5 (tolerance 2/255, 99.9% pixels)
    - "Light" sheet with four sliders; slider drag coalesces into one history entry
  touches: core/imaging/ops, feature/editor/tools/light

- [ ] T14 Color adjustments
  spec: specs/adjust_color.md, specs/render.md
  deps: T13
  done when:
    - Temperature, Tint, Saturation, Vibrance as `Adjust` ops with golden image tests
    - "Color" sheet opens from the tool strip
  touches: core/imaging/ops, feature/editor/tools/color

- [ ] T15 Crop and rotate
  spec: specs/crop.md
  deps: T07, T09, T10
  done when:
    - crop overlay with draggable corners/edges, aspect presets (Free, 1:1, 4:5, 9:16, 16:9)
    - rotate 90° steps and straighten (−45°…45°) with a slider
    - `Crop` op renders correctly; goldens for 1:1 crop and 15° straighten
    - golden: `crop_overlay`
  touches: core/imaging/ops, feature/editor/tools/crop

- [ ] T16 Detail adjustments
  spec: specs/adjust_detail.md, specs/render.md
  deps: T13
  done when:
    - Sharpen (0…1) and Vignette (0…1) as `Adjust` ops with golden image tests
    - "Detail" sheet opens from the tool strip
  touches: core/imaging/ops, feature/editor/tools/detail

## Phase 4 — Browse, persistence, export

- [ ] T17 Project persistence
  spec: specs/persistence.md
  deps: T08
  done when:
    - `EditDocument` saved as JSON + copied source + 512px thumbnail in app storage
    - Room table: id, createdAt, updatedAt, thumbnailPath
    - autosave 2s after the last operation; leaving without changes creates no project
    - DAO tests pass
  touches: core/data

- [ ] T18 Browse home (masonry grid)
  spec: specs/browse.md, DESIGN.md §4 (Image tile), §5
  deps: T02, T17
  done when:
    - 2-column masonry of thumbnails, 8dp gap, 16dp side padding; 3 columns at 600dp+
    - no text on tiles; updated-at below in `bodySm`
    - long-press reveals delete / duplicate as `iconCircle`
    - empty state uses `headingXl` and a primary pill
    - goldens: `browse_grid`, `browse_empty`
  touches: feature/browse

- [ ] T19 Import from Photo Picker
  spec: specs/browse.md
  deps: T18, T04
  done when:
    - `PickVisualMedia` opens from the primary CTA; result creates a project and navigates to the editor
    - unsupported formats show a snackbar
  touches: feature/browse, app/navigation

- [ ] T20 Export
  spec: specs/export.md, DESIGN.md §4 (Bottom sheet)
  deps: T10, T07
  done when:
    - export sheet: format (JPEG 92 / PNG), size (Original / 2048 / 1080), presets (4:5, 9:16)
    - full-resolution render with progress overlay (40% scrim, accent circular progress, cancel)
    - saved via MediaStore to Pictures/<AppName>; success snackbar with an "open" action
    - UI test verifies a file with the expected dimensions is written
  touches: feature/export, core/imaging/render

- [ ] T21 Navigation and polish
  spec: specs/architecture.md
  deps: T19, T20
  done when:
    - graph: Browse → Editor → Export sheet; back from editor autosaves
    - back with an export running shows a destructive confirmation
    - predictive back enabled
    - all goldens re-verified, `scripts/check.sh` exits 0
  touches: app/navigation, feature/*

---

## v2 — AI (do not start; needs specs and a human decision on on-device vs cloud)

- V01 `AiProvider` interface + `FakeAiProvider`
- V02 Mask brush tool (one-finger paint, feeds AI ops)
- V03 AI job overlay (progress, cancel, failure snackbar)
- V04 Background removal → `Operation.AiResult`
- V05 Inpaint (mask + fill)
- V06 Upscale 2×
- V07 Remote provider behind `USE_REMOTE_AI` flag

## Deferred
- D01 Layers panel · D02 Text tool · D03 GPU render (AGSL) · D04 Tablet layout · D05 Onboarding
