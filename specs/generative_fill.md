# specs/generative_fill.md — 채우기 (generative fill)

Owner tasks: T59 (the op), T60 (the provider), T61 (the tool), T62 (the planner function)
Modules: `core/imaging/model` + `core/imaging/render` (the op), `core/ai/gemini` (the call),
`feature/editor/tools/fill` (the tool)
Depends on: generative_erase.md, ai_provider.md, selection_tool.md, edit_model.md, render.md,
prompt_input.md, DESIGN.md §4
Decisions: ADR-011 (the device calls Gemini directly) applies unchanged.

This is Deferred **D10**, promoted. generative_erase.md §5 already anticipated it: the `hint`
parameter on `EraseProvider.erase` "stays because the interface is shared with the fake and with
D10". That prediction was half right — the shape is the same, but a hint and a prompt are not the
same argument, and §3 below says why they get separate interfaces.

## 1. What it does
Replaces whatever the active selection covers with something the **user names**. The user picks a
region with the 선택 tool, switches to 채우기, types or speaks what should be there, and taps 적용.

지우기 and 채우기 are the same mechanism pointed in opposite directions: both send a whitened
region to `gemini-2.5-flash-image` and composite the answer back through the mask. They differ in
exactly one place — the instruction — and in whether a person supplies a noun.

| | 지우기 | 채우기 |
|---|---|---|
| Prompt | none; the instruction is a constant | the user's, required |
| Sheet | none — tapping the tool runs it | a sheet with a prompt bar |
| Instruction | "continue the surrounding scene, introduce no new object" | "place `<prompt>` here, matching the scene" |
| Op | `GenerativeErase(maskId, resultRef)` | `GenerativeFill(maskId, resultRef, prompt)` |

## 2. The flow

```
선택 tool  ─ SAM 3 ─▶  SegMask (ALPHA_8, working size, binary)
채우기 tool → sheet → the user types "빨간 우산" → 적용
  └─ GeminiFillProvider                                     §4
       1. downscale image + mask to ≤ 1024 long edge        (GeminiImageCodec, reused)
       2. WhiteFill.apply — paint every masked pixel #FFFFFF (reused, unchanged)
       3. JPEG q90 → base64
       4. POST …/gemini-2.5-flash-image:generateContent with §3's instruction
       5. decode, reject a still-white answer, scale to working size
  └─ Operation.GenerativeFill(maskId, resultRef, prompt)     §5
  └─ Renderer: out = lerp(in, result, maskAlpha)             §5 — the erase blend, unchanged
```

Every numbered step except 4's instruction text is code that already exists and is **reused, not
forked**. If a step here needs a new encoder, a new white-fill or a new blend, the design drifted —
block the task rather than writing a second copy.

## 3. The wire (T60)
`GeminiEraseClient` already posts exactly this request. The only difference is the text part, so
the client gains **one parameter** rather than a sibling class:

```kotlin
internal suspend fun edit(image: ByteArray, instruction: String): Result<Bitmap>
```

- The class name does not change. It is the one class that knows how to talk to
  `gemini-2.5-flash-image`, and it now takes the sentence from its caller instead of owning one.
- `GeminiEraseProvider` keeps `ERASE_INSTRUCTION`; `GeminiFillProvider` owns `FILL_INSTRUCTION`.
  Two providers, two constants, one transport.
- Every `GeminiEraseClientTest` case must pass **unchanged** apart from supplying the instruction.
  Needing to edit one means the parameter was added in the wrong place.

