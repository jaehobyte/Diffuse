# tasks.md — Ralph loop task queue (v2: server-side selection, prompts, generative erase)

T01–T24 are done; their notes live in `progress.md`. Never re-implement anything recorded there.

Rules for the agent: unchanged — see CLAUDE.md "Ralph loop rules". One task per iteration, `scripts/check.sh` is the only verdict, only edit checkboxes in this file.

Legend: `[ ]` todo · `[x]` done · `[!]` blocked · `[H]` human-only, loop must skip

---

## Prerequisites (human work, not a task — the loop must never pick this up)

Design is settled: ADR-009 (server-side SAM 3 at `~/sam3-server`) and ADR-010 (generative editing
through that server's proxy) are in architecture.md §10, and every spec below exists. What is **not**
done, and what blocks which task:

| Missing | Blocks |
|---|---|
| ~~`okhttp` and `okhttp-mockwebserver` in `libs.versions.toml`~~ done | — |
| ~~CLAUDE.md hard limit amended~~ done | — |
| ~~`AppError` gains `Unauthorized`, `Invalid(detail)`, `Unavailable`~~ done | — |
| ~~`sam3.baseUrl` / `sam3.token` keys in `local.properties`~~ done | — |
| `POST /v1/edit/erase` implemented in `~/sam3-server` | T37 |
| ~~the EdgeTAM `.pte` files deleted~~ done (32 MB) | — |

Only the server endpoint is still outstanding. If a task hits a missing prerequisite, mark it
`[!]` and write the reason in `blocked.md` — do not add the dependency yourself.

---

## Phase 5 — Network and model layer

- [x] T26 `core:ai` module and `SegmentationProvider` interface
  spec: specs/ai_provider.md, architecture.md §6
  deps: —
  done when:
    - new module `core:ai` (depends on `core:common`, and on `core:imaging` for `ImageRef` only);
      `feature:editor` depends on it; dependency-guard updated
    - `interface SegmentationProvider` with `availability: StateFlow<Availability>`,
      `suspend fun open(image: Bitmap): Result<SegSession>`,
      `suspend fun byPoints(s: SegSession, p: PointPrompt): Result<SegMask>`,
      `suspend fun byText(s: SegSession, phrase: String): Result<List<SegMask>>`,
      `suspend fun close(s: SegSession)`
    - `SegSession(imageId, imageWidth, imageHeight, expiresAtEpochMs: Long)` — a `Long`, because
      the catalog has no kotlinx-datetime and nothing adds one;
      `PointPrompt(points: List<PointF> /* normalized 0..1 */, labels: List<Boolean>)`;
      `SegMask(alpha: Bitmap /* ALPHA_8, image size, strictly binary */, score: Float)`
    - `FakeSegmentationProvider`: `byPoints` returns a circle of radius `0.2 × shortEdge` around the
      first foreground point minus `0.1 × shortEdge` circles at each background point; `byText`
      returns two deterministic circles keyed off the phrase hash; `failNext(error)` for error paths
    - `EraseProvider` (ai_provider.md §3) is declared in the same file; T37 implements it
    - tests for the fake; Hilt binding picks the fake in tests, the real provider in the app
  touches: core/ai, settings.gradle.kts (only to add the module — this one exception is pre-approved), feature/editor build file

- [x] T27 `Sam3Client` — the HTTP layer
  spec: specs/segmentation.md, ~/sam3-server specs/api.md
  deps: T26
  done when:
    - `Sam3Client` (OkHttp + kotlinx.serialization) covers `POST /v1/images` (multipart),
      `POST /v1/images/{id}/segment/points`, `.../segment/text`, `DELETE /v1/images/{id}`, `GET /healthz`
    - every request uses `format = "png"`, so the response carries a base64 8-bit grayscale PNG at
      original resolution, which decodes to an opaque bitmap whose **luminance** is the mask.
      **No COCO RLE decoder is written** — the server already offers a usable format, and RLE
      would be dead code
    - bearer token on every `/v1/` call; requests are cancellable and run on `DispatcherProvider.io`
    - error mapping onto the `AppError` cases in architecture.md §9: 400 → `Invalid`, 401 → `Unauthorized`,
      410 → session expiry (handled below, never surfaced as-is), 413 → `TooLarge`,
      415 → `Unsupported`, 503 → `Unavailable`, transport failure → `Io`
    - `410` is surfaced as a distinct `Sam3Error.SessionExpired` rather than an `AppError`, so T28's
      provider — which holds the uploaded bytes and is the only layer able to replay — can absorb it
      (specs/segmentation.md §5)
    - images are downscaled before upload so the body stays under 20 MB
    - `Sam3ClientTest` with `MockWebServer`: each route, each error code, the 410 replay path,
      base64 alpha decoding, and normalized-coordinate encoding. No external host is contacted
  touches: core/ai/sam3

- [x] T28 `Sam3SegmentationProvider` and server settings
  spec: specs/segmentation.md §Availability, §Settings
  deps: T27
  done when:
    - `Sam3SegmentationProvider : SegmentationProvider` implemented over `Sam3Client`;
      at most one live `SegSession`, and opening a second closes the first with `DELETE`
    - `Sam3Settings` holds base URL and token: `local.properties` supplies the build-time default
      via a `BuildConfig` field on `:core:ai` itself (`:app` depends on `:core:ai`, not the other
      way round, and BuildConfig is per-module), overridable at runtime, persisted in
      `SharedPreferences` (the same choice `ExportSettingsStore` already made)
    - `availability` is `Unavailable(AppError.Invalid)` when no base URL is configured and
      `Unavailable(AppError.Unavailable)` when `GET /healthz` fails; it re-checks when settings change
    - masks come back at the uploaded image's size and are scaled to the working image size
    - tests with `MockWebServer`: availability transitions, session replacement, settings round-trip
  note: the settings *sheet* moved to T30, where the tool that needs it lives
  touches: core/ai/sam3, core/ai build file

## Phase 6 — Selection tool

- [x] T29 `Operation.Mask` in the model and renderer
  spec: specs/edit_model.md, render.md
  deps: —
  done when:
    - `Operation.Mask(id, maskRef: ImageRef /* ALPHA_8 PNG in the project folder */)` — it stores
      the alpha only, **not** the prompts, since a merged selection has no single reproducing prompt
    - a `Mask` op alone changes no pixels; the renderer exposes `resolveMask(doc, maskId): Bitmap?`
    - masks are saved by persistence as `mask_<id>.png`; round-trip test
    - at most one **active** mask per document (`EditDocument.activeMaskId`); older masks stay for undo
  touches: core/imaging/model, core/imaging/render, core/data

- [x] T30 "선택" tool: tap-to-segment with a darkened preview
  spec: specs/selection_tool.md, DESIGN.md §4
  deps: T26, T29
  done when:
    - tool strip gains "선택" with the 6dp accent AI dot; greyed when `availability` is
      `Unavailable`, and tapping it then shows a snackbar with the reason
    - opening the sheet runs `open()` once behind the progress overlay "이미지를 분석하는 중"
      (DESIGN.md §4 State display); the sheet holds [반전] [지우기] and Cancel / Apply
    - canvas `gestureMode = SelectPoint`: tap → foreground point, long-press → background point,
      one-finger drag → pan, two fingers → zoom/pan (all per selection_tool.md §2)
    - every point change → `byPoints()` with the full prompt; preview shows the area outside the
      mask at `#000000` 60%, a 1dp `accent` outline on the mask edge, 8dp dots at each point
    - a segment in flight conflates: only the latest prompt is queued
    - Undo/Redo while the sheet is open drive the tool's own point deque, not the document
    - Apply writes `Operation.Mask` and sets `activeMaskId`; Cancel discards; Apply disabled with no mask
    - fully driven by `FakeSegmentationProvider` in tests; UI test covers add fg, add bg, undo, apply, cancel
    - goldens: `select_sheet_open`, `select_mask_preview`
  note: the canvas gesture-mode field and `overlayTransform` already exist from T24 (see progress.md).
        T28's deferred SAM 3 settings sheet lands here, since this is the first screen that needs it
  touches: feature/editor/tools/select, feature/editor/canvas (gesture mode + mask overlay draw only)

- [x] T31 Accumulated mask merging with add / subtract
  spec: specs/selection_tool.md §Merge
  deps: T30
  done when:
    - the tool owns one `accumulated: Bitmap (ALPHA_8)`; the sheet gains an [추가] / [빼기] segmented
      toggle, default 추가
    - a prompt result merges as `add → max(acc, new)` and `subtract → min(acc, 255 - new)`
    - point taps stay a single server-refined prompt: they accumulate into one `PointPrompt` sent to
      `byPoints`, and that result is what merges. Switching to [빼기] starts a fresh point prompt
      rather than adding background points, so the two mechanisms never fight
    - 반전 flips the accumulated mask only; 지우기 clears it and the point prompt, keeping the session
    - each merge is one entry on the tool's deque, so Undo removes exactly one merge
    - `MaskMergeTest` (pure): add then subtract on overlapping circles gives the expected coverage;
      invert is its own inverse; the alpha stays strictly binary
    - golden `select_mask_merged`; `select_sheet_open` gains the mode row, so it is named here
      and may be re-recorded once
  touches: feature/editor/tools/select

- [x] T32 Adjustments limited to the selection
  spec: specs/adjust_light.md / adjust_color.md / adjust_detail.md (amend: masked mode), render.md
  deps: T30
  done when:
    - when `activeMaskId != null`, every `AdjustSheet` shows a toggle "선택 영역에만" (default on);
      `Operation.Adjust` gains `maskId: String?`
    - renderer blends `out = lerp(in, adjusted, mask)`
    - golden `exposure_+0.5_masked` using a fixture mask covering the left half — the right half must
      equal the input exactly
    - existing unmasked goldens unchanged
  touches: core/imaging/ops, core/imaging/render, feature/editor/tools/*

- [x] T33 Background removal from the selection
  spec: specs/selection_tool.md §CutOut
  deps: T30
  done when:
    - the selection sheet gains a primary pill "배경 지우기", visible only once a mask exists
    - `Operation.CutOut(maskId)`: alpha outside the mask → 0; `hasAlpha` becomes true; checkerboard shows
    - export auto-selects PNG (the export.md rule already exists)
    - golden `cutout_render`; UI test: cutout → undo restores alpha; `select_sheet_open` gains
      the 배경 지우기 row, so it is named here and may be re-recorded once
  touches: core/imaging/ops, feature/editor/tools/select, feature/export (only if the auto-PNG rule needs the new flag)

## Phase 7 — Prompt input

- [x] T34 `PromptBar` component
  spec: specs/prompt_input.md, DESIGN.md §4 (prompt bar)
  deps: —
  done when:
    - `PromptBar(value, onValueChange, onSubmit, onMicClick, listening, enabled)` in `core:ui`,
      styled strictly from `Tokens.kt`: 48dp tall, 16dp radius, `editSurfaceRaised` fill,
      24dp mic icon left, 24dp send icon right, `bodyMd` text, 48dp hit areas
    - placeholder and every label come from `strings.xml` in Korean; nothing hardcoded
    - send is enabled only for a non-blank, trimmed value; IME action Done submits
    - the send icon is **never** `accent`; the sheet's one accent stays on its Apply pill (DESIGN.md §1)
    - `listening = true` renders the mic in `accent` — a fill change only, no glow or pulse (DESIGN.md §7)
    - `mic` is hidden entirely when the host passes `onMicClick = null`
    - goldens: `prompt_bar_empty`, `prompt_bar_filled`, `prompt_bar_listening`; 3 behavior tests
  touches: core/ui/components, core/ui/src/main/res (strings)

- [x] T35 Voice input behind a `SpeechInput` interface
  spec: specs/prompt_input.md §Voice
  deps: T34
  done when:
    - `interface SpeechInput { val state: StateFlow<SpeechState>; fun start(localeTag: String); fun stop() }`
      with `SpeechState` = `Idle | Listening(partial: String) | Final(text: String) | Failed(AppError)`
    - `AndroidSpeechInput` wraps `android.speech.SpeechRecognizer` with `ko-KR`; no network code in the app
    - `RECORD_AUDIO` declared and requested at first mic tap; denial → Korean snackbar, mic stays usable
      for a retry; permanent denial → mic hidden for the session
    - `SpeechRecognizer.isRecognitionAvailable(context) == false` → `PromptBar` is hosted without a mic
    - partial results stream into the `PromptBar` text as the user speaks
    - `FakeSpeechInput` drives every test; UI test covers grant, deny, partial → final, and stop
  touches: core/ai/speech, feature/editor, app/src/main/AndroidManifest.xml

- [ ] T36 Prompt or speech → SAM 3 text segmentation
  spec: specs/prompt_input.md §Flow, specs/selection_tool.md §Text
  deps: T31, T34, T35, T28
  done when:
    - the selection sheet hosts the `PromptBar`; submitting a phrase calls `byText()` on the open session
    - the returned instances are unioned into one mask, then merged into the accumulated selection with
      the current [추가] / [빼기] mode — the same merge path T31 already tests
    - a `Final` speech result auto-submits; while `Listening`, the send button is replaced by a stop button
    - `count == 0` → `bodySm` hint "찾지 못했어요. 다른 단어로 해보세요." Not an error, not a snackbar
    - while a text prompt is in flight the bar is disabled and the progress overlay shows with a cancel
      button (DESIGN.md §7 "always show progress and a cancel button during AI work")
    - UI test with the fakes: type → merged mask; speak → merged mask; empty result → hint;
      cancel mid-flight leaves the accumulated mask untouched
    - golden `select_prompt_result`
  touches: feature/editor/tools/select

## Phase 8 — Generative eraser

- [ ] T37 `EraseProvider` and the proxy client
  spec: specs/generative_erase.md, ~/sam3-server specs/api.md (`POST /v1/edit/erase`)
  deps: T27
  done when:
    - `interface EraseProvider { val availability: StateFlow<Availability>; suspend fun erase(image: Bitmap, mask: Bitmap, hint: String?): Result<Bitmap> }`
      in `core:ai`; the app never sees a Gemini key or a Google endpoint
    - `Sam3EraseClient` posts the original and the ALPHA_8 mask as multipart to `/v1/edit/erase` and
      decodes the returned PNG; 60s read timeout; cancellable; errors map as in T27
    - `FakeEraseProvider` fills the mask region with the mean color of the pixels just outside it —
      deterministic, so goldens are stable
    - `MockWebServer` tests: success, timeout, 503, cancellation
  touches: core/ai/erase

- [ ] T38 "지우기" generative eraser tool
  spec: specs/generative_erase.md §Tool, specs/edit_model.md (GenerativeErase)
  deps: T31, T37
  done when:
    - tool strip gains "지우기" with the accent AI dot; disabled with a reason when `activeMaskId == null`
      or `availability` is `Unavailable`
    - running it shows the progress overlay "지우는 중" with a cancel button; cancelling leaves the
      document untouched
    - the result is stored as `erase_<id>.png` and committed as
      `Operation.GenerativeErase(maskId, resultRef: ImageRef)` in **one** history entry
    - renderer: `out = lerp(in, result, maskAlpha)`, so pixels outside the mask keep their original
      values and the document stays non-destructive — undo is a single op removal
    - persistence round-trips the op and its image; autosave and export need no special case
    - golden `generative_erase_render`; UI tests: run → undo restores, run → cancel is a no-op
  touches: core/imaging/model, core/imaging/render, core/data, feature/editor/tools/erase

---

## Deferred
- D01 Layers · D02 Text · D03 GPU render (AGSL) · D04 Tablet · D05 Onboarding
- D06 Box prompt for selection (`/segment/box` already exists server-side, so this is UI only)
- D08 Video (SAM 3 tracking)
- D09 On-device segmentation fallback — the retired EdgeTAM / ExecuTorch plan (ADR-007, ADR-008).
  Revisit only if offline selection becomes a requirement.
- D10 Generative fill / replace, reusing the T37 proxy boundary
