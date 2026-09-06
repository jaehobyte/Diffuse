# blocked.md

Tasks marked `[!]` in work/tasks.md, with what was tried, the error, and the
decision a human needs to make.

## T66 — The AGSL render backend (was D03)

Blocked before it starts, on two things the loop cannot do for itself. Neither is a failure; this
task was written already blocked so that the loop would not pick it up.

**1. minSdk 26 → 33.** `RuntimeShader`, and therefore AGSL, arrives at API 33.
`gradle/libs.versions.toml` is on CLAUDE.md's never-modify list, and this is a product decision
before it is a technical one: it drops Android 8 through 12. A human decides and edits the catalog.
The same change has to correct two statements that become wrong, not merely stale:
- `specs/architecture.md` §2 (Tech stack), "minSdk 26, targetSdk latest stable"
- `specs/imaging.md`, "minSdk is 26 and HEIF decoding only arrives at API 28" — at minSdk 33 the
  HEIF caveat disappears entirely

**2. A benchmark number.** `specs/render.md` sets the budget: `preview`, 4096px source → 1080px
target, < 100 ms p50 on a Pixel 6a-class device. `scripts/bench.sh` exists and is excluded from
`check`, and nobody has run it against a document carrying HSL ops. Measure the six-혼합-slider case
specifically — `HslOps` evaluates eight band weights per pixel and adjust_hsl.md §5 refused to fold
consecutive HSL adjusts into one pass (D14), so six sliders are six full passes over the bitmap.

**The decision a human needs to make:** whether the numbers justify the port at all. Porting a
renderer that already meets its budget is work with no user-visible result, and it doubles the
surface every future op has to satisfy. **If the bench says the budget is met, close T66 as "not
needed"** and record the numbers in `work/decisions.md`. That is a good outcome.

Nothing was attempted. No code was written.

## T57 — A sheet's 취소 must not tap the tool underneath it

**Not reproducible here, and the hypothesis the task was written on is disproven.** No production
code was changed. Three reproduction attempts are committed as
`feature/editor/src/test/kotlin/com/diffuse/feature/editor/tools/SheetCancelTest.kt`; all pass.

**What the report says.** "종종 디테일 탭 눌렀다가 취소하면 그 다음에 빛 탭이 뜨는데 이건 누른적이
없는데 왜 뜨는거지" — cancelling the 디테일 sheet opens 빛, which was never tapped. "종종" —
sometimes, not always.

**What was confirmed.** The geometry is exactly as suspected. Measured on a Pixel 6a qualifier,
`EditSheet`'s pinned [취소 | 적용] row is the last row of a bottom-aligned sheet, and
`EditorScreen`'s `SheetOverlay` draws that sheet straight over the tool strip, so **취소 sits
directly on top of the strip's leftmost item, 빛**. That is why the report names 빛 specifically and
not some other tool.

**What was tried, and what it showed.**
1. `performClick()` on 취소 → the sheet closes, `onToolClick` is never called. Passes.
2. A split gesture modelling the device — `down()` on 취소, recomposition, then `up()` after the
   sheet has been removed → still no tool click. Passes.
3. The decisive one: injecting a touch at 빛's centre **while the sheet is open** → no tool click.
   Compose hit-tests `SheetOverlay` ahead of `EditorBody`, so the strip is already unreachable
   beneath an open sheet.

Attempt 3 disproves the fall-through theory. **The fix the task specifies — making the sheet consume
pointer input — would be a no-op**, so it was not written: a change that cannot fail a test cannot
be reviewed either.

**What a human needs to do.** Reproduce it on the device (SM-S948U, the one from the 2026-09-06 run)
and answer one question: *does the 빛 **sheet** actually open, or does the 빛 tool merely appear
selected?* Those are different bugs. Useful things to capture while there:
- whether it survives disabling gesture navigation (the sheet carries `navigationBarsPadding()`,
  which Robolectric flattens to zero and a real device does not)
- whether it happens after a **slow** press on 취소, or only a quick one
- whether the 디테일 sliders or the 빛 sliders are showing when it happens

**One thing worth fixing regardless, separately from this bug.** `EditorRoute.sheetFor()` returns a
non-null lambda whenever `document != null`, **even with `selectedTool == null`**, so
`EditorScreen`'s `sheet != null` does not mean "a sheet is open". Today that is harmless — the empty
`ToolSheetHost` branch gives the overlay Box zero height and `canvasInset` still computes 0 — so it
is a readability defect with no observable behaviour, which is why it was **not** changed here:
there is no test that can fail for it. It belongs in a cleanup task, not in a bug fix.
