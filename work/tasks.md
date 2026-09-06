# tasks.md — Ralph loop task queue

T01–T53 are done. What each one built is in `progress.md` under `## Done`, why it was built that
way is in `work/decisions.md`, and the `done when` checklists are in git history. None of it is
repeated here: this file is read on every loop iteration, so it holds only what is still open.

Rules for the agent: unchanged — see CLAUDE.md "Ralph loop rules". One task per iteration,
`scripts/check.sh` is the only verdict, only edit checkboxes in this file.

Legend: `[ ]` todo · `[x]` done · `[!]` blocked · `[H]` human-only, loop must skip

---

## Already built — do not re-open

**Mask-scoped local editing is finished** (T29–T33, specs/selection_tool.md §8.1). A SAM 3 selection
is `Operation.Mask` + `activeMaskId`; `Operation.Adjust` carries a `maskId`; the renderer does
`out = lerp(in, op(in), maskAlpha)`; all three adjust sheets show the "선택 영역에만" toggle through
`MaskOption`; and the render golden `exposure_+0.5_masked` plus `MaskedAdjustToggleTest` cover it.
Light, 색상 and 디테일 therefore already apply to a selection only. Phase 9 **consumes** this; it does
not rebuild it.

Never verified on a device — see `progress.md` "Open issues for a human". That is a human's job, not
a task.

---

## Prerequisites (human work, not a task — the loop must never pick this up)

**None outstanding.** Phase 9 needs no server change, no new catalog entry, no new `AppError` case
and no new `Operation`:

| Might have been needed | Status |
|---|---|
| a second credential for the planner | **no.** `gemini-2.5-flash` takes the same key `gemini-2.5-flash-image` already uses, from the same `GeminiSettings` and the same base URL constant |
| a dependency for function calling | **none.** OkHttp and kotlinx.serialization are already in `libs.versions.toml` and already used by `GeminiEraseClient` |
| a change to `~/sam3-server` | **no.** The planner calls `byText`, which T27/T28 already ship against the live service |

Not a blocker, but nothing runs for real without it: **a human must paste a Gemini API key into the
서버 설정 sheet on the device**, and point `sam3.baseUrl` / `sam3.token` at a running service. No key
is shipped, committed, or read from `.env` at build time — see generative_erase.md §2. Every test
uses `FakePlanProvider`, `FakeSegmentationProvider`, `FakeEraseProvider` or `MockWebServer`, so
`check` is green with no key and no server at all.

If a task hits a missing prerequisite, mark it `[!]` and write the reason in `blocked.md`. Do not
add the dependency yourself.

### Open decisions (Phase 10) — a human answers these, the loop must not

Both come out of the same device finding: **SAM 3's text endpoint understands English concepts, not
Korean.** T52 fixes it for the 지시 tool, where the model writes the phrase. Neither of these is a
blocker for T49–T53; they are what to do next if the answers differ from the defaults T52 takes.

1. **The 선택 tool's own prompt bar is still Korean.** There the *user* types the phrase, and
   "나무" reaches `byText` unchanged and finds nothing. The options are a translation call before
   every `byText` (a second model round trip on a path that is currently one), an English
   placeholder and a hint line, or leaving it. Each needs a different amendment to
   prompt_input.md §4, so the loop cannot choose.

2. **English nouns in the 지시 step list.** T52 renders "bus 선택", which is honest and costs
   nothing. The alternative is a second `label` argument on `select_region` carrying Korean for
   display — better reading, but it puts a model-authored string on screen, which vibe_edit.md §3
   currently forbids. That is a spec amendment to §4 and §11, not a task.

---

## Phase 9 — 지시 tool: one sentence becomes a workflow

The user says "나무를 좀 더 푸르게 해줘". `gemini-2.5-flash` is handed the photo and a declaration of
the four things this editor can do, and answers with function calls. The app shows them as a Korean
step list, and on 적용 runs them against the providers the manual tools already use.