**The instruction is an English `internal` constant and is not user-facing** (generative_erase.md
§5's rule, same reason):

```
The image contains a solid pure-white region. Replace that region with: <prompt>.
Render it photorealistically as part of the surrounding scene: match the scene's lighting,
shadow direction, texture, perspective, scale, focus and grain so the result looks like a single
unedited photograph. Do not alter anything outside the white region. Do not add text or a
watermark. If the requested subject cannot plausibly occupy that region, fill the region with a
continuation of the surrounding scene instead. Return only the edited image.
```

The last sentence is the fallback that keeps a bad request from becoming a failed one: an
unfillable prompt degrades into an erase, which is a result the user can see and undo, rather than
a snackbar. `<prompt>` is substituted verbatim — it is the one place in this feature where a
user-authored string reaches the wire.

**The user's prompt is not translated.** SAM 3 needs English because its text endpoint is concept
segmentation (vibe_edit.md §4); `gemini-2.5-flash-image` is a general model and handles Korean.
Adding a translation round trip here would cost a call to fix a problem this model does not have.

`FillProvider` is a separate interface from `EraseProvider` (ai_provider.md §3) rather than a
second method on it. They have different availability semantics only in principle — both derive
from the same key — but a fake that must answer both makes every erase test carry a fill argument
it does not use, and `EraseController` would gain a parameter it always passes blank.

**The no-op guard is shared.** T51's rule — an answer whose masked region comes back ≥
`WHITE_RESULT_THRESHOLD` white is `Unavailable`, not a committed white patch — applies here
unchanged and for a stronger reason: a fill that returns the input is exactly the failure the user
would report as "아무것도 안 생겼어요".

## 4. `GeminiFillProvider` (T60)
```kotlin
@Singleton
class GeminiFillProvider @Inject internal constructor(
    private val client: GeminiEraseClient,
    private val settings: GeminiSettings,
) : FillProvider
```

`fill(image, mask, prompt)` runs generative_erase.md §7's five steps verbatim, plus two guards
before any encoding:
1. `image.size != mask.size` → `Invalid("mask must be the image's size")`.
2. `prompt.isBlank()` → `Invalid("empty prompt")`. The sheet disables 적용 on a blank prompt, so
   this is the guard behind the guard, not the one the user meets.

`availability` derives from `GeminiSettings.config` with **no probe**, for generative_erase.md §7's
reason. Error mapping is generative_erase.md §6, row for row. **No new `AppError` case.**

`AiModule` gains `@Binds fun fill(impl: GeminiFillProvider): FillProvider`. No existing binding
changes.

## 5. Staying non-destructive (T59)
```kotlin
data class GenerativeFill(
    override val id: String,
    val maskId: String,
    val resultRef: ImageRef,   // fill_<id>.png in the project folder, working-resolution size
    val prompt: String,
) : Operation
```
Renderer: `out = lerp(in, result, maskAlpha)` — the same blend `GenerativeErase` uses, dispatched
from the same in-order walk T49 built. **No new renderer path**, one new `when` branch.

- `maskId` must reference an existing `Mask` op, validated on load like `GenerativeErase.maskId`.
- Multiple fills stack, each with its own mask. A fill after an erase composes, in list order.
- `fill_<id>.png` is kept alive by the file store exactly as `erase_<id>.png` is.
- JSON root `v` stays **1**. edit_model.md drops unknown operation types with a warning, so an older
  build opening a newer document loses the fill and still loads.

**`prompt` is stored; `Mask` deliberately stores no prompt.** That is not an inconsistency.
edit_model.md's reason for dropping the selection's prompts is that a v2 selection is merged from
point runs and text phrases, so no single string reproduces it and storing one would be a lie. Here
one string produced the result exactly, and it is what the 지시 step list and any future re-run
would need. It is display and provenance data, never re-sent automatically.

## 6. The tool (T61)
`Tool.Fill(R.string.editor_tool_fill, Icons.Rounded.AutoAwesomeMotion, isAi = true)`, inserted
**after `Tool.Erase`** so the two generative region tools sit together. This moves `Tool.Direct` by
one position; `Tool` is UI state and is not serialized anywhere, so nothing migrates.
`editor_shell_default` is re-recorded **for that one reason**, and the commit message says so —
the same allowance T55 took for 혼합.

Unlike 지우기, this tool has a sheet, because it needs a noun (DESIGN.md §4 Bottom sheet;
max 45%):

```
채우기                                          headingLg
[ 🎤   무엇으로 채울까요? 예: 빨간 우산       ➤ ]   VoicePromptBar, prompt_input.md §2–§3
                                    [취소 | 적용]  Apply is the sheet's one accent
```

- The bar is `VoicePromptBar` with the §7 placeholder, through the `placeholder` parameter T48
  added. The three prompt-bar goldens must pass **without re-recording**.
- 적용 is disabled while the prompt is blank (`EditSheet.applyEnabled`, already a parameter).
- The send icon stays `editInk`. **The sheet's one accent is 적용** — DESIGN.md §4's prompt-bar
  rule, so every sheet commits the same way. Submitting from the IME Done key does what 적용 does.
- While the call is in flight the progress overlay shows `fill_working` with a cancel button
  (DESIGN.md §7). Cancelling leaves the document byte-for-byte untouched, as 지우기 promises.
- On success the sheet closes and the canvas shows the result. On failure the sheet stays open with
  the prompt intact, so a retry costs no retyping — the same shape as 지우기 keeping the selection.

Disabled states, matching generative_erase.md §9 row for row:

| State | String | Action |
|---|---|---|
| `activeMaskId == null` | `fill_needs_selection` | — |
| key is blank | `fill_needs_key` | opens the 서버 설정 sheet |
| failure, `detail` starts with `blocked:` | `fill_blocked` | — |
| the answer came back still white | `fill_empty` | — |
| any other failure | `fill_failed` | — |

## 7. Strings (T61)
Korean, in `feature/editor` `strings.xml`. DESIGN.md §7: feature names are verbs, and 채우기 is one
of the three DESIGN.md names itself.

| Key | Value |
|---|---|
| `editor_tool_fill` | 채우기 |
| `fill_title` | 채우기 |
| `fill_placeholder` | 무엇으로 채울까요? 예: 빨간 우산 |
| `fill_working` | 채우는 중 |
| `fill_needs_selection` | 먼저 영역을 선택해주세요 |
| `fill_needs_key` | 설정에서 Gemini API 키를 입력해주세요 |
| `fill_blocked` | 이 이미지는 편집할 수 없어요 |
| `fill_empty` | 아무것도 만들지 못했어요. 다르게 말해보세요. |
| `fill_failed` | 채우지 못했어요 |
| `direct_step_fill` | 선택 영역에 %1$s 채우기 |

## 8. The planner function (T62)
A seventh entry in vibe_edit.md §4's catalog:

| Function | Parameters | Runs |
|---|---|---|
| `fill_selection` | `prompt: string` (required) | `FillProvider.fill` → `Operation.GenerativeFill` |

- `PlanStep.Fill(prompt)` in `core:ai`, beside `Erase`. §9.1's validation rule covers it with no
  new clause: it consumes a selection, so it needs an earlier `Select` or a live `activeMaskId`.
- `PLAN_SYSTEM_INSTRUCTION` gains one rule and one worked example: **"채우기" replaces, "지우기"
  removes** — `erase_selection` when the user wants the thing gone, `fill_selection` when they name
  what should be there instead.
- The model writes `prompt` in **English**, for the reason T52 recorded: the instruction already
  tells it every phrase argument is English, and one rule is cheaper than a per-argument exception.
  The §7 step template therefore renders an English noun, exactly as `direct_step_select` renders
  "bus 선택" — the open decision recorded in tasks.md covers both, and resolving it resolves both.
- A blank or absent `prompt` **drops the step** with a log warning, the rule vibe_edit.md §5
  applies to every malformed argument.

## 9. Tests
No test contacts an external host.

- `GeminiEraseClientTest`: every existing case passes with the instruction supplied by the caller;
  one new case asserts the instruction on the wire is the caller's, not a constant inside the client.
- `GeminiFillProviderTest`: a blank prompt fails before encoding; a mismatched mask size fails
  before encoding; the bytes on the wire are the **whitened** image (decode the recorded body and
  sample a masked pixel, the way T42's load-bearing test does); the prompt appears in the
  instruction verbatim; a still-white answer fails and commits nothing; `availability` flips with
  the key.
- `FakeFillProvider` in `core/ai/src/testShared`, beside the other three: `next(bitmap)`,
  `failNext(error)`, and a deterministic default so a golden can depend on it.
- Round-trip: a `GenerativeFill` carrying a prompt survives `EditDocumentJson`; a `maskId` pointing
  at a missing op fails validation on load; an unknown op still drops with the document loading.
- Render golden `generative_fill_render`, built the way `generative_erase_render` was, plus an
  order test: `[Mask, GenerativeFill, Adjust(masked)]` leaves the adjustment visible **inside** the
  filled region (T49's rule, re-checked for the new op).
- Tool tests: 적용 is disabled on a blank prompt; a blank key greys the tool and tapping it opens
  the 서버 설정 sheet; no selection shows `fill_needs_selection`; a failure leaves the sheet open
  with the prompt intact; cancelling mid-call commits nothing.
- Goldens (UI): `fill_sheet_open`, `fill_sheet_typed`. `editor_shell_default` re-recorded for the
  new strip item only.
- Planner: the recorded body declares seven functions; `fill_selection` with a prompt decodes to
  one `PlanStep.Fill`; a blank prompt drops the step and later steps survive.

## 10. What this does not do
- **No inpainting brush.** The selection comes from the 선택 tool, which is where selection lives.
- **No variations.** One call, one answer, undo as the correction. A "다시" button that re-rolls
  would need the prompt, the mask and a second billed call per tap; rephrasing is the same cost and
  the user already has the bar.
- **No prompt history.** D11 covers it for both sheets at once if it is ever wanted.
- **No fill without a selection.** Filling the whole frame is not a fill, and extending past the
  frame is 확대 (outpaint.md).
