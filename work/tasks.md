# tasks.md — Ralph loop task queue

T01–T56 are done. What each one built is in `progress.md` under `## Done`, why it was built that
way is in `work/decisions.md`, and the `done when` checklists are in git history. None of it is
repeated here: this file is read on every loop iteration, so it holds only what is still open.

Rules for the agent: unchanged — see CLAUDE.md "Ralph loop rules". One task per iteration,
`scripts/check.sh` is the only verdict, only edit checkboxes in this file.

Legend: `[ ]` todo · `[x]` done · `[!]` blocked · `[H]` human-only, loop must skip

---

## Queue

**Phase 12, T59–T65.** T57 and T66 are `[!]` — both need a human. Pick the first `[ ]` whose deps
are all `[x]`, as always.

---

## Already built — do not re-open

**Mask-scoped local editing is finished** (T29–T33, specs/selection_tool.md §8.1). A SAM 3 selection
is `Operation.Mask` + `activeMaskId`; `Operation.Adjust` carries a `maskId`; the renderer does
`out = lerp(in, op(in), maskAlpha)`; all three adjust sheets show the "선택 영역에만" toggle through
`MaskOption`; and the render golden `exposure_+0.5_masked` plus `MaskedAdjustToggleTest` cover it.

**지시 is finished** (Phase 9, T44–T48, specs/vibe_edit.md). One sentence becomes a plan of
`PlanStep`s that `PlanRunner` executes against the providers the manual tools already use.

**Phase 10's three device defects are fixed** (T49–T53): the renderer walks operations in list
order, the erase mask carries a margin, and both prompts were rewritten. T53 proved the
generative + adjust combinations against the real renderer without adding production code.

**혼합 is finished** (Phase 11, T54–T56, specs/adjust_hsl.md): eight hue bands × 색조/채도/휘도 as
24 appended `AdjustKind` entries, the `Tool.Mix` sheet, and the planner's fifth function
`adjust_color_range`.

None of it is verified on a device beyond the 2026-09-06 run — see `progress.md` "Open issues for a
human". That is a human's job, not a task.

---

## Prerequisites (human work, not a task — the loop must never pick this up)

- [H] **minSdk 26 → 33 in `gradle/libs.versions.toml`.** T66 (the AGSL backend) cannot start
  without it: `RuntimeShader` arrives at API 33. CLAUDE.md freezes that file, and this is a product
  decision as much as a technical one — it drops Android 8 through 12. The same change must correct
  `specs/architecture.md` §2 and imaging.md's "minSdk is 26 and HEIF decoding only arrives at
  API 28", which becomes wrong rather than merely stale. See gpu_render.md §1.
- [H] **A `scripts/bench.sh` number showing the render budget is missed.** Also T66. Porting a
  renderer that already meets its budget is work with no user-visible result. Measure the six-HSL-
  adjust case specifically (gpu_render.md §1). If the budget is met, T66 closes as "not needed" and
  the numbers go in `work/decisions.md` — that is a good outcome, not a failure.

Nothing runs for real without this either: **a human must paste a Gemini API
key into the 서버 설정 sheet on the device**, and point `sam3.baseUrl` / `sam3.token` at a running
service. No key is shipped, committed, or read from `.env` at build time — see generative_erase.md
§2. Every test uses `FakePlanProvider`, `FakeSegmentationProvider`, `FakeEraseProvider` or
`MockWebServer`, so `check` is green with no key and no server at all.

If a task hits a missing prerequisite, mark it `[!]` and write the reason in `blocked.md`. Do not
add the dependency yourself.

---

## Open decisions — a human answers these, the loop must not

### From Phase 10

Both come out of the same device finding: **SAM 3's text endpoint understands English concepts, not
Korean.** T52 fixed it for the 지시 tool, where the model writes the phrase. These are what to do
next if the answers differ from the defaults T52 took.