**Nothing in this phase touches the renderer, the document model, persistence, export or history's
semantics.** A plan is a script for tools that exist. If a task looks like it needs a new
`Operation` or a new `AppError` case, that is a signal the design drifted — block it rather than
adding one. specs/vibe_edit.md is the whole feature.

- [x] T44 `EditPlanProvider` and the plan model
  spec: specs/vibe_edit.md §7, specs/ai_provider.md §2, §3, §6
  deps: —
  done when:
    - `PlanStep` (`Select`, `Adjust`, `Erase`, `CutOut`), `EditPlan(steps)` and `EditPlanProvider`
      in `core/ai`, beside `SegmentationProvider` and `EraseProvider`
    - `PlanStep.Adjust` carries `AdjustKind`, not a `String` (ai_provider.md §2 records why)
    - that makes `core:ai → core:imaging` a **real** edge for the first time: add
      `implementation(projects.core.imaging)` to `core/ai/build.gradle.kts`. The dependency-guard
      allowlist in the root `build.gradle.kts` already permits it, so **do not edit the root file**
      — CLAUDE.md freezes it, and it needs no change. If `dependencyGuard` fails on this edge,
      stop and block; do not widen the allowlist
    - nothing else in `core:imaging` may be reached for — no `EditDocument`, no `Operation`, no
      renderer
    - an empty `EditPlan` is a **valid** answer, not a failure. Nothing in this task may treat
      `steps.isEmpty()` as an error
    - `FakePlanProvider` in `core/ai/src/testShared`, beside the other two fakes: `availability =
      Ready`, `next(plan)`, `failNext(error)`, and the default plan from ai_provider.md §6
    - tests: `FakePlanProviderTest` mirroring `FakeEraseProviderTest` — the default plan, `next`
      overriding it once, `failNext` failing exactly one call
  touches: core/ai/EditPlanProvider.kt, core/ai/src/testShared, core/ai tests, core/ai/build.gradle.kts

- [x] T45 `GeminiPlanClient` — the function-calling layer
  spec: specs/vibe_edit.md §4, §5, §6
  deps: T44
  done when:
    - `GeminiPlanClient` (OkHttp + kotlinx.serialization) posts to
      `{baseUrl}/v1beta/models/gemini-2.5-flash:generateContent` — the text model, **not**
      `-image`
    - the key travels in the `x-goog-api-key` **header**, never a query parameter
    - the body carries `systemInstruction`, one `inlineData` part, one `text` part with the user's
      sentence verbatim, `tools[0].functionDeclarations` with §4's four functions, and
      `toolConfig.functionCallingConfig.mode = "ANY"`
    - the four declarations and the system instruction are `internal` English constants. They are
      wire payload, not user-facing strings, so they do **not** go in `strings.xml`
    - the response reader keeps every `functionCall` part **in order** and skips text parts
    - an unknown function name, a missing required argument, or a non-finite `value` **drops that
      step with a log warning** and keeps the rest — the rule `EditDocumentJson` uses for an
      unknown op. `value` is clamped to the kind's range from edit_model.md
    - connect 10 s, read 30 s, cancellable, on `DispatcherProvider.io`
    - errors map exactly as generative_erase.md §6 does, plus §6's two rows here: no `functionCall`
      part is an **empty plan**, and a safety block is `Invalid("blocked:<reason>")`. No new
      `AppError` case
    - `GeminiPlanClientTest` on `MockWebServer`, one test per bullet of vibe_edit.md §12's first
      list, including: the key absent from the URL, two calls decoding in order, a text part between
      them being skipped, an unknown name dropped while the rest survive, and cancellation mid-flight
  touches: core/ai/gemini

