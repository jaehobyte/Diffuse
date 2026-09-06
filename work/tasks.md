# tasks.md — Ralph loop task queue

T01–T43 are done. What each one built is in `progress.md` under `## Done`, why it was built that
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

- [ ] T45 `GeminiPlanClient` — the function-calling layer
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

- [ ] T46 `GeminiPlanProvider`
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

- [ ] T47 `PlanRunner` — executing the steps
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

- [ ] T48 지시 tool — the sheet, the bar, and the preview
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

## Backlog

_Empty._ Phase 8 closed the generative eraser and Phase 9 is the last planned line for v3.

---

## Deferred
- D01 Layers · D02 Text · D03 GPU render (AGSL) · D04 Tablet · D05 Onboarding
- D06 Box prompt for selection (`/segment/box` already exists server-side, so this is UI only)
- D08 Video (SAM 3 tracking)
- D09 On-device segmentation fallback — the retired EdgeTAM / ExecuTorch plan (ADR-007, ADR-008).
  Revisit only if offline selection becomes a requirement.
- D10 Generative fill / replace, reusing the T42 `GeminiEraseClient` boundary. The only new pieces
  are a different instruction constant and a prompt bar to source it from. Once it exists it is also
  a fifth entry in the T45 function catalog.
- D11 Prompt history in the 지시 sheet — the last few requests, tappable to re-run. Deliberately out
  of Phase 9 (vibe_edit.md §13); the plan itself is never persisted.
- D12 `crop` as a planner function. Excluded from T45 on purpose (vibe_edit.md §4): a model
  inventing four normalized coordinates from a sentence throws away framing the user chose. It needs
  a preview of the rect before it can be offered, not another line of step text.
- D13 A multi-turn planning loop (ADR-012's rejected alternative). Only worth revisiting if
  single-shot plans are measurably wrong often enough to pay a round trip per step.