1. **The 선택 tool's own prompt bar is still Korean.** There the *user* types the phrase, and
   "나무" reaches `byText` unchanged and finds nothing. The options are a translation call before
   every `byText` (a second model round trip on a path that is currently one), an English
   placeholder and a hint line, or leaving it. Each needs a different amendment to
   prompt_input.md §4, so the loop cannot choose.

2. **English nouns in the 지시 step list.** T52 renders "bus 선택", which is honest and costs
   nothing. The alternative is a second `label` argument on `select_region` carrying Korean for
   display — better reading, but it puts a model-authored string on screen, which vibe_edit.md §3
   currently forbids. That is a spec amendment to §4 and §11, not a task.

### From Phase 11

3. ~~**render.md line 54**~~ — **resolved.** The sentence now points at adjust_hsl.md §10 for the
   HSL kinds. Done outside the loop, since `specs/*.md` is frozen for it.
4. **adjust_hsl.md §7's two rulings** — the selected chip is an `editInk` ring rather than the
   accent, and 색조 labels both `color_tint` and `mix_hue` — were taken on DESIGN.md's behalf and
   are now recorded in T55's goldens. Overruling them from here means re-recording those goldens.

---

## Phase 12 — 자르기를 시킬 수 있게, 만들 수 있게, 넓힐 수 있게

Five things the user asked for after the first device run: a sheet-cancel bug, a sixth planner
function, a generative tool that takes a noun, a tool that draws past the frame, and the AGSL port
that has been Deferred since T01.

**Read the spec before coding.** Three of these have specs written from scratch —
`specs/generative_fill.md`, `specs/outpaint.md`, `specs/gpu_render.md` — and the rest amend existing
ones. The `spec:` line on each task is not optional reading.

The one thing genuinely new to the architecture is `Operation.Outpaint`: it is the **only** op that
makes the canvas bigger. outpaint.md §2–§3 is the whole reason it has the shape it does, and it
names the two designs it rejects. If a task there looks like it needs a second coordinate space, the
design drifted — block it rather than adding one.

- [!] T57 A sheet's 취소 must not tap the tool underneath it
  spec: specs/editor_shell.md ("Sheets slide up over the tool strip"), DESIGN.md §4 (Bottom sheet)
  deps: —
  report: "종종 디테일 탭 눌렀다가 취소하면 그 다음에 빛 탭이 뜨는데 이건 누른적이 없는데 왜
  뜨는거지" — cancelling a sheet opens a tool the user never tapped.
  blocked: not reproducible off-device; the fall-through hypothesis is **disproven**. See
  `blocked.md`. Three reproduction attempts are committed as `SheetCancelTest` and pass.
  done when:
    - **a failing test first.** `EditSheet`'s `[취소 | 적용]` row is the last row of a bottom-aligned
      sheet, so 취소 sits directly over the tool strip's leftmost item, 빛 — `EditorScreen.kt`'s
      `SheetOverlay` is a plain `Box` at `Alignment.BottomCenter` over `EditorBody`, with no scrim
      and no animation. Reproduce **that**: a click on 취소 leaves `selectedTool` null and opens no
      second sheet
    - if it cannot be reproduced within the attempt budget, **mark the task `[!]` and write what was
      tried in `blocked.md`.** Do not "fix" an unreproduced bug — the geometry above is a hypothesis,
      and a speculative change here is untestable and unreviewable
    - the fix makes the sheet consume pointer input so nothing beneath it is hit-testable while it is
      open. **A full-screen scrim is not an acceptable fix**: DESIGN.md §4 gives these sheets no
      scrim, and adding one is a design change wearing a bug fix's clothes
    - companion cleanup, same task because it is the same defect's other half:
      `EditorRoute.sheetFor()` returns a non-null lambda whenever `document != null`, **even with
      `selectedTool == null`**, so `EditorScreen`'s `sheet != null` no longer means "a sheet is open"
      and `canvasInset` is computed from that. It must return null when no sheet is open
    - every existing sheet golden passes **without re-recording**. This changes input handling, not
      layout; a golden that moves means the fix reached too far
    - one test covers 빛, 색, 혼합 and 디테일 at once, since all four share `EditSheet`
  touches: feature/editor/EditorScreen.kt, feature/editor/EditorRoute.kt, feature/editor tests