- [x] T46 `GeminiPlanProvider`
  spec: specs/vibe_edit.md §8, specs/ai_provider.md §7
  deps: T45
  done when:
    - `GeminiPlanProvider : EditPlanProvider` runs §8's four steps: blank request → `Invalid` before
      any encoding, `GeminiImageCodec.encode` **unchanged and reused**, `ensureActive()`, then the
      call — all on `DispatcherProvider.io`
    - no new encoder. If `GeminiImageCodec` needs a change to be used without a mask, that is a
      signal to stop and block, not to fork it
    - `availability` is derived from `GeminiSettings.config` with **no network probe**: blank key →
      `Unavailable(Invalid("no api key"))`, non-blank → `Ready`. Probing costs a billed call, the
      same argument generative_erase.md §7 makes
    - `AiModule` gains `@Binds fun plan(impl: GeminiPlanProvider): EditPlanProvider`. No existing
      binding changes
    - `GeminiPlanProviderTest` per vibe_edit.md §12: blank request fails before encoding, the image
      on the wire is ≤ 1024 on the long edge, availability flips with the key, zero function calls
      gives an empty `EditPlan` rather than a failure
  touches: core/ai/gemini, core/ai/AiModule.kt, core/ai tests

- [x] T47 `PlanRunner` — executing the steps
  spec: specs/vibe_edit.md §9
  deps: T44
  done when:
    - `PlanRunner` in `feature/editor/tools/direct` with `validate(plan, document): PlanStep?` and
      `run(plan, document, preview): Flow<RunEvent>`, `RunEvent` being
      `Started` / `Committed` / `Stopped` / `Completed`
    - `validate` enforces §9.1's single rule: `Adjust(masked = true)`, `Erase` and `CutOut` each need
      an earlier `Select` in the same plan or a non-null `activeMaskId` on the document. A masked
      adjust with no selection is **rejected**, never silently applied to the whole photo
    - each step runs as §9.2's table says, reusing what exists: `byText` + `MaskOps.union`,
      `document.withMask`, `withAdjust`, `withGenerativeErase`, `withCutOut`, and `erase.erase`.
      No new imaging code and no new `EditDocument` helper
    - the runner takes **save lambdas, not `ProjectRepository`** — `projectId` lives in
      `EditorViewModel`'s `SavedStateHandle`, which is why `EraseController` is already wired that
      way
    - a `Select` returning an empty list **stops the run**; it is not the selection tool's hint here,
      because the steps after it were written for a selection that does not exist
    - the runner opens a `SegSession` at the first `Select` and closes it when the run ends
    - one `Committed` per step, and the caller pushes it — the runner never touches `HistoryStack`,
      the same split `SelectionController` and `EraseController` use
    - a failure or a cancellation ends the run with everything before it already emitted. The
      in-flight step commits nothing
    - `PlanRunnerTest` with the fakes and Turbine: one test per bullet of vibe_edit.md §12's third
      list, including the load-bearing one — a failure at step 2 leaves step 1's document committed
  touches: feature/editor/tools/direct

- [x] T48 지시 tool — the sheet, the bar, and the preview
  spec: specs/vibe_edit.md §3, §10, §11, §12; specs/prompt_input.md §2; specs/editor_shell.md
  deps: T46, T47
  done when:
    - `Tool.Direct(editor_tool_direct, …, isAi = true)` **appended** to the enum, so it carries the
      6dp accent dot. No existing entry moves
    - `PromptBar` gains a `placeholder: String` parameter defaulting to the string it hardcodes
      today, and `VoicePromptBar` forwards it. The three prompt-bar goldens must pass **without
      re-recording** — needing to re-record one means the default was not preserved
    - `DirectSheet` per §3: title, `VoicePromptBar`, the step list when a plan exists, pinned
      [취소 | 적용] with 적용 as the sheet's one `primary` pill. Max 45% height, the list scrolls
    - step lines are built from the §11 templates and the existing slider labels. **No text from the
      model reaches the screen** — a test asserts a response's text part is not rendered
    - `DirectController` owns plan state; `EditorViewModel` owns the run, pushing one history entry
      per `Committed` with **no coalesce key**
    - the progress overlay shows `direct_planning` while planning and `direct_running` while
      running, both with a cancel button (DESIGN.md §7)
    - a blank key greys the tool and tapping it **opens the 서버 설정 sheet**, through the same
      controller-returns-an-intent shape T43 used for 지우기. There is one settings sheet and one
      owner
    - an empty plan, or one `validate` rejects, shows `direct_not_understood` as a `bodySm` hint
      under the bar — never a snackbar, never a dialog. 적용 stays disabled
    - a run containing a `Select` clears `SelectionState.session` (§9.4), so the 선택 tool re-opens
      rather than prompting against a dead handle
    - the §11 strings are added to `feature/editor` `strings.xml`; nothing is hardcoded in a
      Composable
    - goldens `direct_sheet_open` and `direct_plan_preview`. **No render golden** — this feature adds
      no renderer path
    - tests: one per bullet of vibe_edit.md §12's fourth list
  touches: feature/editor/tools/direct, feature/editor/Tool.kt, feature/editor/EditorViewModel.kt,
  feature/editor/EditorRoute.kt, feature/editor/tools/prompt/VoicePromptBar.kt,
  feature/editor strings.xml, core/ui/components/PromptBar.kt

