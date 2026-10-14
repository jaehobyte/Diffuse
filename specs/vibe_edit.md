# specs/vibe_edit.md — 지시 tool (plan-and-run editing)

Owner tasks: T44 (`EditPlanProvider`), T45 (`GeminiPlanClient`), T46 (`GeminiPlanProvider`),
T47 (`PlanRunner`), T48 (the tool), T58 (`crop_ratio`), T62 (`fill_selection`)
Modules: `core/ai/gemini` (plan client + provider), `feature/editor/tools/direct` (tool, runner)
Decisions: ADR-012 (one planning call, function calling, the app executes)
Depends on: ai_provider.md, segmentation.md, generative_erase.md, generative_fill.md,
selection_tool.md, prompt_input.md, edit_model.md, history.md, crop.md, DESIGN.md §4

Every capability this feature orchestrates is already built. Nothing here adds an `Operation`, a
renderer path, a persistence rule or an `AppError` case: the planner's whole job is to decide which
of the existing tools to run, in what order, with what arguments.

## 1. What it does
The user says "나무를 좀 더 푸르게 해줘". The app sends that sentence and the photo to
`gemini-3.8-flash` along with a description of every editing function it has, gets back a list of
function calls, shows them as a Korean step list, and — once the user taps 적용 — runs them in
order against the same providers the manual tools use.

The point is that the model chooses the *workflow*, not the pixels. Segmentation still comes from
SAM 3, adjustments still come from `Ops.kt`, and the erase still comes from
`gemini-2.5-flash-image`. A plan is a script for tools that already exist.

## 2. The flow

```
"나무를 좀 더 푸르게 해줘"  (typed, or spoken → prompt_input.md §3)
  └─ GeminiPlanProvider                                      §7, §8
       1. encode the current preview  (GeminiImageCodec, ≤1024, JPEG q90)
       2. POST …/gemini-3.8-flash:generateContent with one propose_edit envelope   §5, §14
       3. read the single functionCall, decode and check it whole                §5, §14
  └─ PlanProposal(observations, goals, …, workflow = [Select("tree"), Adjust(Saturation, +0.3, masked)])
  └─ validate against the document                            §9.1
  └─ preview list, [취소 | 적용]                               §3
  └─ PlanRunner                                               §9
       Select("나무")      → segmentation.byText → union → Operation.Mask → history.push
       Adjust(Saturation)  → doc.withAdjust(kind, value, activeMaskId) → history.push
```

One planning call per request. The model never sees the intermediate result, which is what makes
the round trip cheap and the failure surface small; §13 records what that costs.

The sentence suggestions under the bar (§14) are **not** part of this flow: they are a separate,
explicitly requested, read-only call that only fills the bar. A picked sentence is then planned by
exactly this one call, like a typed one.

## 3. The tool (T48)
An entry in `Tool`, **appended at the end** the way `Select` and `Erase` were (T61 and T65 later
insert 채우기 and 확대 ahead of it, which moves its position but not its behaviour):
`Direct(R.string.editor_tool_direct, …, isAi = true)`, so it carries the 6dp accent dot every AI
tool carries (DESIGN.md §4). Label 지시 — a verb, and short enough for a 64dp strip item. The strip
is a `LazyRow` and has scrolled since it passed four items (editor_shell.md), so no reordering is
needed to make room.

The sheet, top to bottom (DESIGN.md §4 Bottom sheet; max 45% height, the step list scrolls):

```
지시                                            headingLg
[ 🎤   무엇을 바꿀까요? 예: 나무를 더 푸르게    ➤ ]   VoicePromptBar, prompt_input.md §2–§3
1. 나무 선택                                     bodyMd, appears when a plan arrives
2. 선택 영역 채도 +30
                                    [취소 | 적용]  Apply is the sheet's one filled primary
```

- The bar is `VoicePromptBar` with a different placeholder (§11). That placeholder becomes a
  parameter on `PromptBar` in T48, defaulting to the string the 선택 tool already passes, so no
  existing behaviour or golden moves (prompt_input.md §2). The mic behaves
  exactly as prompt_input.md §3 specifies, including a `Final` result auto-submitting and the mic
  being absent when the device has no recognizer.
- Submitting asks for a plan. While that call is in flight the bar is disabled and the progress
  overlay shows `direct_planning` with a cancel button (DESIGN.md §4 State display, §7).
- A plan arrives → the step list replaces nothing; it appears under the bar and 적용 becomes
  enabled. 적용 is `primary`, the surface's one filled primary — the prompt bar's send icon stays
  neutral (prompt_input.md §2).
- Submitting a second phrase replaces the previous plan. Nothing has been applied yet, so there is
  nothing to undo.
- 적용 runs the plan (§9). The sheet closes when the run ends, whether it completed or stopped.
- 취소 / system back discards the plan. The document is untouched, because nothing runs before 적용.

**Step lines are assembled locally, never quoted from the model.** DESIGN.md §9 puts user-facing
Korean in `strings.xml`, and a model-authored sentence would route around that — it could not be
reviewed, translated or kept in tone. Each `PlanStep` renders through one template (§11) using the
adjustment labels the sheets already use (`light_exposure`, `color_saturation`, …). Text parts in
the response are therefore ignored, not shown. §14 allows only its validated, length-capped
plain-text fields (observations, goals, constraints, effects, limitations) on screen, under local
headings.

## 4. The function catalog (T45, extended by T58 and T62)
Eight tools — everything the editor can do that a sentence can plausibly ask for (`apply_style`
from style_match.md §6 is the eighth). Since 2026-10-13 (§14) they are no longer separate function
declarations: they are the closed `tool` enum of a workflow step inside the one `propose_edit`
envelope, with the same argument names, enums, ranges and conversions. Names,
descriptions and enum values are **English code constants**: they are wire payload sent to a model,
not strings a person reads (the same rule generative_erase.md §5 applies to its instruction).