- [x] T58 `crop_ratio` — the planner's sixth function
  spec: specs/vibe_edit.md §4, §4.1, §5, §7, §9.1, §9.2, §11; specs/crop.md
  deps: —
  done when:
    - `crop_ratio(ratio: enum{square, portrait_4_5, story_9_16, landscape_16_9})` declared in
      `GeminiPlanCatalog` per §4.1, as English `internal` constants beside the other five. Wire
      payload, so **not** in `strings.xml`. `Free` is not a wire value — a model choosing 자유 is
      choosing nothing
    - `PlanStep.Crop(ratio)` and `enum class CropRatio` in `core:ai` (ai_provider.md §3). `CropRatio`
      maps to `AspectPreset` at the `feature:editor` boundary — **`core:ai` does not reach for crop
      geometry**, and the module edge stays "`AdjustKind`, and now `CropRatio`"
    - `GeminiPlanClient` normalizes per §5: **keep the last `crop_ratio`, move it to the end** of the
      step list, whatever order the model emitted. No other step is reordered. An unknown `ratio`
      drops the step and later steps survive
    - `PlanRunner`'s `Crop` step commits
      `CropState.from(document, sourceAspect).withPreset(preset).applyTo(document)` — **no new
      geometry.** `CropGeometry.applyPreset` already computes the centred rect the chips use; writing
      a centred-rect helper here means the existing one was not found
    - `validate` gains **no clause**: a `Crop` consumes no selection. Needing a new rule in §9.1
      means the step is carrying more than a ratio
    - the hand-off is `EditorViewModel`'s, not the runner's: when the run ends, if the plan's last
      step was a `Crop`, call `onToolClick(Tool.Crop)` once the sheet has closed. `RunEvent` and the
      runner's constructor are **unchanged**
    - `PLAN_SYSTEM_INSTRUCTION` gains §4's two new rules (crop at most once; never to "improve"
      framing the user did not ask to reframe) and one worked example, and nothing else
    - `direct_step_crop` in `feature/editor` `strings.xml`, rendering the preset's own chip label
    - tests: §12's planner list, plus the recorded body declaring six functions; a `crop_ratio` sent
      first still decodes last; two of them decode to one; `[Select, Adjust, Crop]` leaves the `Crop`
      last in `document.operations`; the ViewModel opens 자르기 after such a run
  touches: core/ai/gemini/GeminiPlanCatalog.kt, core/ai/gemini/GeminiPlanClient.kt,
  core/ai/EditPlanProvider.kt, core/ai tests, feature/editor/tools/direct,
  feature/editor/EditorViewModel.kt, feature/editor strings.xml, feature/editor tests

- [ ] T59 `Operation.GenerativeFill` — the op, the blend, the round trip
  spec: specs/generative_fill.md §5, §9; specs/edit_model.md; specs/render.md
  deps: —
  done when:
    - `Operation.GenerativeFill(id, maskId, resultRef, prompt)` in `core/imaging/model`, and
      `document.withGenerativeFill(...)` beside `withGenerativeErase`
    - the renderer dispatches it from T49's **existing** in-order walk with one new `when` branch and
      the **same** `lerp(in, result, maskAlpha)` composite. **No new renderer path.** If the blend
      looks like it needs changing, stop and re-read render.md
    - `maskId` validation, file lifetime (`fill_<id>.png`) and history reuse `GenerativeErase`'s
      rules with no special case anywhere
    - the JSON root `v` stays **1**: only an op type was added, and an older build drops it and still
      loads the document (edit_model.md Serialization)
    - `prompt` round-trips. edit_model.md now records why this op stores one and `Mask` does not
    - render golden `generative_fill_render`, listed in `golden_manifest.txt` under a `# T59`
      heading. Nothing else in that manifest moves
    - order test: `[Mask, GenerativeFill, Adjust(masked)]` leaves the adjustment visible **inside**
      the filled region — T49's rule, re-checked for the new op
    - the existing render goldens pass **without re-recording**
  touches: core/imaging/model/Operation.kt, core/imaging/model (document helpers),
  core/imaging/render/Renderer.kt, core/imaging (serialization), core/imaging tests,
  core/imaging/src/test/resources/golden, core/imaging/src/test/resources/golden_manifest.txt