---

## Phase 10 — what the device said

The first real run on a phone (SM-S948U, Android 16, 2026-09-06) against live Gemini and a live
SAM 3. Three defects, all of them invisible to `check`, because every test in T26–T48 is a fake or
`MockWebServer`. Each task below names the one it fixes.

**None of these needs a new `Operation`, a new `AppError` case or a new dependency.** T49 is a
straight spec-conformance bug; the rest are a mask margin, two prompt rewrites and a guard.

- [x] T49 The renderer applies operations in list order
  spec: specs/generative_erase.md §10, specs/render.md "Pipeline order", specs/selection_tool.md §8.1, §8.2
  deps: —
  report: "지우기 되면 그 이후에 채도가 적용이 안 되네" — a plan of [Select, Erase, Adjust] applies
  the erase and silently drops the adjustment.
  done when:
    - `Renderer.applyOperations` walks `document.operations` **once, in list order**, dispatching
      `Adjust`, `GenerativeErase` and `CutOut` as it meets them. `Mask` contributes no pixels.
      `Crop` still runs last regardless of its position (render.md), and is the only exception
    - this is a **bug fix, not a new rule**: generative_erase.md §10 already says "Ops added after
      the erase apply on top of it, in list order". Today's loop applies *every* adjust, then
      *every* erase, then *every* cut-out, so a masked adjust committed after an erase is computed
      first and then overwritten by the erase result inside the same mask — invisible, every time
    - `onProgress` still reaches exactly 1f and never goes backwards
    - the three existing render goldens (`exposure_+0.5_masked`, `generative_erase_render`,
      `cutout_render`) pass **without re-recording**: their documents put the adjust first, so a
      correct fix cannot move them. If one moves, the fix is wrong — stop and re-read §10
    - tests: [Mask, GenerativeErase, Adjust(masked)] leaves the adjustment visible **inside** the
      erased region; [Mask, Adjust(masked), GenerativeErase] leaves the erase on top; a `CutOut`
      before an `Adjust` keeps the alpha it cut; two erases stack in list order
  touches: core/imaging/render/Renderer.kt, core/imaging tests

