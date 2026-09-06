# tasks.md — Ralph loop task queue

T01–T38 are done. What each one built is in `progress.md` under `## Done`, why it was built that
way is in `work/decisions.md`, and the `done when` checklists are in git history. None of it is
repeated here: this file is read on every loop iteration, so it holds only what is still open.

Rules for the agent: unchanged — see CLAUDE.md "Ralph loop rules". One task per iteration,
`scripts/check.sh` is the only verdict, only edit checkboxes in this file.

Legend: `[ ]` todo · `[x]` done · `[!]` blocked · `[H]` human-only, loop must skip

---

## Prerequisites (human work, not a task — the loop must never pick this up)

**None outstanding.** The v3 line needs no server change, no new catalog entry and no new
`AppError` case:

| Was needed | Status |
|---|---|
| `POST /v1/edit/erase` in `~/sam3-server` | **struck.** ADR-011 moved the call onto the device; `~/sam3-server` stays segmentation-only |
| CLAUDE.md's "never call an external host" hard limit | lifted 2026-09-06 by a human, because production code now calls `generativelanguage.googleapis.com`. Tests are unaffected: they still only reach `MockWebServer` on localhost, via `GeminiEraseClient`'s `baseUrl` seam |
| a dependency for the Gemini call | none. OkHttp, kotlinx.serialization and `android.util.Base64` are all already used by `Sam3Client` / `MaskCodec` |

Not a blocker, but nothing runs for real without it: **a human must paste a Gemini API key into
the 서버 설정 sheet on the device.** No key is shipped, committed, or read from `.env` at build
time — see generative_erase.md §2. Every test uses `FakeEraseProvider` or `MockWebServer`, so
`check` is green with no key at all.

If a task hits a missing prerequisite, mark it `[!]` and write the reason in `blocked.md`. Do not
add the dependency yourself.

---

## Phase 8 — The eraser calls Gemini directly

Replaces the T37/T38 proxy transport. **The document model and the tool are already built and are
not re-implemented**: `Operation.GenerativeErase`, the renderer blend, `erase_<id>.png`
persistence, `EraseController`, `FakeEraseProvider` and the `generative_erase_render` golden all
stay exactly as they are. Only the layer between `EraseProvider` and the wire is new.
generative_erase.md §13 lists the three files that come out, and nothing else may.

- [x] T39 `GeminiSettings` and the key field
  spec: specs/generative_erase.md §2, §8
  deps: —
  done when:
    - `GeminiConfig(apiKey: String, baseUrl: String = DEFAULT_BASE_URL)` and `GeminiSettings` in
      `core/ai/gemini`: `SharedPreferences` file `gemini_settings`, key `api_key`, default the
      **empty string**, exposing `config: StateFlow<GeminiConfig>` and `update(apiKey: String)`
    - **no** `BuildConfig` field, **no** `local.properties` key, and nothing in Gradle reads `.env`.
      A published APK must contain no credential (§2)
    - `baseUrl` is a constant with a constructor seam for tests, never a user-editable field — a
      text box for it would be a way to send the user's key to an arbitrary host
    - separate from `Sam3Settings`: different host, different credential, different lifetime
    - `Sam3SettingsSheet` gains a third field labelled `Gemini API 키` under
      `PasswordVisualTransformation`; its title string becomes `서버 설정`; `onSave` carries the key
      alongside the base URL and token, and `EditorRoute` wires it through
    - tests: SharedPreferences round-trip, the empty default, `config` emitting on `update`, the
      sheet emitting all three values on save, and the key field rendering masked
  note: no screenshot golden covers this sheet (`feature/editor/src/test/screenshots` has no entry
  for it), so the new field re-records nothing. Do not add one.
  touches: core/ai/gemini, feature/editor/tools/select/Sam3SettingsSheet.kt,
  feature/editor/EditorRoute.kt, feature/editor strings.xml

- [ ] T40 `GeminiEraseClient` — the HTTP layer
  spec: specs/generative_erase.md §5, §6
  deps: T39
  done when:
    - `GeminiEraseClient` (OkHttp + kotlinx.serialization) posts to
      `{baseUrl}/v1beta/models/gemini-2.5-flash-image:generateContent`
    - the key travels in the `x-goog-api-key` **header**, never a query parameter (§5)
    - request JSON is camelCase on both sides — one `contents[0].parts` entry carrying
      `inlineData { mimeType, data }`, one carrying `text`, plus
      `generationConfig.responseModalities = ["IMAGE"]`
    - the instruction is an `internal` English constant (§5 quotes it verbatim), with
      `The white region previously contained: <hint>.` appended only when `hint` is non-blank.
      It is wire payload, not a user-facing string, so it does **not** go in `strings.xml`
    - the response reader scans `candidates[0].content.parts` for the first `inlineData` and
      **ignores text parts** rather than failing on them
    - connect 10 s, read 60 s, cancellable, on `DispatcherProvider.io`
    - every row of §6 maps as written, using only the existing `AppError` cases. A safety block is
      `Invalid("blocked:<reason>")`; a 200 with no image part is `Unsupported`
    - `GeminiEraseClientTest` on `MockWebServer`: path and method; the key present in the header and
      **absent from the request URL**; the body shape; a base64 image part decoding; a text part
      being skipped to reach the image part; each §6 row; cancellation mid-flight closing the call
  touches: core/ai/gemini