- [ ] T60 `FillProvider`, and the instruction becomes an argument
  spec: specs/generative_fill.md §3, §4, §9; specs/ai_provider.md §3; specs/generative_erase.md §4, §5
  deps: —
  done when:
    - `GeminiEraseClient` exposes `edit(image, instruction)`. **The class is not renamed** and
      `GeminiEraseProvider` keeps `ERASE_INSTRUCTION`. Every `GeminiEraseClientTest` case passes
      unchanged apart from supplying the instruction; needing to edit one means the parameter landed
      in the wrong place
    - `FillProvider` in `core:ai` beside `EraseProvider` (ai_provider.md §3), and `GeminiFillProvider`
      running generative_erase.md §7's five steps verbatim, plus a blank-prompt guard before any
      encoding
    - `WhiteFill` and `GeminiImageCodec` are **reused unchanged**. A second white-fill is the signal
      the design drifted
    - `FILL_INSTRUCTION` per §3, an English `internal` constant, including the fallback sentence that
      degrades an unfillable prompt into a continuation rather than a failure
    - T51's still-white guard applies here too, at the **same** threshold constant — not a second one
    - error mapping is generative_erase.md §6 row for row. **No new `AppError` case**
    - `AiModule` gains `@Binds fun fill(impl: GeminiFillProvider): FillProvider`. No existing binding
      changes
    - `FakeFillProvider` in `core/ai/src/testShared` beside the other three, deterministic enough for
      a golden to depend on
    - tests: §9's client and provider lists, including the load-bearing one — decode the recorded
      body, assert a masked pixel is white and the prompt is in the instruction verbatim
  touches: core/ai/gemini, core/ai (the provider interface file), core/ai/AiModule.kt,
  core/ai/src/testShared, core/ai tests

- [ ] T61 채우기 — the tool and its sheet
  spec: specs/generative_fill.md §6, §7, §9; specs/prompt_input.md §2–§3; specs/editor_shell.md; DESIGN.md §4
  deps: T59, T60
  done when:
    - `Tool.Fill(editor_tool_fill, …, isAi = true)` inserted **after `Tool.Erase`**, so the two
      generative region tools sit together. `Tool` is not serialized anywhere, so nothing migrates
    - `FillSheet`: title, `VoicePromptBar` with the §7 placeholder through T48's `placeholder`
      parameter, pinned [취소 | 적용]. **The three prompt-bar goldens pass without re-recording**
    - 적용 is disabled while the prompt is blank, through `EditSheet.applyEnabled` — an existing
      parameter, not a new one. The send icon stays `editInk`: **the sheet's one accent is 적용**
      (DESIGN.md §4 prompt bar), so this sheet commits the way every other one does
    - `FillController` owns the call and the state, shaped like `EraseController`, including the
      controller-returns-an-intent pattern for a blank key opening the 서버 설정 sheet. One settings
      sheet, one owner
    - on failure the sheet stays open with the prompt intact; cancelling commits nothing
    - the §7 strings in `feature/editor` `strings.xml`; nothing hardcoded in a Composable
    - goldens `fill_sheet_open` and `fill_sheet_typed`; `editor_shell_default` re-recorded **for one
      reason only** — the strip gains a ninth item — and the commit message says so
    - tests: §9's tool list
  touches: feature/editor/tools/fill, feature/editor/Tool.kt,
  feature/editor/tools/ToolSheetHost.kt, feature/editor/EditorViewModel.kt,
  feature/editor/EditorRoute.kt, feature/editor strings.xml, feature/editor tests,
  feature/editor screenshot goldens