- [x] T50 The erase mask gets a margin
  spec: specs/generative_erase.md §3, §4, §10; specs/selection_tool.md §4
  deps: —
  report: "세그먼테이션 시에 가장자리가 좀 나는 경우가 있어서 마진을 좀 더 줘야할 것 같아" — the
  object's outline survives the erase as a halo.
  done when:
    - `MaskOps.dilated(mask, radiusPx)`: binary in, binary out, `ALPHA_8`, always a superset of the
      input, radius 0 returns a copy, deterministic (a golden depends on it)
    - the radius is one named constant derived from the image, never a number at a call site:
      `max(ERASE_MARGIN_MIN_PX, round(ERASE_MARGIN_FRACTION * min(width, height)))`. The KDoc says
      why: SAM 3 cuts at the visible edge, so the antialiased fringe and the contact shadow sit
      *outside* the mask and are exactly what the model paints around
    - **the dilated mask is what the document stores.** The pixels sent to Gemini and the
      renderer's `lerp(in, result, maskAlpha)` must use the *same* mask, or the fringe the model
      just painted over is restored from the source and the halo survives the fix. So an erase
      commits `Mask(dilated)` + `GenerativeErase(thatMaskId, …)` as **one** history entry
    - `activeMaskId` does **not** move to the dilated mask: a following adjust or cut-out still
      uses what the user actually selected
    - both erase paths go through the same helper — `EraseController` and `PlanRunner`'s `Erase`
      step. Two copies of a dilation radius is how the two paths drift apart
    - tests: dilation is a superset and stays strictly binary; a 1px mask grows on both sides; the
      recorded Gemini body's white region is larger than the selection (decode it, the way T42's
      load-bearing test does); the commit is two ops and one undo. `generative_erase_render` is
      named here, so re-record it **only if it actually moves**, and say so in the commit message
  touches: feature/editor/tools/select/MaskOps.kt, feature/editor/tools/erase,
  feature/editor/tools/direct, feature/editor tests, core/imaging/src/test/resources/golden

- [x] T51 The erase instruction, and an answer that is still a hole
  spec: specs/generative_erase.md §4, §5, §6
  deps: —
  report: "erase 시에 image가 inpainting되지 않고 그냥 하얀색만 남는 경우가 있어."
  done when:
    - `GeminiEraseClient.INSTRUCTION` is rewritten to close the no-op: it must say that **no white
      may remain**, that the answer is the same photograph with that area filled in, and that
      returning the input unchanged is not an answer
    - the hint sentence stops being ambiguous. Today it reads "The white region previously
      contained: <hint>." next to "Do not introduce any new object", which can be read as an
      instruction to paint the thing back in. It must say the thing was **removed** and must not
      be drawn again
    - `PlanRunner` passes the most recent `Select` phrase as the erase hint instead of `null`, so
      the model is told what is gone. `EraseController` keeps passing `null` — the manual tool has
      no phrase to give
    - `GeminiEraseProvider` refuses a no-op answer: when ≥ `WHITE_RESULT_THRESHOLD` of the masked
      pixels come back within 2/255 of pure white, the erase fails with `AppError.Unavailable`
      instead of committing a white patch. A white wall is the one benign case §4 already names,
      and the cost there is a retry, not lost work
    - the instruction stays an English `internal` constant and stays out of `strings.xml` (§5)
    - tests: the recorded body carries the new sentences; a hint renders as the removal sentence;
      a still-white answer fails and commits nothing; a filled answer succeeds
  touches: core/ai/gemini, core/ai tests, feature/editor/tools/direct

- [x] T52 The planner writes English phrases and finishes its plans
  spec: specs/vibe_edit.md §4, §5, §11
  deps: —
  report: "erase bus하는 경우에 어떨 땐 bus 지우기까지 되는데 어쩔 땐 그냥 버스 선택하기까지밖에
  안 돼" and "SAM이 한국어 prompt를 이해 못한다."
  done when:
    - `PLAN_SYSTEM_INSTRUCTION` says the `phrase` argument is **English, always**, whatever
      language the request is in, and `select_region`'s own description says it too. SAM 3's text
      endpoint is English concept segmentation; a Korean phrase finds nothing, which on the device
      reads as "찾지 못했어요"
    - it also says: emit **every** call the request needs, in one turn, and never stop after the
      selection. "버스 지워줘" is `select_region("bus")` **and** `erase_selection`; the select
      alone is a wrong answer. Two or three worked examples go in the instruction, because the
      report is that the model gets this right only half the time
    - and: after `erase_selection` or `cut_out_selection`, an adjustment about the whole photo
      passes `masked=false`. A masked adjust after an erase lands inside the hole and does nothing
      the user can see — the same symptom T49 fixes from the other end
    - the §11 step line renders the English phrase ("bus 선택") for now. That is deliberate and
      recorded under "Open decisions": a Korean label would be a second model-authored string on
      screen, and vibe_edit.md §3 keeps the model's words off it
    - tests: the instruction on the wire carries each rule; select+erase still decodes to two steps
      in order; `DirectSheetTest` renders an English phrase through the template
  touches: core/ai/gemini/GeminiPlanCatalog.kt, core/ai tests, feature/editor tests

