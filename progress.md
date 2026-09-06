# progress.md

## Current

**T22-T24 complete.** `scripts/check.sh` green offline. The v1.1 fix phase is done.

**v2 was re-planned on 2026-09-06** (user decision). On-device EdgeTAM is dropped in favour of the
server-side SAM 3 service at `~/sam3-server`, and the backlog gains a prompt bar with voice input
and a generative eraser. `work/tasks.md` is now T26-T38 in four phases, with the human
prerequisites listed at the top of that file rather than as a task. ADR-007 and ADR-008 are struck
in architecture.md §10; ADR-009 and ADR-010 replace them.

Specs are written and consistent: `ai_provider.md`, `segmentation.md`, `selection_tool.md`
(rewritten), `prompt_input.md`, `generative_erase.md` (new), plus amendments to `edit_model.md`,
`architecture.md` (§2, §6, §8, §9, §10) and `DESIGN.md` (§1 accent ruling, §4 prompt bar).

**T26-T38 done — the whole v2 backlog is implemented and `scripts/check.sh` is green offline.**

One prerequisite is still open: `POST /v1/edit/erase` does not exist in `~/sam3-server` yet, so
the generative eraser has nothing real to talk to. Everything else runs against a running SAM 3
service once `sam3.baseUrl` / `sam3.token` are set (or entered in the in-app settings sheet).

## Done

- T38 Generative eraser — `Operation.GenerativeErase` carrying its own pixels, the renderer
  blending them through the mask, `erase_<id>.png` persistence, and a sheet-less 지우기 tool.
  `EraseController` owns the run→save→commit sequence. 12 tests + golden
  `generative_erase_render`.

- T37 `EraseProvider` — `Sam3EraseClient` posting image + mask + hint to `/v1/edit/erase` with
  a 60s read timeout, `Sam3EraseProvider` reusing segmentation's availability, and `MaskPng`.
  10 MockWebServer tests.

- T36 Prompt or speech → mask — the selection sheet hosts `VoicePromptBar`, a phrase runs
  `byText`, its instances union into one mask and merge by the current mode, and `count == 0`
  is the 찾지 못했어요 hint rather than an error. 9 tests + golden `select_prompt_result`.

- T35 Voice input — `SpeechInput` / `SpeechState`, `AndroidSpeechInput` over the OS recogniser
  in `ko-KR` with partial results, `FakeSpeechInput`, and `VoicePromptBar` owning the
  RECORD_AUDIO grant. 7 tests.

- T34 `PromptBar` — 48dp / 16dp radius / `editSurfaceRaised`, mic and send at 48dp hit areas,
  Korean placeholder, IME Done submitting the trimmed value, and the mic turning accent only
  while listening. 8 tests + goldens `prompt_bar_empty` / `_filled` / `_listening`.

- T33 Background removal — `Operation.CutOut`, `CutOutOp` doing `alpha = min(alpha, maskAlpha)`,
  `EditDocument.hasAlpha`, 배경 지우기 writing the mask and the cut-out as one history entry, and
  `ExportSettings.autoFormatFor` picking PNG. 13 tests + render golden `cutout_render`.

- T32 Masked adjustments — `Operation.Adjust.maskId`, one live Adjust per `(kind, maskId)`,
  `MaskBlend` doing `lerp(in, adjusted, maskAlpha)` in the renderer, the "선택 영역에만" switch on
  all three adjust sheets, and the scrim on the canvas while it is on. 12 tests + render golden
  `exposure_+0.5_masked`; every existing golden unchanged.

- T31 Accumulated merging — `MaskOps.merged/union`, a [추가 | 빼기] chip row, and an undo that
  drops a point inside a run and one whole merge once the run is empty. 12 tests + golden
  `select_mask_merged`; `select_sheet_open` re-recorded for the new row.

- T30 "선택" tool — `SelectionController` owns the whole tool (availability, session, points,
  mask, settings sheet); `EditorViewModel` only commits it. Canvas `gestureMode = SelectPoint`
  with normalized taps, `MaskOutline` tracing the scrim and the outline from one `Region` path,
  the AI dot and greyed-tool state in the strip, and the SAM 3 settings sheet T28 deferred.
  20 tests + goldens `select_sheet_open`, `select_mask_preview`.

- T29 `Operation.Mask` — the op, `EditDocument.activeMaskId` / `withMask` / `referencesResolve`,
  JSON, `MaskIo` (ALPHA_8 ↔ PNG), `Renderer.resolveMask` with a 2-entry cache, and
  `ProjectRepository.saveMask` writing `mask_<id>.png`. 21 tests.

- T28 `Sam3SegmentationProvider` — one live session plus the bytes that opened it, so §5's
  expiry replay (re-upload once, repeat the prompt) never reaches the caller. `Sam3Settings`
  on SharedPreferences with `local.properties` defaults through `:core:ai`'s own BuildConfig,
  `Sam3ImageCodec` (2048px, JPEG 90 → 75), and `AiModule`. 19 tests.