- [ ] T62 `fill_selection` — the planner's seventh function
  spec: specs/generative_fill.md §8, §9; specs/vibe_edit.md §4, §5, §9.1, §9.2, §11
  deps: T58, T60, T61
  note: T58 is a dep only because both tasks add to the same catalog and this one's test asserts
  the count. Running them the other way round would make that assertion read six, not seven.
  done when:
    - `fill_selection(prompt: string)` declared in `GeminiPlanCatalog` per §8, English `internal`
      constants beside the other six
    - `PlanStep.Fill(prompt)` in `core:ai`; `PlanRunner` gains the `FillProvider` and a
      `saveFillResult` lambda (vibe_edit.md §9), and the `Fill` row of §9.2
    - `validate` needs **no new rule** — `Fill` consumes a selection, which §9.1 already covers.
      Adding a clause means the step is carrying more than a prompt
    - `PLAN_SYSTEM_INSTRUCTION` gains §8's one rule — 채우기 replaces, 지우기 removes — and one
      worked example, and nothing else
    - the model writes `prompt` in English, per T52's existing rule. `direct_step_fill` renders it
      through the §11 template: the same open question `direct_step_select` already carries, and not
      a new one to solve here
    - a blank or absent `prompt` **drops the step**; later steps survive
    - tests: §9's planner list, including the recorded body declaring seven functions
  touches: core/ai/gemini/GeminiPlanCatalog.kt, core/ai/gemini/GeminiPlanClient.kt,
  core/ai/EditPlanProvider.kt, core/ai tests, feature/editor/tools/direct,
  feature/editor/EditorViewModel.kt, feature/editor strings.xml, feature/editor tests

- [ ] T63 `Operation.Outpaint` — the one op that makes the canvas bigger
  spec: specs/outpaint.md §2, §3, §4, §8; specs/edit_model.md; specs/render.md; specs/crop.md
  deps: —
  done when:
    - **read outpaint.md §2 and §3 before writing a line.** The coordinate-space decision is the
      whole design, and the two rejected alternatives are named there so they are not re-invented
    - `Margins` and `Operation.Outpaint(id, margins, resultRef)` in `core/imaging/model`;
      `MAX_MARGIN_FRACTION = 0.5f` is one named constant with §3's sentence as its KDoc
    - `withOutpaint` **inserts at index 0** and replaces an existing one. A document holds at most one
    - `withOutpaint` **refuses** while any `Mask`, `CutOut`, `GenerativeErase` or `GenerativeFill` op
      exists, and **re-normalizes an existing `Crop.rect`** with §3's arithmetic. Both are
      model-layer rules, not tool-layer ones, so neither the tool nor a future planner can bypass them
    - the renderer gains render.md's Pipeline step 2 and nothing else: the expanded canvas,
      `resultRef` scaled to fill it, the decoded source drawn into the interior with an
      `OUTPAINT_BLEND_PX = 8` alpha ramp. §4 records why this departs from generative_erase.md §11's
      hard edge
    - `Renderer.full` composes the stored PNG rather than dropping it, and the interior is the
      **full-resolution source**, not the model's upscaled version — that is the entire reason §3
      chose this shape over flattening. A golden samples well inside the ramp and proves it
    - `onProgress` still reaches exactly 1f and never goes backwards
    - the JSON root `v` stays **1**
    - goldens `outpaint_render` and the interior-fidelity case, under a `# T63` heading in
      `golden_manifest.txt`. Every existing render golden passes **without re-recording**
    - tests: §8's geometry and document lists — the crop re-normalization is the identity at zero
      margins and round-trips through 0.25
  touches: core/imaging/model/Operation.kt, core/imaging/model (document helpers),
  core/imaging/render/Renderer.kt, core/imaging tests,
  core/imaging/src/test/resources/golden, core/imaging/src/test/resources/golden_manifest.txt