- [x] T53 The generative + adjust workflow, proven rather than assumed
  spec: specs/vibe_edit.md §9, specs/generative_erase.md §10, specs/selection_tool.md §8.1, §8.2
  deps: T49, T50
  report: "생성형 기능과 기존 보정 기능의 워크플로우 발생 시 적용이 잘 되는지 확인해봐."
  done when:
    - a test-only task: it adds no production code. If it needs any, T49 or T50 is incomplete —
      go back rather than patching here
    - `core:imaging`, against the **real** `Renderer` and the `fixtures/` images, one case per row:
      erase → global adjust; erase → masked adjust (visible *inside* the hole); adjust → erase;
      erase → cut-out; cut-out → adjust; erase → erase. Each asserts pixels, not op counts
    - `feature:editor`, through `EditorViewModel`: a plan of [Select, Erase, Adjust(masked=false)]
      leaves ops in that order with `maskId = null` on the adjust, and three undos peel it apart
      in reverse
    - the full-resolution path is covered too: the same document through `Renderer.full` composes
      the stored result rather than dropping it (generative_erase.md §11)
  touches: core/imaging tests, feature/editor tests

---

## Phase 11 — 혼합: HSL 색상 보정

Eight hue bands × 색조/채도/휘도, so "the reds are too strong" and "make the sky bluer" stop being
requests only a global slider can half-answer. specs/adjust_hsl.md is the whole feature.

**It adds 24 `AdjustKind` entries and one op function, and nothing else.** No new `Operation`, no
JSON `v` bump, no renderer path, no new `PlanStep`, no new `AppError` case, no new dependency. If a
task here looks like it needs one, the design drifted — block it rather than adding one.

### Open decisions (Phase 11) — a human answers these, the loop must not

1. **render.md line 54** ("Golden image per `AdjustKind` at +0.5 and −0.5") is no longer what the
   project does now that there are 34 kinds; adjust_hsl.md §10 is the rule for the HSL ones. It
   needs one sentence pointing there, and `specs/*.md` is frozen for the loop.
2. **adjust_hsl.md §7's two rulings** — the selected chip is an `editInk` ring rather than the
   accent, and 색조 labels both `color_tint` and `mix_hue` — are taken on DESIGN.md's behalf.
   Cheaper to overrule before T55 records its goldens than after.

Neither blocks T54–T56: the tasks proceed on the spec as written.

