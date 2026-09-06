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
`gemini-2.5-flash` along with a description of every editing function it has, gets back a list of
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
       2. POST …/gemini-2.5-flash:generateContent with 7 functionDeclarations   §5
       3. read candidates[0].content.parts[*].functionCall, in order            §5
  └─ EditPlan(steps = [Select("나무"), Adjust(Saturation, +0.3, masked = true)])
  └─ validate against the document                            §9.1
  └─ preview list, [취소 | 적용]                               §3
  └─ PlanRunner                                               §9
       Select("나무")      → segmentation.byText → union → Operation.Mask → history.push
       Adjust(Saturation)  → doc.withAdjust(kind, value, activeMaskId) → history.push
```

One planning call per request. The model never sees the intermediate result, which is what makes
the round trip cheap and the failure surface small; §13 records what that costs.

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
                                    [취소 | 적용]  Apply is the sheet's one accent
```

- The bar is `VoicePromptBar` with a different placeholder (§11). That placeholder becomes a
  parameter on `PromptBar` in T48, defaulting to the string the 선택 tool already passes, so no
  existing behaviour or golden moves (prompt_input.md §2). The mic behaves
  exactly as prompt_input.md §3 specifies, including a `Final` result auto-submitting and the mic
  being absent when the device has no recognizer.
- Submitting asks for a plan. While that call is in flight the bar is disabled and the progress
  overlay shows `direct_planning` with a cancel button (DESIGN.md §4 State display, §7).
- A plan arrives → the step list replaces nothing; it appears under the bar and 적용 becomes
  enabled. 적용 is `primary`, and it is the surface's one accent — the prompt bar's send icon stays
  `editInk` (prompt_input.md §2).
- Submitting a second phrase replaces the previous plan. Nothing has been applied yet, so there is
  nothing to undo.
- 적용 runs the plan (§9). The sheet closes when the run ends, whether it completed or stopped.
- 취소 / system back discards the plan. The document is untouched, because nothing runs before 적용.

**Step lines are assembled locally, never quoted from the model.** DESIGN.md §9 puts user-facing
Korean in `strings.xml`, and a model-authored sentence would route around that — it could not be
reviewed, translated or kept in tone. Each `PlanStep` renders through one template (§11) using the
adjustment labels the sheets already use (`light_exposure`, `color_saturation`, …). Text parts in
the response are therefore ignored, not shown.

## 4. The function catalog (T45, extended by T58 and T62)
Seven functions — everything the editor can do that a sentence can plausibly ask for. Names,
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
3. **The 자르기 tool opens straight afterwards** (§9.2), with the preset chip selected and the
   committed rect loaded. The model chose the ratio; the user chooses the framing, by dragging, in
   the tool that was built for it. The wrong-crop failure mode the old ruling feared costs one drag
   rather than one undo.

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
POST {baseUrl}/v1beta/models/gemini-2.5-flash:generateContent
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
  "tools": [{ "functionDeclarations": [ /* §4's seven, OpenAPI-subset schemas */ ] }],
  "toolConfig": { "functionCallingConfig": { "mode": "ANY" } }
}
```

- **The model is `gemini-2.5-flash`, not `-image`.** This call produces a decision, not pixels; the
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

**Reading the response.** `candidates[0].content.parts` is scanned in order and every part carrying
`functionCall { name, args }` is kept; text parts are skipped, the way the eraser skips them
(generative_erase.md §5). A part whose `name` is not one of §4's seven, or whose `args` do not
satisfy §4's types, is **dropped with a log warning** rather than failing the whole plan — the same
rule `EditDocumentJson` applies to an unknown operation, and for the same reason: one bad element
should not lose the good ones. `value` is clamped to the kind's range from edit_model.md
(−1…1, or 0…1 for `sharpen` and `vignette`); a non-finite `value` drops the step.

**`crop_ratio` is normalized after decoding (T58).** However many the model emitted and wherever
it put them, the client keeps the **last** one and moves it to the **end** of the step list. Two
crops in one plan is the model restating itself, not a request to crop twice, and `Operation.Crop`
is at-most-one anyway (edit_model.md). An unknown `ratio` value drops the step, the same rule every
other malformed argument gets. No other step is reordered — the model's order is the plan
everywhere else.

**Zero function calls is a valid answer, not an error.** It is what the last line of the system
instruction asks for when the request is out of scope, and it surfaces as the `direct_not_understood`
hint (§10) — never a snackbar, matching the way `byText` returning nothing is a hint in
selection_tool.md §7.

## 6. Error mapping
Identical to generative_erase.md §6, row for row, onto the same `AppError` cases. **No new case is
added.** Two rows differ because a plan is not an image:

| Condition | `AppError` |
|---|---|
| `200` with no `functionCall` part | *not an error* — an empty `EditPlan`, shown as the §10 hint |
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
    suspend fun plan(image: Bitmap, request: String): Result<EditPlan>
}
```

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
1. `request.isBlank()` → `Invalid("empty request")` before any encoding.
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

- `GeminiPlanClientTest` on `MockWebServer`: the path names `gemini-2.5-flash`; `x-goog-api-key` is
  present and the key is **absent from the URL**; the body carries seven `functionDeclarations`, the
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
