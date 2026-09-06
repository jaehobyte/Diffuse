# progress.md

## Current

**T44 done. Next: T45 `GeminiPlanClient`** — the function-calling layer (specs/vibe_edit.md §4–§6).

## Done

- T44 `EditPlanProvider` — `PlanStep` (`Select` / `Adjust` / `Erase` / `CutOut`), `EditPlan` and
  the provider interface in `core/ai`; `Adjust` carries `AdjustKind`, which makes
  `core:ai → core:imaging` a real build edge for the first time (one line, root file untouched).
  `FakePlanProvider` with ai_provider.md §6's default plan. 4 tests.

- T43 지우기 tells the user which thing is missing — `EraseTap` (Run / OpenSettings / Refused),
  `erase_needs_key` opening the 서버 설정 sheet the way 선택 does, a `blocked:` detail showing
  `erase_blocked`, and the selection surviving every failure. 7 new tests, one per §9 row.

- T42 `GeminiEraseProvider` — `GeminiImageCodec` (1024 long edge, q90 → 75, 20 MB cap, the mask
  nearest-neighbour), §7's five steps on `dispatchers.io`, probe-free availability off the key,
  and the `AiModule` binding swapped. The proxy's four files deleted. 15 tests, including the
  one that decodes the recorded request body and finds the masked pixels white.

- T41 `WhiteFill` — masked pixels to opaque `#FFFFFFFF`, everything else copied verbatim, the
  input never mutated, a wrong-size mask throwing. 6 tests.

- T40 `GeminiEraseClient` — `POST …:generateContent`, the key in `x-goog-api-key` and never in
  the URL, camelCase in and out, the English instruction constant with its optional hint
  sentence, the first `inlineData` part winning over text parts, and §6's table row for row.
  23 MockWebServer tests.

- T39 `GeminiSettings` — `SharedPreferences` file `gemini_settings`, empty default, no
  `BuildConfig` field and no `.env` read, plus a masked `Gemini API 키` field on the (now
  three-field) 서버 설정 sheet. 9 tests.

- T38 Generative eraser — `Operation.GenerativeErase` carrying its own pixels, the renderer
  blending them through the mask, `erase_<id>.png` persistence, and a sheet-less 지우기 tool.
  `EraseController` owns the run→save→commit sequence. 12 tests + golden
  `generative_erase_render`.

- T37 `EraseProvider` — `Sam3EraseClient` posting image + mask + hint to `/v1/edit/erase` with
  a 60s read timeout, `Sam3EraseProvider` reusing segmentation's availability, and `MaskPng`.
  10 MockWebServer tests. **Superseded by ADR-011**: T42 deleted all four of those files. The
  `EraseProvider` interface it declared stays, and so does everything T38 built on it.

- T36 Prompt or speech → mask — the selection sheet hosts `VoicePromptBar`, a phrase runs
  `byText`, its instances union into one mask and merge by the current mode, and `count == 0`
  is the 찾지 못했어요 hint rather than an error. 9 tests + golden `select_prompt_result`.

- T35 Voice input — `SpeechInput` / `SpeechState`, `AndroidSpeechInput` over the OS recogniser
  in `ko-KR` with partial results, `FakeSpeechInput`, and `VoicePromptBar` owning the
  RECORD_AUDIO grant. 7 tests.

- T34 `PromptBar` — 48dp / 16dp radius / `editSurfaceRaised`, mic and send at 48dp hit areas,
  Korean placeholder, IME Done submitting the trimmed value, and the mic turning accent only
  while listening. 8 tests + goldens `prompt_bar_empty` / `_filled` / `_listening`.

_T01–T33 trimmed per CLAUDE.md (keep the last 10)._

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
  into the 서버 설정 sheet the 지우기 tool is greyed and, on tap, opens that sheet (T43). `check`
  is unaffected — every test uses `FakeEraseProvider` or `MockWebServer`.

- **The Gemini call itself has never reached Google.** Every T40/T42 test is `MockWebServer` on
  localhost, so the request shape is verified against specs/generative_erase.md §5 and not against
  the live API. The first real key will also be the first real response.

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