- [x] T54 The bands, the maths, and 24 kinds
  spec: specs/adjust_hsl.md §2, §3, §4, §5, §10; specs/edit_model.md; specs/render.md
  deps: —
  done when:
    - `HslBand`, `HslChannel`, `HslTarget` and `HslColor` in `core/imaging/model/Hsl.kt`;
      `AdjustKind` gains `hsl: HslTarget? = null` and the 24 `Hsl<Band><Channel>` entries,
      **appended**, so no existing entry moves
    - the band centres live in `HslBand` and nowhere else — no degree literal at a call site
    - `HslOps` implements §4 exactly: tent weights that sum to 1 with a weight of 0 at every other
      band's centre, the `smoothstep(0.05, 0.20, s)` neutral gate, and the three channel formulas.
      휘도 scales RGB by `2^(v × w × 0.5)`; it does not write HSL's `l`
    - `Ops.adjust` dispatches every HSL kind through `kind.hsl` in **one** branch, not 24, and
      without a `!!`. Needing either means §3's single nullable field was not followed
    - `Pixels.kt`'s `mapPixels`, `smoothstep`, `exposureGain` and `packRgb` are reused, not
      re-implemented, and `HslColor.fromRgb` writes into a scratch array the pass owns — one
      allocation per pass, never one per pixel
    - **the renderer is not touched.** T49's in-order walk already dispatches `Adjust`, and the
      masked path already blends. If a change there looks necessary, stop and block
    - the JSON root `v` stays 1 and no serializer changes: only enum names were added
    - goldens: the eight files of §10, listed in `golden_manifest.txt` under a `# T54` heading.
      Nothing else in that manifest moves
    - property tests per §10, on a band strip the test **builds in code**. No new file under
      `fixtures/` — those are human-committed (testing.md §7)
    - `EditDocumentJson` round-trips an HSL `Adjust` carrying a `maskId`, and an unknown kind still
      drops with the document still loading
    - `labelRes()` maps the 24 kinds to their **channel** label and the three `mix_*` strings of §9
      exist. This is here rather than in T55 because `AdjustKind` gaining entries makes that `when`
      non-exhaustive: without it `:feature:editor` does not compile and T54 cannot be green on its
      own. The band labels and everything else about the sheet stay in T55
  touches: core/imaging/model/Hsl.kt, core/imaging/model/Operation.kt,
  core/imaging/render/HslOps.kt, core/imaging/render/Ops.kt, core/imaging tests,
  core/imaging/src/test/resources/golden, core/imaging/src/test/resources/golden_manifest.txt,
  feature/editor/tools/ToolLabels.kt, feature/editor strings.xml

- [ ] T55 혼합 sheet — the chip row and the tool
  spec: specs/adjust_hsl.md §6, §7, §9, §10; specs/adjust_color.md; DESIGN.md §4, §5
  deps: T54
  done when:
    - `AdjustSheet` gains **one** parameter, `header: @Composable ColumnScope.() -> Unit = {}`,
      rendered between the "선택 영역에만" toggle and the sliders. `light_sheet_open`,
      `color_sheet_open` and `detail_sheet_open` pass **without re-recording** — one of them moving
      means the parameter was not added the way §6 says. The sheet is not duplicated
    - `Tool.Mix(editor_tool_mix, Icons.Rounded.Colorize)` inserted **after `Tool.Color`**, and
      `ToolSheetHost` gains one `when` branch. Adding a tool stays "one entry here plus one tool
      definition" (architecture.md §5.2)
    - `MixSheet` per §6: the chip row as `header`, the selected band's three kinds as the sliders
      in 색조 → 채도 → 휘도 order, `maskOption` forwarded unchanged so 선택 영역에만 works here too,
      and the selected band in `rememberSaveable` — not in the document, not in `EditorUiState`
    - chips per §6: a 32dp swatch filled from `HslColor.toRgb(centre, 0.7f, 0.5f)` (the same
      conversion the maths uses, so a swatch cannot drift from its band), 48dp hit area, 12dp
      spacing, selected marked by a 2dp `editInk` ring and an `editInk` label. **The sheet's one
      accent stays on 적용** (§7); no chip is ever accent
    - `stepLabel()` beside `labelRes()` composes the band prefix, and `DirectSheet` renders through
      it. No new step template. (`labelRes()` and the three channel strings landed in T54 — see the
      note there)
    - the remaining ten strings of §9 in `feature/editor` `strings.xml`; nothing hardcoded in a
      Composable
    - goldens `mix_sheet_open` and `mix_sheet_band_selected`; `editor_shell_default` re-recorded
      **for one reason only** — the strip gains an eighth item — and the commit message says so
    - tests: §10's sheet list, including the drag→one history entry→undo case and the assertion
      that the Red swatch's colour is not the `accent` token
  touches: feature/editor/tools/mix, feature/editor/tools/AdjustSheet.kt,
  feature/editor/tools/ToolSheetHost.kt, feature/editor/tools/ToolLabels.kt,
  feature/editor/tools/direct/DirectSheet.kt, feature/editor/Tool.kt, feature/editor strings.xml,
  feature/editor tests, feature/editor screenshot goldens