- T27 `Sam3Client` — OkHttp + kotlinx.serialization over the five SAM 3 routes, `Sam3Outcome`
  with `SessionExpired` as its own case, and `MaskCodec`. 17 MockWebServer tests, localhost only.

- T26 `core:ai` module — `SegmentationProvider` (`open`/`byPoints`/`byText`/`close`) and
  `EraseProvider` behind `Availability`, plus the two fakes in `src/testShared/kotlin` so
  `:feature:editor` tests can compile them. 15 tests. `settings.gradle.kts`, the dependencyGuard
  module map and `:feature:editor` all gained the `:core:ai` edge.

- T24 Live rotate / straighten preview — `OverlayTransform` in the canvas rotates the drawn
  bitmap about the image centre with no `Renderer` pass; quarter turns swap the fitted size.
  2 transform tests + goldens `crop_live_rotate_15` / `crop_live_rotate_90`.

- T23 Crop preset aspect — the geometry was right; `EditorRoute` fed it a constant 4:3.
  `CropState` now carries `sourceAspect` (from the bare-source preview) and flips it on odd
  quarter turns. `presetAspectMatchesInPixels` covers five presets x both orientations.

- T22 Reset to original — `RestartAlt` icon between Redo and Compare, `resetToOriginal()`
  as one uncoalesced history step, viewport zeroed so the canvas refits. 1 test +
  `editor_shell_default` re-recorded.

- T21 Navigation and polish — Hilt graph, Browse → Editor → Export sheet, autosave on
  back, destructive confirmation while exporting, predictive back.
- T20 Export — format/size/preset sheet, render→crop→downscale pipeline, MediaStore
  writer with IS_PENDING, progress overlay with cancel. 8 tests + 2 goldens.
- T19 Import from Photo Picker — `BrowseImport`, `BrowseRoute` with `PickVisualMedia`,
  40% scrim while decoding, `AppError` → Korean snackbar. 6 tests.
- T18 Browse home — staggered 2/3-column masonry, long-press actions with a destructive
  confirmation, Korean relative times, empty state. 7 tests + 4 goldens.
- T17 Project persistence — Room `projects` table with exported schema, atomic document
  writes, thumbnails, `ProjectAutosave` with a 2s debounce. 14 tests.
- T16 Detail adjustments — Sharpen (separable unsharp mask) and Vignette, `DetailSheet`;
  6 tests + 2 render goldens + `detail_sheet_open`. Every `AdjustKind` now has real maths.
- T15 Crop and rotate — rotate-then-crop render, `CropGeometry` auto-shrink, overlay with
  handles and thirds grid, preset/straighten/90° sheet. 15 tests + 4 goldens.

_T01–T14 trimmed per CLAUDE.md (keep the last 10). Their decisions are still in ## Decisions

Moved to `work/decisions.md`, one entry per task, newest first.

## Open issues for a human

- **The v2 tools have never run on a device.** Every test uses the fakes or MockWebServer.
  The *server* half was verified for real on 2026-09-06: `facebook/sam3` on the T4, a click
  returning 3 masks at 0.97, and `"parrot"` finding both birds in `photo_512.png` at 0.98. The
  app half cannot be checked on this machine — no `/dev/kvm`, no emulator package, no attached
  device — so it needs a phone or a workstation.

- **`POST /v1/edit/erase` returns 404**: it is not implemented in `~/sam3-server`. The
  generative eraser has nothing to talk to until it is.

- **The crop tool previews the *cropped* image, not the full source.** specs/crop.md says
  opening 자르기 refits to the un-cropped source; the ViewModel just renders the current
  document, so an existing Crop is baked into what the overlay sits on. Harmless until
  T24 made the rotation visible; the fix is to render the document minus its Crop while
  the sheet is open.

- **Compare in the editor route is not wired to the ViewModel.** `EditorScreen` owns the
  hold state and swaps to `source`, which the VM renders, but `onCompareChange` is a no-op
  at the route level.

- **specs/export.md asks for DataStore; T20 used `SharedPreferences`.** The catalog is
  frozen by CLAUDE.md and has no DataStore entry. Either add one and migrate
  `ExportSettingsStore`, or amend the spec.
- **The MediaStore write is not covered by a test.** `ImageStore` is an interface and the
  pipeline is tested through a fake; the `MediaStoreImageStore` implementation itself needs
  a device or a Robolectric shim that does not exist yet.

- **The release APK is over the 15 MB budget** (16.06 MB at T12; architecture.md §8 returned to
  15 MB when ADR-008 was struck, so this is live again). `isMinifyEnabled = false`, so R8 strips
  nothing. Enabling R8 is the obvious first move.

- **specs/render.md and architecture.md disagree on error style**: render.md throws, §9 mandates
  `Result` + `AppError`. architecture.md wins on conflict, so render.md is simply stale.
- **`photo_12mp.jpg` is 1.42 MB, not the "~3MB" testing.md §7 states** — 4000×3000 with EXIF
  orientation 6 as required, but upscaled from 768×512, so it compresses well.
- **`fixtures/`, `scripts/check.sh` and `core:common` are Phase 0 human deliverables**, so they
  sit outside the `touches` lists of the tasks that specify them.
