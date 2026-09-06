# progress.md

## Current

**T40 — `GeminiEraseClient`, the HTTP layer.**
1. `GeminiDto.kt`: camelCase request/response shapes, `explicitNulls = false` so a null part is
   simply absent → verify: the body-shape test.
2. `GeminiEraseClient`: POST `{baseUrl}/v1beta/models/gemini-2.5-flash-image:generateContent`,
   key in `x-goog-api-key`, connect 10s / read 60s, cancellable, on `dispatchers.io`.
3. The instruction is an `internal` English constant; the hint sentence appends only when set.
4. §6's table verbatim, existing `AppError` cases only; a block is `Invalid("blocked:<reason>")`.
5. Verify: `GeminiEraseClientTest` on `MockWebServer`, then `scripts/check.sh` green.

## Done

- T38 Generative eraser — `Operation.GenerativeErase` carrying its own pixels, the renderer
  blending them through the mask, `erase_<id>.png` persistence, and a sheet-less 지우기 tool.
  `EraseController` owns the run→save→commit sequence. 12 tests + golden
  `generative_erase_render`.

- T37 `EraseProvider` — `Sam3EraseClient` posting image + mask + hint to `/v1/edit/erase` with
  a 60s read timeout, `Sam3EraseProvider` reusing segmentation's availability, and `MaskPng`.
  10 MockWebServer tests. **Superseded by ADR-011**: T42 deletes all three of those files. The
  `EraseProvider` interface it declared stays.

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

_T01–T28 trimmed per CLAUDE.md (keep the last 10)._

## Decisions

Moved to `work/decisions.md`, one entry per task, newest first.

## Open issues for a human

- **The v2 tools have never run on a device.** Every test uses the fakes or MockWebServer.
  The *server* half was verified for real on 2026-09-06: `facebook/sam3` on the T4, a click
  returning 3 masks at 0.97, and `"parrot"` finding both birds in `photo_512.png` at 0.98. The
  app half cannot be checked on this machine — no `/dev/kvm`, no emulator package, no attached
  device — so it needs a phone or a workstation.

- **The eraser needs a Gemini API key entered on the device.** No key is shipped, committed, or
  read from `.env` at build time (ADR-011, generative_erase.md §2), so until someone pastes one
  into the 서버 설정 sheet the 지우기 tool reports itself unavailable. `check` is unaffected — every
  test uses the fake or `MockWebServer`.

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