| Function | Parameters | Runs |
|---|---|---|
| `select_region` | `phrase: string` (required) | `SegmentationProvider.byText`, union of the instances, `Operation.Mask` + `activeMaskId` — selection_tool.md §4, §6 |
| `adjust` | `kind: enum` (required), `value: number` (required), `masked: boolean` (default `true`) | `document.withAdjust(kind, value, maskId)` — edit_model.md |
| `erase_selection` | none | `EraseProvider.erase` → `Operation.GenerativeErase` — generative_erase.md §10 |
| `cut_out_selection` | none | `Operation.CutOut` — selection_tool.md §8.2 |
| `fill_selection` | `prompt: string` (required) | `FillProvider.fill` → `Operation.GenerativeFill` — generative_fill.md §8 |
| `crop_ratio` | `ratio: enum` (required) | a centred `Operation.Crop` at that ratio, then the 자르기 tool opens — §4.1 |

`kind` is the ten `AdjustKind` names in lower snake case: `exposure`, `contrast`, `highlights`,
`shadows`, `temperature`, `tint`, `saturation`, `vibrance`, `sharpen`, `vignette`.

**A fifth function, `adjust_color_range`, is specified in adjust_hsl.md §8**: it decodes into
ordinary `PlanStep.Adjust` steps, so §7, §9 and §11 are unchanged, and `kind` above stays these ten
names and never carries an HSL kind.

### 4.1 `crop_ratio`, and why it is safe when a free crop was not (T58)
This section replaces the ruling that stood from T45 to T56. That ruling said: *"자르기 is
deliberately absent. A crop is a rectangle in normalized coordinates, and a model asked for one
from a sentence has to invent four numbers it cannot verify against what the user meant."* The
objection was sound and is **unchanged** — it is the reason `crop_ratio` has the shape it does.

`ratio` is an enum, not four numbers. Its values are the preset chips crop.md "What the user sees" already ships:
`square` (1:1), `portrait_4_5` (4:5), `story_9_16` (9:16), `landscape_16_9` (16:9). The model picks
a name from a closed set; it cannot express a rectangle, so it cannot invent one. What it is
actually answering is "which aspect does 인스타 포스팅 mean", which is a language question and the
one thing in this feature a model is better at than a rule.

Three properties make the step safe to run without a preview of the rect:

1. **The rect is computed, not received.** `CropState.from(document, aspect).withPreset(preset)`
   produces the largest centred rect at that ratio, through `CropGeometry.applyPreset` — the same
   code path the manual chips use. No new geometry is written for this feature.
2. **The step is always last.** However the model orders its calls, the client moves the
   `crop_ratio` step to the end of the list and keeps only the final one (§5). This matches
   render.md, where `Crop` already applies last regardless of list position, and it means an
   adjustment is never computed against a smaller frame than the user will see.