- [ ] T64 `WhitePad` and `OutpaintProvider`
  spec: specs/outpaint.md §5, §8; specs/ai_provider.md §3; specs/generative_erase.md §4
  deps: —
  done when:
    - `WhitePad.apply(image, margins)` beside `WhiteFill` in `core/ai/gemini`: a new ARGB_8888 canvas
      with the new border opaque `#FFFFFF`, the interior copied verbatim, the input untouched
    - `OutpaintProvider` and `Margins` in `core:ai` (ai_provider.md §3); `GeminiOutpaintProvider`
      posting through the **same** `GeminiEraseClient.edit` seam T60 adds, with its own English
      `internal` instruction constant
    - **the aspect guard of §5**: an answer whose aspect differs from the request by more than 2%
      fails with `Unsupported` rather than being scaled into place. generative_erase.md §11 accepted
      that risk without a guard because nothing outside its mask could move; here the whole frame is
      at stake, so the guard is written
    - T51's still-white guard applies, measured over the **border** rather than a mask, at the same
      threshold constant
    - error mapping is generative_erase.md §6 row for row. **No new `AppError` case**
    - `AiModule` gains one `@Binds`; no existing binding changes. `FakeOutpaintProvider` in
      `core/ai/src/testShared`
    - tests: §8's `WhitePadTest` and provider list, including decoding the recorded body and sampling
      a **border** pixel white
  touches: core/ai/gemini, core/ai (the provider interface file), core/ai/AiModule.kt,
  core/ai/src/testShared, core/ai tests

- [ ] T65 확대 — the overlay and the sheet
  spec: specs/outpaint.md §6, §7, §8; specs/canvas.md; specs/crop.md; DESIGN.md §2, §4, §5
  deps: T63, T64
  done when:
    - `Tool.Expand(editor_tool_expand, …, isAi = true)` inserted after `Tool.Fill`
    - the overlay claims canvas.md's **single** overlay slot the way 자르기 does: the pending margins
      drawn with the 8dp `canvasCheckerA`/`canvasCheckerB` pattern DESIGN.md §2 already defines for "no pixels here",
      and four 24dp edge handles with 48dp hit areas that **drag outward only**, clamping at
      `MAX_MARGIN_FRACTION`. No rubber-band, no snap. A drag outside a handle pans the canvas
    - `ExpandSheet` per §6: title, the ratio readout in `mono` (DESIGN.md §3's role for computed
      numbers), pinned [취소 | 적용] with 적용 the sheet's one accent. **No prompt bar** — §6 says why
    - 적용 disabled while every margin is 0; the mask-op guard greys the tool with
      `expand_after_mask`; a blank key opens the 서버 설정 sheet through the same intent shape
    - cancelling leaves the document byte-for-byte untouched; a failure leaves the sheet open with
      the margins intact
    - the §7 strings in `feature/editor` `strings.xml`; nothing hardcoded in a Composable
    - goldens `expand_overlay` and `expand_sheet_open`; `editor_shell_default` re-recorded **for one
      reason only** — the strip gains a tenth item
    - tests: §8's tool list
  touches: feature/editor/tools/expand, feature/editor/Tool.kt,
  feature/editor/tools/ToolSheetHost.kt, feature/editor/EditorViewModel.kt,
  feature/editor/EditorRoute.kt, feature/editor strings.xml, feature/editor tests,
  feature/editor screenshot goldens