- [ ] T56 `adjust_color_range` — the planner's fifth function
  spec: specs/adjust_hsl.md §8, §10; specs/vibe_edit.md §4, §5
  deps: T54
  done when:
    - `adjust_color_range` declared in `GeminiPlanCatalog` per §8, as English `internal` constants
      beside the other four. Wire payload, so **not** in `strings.xml`
    - `GeminiPlanClient` expands one call into up to three ordinary `PlanStep.Adjust` steps in
      hue → saturation → luminance order, each clamped to the kind's range. **No new `PlanStep`, no
      `PlanRunner` change, no new §11 template.** If one looks necessary, stop and block — §8 chose
      this shape precisely so the runner and the sheet stay untouched
    - drop rules per §8: an unknown `color` drops the call and later calls survive; a non-finite or
      absent channel drops that channel only; all three absent contributes no steps, and a plan
      that ends up empty is a **valid** empty plan, not a failure
    - `adjust`'s `kind` enum and `adjustKindOf` filter `it.hsl == null`, so the wire still carries
      exactly ten names. The `wireName` KDoc ("§4's ten `AdjustKind` names") is updated to say why
      the HSL kinds never travel that path
    - `PLAN_SYSTEM_INSTRUCTION` gains §8's one rule and one example, and nothing else
    - tests: §10's planner list, including the recorded body listing ten `kind` values with no HSL
      among them, and `blue` + two channels decoding to two steps in order
  touches: core/ai/gemini/GeminiPlanCatalog.kt, core/ai/gemini/GeminiPlanClient.kt, core/ai tests

---

## Backlog

_Empty._ Phase 10 closed what the first device run found. The next queue comes from the **second**
device run: T51 and T52 are prompt rewrites, and only a real model can say whether they hold.

Still waiting on that second device run; Phase 11 is queued ahead of it because it needs no device
and no key — every one of its tests is a golden, a property or `MockWebServer`.

---

## Deferred
- D01 Layers · D02 Text · D03 GPU render (AGSL) · D04 Tablet · D05 Onboarding
- D06 Box prompt for selection (`/segment/box` already exists server-side, so this is UI only)
- D08 Video (SAM 3 tracking)
- D09 On-device segmentation fallback — the retired EdgeTAM / ExecuTorch plan (ADR-007, ADR-008).
  Revisit only if offline selection becomes a requirement.
- D10 Generative fill / replace, reusing the T42 `GeminiEraseClient` boundary. The only new pieces
  are a different instruction constant and a prompt bar to source it from. Once it exists it is also
  another entry in the T45 function catalog.
- D11 Prompt history in the 지시 sheet — the last few requests, tappable to re-run. Deliberately out
  of Phase 9 (vibe_edit.md §13); the plan itself is never persisted.
- D12 `crop` as a planner function. Excluded from T45 on purpose (vibe_edit.md §4): a model
  inventing four normalized coordinates from a sentence throws away framing the user chose. It needs
  a preview of the rect before it can be offered, not another line of step text.
- D13 A multi-turn planning loop (ADR-012's rejected alternative). Only worth revisiting if
  single-shot plans are measurably wrong often enough to pay a round trip per step.
- D14 Folding consecutive HSL adjusts into one pass. Rejected as an optimisation in
  adjust_hsl.md §5: evaluating every band against the input pixel simultaneously is a different
  result from applying the ops in order, so it is a maths change. Revisit only with a `bench.sh`
  number showing a realistic document missing the preview budget.
- D15 Per-band range editing — the eyedropper that redefines where a band starts and ends. The
  eight centres in adjust_hsl.md §2 are fixed in v1.