- [ ] T41 `WhiteFill` — painting the hole
  spec: specs/generative_erase.md §4
  deps: —
  done when:
    - `internal object WhiteFill { fun apply(image: Bitmap, mask: Bitmap): Bitmap }` in
      `core/ai/gemini`
    - every pixel whose mask alpha is non-zero becomes opaque `#FFFFFFFF`; every other pixel is
      copied verbatim; the returned bitmap is `ARGB_8888` at the input size
    - the input bitmap is never mutated
    - no feathering and no partial blend — the mask is binary by segmentation.md §1, and a soft
      edge would hand the model exactly the ambiguity this is meant to remove
    - it lives in `core/ai/gemini`, not `core:imaging`: it is how one provider talks to one model,
      and `core:ai` keeps its `core:imaging` dependency to `ImageRef` alone (ai_provider.md §2)
    - `WhiteFillTest`: masked pixels are exactly `0xFFFFFFFF`; unmasked pixels equal the input; the
      input is unchanged afterwards; an all-clear mask yields a pixel-identical copy; a mask whose
      size differs from the image's fails loudly
  touches: core/ai/gemini

- [ ] T42 `GeminiEraseProvider`, and the proxy comes out
  spec: specs/generative_erase.md §7, §11, §12, §13
  deps: T40, T41
  done when:
    - `GeminiImageCodec`: longest edge down to **1024**, JPEG quality 90 with a single fallback to
      75, `null` past 20 MB → `TooLarge`. The mask is scaled to the same size with
      **nearest neighbour** and stays strictly binary
    - `GeminiEraseProvider : EraseProvider` performs §7's five steps in order: size guard →
      downscale → `WhiteFill` → call → decode and scale back **bilinearly** to the caller's size.
      On `DispatcherProvider.io`, `ensureActive()` before the network call
    - a mask whose size differs from the image's fails with `Invalid` before any request is built
    - `availability` is derived from `GeminiSettings.config` with **no network probe**: blank key →
      `Unavailable(Invalid("no api key"))`, non-blank → `Ready`. Probing Gemini would be a billed
      generation, which is why this departs from segmentation.md §7
    - `AiModule` binds `GeminiEraseProvider`; the `Sam3EraseProvider` binding is removed
    - exactly the three files in §13 are deleted — `Sam3EraseClient.kt`, `Sam3EraseProvider.kt`,
      `Sam3EraseClientTest.kt` — plus `MaskPng.kt` **only if** nothing references it afterwards.
      Nothing else may be removed
    - `GeminiImageCodecTest` and `GeminiEraseProviderTest` per §12, including the load-bearing one:
      decode the recorded request body and assert a sampled masked pixel is **white**, proving the
      whitened image is what went on the wire
    - the T38 round-trip, validation and UI tests and the `generative_erase_render` golden pass
      **without edits**. Needing to touch one of them means the swap reached further than §13 —
      stop and block rather than editing the test
  touches: core/ai/gemini, core/ai/AiModule.kt, core/ai/erase (deletions only), core/ai tests

- [ ] T43 지우기 tool — telling the user which thing is missing
  spec: specs/generative_erase.md §9
  deps: T42
  done when:
    - two strings added to `feature/editor` `strings.xml`: `erase_needs_key`
      "설정에서 Gemini API 키를 입력해주세요" and `erase_blocked` "이 이미지는 편집할 수 없어요"
    - `EraseController` distinguishes §9's four states: no selection, no key, a blocked generation,
      any other failure
    - tapping the disabled tool when the key is blank **opens the 서버 설정 sheet**, the way the
      선택 tool already does when the base URL is blank — a snackbar alone tells the user what is
      wrong without offering the fix
    - a failure whose `AppError.Invalid.detail` starts with `blocked:` shows `erase_blocked`;
      anything else shows `erase_failed`
    - the selection survives every failure, so a retry costs no re-selection
    - cancelling still leaves the document byte-for-byte untouched
    - one test per row of §9's table
  touches: feature/editor/tools/erase, feature/editor/EditorRoute.kt, feature/editor strings.xml

---

## Backlog

_Empty beyond Phase 8._ T26–T38 completed the v2 line — server-side SAM 3 selection, add/subtract
merging, masked adjustments, cut-out, the prompt bar with voice, and the generative eraser — and the
segmentation half is verified on a device against the real model.

---

## Deferred
- D01 Layers · D02 Text · D03 GPU render (AGSL) · D04 Tablet · D05 Onboarding
- D06 Box prompt for selection (`/segment/box` already exists server-side, so this is UI only)
- D08 Video (SAM 3 tracking)
- D09 On-device segmentation fallback — the retired EdgeTAM / ExecuTorch plan (ADR-007, ADR-008).
  Revisit only if offline selection becomes a requirement.
- D10 Generative fill / replace, reusing the T42 `GeminiEraseClient` boundary. The only new pieces
  are a different instruction constant and a prompt bar to source it from.