- [!] T66 The AGSL render backend (was D03)
  spec: specs/gpu_render.md; specs/render.md; specs/adjust_hsl.md §10
  deps: both `[H]` prerequisites above
  blocked: a human must bump minSdk to 33 on a frozen file, **and** produce a `scripts/bench.sh`
  number showing the render budget is actually missed. See `blocked.md`.
  done when:
    - `scripts/check.sh` green with `golden_manifest.txt` **untouched** and the 2/255 · 99.9%
      tolerance **untouched**. That is the whole success criterion
    - no golden re-recorded. Not one. A kind whose shader cannot match its golden stays on the CPU
      implementation — partial adoption is an acceptable outcome, a re-recorded golden is not
    - `Renderer`'s interface and `Ops.kt`'s signatures unchanged; the port replaces internals
    - **no `Build.VERSION` check anywhere.** With minSdk 33 there is one implementation or none
      (gpu_render.md §2); a version branch means the prerequisite was skipped
    - the masked composite, `GenerativeErase`, `GenerativeFill`, `Outpaint` and `Crop` stay on the
      Canvas path — they composite, they do not compute
    - bench numbers before and after, in the commit message **and** in `work/decisions.md`
    - shader chaining (gpu_render.md §4 step 3) is **out of scope** and becomes its own task if the
      numbers still ask for it

---

## Backlog

_Empty._ The next queue comes from the **second** device run: T51 and T52 are prompt rewrites, and
only a real model can say whether they hold.

**Phase 11 needs that run too**: T56's rules are a prompt change, and only a real model can say
whether the planner reaches for `adjust_color_range` instead of trying to select a colour.
Everything else in Phase 11 is proven by goldens and properties.

**Phase 12 adds three more prompt questions to that same run**, and no fourth: whether the planner
picks `crop_ratio` for "인스타에 올릴 비율로" instead of ignoring the shape; whether it tells
`fill_selection` and `erase_selection` apart; and whether `gemini-2.5-flash-image` will actually
paint past a white border rather than returning the photograph it was given (outpaint.md §5's guard
turns that failure into a retry, but only a device run says how often it fires). T57, T59, T63 and
T66 need no device at all — they are goldens, properties and a benchmark.

---

## Deferred
- D01 Layers · D02 Text · D04 Tablet · D05 Onboarding
  - _(D03 GPU render promoted to T66; see specs/gpu_render.md. D10 promoted to
    specs/generative_fill.md. D12 promoted to T58 — vibe_edit.md §4.1 records why the objection that
    deferred it no longer applies to a closed set of ratios.)_
- D06 Box prompt for selection (`/segment/box` already exists server-side, so this is UI only)
- D08 Video (SAM 3 tracking)
- D09 On-device segmentation fallback — the retired EdgeTAM / ExecuTorch plan (ADR-007, ADR-008).
  Revisit only if offline selection becomes a requirement.
- D11 Prompt history in the 지시 sheet — the last few requests, tappable to re-run. Deliberately out
  of Phase 9 (vibe_edit.md §13); the plan itself is never persisted.
- D13 A multi-turn planning loop (ADR-012's rejected alternative). Only worth revisiting if
  single-shot plans are measurably wrong often enough to pay a round trip per step.
- D14 Folding consecutive HSL adjusts into one pass. Rejected as an optimisation in
  adjust_hsl.md §5: evaluating every band against the input pixel simultaneously is a different
  result from applying the ops in order, so it is a maths change. Revisit only with a `bench.sh`
  number showing a realistic document missing the preview budget.
- D15 Per-band range editing — the eyedropper that redefines where a band starts and ends. The
  eight centres in adjust_hsl.md §2 are fixed in v1.
- D16 Margin presets for 확대 — a "9:16으로" chip that computes the margins instead of dragging for
  them. Deliberately out of T65 (outpaint.md §9): the drag has to exist first so the user can
  correct what a preset guessed.
- D17 Outpainting as a planner function. Out of Phase 12 on purpose (outpaint.md §9): asked to make
  a photo 9:16, a model would have to choose between discarding pixels and inventing them, and that
  choice belongs to the person who took the photograph. `crop_ratio` is the answer the planner gets.
  Revisit only with a device run showing users ask for it by sentence.