3. **The crop is in the candidate the user reviews** (§18, §19; 2026-10-14, D101 — replaces "the
   자르기 tool opens straight afterwards"). The computed rect is part of the whole result compared
   전/후 before anything is kept, so a wrong framing is seen before it is applied; turning the crop
   item off and running again removes it, and the manual 자르기 tool still opens on the applied rect
   for a different framing. It is no longer opened, or required, after 적용.

The system instruction, also an English constant:

```
You are the planner for an Android photo editor. You are given a photo and the user's request,
which is usually Korean. Call the editing functions that fulfil the request, in the order they
must run. Rules:
- Use the fewest steps that achieve the request.
- To change only part of the photo, call select_region first and then call adjust with
  masked=true. A selection stays active until the next select_region call.
- select_region takes a short noun phrase naming the thing, never a sentence and never a verb.
- Values are relative strengths, not absolute settings: a slight change is 0.2, a clear change
  is 0.4, a strong change is 0.7. Use the ends of the range only when the user asked for an
  extreme.
- Use fill_selection when the user names what should be there instead, and erase_selection when
  they only want the thing gone.
- Call crop_ratio at most once, and only when the request names a shape, a platform or a format.
  Never crop to "improve" a photo the user did not ask to reframe.
- If the request cannot be met with these functions, call nothing.
```

The "short noun phrase" rule is not style advice: SAM 3's text endpoint is concept segmentation and
a sentence degrades it (prompt_input.md §1). The rule lives here because the model, not the user,
is now the one writing that phrase.

## 5. The call (T45)
`GeminiPlanClient`, OkHttp + kotlinx.serialization, shaped like `GeminiEraseClient` and sharing its
credential and host.

```
POST {baseUrl}/v1beta/models/gemini-3.8-flash:generateContent
  x-goog-api-key: <the key from GeminiSettings>
  Content-Type: application/json
```
```json
{
  "systemInstruction": { "parts": [{ "text": "<§4>" }] },
  "contents": [{
    "role": "user",
    "parts": [
      { "inlineData": { "mimeType": "image/jpeg", "data": "<base64 of the preview>" } },
      { "text": "<the user's sentence, verbatim>" }
    ]
  }],
  "tools": [{ "functionDeclarations": [ /* one: propose_edit (§14) */ ] }],
  "toolConfig": { "functionCallingConfig": { "mode": "ANY" } }
}
```

- **The model is `gemini-3.8-flash`, not `-image`.** This call produces a decision, not pixels; the
  image model neither declares functions nor benefits from being asked to.
- `mode: "ANY"` forces function calls over prose. A model that answers "네, 나무를 푸르게
  해드릴게요" in text has produced nothing the app can run.
- The key travels in the `x-goog-api-key` **header**, never a query parameter, for the reason
  generative_erase.md §5 gives: URLs reach logs, crash reports and recorded requests.
- `baseUrl` is the same injectable constant `GeminiEraseClient` uses — the production host, with a
  constructor seam so tests point at `MockWebServer`. It is not a user-editable field.
- Timeouts: connect 10 s, read 30 s. Half the eraser's read timeout, because planning is a text
  generation and a 60 s wait for one would mean something is wrong. Cancellable.
- camelCase on both sides (`inlineData`, `functionCall`, `functionDeclarations`).

**Reading the response (replaced 2026-10-13, §14).** The user part carries the image, a context
text (mode, current settings as final values, selection, crop) and — when not blank — the user's
text verbatim. Text parts in the answer are skipped. Exactly one `functionCall`, named
`propose_edit`, must be present; none, two, or another name refuses the answer. The envelope is
decoded **atomically**: a missing required field, an unknown tool/kind/ratio/style/colour, a
non-numeric or non-finite number, a dangling goal or observation id, a duplicate id, a text over
240 characters or a list over its cap refuses the **whole** proposal as
`Invalid("unplannable:<rule>")`. The old "drop the bad call, keep the rest" rule is gone: a workflow
that silently lost its select step would run its masked adjust on a leftover selection. A finite
`value` outside the kind's range is still clamped, and the step list shows the clamped value.

**`crop_ratio` is normalized after decoding (T58).** Wherever the model put it, the client moves the
one crop to the **end** of the step list. Since §14 a second crop refuses the proposal (it was the
model restating itself), and an unknown `ratio` refuses it too. No other step is reordered — the model's order is the plan
everywhere else.

**Zero function calls is refused, not an empty plan (§14).** A judgement that nothing should run
is the explicit `no_change` / `needs_clarification` / `unsupported` outcome with a reason; a refused
answer surfaces as the `direct_not_understood` hint (§10), never a snackbar.

## 6. Error mapping
Identical to generative_erase.md §6, row for row, onto the same `AppError` cases. **No new case is
added.** Two rows differ because a plan is not an image:

| Condition | `AppError` |
|---|---|
| `200` with no `propose_edit`, two envelopes, or an envelope §14 refuses | `Invalid("unplannable:<rule>")`, shown as the §10 hint |
| `200` with `promptFeedback.blockReason`, or `finishReason` `SAFETY` / `PROHIBITED_CONTENT` | `Invalid("blocked:<reason>")`, shown as `direct_blocked` |

## 7. The plan model (T44)
In `core:ai`, beside `SegmentationProvider` and `EraseProvider`:

```kotlin
sealed interface PlanStep {
    data class Select(val phrase: String) : PlanStep
    data class Adjust(val kind: AdjustKind, val value: Float, val masked: Boolean) : PlanStep
    object Erase : PlanStep
    object CutOut : PlanStep
    data class Fill(val prompt: String) : PlanStep          // T62, generative_fill.md §8
    data class Crop(val ratio: CropRatio) : PlanStep         // T58, §4.1
}

/** §4.1's closed set. The four values crop.md "What the user sees"'s preset chips already offer. */
enum class CropRatio { Square, Portrait4x5, Story9x16, Landscape16x9 }

/** [steps] in execution order; empty means the model declined to act (§5). */
data class EditPlan(val steps: List<PlanStep>)

interface EditPlanProvider {
    val availability: StateFlow<Availability>
    suspend fun plan(image: Bitmap, request: PlanRequest): Result<PlanProposal>   // §14
}
```

`EditPlan` stays the runner's input: `PlanProposal.plan` is its workflow's steps in order.

`PlanStep.Adjust` carries `AdjustKind`, which makes `core:ai → core:imaging` a real build edge for
the first time — the module was written against `Bitmap` and never actually imported anything from
`core:imaging` (ai_provider.md §2). That is a deliberate amendment, not an oversight: the
alternative is returning the function name as a string and mapping it to the enum in
`feature:editor`, which splits one validation into two and leaves `PlanStep` unable to say what it
means. `AdjustKind` is a pure enum in the model layer with no Android dependency, and
dependency-guard has allowed the edge since T01, so it costs one line in a module build file.

`plan` is a single request/response call with no session, so it needs none of `SegmentationProvider`'s
session contract. Everything else in ai_provider.md §4 holds: `DispatcherProvider.io`, cancellation
propagates, `Result` never throws across the boundary.

`FakePlanProvider` drives every UI test: `next(plan)` sets what the following call returns,
`failNext(error)` makes it fail, and the default is
`EditPlan(listOf(Select("나무"), Adjust(Saturation, 0.3f, masked = true)))` — the request this
feature was specified from, so the goldens read as the story.

## 8. `GeminiPlanProvider` (T46)
```kotlin
@Singleton
class GeminiPlanProvider @Inject internal constructor(
    private val client: GeminiPlanClient,
    private val settings: GeminiSettings,
    private val dispatchers: DispatcherProvider,
) : EditPlanProvider
```

`plan(image, request)`:
1. `mode == Direct && text.isBlank()` → `Invalid("empty request")` before any encoding. A blank
   `Recommend` is sent (§14).
2. `GeminiImageCodec.encode` — the existing encoder, unchanged: longest edge to 1024, JPEG q90
   falling back to 75 once, `TooLarge` past 20 MB. No mask is involved.
3. `ensureActive()`, then the §5 call on `dispatchers.io`.
4. Map the response per §5 and §6.

`availability` is derived from `GeminiSettings.config` with **no probe**, exactly as
generative_erase.md §7 argues: a blank key is `Unavailable(Invalid("no api key"))`, a present key is
`Ready`. Reachability is discovered by the call the user asked for.

`AiModule` gains `@Binds abstract fun plan(impl: GeminiPlanProvider): EditPlanProvider`.

## 9. Running the plan (T47)
`PlanRunner` lives in `feature/editor/tools/direct` — it needs `SegmentationProvider`,
`EraseProvider` and `ProjectRepository` at once, which is a feature-layer concern, and it is the
same split `SelectionController` and `EraseController` already use.

```kotlin
sealed interface RunEvent {
    data class Started(val index: Int) : RunEvent
    data class Committed(val index: Int, val document: EditDocument) : RunEvent
    data class Stopped(val index: Int, val error: AppError) : RunEvent
    object Completed : RunEvent
}

class PlanRunner(
    private val segmentation: SegmentationProvider,
    private val erase: EraseProvider,
    /** T62. A `Fill` step is the only one that uses it (generative_fill.md §8). */
    private val fill: FillProvider,
    private val dispatchers: DispatcherProvider,
    /** `repository.saveMask(projectId, …)`, bound by the ViewModel. */
    private val saveMask: suspend (String, Bitmap) -> Result<ImageRef>,
    /** `repository.saveEraseResult(projectId, …)`, likewise. */
    private val saveEraseResult: suspend (String, Bitmap) -> Result<ImageRef>,
    /** `repository.saveFillResult(projectId, …)` (T62). */
    private val saveFillResult: suspend (String, Bitmap) -> Result<ImageRef>,
) {
    fun validate(plan: EditPlan, document: EditDocument): PlanStep?
    fun run(plan: EditPlan, document: EditDocument, preview: Bitmap): Flow<RunEvent>
}
```

The runner takes save lambdas rather than `ProjectRepository`, because `projectId` lives in
`EditorViewModel`'s `SavedStateHandle` and not in the document. That is the shape `EraseController`
already uses for `saveEraseResult`, and copying it keeps one class knowing which project is open.

A cold `Flow` rather than callbacks: the runner chains each step onto the previous step's document,
and the caller — `EditorViewModel`, the only object holding the `HistoryStack` — decides what a
`Committed` means. Collection is cancellable, which is what the overlay's cancel button needs.

### 9.1 Validation, before anything is shown
`validate` returns the first step that cannot run, or `null`. One rule: **a step that consumes a
selection must have one.** `Adjust(masked = true)`, `Erase`, `CutOut` and `Fill` each require
either an earlier `Select` in the same plan or a non-null `activeMaskId` on the document.

`Crop` consumes no selection and needs no clause. T58 and T62 therefore add a step to the list this
rule already covers and add **no new rule** — a signal the shape is right.

This runs when the plan arrives, not when 적용 is tapped, so a plan that cannot work is never shown
as if it could. A failing validation is the `direct_not_understood` hint, the same as an empty plan
— from the user's side those are the same event.

The alternative was to let a masked `Adjust` with no selection quietly apply to the whole photo.
That turns "나무를 푸르게" into a global saturation lift with no warning, which is worse than being
told to rephrase.

### 9.2 Step execution
| Step | What runs |
|---|---|
| `Select(phrase)` | `byText(session, phrase)` → `MaskOps.union` (selection_tool.md §4) → `saveMask` → `document.withMask` + `activeMaskId`. An empty result is a **failure** for the run, mapped to `direct_not_found` |
| `Adjust(kind, value, masked)` | `document.withAdjust(kind, value, if (masked) document.activeMaskId else null)` |
| `Erase` | `erase.erase(preview, mask, hint = null)` → `saveEraseResult` → `document.withGenerativeErase` |
| `CutOut` | `document.withCutOut(activeMaskId)` |
| `Fill(prompt)` | `fill.fill(preview, mask, prompt)` → `saveFillResult` → `document.withGenerativeFill(maskId, ref, prompt)` — generative_fill.md §5 |
| `Crop(ratio)` | `CropState.from(document, sourceAspect).withPreset(ratio.preset).applyTo(document)` — one history entry, no new geometry (§4.1) |

`Select` is the only step that needs a `SegSession`. The runner opens one on the current preview at
the first `Select` and closes it when the run ends.

**The hand-off after a `Crop` step is not the runner's job.** The runner commits the crop and ends
the run like any other step; `EditorViewModel` sees that the plan's last step was a `Crop` and calls
`onToolClick(Tool.Crop)` after the sheet closes. Keeping it there costs one branch in the ViewModel
and leaves `RunEvent` and the runner's contract untouched — the runner still only commits documents,
which is what makes `PlanRunnerTest` able to assert about it without a UI.

An empty `byText` is a hint in the manual tool (selection_tool.md §7) but a stop here, because the
steps after it were written for a selection that does not exist. The user is told which word failed.

### 9.3 History, and what a partial run leaves behind
**One step, one `history.push`, no coalesce key.** A three-step plan is three entries: undo peels
the plan apart in the order the list showed it, and the user can stop halfway.

The alternative — one entry for the whole plan — was rejected because it cannot represent a run that
stopped in the middle. With per-step entries, a stop is simply a shorter stack, and no special case
is needed anywhere in history.md.

- **A step fails** → `Stopped`, the run ends, every earlier step stays committed. Snackbar per §10.
- **The user cancels** → the in-flight step commits nothing; earlier steps stay. The same shape as a
  failure, so there is one rule instead of two. This is narrower than the eraser's "cancelling leaves
  the document byte-for-byte untouched" (generative_erase.md §9): that promise is per step and still
  holds for the step that was running.
- **Every step succeeds** → `Completed`, the sheet closes, the canvas shows the result.

### 9.4 The session the 선택 tool was holding
`SegmentationProvider` keeps at most one live session and a second `open` closes the first
(ai_provider.md §4). A run containing a `Select` therefore invalidates the session
`SelectionController` cached. `EditorViewModel` clears `SelectionState.session` when such a run
starts, so the 선택 tool re-opens on its next open instead of prompting against a dead handle.

The tools are already exclusive (selection_tool.md §9), so the cost is one extra `open` — the
same one the user would pay after a crop.

## 10. Availability and what the user is told
`availability` comes from the Gemini key alone, as §8 says. The tool is greyed when it is blank and
tapping it **opens the 서버 설정 sheet**, exactly as the 지우기 tool does (generative_erase.md §9)
— through the same `EraseTap`-shaped return, because the sheet has one owner.

SAM 3 is deliberately not part of this check. A plan with no `Select` needs no segmentation server,
and blocking those requests on a service they do not use would be wrong. A `Select` step against an
unreachable server fails in §9.2 and reports the segmentation reason.

| State | String |
|---|---|
| key is blank | `direct_needs_key` — and the 서버 설정 sheet opens |
| the plan came back empty, or `validate` rejected it | `direct_not_understood` (a `bodySm` hint under the bar, not a snackbar) |
| planning failed, `detail` starts with `blocked:` | `direct_blocked` |
| planning failed otherwise | `direct_failed` |
| a `Select` step found nothing | `direct_not_found` |
| a `Fill` step failed | the fill's own message (generative_fill.md §6), or `direct_failed` |
| any other step failed | the failing step's own message, or `direct_failed` |

## 11. Strings (T48)
All Korean, all in `feature/editor` `strings.xml`. The step templates are what keep the model's
prose out of the UI (§3).

| Key | Value |
|---|---|
| `editor_tool_direct` | 지시 |
| `direct_title` | 지시 |
| `direct_placeholder` | 무엇을 바꿀까요? 예: 나무를 더 푸르게 |
| `direct_planning` | 무엇을 할지 생각하는 중 |
| `direct_running` | 적용하는 중 |
| `direct_not_understood` | 무엇을 할지 모르겠어요. 다르게 말해보세요. |
| `direct_not_found` | %1$s을(를) 찾지 못했어요 |
| `direct_needs_key` | 설정에서 Gemini API 키를 입력해주세요 |
| `direct_blocked` | 이 이미지는 편집할 수 없어요 |
| `direct_failed` | 적용하지 못했어요 |
| `direct_step_select` | %1$s 선택 |
| `direct_step_adjust` | %1$s %2$d |
| `direct_step_adjust_masked` | 선택 영역 %1$s %2$d |
| `direct_step_erase` | 선택 영역 지우기 |
| `direct_step_cutout` | 배경 지우기 |
| `direct_step_fill` | 선택 영역에 %1$s 채우기 |
| `direct_step_crop` | %1$s 비율로 자르기 |

`%1$s` in the adjust templates is the existing slider label (`light_exposure` 노출,
`color_saturation` 채도, …) and `%2$d` is the −100…100 integer the sliders already display, so a
step reads in the same units the manual sheet would have shown.

`direct_step_crop`'s `%1$s` is the preset's own chip label from crop.md "What the user sees" — "1:1", "4:5", "9:16",
"16:9" — so the step names the ratio in the characters the 자르기 sheet is about to show the user.
`direct_step_fill`'s `%1$s` is the model's prompt, which is English (generative_fill.md §8), the
same open question `direct_step_select` already carries.

## 12. Tests
No test reaches an external host; `GeminiPlanClient`'s `baseUrl` seam is what makes that true, and
every UI test uses `FakePlanProvider`.

- `GeminiPlanClientTest` on `MockWebServer`: the path names `gemini-3.8-flash`; `x-goog-api-key` is
  present and the key is **absent from the URL**; the body carries one `propose_edit` declaration (§14), the
  system instruction, the image part and the user's sentence verbatim; `toolConfig.mode` is `ANY`;
  two `functionCall` parts decode into two steps **in order**; a text part between them is skipped;
  an unknown function name is dropped and the rest survive; an out-of-range `value` is clamped and a
  non-finite one drops the step; every row of generative_erase.md §6 plus §6's two; cancellation
  mid-flight closes the call.
- `GeminiPlanProviderTest`: a blank request fails before encoding; the encoded image on the wire is
  ≤ 1024 on the long edge; `availability` flips with the key; zero function calls yields an empty
  `EditPlan` rather than a failure.
- `PlanRunnerTest` with the fakes (Turbine on the event flow):
  - the three-step happy path emits `Started`/`Committed` per step then `Completed`, and each
    `Committed` document contains exactly one more op than the last
  - `Adjust(masked = true)` after a `Select` carries the new `activeMaskId`; with `masked = false`
    it carries `null`
  - a `Select` returning an empty list emits `Stopped(0)` and no `Committed`
  - a failure at step 2 leaves step 1's document committed — the partial-run guarantee
  - cancelling the collection mid-step commits nothing for that step
  - `validate` rejects a masked `Adjust`, an `Erase`, a `CutOut` and a `Fill` with no selection
    anywhere, and accepts each of them when the document already has an `activeMaskId`
  - a `Crop` step commits a centred rect at the requested ratio and needs no selection; the plan
    `[Select, Adjust, Crop]` leaves the `Crop` last in `document.operations`
  - a plan whose `crop_ratio` arrived first still runs it last (§5's normalization), asserted on the
    decoded `EditPlan` rather than on the runner
- Tool tests: a blank key greys the tool and tapping it opens the 서버 설정 sheet; an empty plan
  shows the hint and leaves 적용 disabled; 취소 after a plan arrives leaves the document untouched;
  a second submit replaces the first plan; a `Final` speech result auto-submits (prompt_input.md §3
  behaviour, re-checked in this host); the step list renders from the templates and never from a
  response's text part.
- Goldens: `direct_sheet_open` (bar only), `direct_plan_preview` (the two-step plan above).
  No render golden — this feature adds no renderer path; the ops it produces are already covered by
  `exposure_+0.5_masked`, `cutout_render` and `generative_erase_render`.

## 13. What this does not do, and why
- **No multi-turn loop.** The model does not see the result of step 1 before writing step 2. A loop
  would let it correct itself, at the cost of one billed round trip per step, a much larger failure
  surface, and a progress overlay that cannot say how many steps are left. One shot, a preview the
  user approves, and undo as the correction mechanism.
- **No free crop.** `crop_ratio` picks from a closed set of four ratios and hands the framing back
  to the user (§4.1). A model that could express an arbitrary rectangle is still out of scope, for
  the reason §4.1 quotes.
- **No outpainting.** 확대 is a manual tool; outpaint.md §9 records why a model should not choose
  between discarding pixels and inventing them.
- **No editing of the plan.** The step list is read-only: 적용 or 취소. Per-step toggles would be a
  small editor for a script the user did not write; rephrasing is cheaper and the plan is two or
  three lines.
- **The plan is not persisted.** It exists between the response and 적용, and dies with the sheet.
  What survives is the operations it produced, which the document model already knows how to store.
- **No prompt history.** D11 if it is ever wanted.

## 14. 보정 추천 and the shared evaluation (2026-10-13, replaces the 2026-10-07 suggestions)
D091's photo-tailored sentence ids are replaced by D094: one planner evaluates the photo and an
optional request, keeps observation and goal apart, and proposes a workflow the user reviews and
applies. Example sentences remain an input aid.

- **Entry.** Under the bar: `이렇게 말해보세요` with three examples (`조금 더 밝게`, `따뜻한 분위기로`,
  `색감을 자연스럽게`) — a pick only fills the bar (`RequestSource.Suggestion`), never sends — and the
  separate `보정 추천 받기` with `현재 사진의 축소본과 입력한 요청을 Gemini에 보내 추천해요`. It works with an
  empty bar and while typing (examples hide on typing or during review; 숨기기 hides only them).
  Opening the sheet, typing, document or settings changes send nothing.
- **One planner, two modes.** `보정 추천 받기` is `PlanRequest(text, Recommend, context)`; send/voice
  final is `PlanRequest(text, Direct, context)` (blank still refused). Same system instruction,
  same envelope, same priorities, same checks. One tap or send is one call; nothing retries itself.
- **Context.** `planContextOf(document)` sends every `Adjust` as the final value `withAdjust` would
  replace (masked or whole-photo), the selection and crop flags. The instruction says values are
  final settings ("더 밝게" at exposure 0.40 means a value above 0.40), a new masked adjust starts
  at 0 on top of whole-photo values, and a style replaces the whole-photo values it carries.
- **Evaluation rules** (in `PLAN_SYSTEM_INSTRUCTION`, not only here): observation ≠ taste (dark,
  empty, desaturated are not defects; silhouettes, night, high/low-key allowed; `no_change` when
  nothing needs doing); readable shape/texture of what matters over average brightness, material
  lightness differences kept, shadow lifting may add noise, no histogram centring; colour roles
  (preserve / emphasize / background — no global saturation that mostly strengthens the sky; skin,
  product, petal differences kept); white balance only for a clear cast or a request; no promise
  that sharpening recovers blur; composition observed, never enforced, reframing only on request
  and only by ratio; minimal steps and conservative values; one goal set, keep-the-mood default.
  **Superseded by §19 (2026-10-14):** reframing, erasing a visible distraction, a style and Topaz
  may be default suggestions; quality, not the fewest steps or smallest values, is the aim.
- **Priorities.** `PlanPriority`: explicit goal 5, explicit object 3, unrequested improvement 1 —
  ranks, not probabilities or value multipliers. Hard constraints (`subject_only`,
  `keep_background`, `keep_colors`, `other`) outrank any sum. Naming an object is not a goal;
  unidentifiable targets and contradictions are `needs_clarification`; free crops and missing
  tools are `unsupported` or a limitation.
- **Envelope `propose_edit`.** `outcome`, `reason`, `observations[{id, subject, note, confidence
  high|medium|low, unknown}]`, `intent{targets, wants, constraints[{text, kind, explicit}]}`,
  `goals[{id, text, source user|default, scope global|subject|color_range, target,
  observation_ids}]`, `color_roles[{role, color, target}]`, `workflow[{tool, goal_id, args, effect,
  caution}]`, `limitations[]`. Caps: 240 chars per text, 8 observations, 8 goals, 12 workflow
  items, 6 limitations, 8 colour roles. `masked` left out follows the goal's scope.
- **Mechanical checks (`ProposalCheck`)**, any one refuses the whole answer: ready ⇔ non-empty
  workflow with at least one non-Select step and no reason needed; other outcomes have no workflow
  and a reason; a user goal needs non-blank text; ~~Style/Crop/Erase/CutOut/Fill only under a user
  goal~~ (removed by §19); a subject goal uses only Select and selection-consuming steps, each after its **own**
  goal's Select; a global goal only unmasked Adjust, Style, Crop; a colour-range goal only unmasked
  HSL; every Select is used before the next one; `subject_only`/`keep_background` allow no
  whole-photo tone step (crop allowed); `keep_colors` allows no temperature/tint/saturation/
  vibrance/HSL/style; at most one style and no unmasked non-HSL adjust beside it; at most one crop.
  `PlanRunner.validate` (§9.1) still runs on arrival. Whether the model *understood* the photo is
  not checkable here — that is the image evaluation's job.
- **States** (`ProposalPhase`): idle, loading (inline + 취소 for Recommend; overlay for Direct),
  ready, no_change, needs_clarification, unsupported (reason + `요청을 고쳐 다시 추천받을 수 있어요`),
  not understood (hint), failed (snackbar `추천을 받지 못했어요`/`direct_failed`, text kept, explicit
  `다시 시도`), cancelled. 적용 is enabled only for ready.
- **Review.** `관찰 근거` (subject: note (확신 높음/보통/낮음), unknowns), `보정 목표` ([사용자 요청] or
  [추천 기본값] + target, colours to emphasize), `보존할 내용` (constraints, inferred ones marked
  `(추정)`, colours to keep/background), `실행 단계` (local §11 templates with final values, plus
  `예상:`/`주의:` and that predictions are not guarantees), `확인 사항` (limitations). Model text is
  plain `Text`, never markup or links; step lines stay local templates. The sheet's content scrolls,
  [취소 | 적용] stay pinned.
- **Snapshot and lifetime (`DirectProposals`).** A request is pinned to (document — the one the
  frame was rendered from, its id is the project — text, mode, context, Gemini settings generation)
  and a sequence number. Only a frame with `renderedDocument == document` is sent; a Direct send
  before that waits for it behind the overlay, a recommendation is disabled with `사진을 준비하는 중`.
  Text, document (undo/redo/adjust; not the run's own commits), settings, project, closing the sheet
  or another tool and 취소 drop the answer, the cache and the request; a late answer is dropped even
  if the provider ignored cancellation. A new send cancels a recommendation in flight. The last
  answer is cached in memory for an identical snapshot only; `다시 추천` asks once more. 적용 checks
  the snapshot again, runs the **reviewed** plan without re-planning, and a double tap runs once.
  Process death keeps nothing and resumes nothing.
- **Unchanged:** no automatic upload or apply; Undo (§9's per-step history and partial runs are
  superseded by §18: one reviewed candidate, one history entry);
  segmentation failure stops the run with no global fallback; bitmap ownership, IO dispatcher,
  cancellation and session release. The analysis fields are not logged — only the outcome and step
  kinds.

## 15. Direction, the exact snapshot and explicit targets (2026-10-13, D099)
Supersedes §14's per-step history, the unconditional reselection, the fixed 0.15/0.3 steps and
"style + whole-photo adjust is always refused".

- **Direction first.** `propose_edit` carries `direction {subject, strengths[], preserve[],
  objective, success, source}`, required when ready. `source=default` marks an inferred objective as
  an intention, not an observation. Every step still names a goal; no_change and
  needs_clarification stay valid. The review shows it first, briefly (보정 방향).
- **Snapshot.** `planContextOf` builds, from the document the frame was rendered from: every
  `Adjust` as `(kind, final value, ref aN, order, target ref)`; colour ranges with "a band or channel
  not listed is 0"; every mask as `tN` with active flag and a description only when this session
  confirmed it (otherwise *unknown*, never guessed); every operation in render order (generated
  pixels by kind only); the crop rect; the preview size; each style's real values; Topaz capability.
  It states the photo is already rendered with all of it. Mask ids, paths, keys and job ids stay
  local. The snapshot includes the session notes and the Gemini + Topaz settings generation; any
  change drops the proposal and its candidate.
- **Targets.** `select_region(phrase, target, multiple)` declares a new label (never a snapshot
  ref, never twice). `adjust(kind, value, target?)`: no target = whole photo; a snapshot ref updates
  that mask's setting **in place**; a label acts on that new mask (which may overlap and accumulate).
  `erase_selection` / `cut_out_selection` / `fill_selection(target)` take no `masked` and consume the
  selection made right before them; a cut-out deletes **outside** it. There is no `masked` argument;
  `masked=true` refuses the proposal. g1 → g2 → g1 is legal only by naming g1's label; an unnamed
  subject step is refused, never resolved to the active mask (review N2). `PlanRunner.validate` and
  the run enforce the same, with no fallback to the document's active mask.
- **Values are final** everywhere, a left-out value keeps the current one, an explicit 0 resets.
- **State simulation** (`StateSimulation`) replays the steps on the snapshot with `withAdjust`'s and
  `atIntensity`'s rules and refuses: one setting written twice; a value a later style replaces; an
  adjust that repeats (±0.01) or reverses the style before it; a style that writes nothing. Going
  further in the style's own direction, or a kind the style does not write, is allowed. Without the
  catalog's values nothing is claimed. `PlanStateSimulationTest` compares it with the engine.

## 16. Topaz in a plan
`topaz_enhance(purpose, scale?, face_recovery?, sharpen_strength?)` maps onto the nine
`TopazPurpose`s (no model/endpoint strings). **§19 (2026-10-14):** it may follow local steps as the
plan's last hand-off (the client moves it after the crop); at most one per plan; global scope; never
under an **explicit** subject_only / keep_background / colour constraint, never after a cut-out,
never for a transparent document. Options are checked by `TopazJob.problem()` with the snapshot's
working size when known. Every purpose and face recovery may be a default suggestion; the catalog
describes each purpose on its own line (upscale vs crop, sharpen vs the local sharpen slider). The
workflow is §19's (D101): the step runs last, once, on the local candidate's full render, after one
`선택한 보정 실행`; a plan arriving submits nothing; results stay D098's.

## 17. Preservation constraints
`subject_only` / `keep_background`: local steps and a requested crop (with a limitation that the crop
removes what is outside the frame); no whole-photo adjust, colour range, style, cut-out or Topaz.
`keep_frame`: no crop or cut-out. `keep_hue`: no temperature, tint, hue, style, generative or Topaz.
`keep_colors` (default reading of "색은 바꾸지 마"): additionally no saturation, vibrance or colour
range; the review notes tonal steps still change perceived colour. `keep_exact_colors`: only select
and crop. The code checks a plan against the constraint the model wrote; it cannot prove the model
classified the request correctly. **§19:** only `explicit=true` constraints (what the user said)
refuse a proposal or block 적용 (`ProtectedChanged`); an inferred one is shown as `(추정)` and is
the model's taste, never a rule.

## 18. Candidate preview, confirmation, comparison
Flow: snapshot → plan → checks → **선택한 보정 실행** (§19, D101; was 미리보기) → candidate → compare → 적용 / 강도 줄이기 / 되돌리기.
- The runner builds a candidate document; nothing is pushed to history or autosaved. 적용 re-checks
  the document, text, settings and notes and commits **once** (one undo). A failure, a "no", a
  cancel, an undo/redo, another project, a settings change or closing the sheet drops the candidate
  and deletes the files it wrote (`ProjectRepository.discardFiles`, mask_/erase_/fill_ only, never
  ones the saved document references). Process death keeps nothing; the original is reopened.
- **§19 (D101):** a new selection the plan's own phrase found is used without asking once the checks
  below pass; the review names what each was found for (`AI가 찾은 대상: …`) and the next snapshot
  describes it as found (`TargetSource.Resolved`), never as confirmed by the user. Only an existing
  selection of unknown meaning is shown on the overlay before it is used (선택 확인 / 중단, or turn its
  item off). The segmenter's answer is checked first:
  no instance with a set pixel → "찾지 못했어요"; a size other than the frame's → failure; several
  instances for a step without `multiple` → "여러 개 찾아졌어요", never unioned. No fallback, retry
  or partial apply.
- Local checks before review: crop (frame change, told apart from colour), generated patches
  (judged by the user), tonal steps under a colour rule, and — under subject_only/keep_background —
  the share of pixels outside the used selections (dilated by 1/50 of the width) that moved more
  than 2/255, on renders without the crop; above 0.5 % 적용 is off. A leaked mask passes this check,
  which is why the overlay exists. Meaning and taste are the user's, by holding 전후 비교
  (`HoldPill`; canvas shows this input while held).
- 강도 줄이기: each numeric setting that differs moves to `b + 0.5 (t − b)` (b = input value for the same
  kind and mask, 0 for a new mask); a style per value; within half a slider step of b it is b; crops,
  erases, fills and cut-outs are untouched; no server call. Nothing left → "더 줄이면 바뀌는 것이
  없어요", candidate dropped.
- Paid generative steps say so before 실행 and are called once; rejecting or comparing never
  calls again.
- Strength guidance in the prompt comes from `PlanStrengthEvidenceTest` (work/evidence/plan_strength,
  real CPU ops on photo_512 and synthetic dark/pastel/noisy variants). Kinds without a measured row
  are told to stay small and call the amount a guess.

## 19. Quality-first suggestions and per-item choice (2026-10-14, D100)
Supersedes §14's "minimal steps / reframing only on request / never erase unasked", §14's
"Style/Crop/Erase/CutOut/Fill only under a user goal", §16's "Topaz is the whole workflow" and the
request-only Topaz purposes. Unchanged: D098's result boundary, explicit user constraints, the
envelope and every structural check, the snapshot/lifetime rules of §14/§15 and §18's candidate.

- **Policy (both modes, one instruction).** The best finished photo for its subject, composition,
  mood and the user's purpose; any tool — light, colour, detail, style, ratio crop, erasing a visible
  distraction, generative fill, one Topaz purpose — when this photo benefits; never omitted only for
  not being named, never added only because it exists; `no_change` stays valid. Erase only a specific
  thing that can be pointed at, with what and why; never the subject or part of the story. In Direct,
  the request is met with user goals and anything more is a separate `default` goal (shown as
  `요청 외에 AI가 추가로 제안한 보정이에요`). Independent effects are separate steps; a style is one.
  The prompt says this; nothing measures whether the model now chooses better.
- **Checks.** `ProposalCheck` no longer refuses a step for its goal's source. It still refuses
  unknown tools/options/targets, dangling ids, goal-scope violations, a second crop/style/Topaz,
  Topaz after a cut-out, steps an **explicit** constraint forbids, and §15's state simulation.
  Sources are never rewritten.
- **Items.** `PlanProposal.items`: every non-`Select` step is one item, id = the decoder's wire
  position (`s3`, `s3.1`… for colour-range channels), kept through the crop/Topaz reorder.
  `selected(chosen)` keeps the chosen items plus the selections they consume, in the original order;
  a selection runs iff one of its consumers is on. `selectionProblem(context)` re-runs targets,
  explicit constraints, Topaz structure and the state simulation on the choice, and
  `PlanRunner.validate` re-runs on the document, before every run. A refused model answer is still
  refused whole; turning an item off is the user's and is never done silently by the app.
- **State.** `DirectState.chosen` (ids) and `blocks` (id → `TopazBlock`) belong to the current
  proposal; a new or dropped proposal resets them. Every item starts on except a blocked one
  (`NoKey`, or an input the purpose cannot take), which starts off with its reason on the row. Toggles
  call nothing (no Gemini, SAM, generative or Topaz call, no edit). They are locked while a run,
  overlay confirmation or check is in progress; 취소 is available and the choice is editable after.
- **Rows** (`DirectSheet` 실행 단계, inside the existing scroll): `선택한 보정 n/m개` or
  `적용할 보정을 선택하세요`; per item the correction ("sign 지우기", "노출 12", local templates), `대상:`
  (the goal's Korean target, else the phrase, else 사진 전체), 예상/주의, cost (`생성 보정 · …`,
  `Topaz 유료 · …`, generative Topaz warns it may redraw), the AI-addition line, a block reason and
  `적용함`/`적용 안 함`. The whole row is one `toggleable(role = Switch)` ≥ 48dp with a state
  description; no wire names are shown.
- **Flow.** plan → choice → 선택한 보정 실행 (every chosen item, Topaz last — see *One run to the end*) →
  candidate → compare / 강도 줄이기 / change the choice → keep. With nothing on, 실행/적용 are off and no history is made. A candidate carries the
  local choice it was built for; 적용 is enabled only when that equals the current choice.
- **Rebuild.** Changing the local choice under a candidate rebuilds it from the same input with the
  chosen steps only (never by deleting operations from the old result). `PlanCache` (session-owned)
  reuses confirmed selections (no SAM call, no second overlay) and generated patches keyed by step +
  phrase + the input's non-`Adjust` operations + active selection — exactly what the provider was
  shown. A rebuild that would need a remote call stops at `CandidatePhase.Stale`
  (`선택이 바뀌어 다시 실행해야 해요`, canvas shows the input, 적용 off) until the user taps
  `선택한 보정 다시 실행`, which calls only what misses. No automatic retry or re-charge; compare never calls.
  Every local choice off drops the candidate. A rebuild after 강도 줄이기 is at the proposal's own
  strength and says `선택을 바꿔 제안한 원래 강도로 다시 만들었어요`; halving never revives an off item.
- **Lifetime and files.** The session runs from the first run to 적용 / invalidation / close; a
  sequence number drops any late run or check. 되돌리기 and a failed or cancelled run keep the session's
  files for reuse; the session's end deletes every file its runs wrote except the applied document's
  (`ProjectRepository.discardFiles`). Process death keeps nothing and resumes nothing.
- **One run to the end (2026-10-14, D101; replaces D100's Topaz hand-off and §18's per-selection
  confirmation).** Contract: plan / toggle review (no call) → one `선택한 보정 실행` → local steps,
  found selections, generated patches and the crop into an uncommitted candidate → the chosen Topaz
  step on that candidate → the whole result on this canvas → the user keeps it. Recommendation and
  지시 share this path; before the tap nothing is submitted. The item rows and
  `실행하면 … Topaz 화질 개선까지 자동으로 진행해요. 이때 크레딧이 사용돼요` say what is paid first.
  - Topaz step: the candidate is re-checked (`TopazBlock`: key, real size once cropped, alpha) and,
    unless blocked or under a `ProtectedChanged` finding, rendered with `Renderer.full`, checked with
    `TopazJob.problem()` on the real input (options a purpose does not offer run as its defaults) and
    submitted exactly once through `TopazProcessor` — the 화질 개선 sheet's own processing; the sheet
    is not opened. Progress is the run's last step with the provider's stage and only its own
    percentage. Nothing is committed and no project is created to make the input.
  - Review: the canvas shows the complete result only — the Topaz result (sampled at preview size,
    its own aspect) with Topaz on, the local candidate without; 전 is the workflow's input (`HoldPill`,
    calls nothing). A local + Topaz choice without a Topaz result shows the input and no 후. Without
    Topaz, 적용 commits once (one undo). With Topaz, `고해상도 저장` (byte copy) or `새 편집본으로 열기`
    (one `create`, local edits already in its pixels, nothing re-applied); the original's document,
    history and autosave never change; nothing is saved or opened automatically; a create nobody
    wants any more is deleted.
  - Toggles after a run: a local change rebuilds offline (§19 Rebuild); the Topaz item alone moving
    shows this session's result for the same candidate or none. Topaz results are kept per session
    keyed by the candidate's source and rendered operations (ids a rebuild re-mints ignored), the item
    and the key generation — not by `PlanCache`'s generated-patch key, which ignores `Adjust`. A miss
    (another local choice, 강도 줄이기) shows no result, nothing to keep, and the primary
    `선택한 보정 다시 실행` (one submit). Topaz off shows the local candidate; off→on for the same input
    reuses the result; toggling, comparing and recomposing never submit.
  - Failures: a local failure stops the run with its reason and nothing after it runs. A Topaz
    failure (or `SubmitUnknown`, with its existing warning) keeps the local candidate under review
    with the reason and `화질 개선 항목을 끄면 …`; it is never retried by itself, only by 다시 실행.
    A failed save or copy reuses the result and never calls Topaz.
  - Identity and files: the run sequence number covers the local steps, the Topaz step, a save and a
    copy; cancel, close/Back, another tool, a document or settings change, or a new run makes a late
    stage, result or file land nowhere (a result file from a provider that ignored its cancellation is
    deleted). The session's Topaz folder is discarded at the session's end after a save is stopped and
    a create has finished. Process death resumes nothing.
  One purpose per plan; multiple Topaz results are never chained.
