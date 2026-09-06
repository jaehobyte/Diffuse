# specs/adjust_hsl.md — 혼합 tool (per-colour HSL)

Owner tasks: T54 (maths), T55 (sheet), T56 (planner)
Module: `core/imaging/model`, `core/imaging/render`, `feature/editor/tools/mix`, `core/ai/gemini`
Depends on: edit_model.md, render.md, adjust_color.md (sheet mechanics), vibe_edit.md §4,
DESIGN.md §4 Bottom sheet / Slider

## 1. What it is

Eight hue bands, three channels each: 색조 / 채도 / 휘도. "The reds are too strong", "make the sky
bluer" and "warm up the skin without touching the sky" are the requests 색's four global sliders
cannot answer, because every one of them moves the whole frame.

The whole feature is **24 more `AdjustKind` entries and one more op function**. No new `Operation`,
no serialization change, no renderer path, no history rule, no `AppError` case. Masking, undo,
coalescing, persistence and full-resolution export follow for free, because an HSL slider is an
`Adjust` like every other slider (edit_model.md).

## 2. The bands

| Band | Centre | Wire name | Chip label |
|---|---|---|---|
| Red | 0° | `red` | 빨강 |
| Orange | 30° | `orange` | 주황 |
| Yellow | 60° | `yellow` | 노랑 |
| Green | 120° | `green` | 초록 |
| Aqua | 180° | `aqua` | 청록 |
| Blue | 240° | `blue` | 파랑 |
| Purple | 280° | `purple` | 보라 |
| Magenta | 320° | `magenta` | 자홍 |

The spacing is deliberately uneven: skin, foliage and sky are where a photo editor's requests
land, so 빨강/주황/노랑 are 30° apart while 초록→청록→파랑 are 60°. The weight function (§4) spans
whatever gap it is given, so the table is the only place these numbers appear.

## 3. Model (T54)

`core/imaging/model/Hsl.kt`:

```kotlin
enum class HslBand(val centerDeg: Float) {
    Red(0f), Orange(30f), Yellow(60f), Green(120f),
    Aqua(180f), Blue(240f), Purple(280f), Magenta(320f),
}

enum class HslChannel { Hue, Saturation, Luminance }

data class HslTarget(val band: HslBand, val channel: HslChannel)

/** Shared by the renderer and the sheet's chips, so a swatch cannot drift from its band. */
object HslColor {
    fun toRgb(hueDeg: Float, saturation: Float, lightness: Float): Int
    /** Writes [h 0..360, s 0..1, l 0..1] into [out], which the caller reuses. */
    fun fromRgb(r: Float, g: Float, b: Float, out: FloatArray)
}
```

`fromRgb` writes into a caller-owned array rather than returning one: it runs once per pixel, and
`mapPixels` is a single-threaded inline loop, so one scratch array per pass costs nothing and one
per pixel would cost a preview. There is still exactly one definition of the conversion — a second
copy inlined "for speed" is how the chip swatch and the op it names drift apart.

`AdjustKind` gains a nullable target and 24 appended entries:

```kotlin
enum class AdjustKind(
    val range: ClosedFloatingPointRange<Float>,
    val hsl: HslTarget? = null,
) {
    // … the existing ten, unchanged …
    HslRedHue(ZERO_CENTRED, HslTarget(HslBand.Red, HslChannel.Hue)),
    HslRedSaturation(ZERO_CENTRED, HslTarget(HslBand.Red, HslChannel.Saturation)),
    HslRedLuminance(ZERO_CENTRED, HslTarget(HslBand.Red, HslChannel.Luminance)),
    // … Orange, Yellow, Green, Aqua, Blue, Purple, Magenta …
}
```

Naming is mechanical: `Hsl<Band><Channel>`. The metadata is one nullable field rather than two,
so `Ops` reads it without a `!!` and an HSL kind can never be half-declared.

Rules, all of them inherited rather than new:
- Every HSL kind is zero-centred, `[-1, 1]`, displayed as −100…100. 0 is neutral.
- A value back at 0 deletes the `Adjust` (edit_model.md). This is what makes §5's cost argument
  true, so it is load-bearing here, not incidental.
- One live `Adjust` per `(kind, maskId)`, so a masked 빨강 채도 and a global 빨강 채도 coexist.
- JSON root `v` stays **1**. The shape did not change; only enum names were added. An older build
  reading a newer document drops the unknown kinds with a log warning and still loads it
  (edit_model.md §Serialization) — a lost slider, never a failed open.

## 4. Maths (T54)

`core/imaging/render/HslOps.kt`, reached through `Ops.adjust(kind)` like every other op. One pixel
pass, the same clamp rule as the rest of render.md, and `Pixels.kt`'s existing helpers —
`mapPixels`, `smoothstep`, `exposureGain`, `packRgb` — rather than new copies of them.

**Band weight.** With the centres of §2 sorted and wrapped (`c[-1] = c[7] − 360`,
`c[8] = c[0] + 360`), band *i*'s tent for a hue *h* is

```
h in [c[i-1], c[i]] → (h − c[i-1]) / (c[i] − c[i-1])
h in [c[i],  c[i+1]] → (c[i+1] − h) / (c[i+1] − c[i])
otherwise            → 0
```

so the weights sum to exactly 1 for every hue, and a band's weight at any **other** band's centre
is exactly 0. That second property is what §10's "the other seven colours did not move" test
rests on; a smoother falloff would blur bands into each other and make the test a tolerance
argument instead of a fact.

**Neutral gate.** `w = tent(h) × smoothstep(0.05, 0.20, s)`. Hue is meaningless for a grey pixel
and numerically unstable near grey, so the neutral patch of the fixture must not move when any
slider does. There is deliberately **no** lightness gate: near-black and near-white pixels have an
unstable hue too, but every one of the three channels is invisible there anyway, and a second gate
is two more constants to justify.

**Channels**, for value `v ∈ [-1, 1]` and the weight `w` above:

| Channel | Formula |
|---|---|
| 색조 Hue | `h' = (h + v × 30 × w) mod 360`, s and l unchanged |
| 채도 Saturation | `s' = clamp(s × (1 + v × w), 0, 1)` — v = −1 at full weight is grey |
| 휘도 Luminance | `rgb × 2^(v × w × 0.5)`, clamped per channel |

±30° is the tightest band spacing in §2's table: at v = ±1 and full weight a red moves as far as
orange's centre and no further, so a slider at its end is still an edit and not a hue rotation. 휘도 is applied to RGB rather than to HSL's `l` on purpose — it is the EV idiom
`LightOps` already uses, it preserves the hue and saturation ratios exactly, and it avoids a
second HSL round trip.

**Order.** Each kind is its own op, so the ops run in list order like everything else, and a hue
shift changes which band a pixel belongs to for the ops after it. That is the same property
`Exposure→Contrast ≠ Contrast→Exposure` already has (render.md §Tests) and is not a bug: the
document is an ordered list, and the sheet writes the sliders a user touches in the order they
touch them.

## 5. Renderer and cost

**The renderer does not change.** T49's single in-order walk dispatches `Adjust`, and a masked HSL
adjustment blends through `lerp(in, op(in), maskAlpha)` exactly as a masked exposure does.

Cost is one full pass per **non-zero** kind, at roughly `vibrance`'s cost plus one RGB→HSL
conversion. A zero slider is not an op (§3), so the realistic bill is the number of sliders the
user actually moved — two or three, not 24. A document carrying all 24 is outside render.md's
100 ms preview budget and this spec does not pretend otherwise.

If the budget is missed at a realistic count, the answer is a `bench.sh` number and a blocked
task, **not** folding consecutive HSL ops into one pass. Folding evaluates every band against the
input pixel simultaneously, which is a different result from applying them in order (§4), so it is
a change to the maths wearing an optimisation's clothes. It is D14.

## 6. The sheet (T55)

`Tool.Mix(R.string.editor_tool_mix, Icons.Rounded.Colorize)`, inserted **after `Tool.Color`** —
혼합 is a colour tool and belongs beside 색. Insertion moves the later entries one slot along the
strip, so `editor_shell_default` must be re-recorded; it would have moved anyway, because the
strip gains an item. That is the only reason it may move. Nothing else about the shell changes.

**`AdjustSheet` gains one optional parameter** and nothing else:

```kotlin
header: @Composable ColumnScope.() -> Unit = {}
```

rendered between the "선택 영역에만" toggle and the sliders. The default is empty, so
`light_sheet_open`, `color_sheet_open` and `detail_sheet_open` must pass **without re-recording** —
if one moves, the parameter was not added the way this section says. adjust_color.md's "the sheet
composable is the same generic `AdjustSheet(kinds)`; do not duplicate it" still holds: `MixSheet`
is a `header` and a three-element `kinds` list, not a second sheet.

**`MixSheet`** (`feature/editor/tools/mix/MixSheet.kt`):
- title `mix_title`, `maskOption` forwarded unchanged, so 선택 영역에만 works here for free;
- `header` is the band chip row;
- `kinds` is the selected band's three kinds, in the order 색조 → 채도 → 휘도;
- the selected band is `rememberSaveable` UI state, not document or `EditorUiState` state. It
  survives rotation and resets to 빨강 when the sheet is re-opened. Nothing about a band selection
  is worth persisting: the values themselves are already in the document, and the sliders show
  them (adjust_light.md).

**The chip row**: horizontally scrollable `Row`, `Arrangement.spacedBy(12.dp)` (DESIGN.md §1). Each
chip is a 48dp-wide column with a 48dp hit area: a 32dp circular swatch filled with
`HslColor.toRgb(band.centerDeg, 0.7f, 0.5f)`, and the band label below in `label` type. Selected:
a **2dp `editInk` ring** 3dp outside the swatch, and the label in `editInk`; unselected labels are
`editInkSecondary`. The swatch itself is the same size either way, so the row does not reflow when
the selection moves. Test tag `MixBandChip:<band>`.

## 7. Two rulings this section owns

**The selected chip is never `accent`.** DESIGN.md §4 marks a selected tool with the accent, but
that rule is written for the tool strip, and §1 allows the accent once per *surface*. A sheet's one
accent is its 적용 pill — the prompt bar gives its accent up for exactly this reason. So selection
here is a ring in `editInk`, and the eight swatches are **content colour, not chrome**: they are
the thing being edited, in the same sense the photo is, which is why a red swatch sitting near a
red 적용 pill does not read as a second call to action. A test asserts the red swatch's ARGB is not
the `accent` token, so this stays a stated decision rather than a coincidence.

**색조 is used twice, and that is intended.** `color_tint` is 색조 (adjust_color.md) and `mix_hue`
is 색조 as well. They are different controls in different sheets, and both are what a Korean
photo editor calls them; the 지시 step line disambiguates by band prefix ("빨강 색조 40"), which the
global 색조 never carries. Recorded because a reader will otherwise assume it is a copy-paste
mistake.

## 8. The planner (T56)

vibe_edit.md §4's catalog gains a **fifth function**. Everything else about the 지시 tool is
untouched: no new `PlanStep`, no `PlanRunner` change, no new §11 template, no new string template,
no new error.

```
adjust_color_range(
    color:      enum(red, orange, yellow, green, aqua, blue, purple, magenta),  // required
    hue:        number,          // optional, −1…1
    saturation: number,          // optional, −1…1
    luminance:  number,          // optional, −1…1
    masked:     boolean,         // optional, default **false** — see below
)
```

**`masked` defaults to `false` here, unlike `adjust`.** This section first said "default true, as
`adjust`", and implementing it showed that to be wrong: `PlanRunner.validate` rejects an
`Adjust(masked = true)` with no `Select` before it and no `activeMaskId` (vibe_edit.md §9.1), so
"하늘을 더 파랗게" — the example this function exists for — would have failed the whole plan with
"무엇을 할지 모르겠어요". A colour range is chosen by colour rather than by region; it is already a
kind of selection. A model that wants both says `masked=true` explicitly.

`GeminiPlanClient` **expands one call into up to three ordinary `PlanStep.Adjust` steps**, in the
fixed order hue → saturation → luminance, each with the `AdjustKind` for `(band, channel)` and the
value clamped to the kind's range. This is the whole reason for the shape: the runner already
knows how to apply an `Adjust`, and a step list of `Adjust`s is a step list the sheet already
renders.

Dropping follows T45's existing rule — drop the bad thing, keep the rest:
- an unknown `color` drops the whole call;
- a non-finite or absent channel drops that channel only;
- a call whose three channels are all absent or all 0 contributes no steps, and if that leaves the
  plan empty it is an empty plan, which vibe_edit.md §7 says is a valid answer, not a failure.

**`adjust`'s `kind` enum stays the ten non-HSL names**, via
`AdjustKind.entries.filter { it.hsl == null }`. Two ways to say the same thing is how a planner
learns to say it badly, and 34 enum values on the argument the model gets wrong most often is the
opposite of what T52 spent an iteration fixing. `adjustKindOf` filters the same way, so a model
that guesses `kind="hslredhue"` hits the existing unknown-name drop.

Two additions to `PLAN_SYSTEM_INSTRUCTION`, in T52's voice:
- a rule: to change one colour range only — "make the sky bluer", "the reds are too strong" —
  call `adjust_color_range`, which needs no selection. `select_region` is for a *thing* in the
  photo, not a colour;
- an example: `"하늘을 더 파랗게 해줘" -> adjust_color_range(color="blue", saturation=0.4)`.

**Step lines** need no new template. `direct_step_adjust` is `%1$s %2$d`, and `%1$s` becomes
"빨강 채도" through one helper beside `labelRes()`:

```kotlin
@Composable internal fun AdjustKind.stepLabel(): String =
    hsl?.let { stringResource(it.band.labelRes()) + " " + stringResource(labelRes()) }
        ?: stringResource(labelRes())
```

`labelRes()` keeps its invariant — one entry per kind, so no adjustment reaches the UI without a
label — by returning the **channel** label for HSL kinds, which is also what the sheet's slider
rows need under the chip that already names the band. The join is a bare space rather than a
template because the app is Korean-only by decision (testing.md §9); if that ever changes, this
helper is the one place a template goes.

## 9. Strings (T55)

Korean, in `feature/editor` `strings.xml`, nothing hardcoded in a Composable (DESIGN.md §9).

| Key | Value |
|---|---|
| `editor_tool_mix` | 혼합 |
| `mix_title` | 혼합 |
| `mix_hue` | 색조 |
| `mix_saturation` | 채도 |
| `mix_luminance` | 휘도 |
| `mix_band_red` | 빨강 |
| `mix_band_orange` | 주황 |
| `mix_band_yellow` | 노랑 |
| `mix_band_green` | 초록 |
| `mix_band_aqua` | 청록 |
| `mix_band_blue` | 파랑 |
| `mix_band_purple` | 보라 |
| `mix_band_magenta` | 자홍 |

Thirteen strings for 24 sliders: the band name and the channel name each exist once, and §8's
helper is what composes them.

The three `mix_hue` / `mix_saturation` / `mix_luminance` entries and `labelRes()`'s mapping ship
with **T54**, not T55: adding entries to `AdjustKind` makes `labelRes()`'s `when` non-exhaustive,
so `:feature:editor` stops compiling the moment the kinds exist. A task that leaves `check` red is
not a task, and "one entry per kind, so no adjustment reaches the UI without a label" is the same
rule read forwards.

## 10. Tests

**Render goldens (T54), four kinds at ±0.5 — eight files.** `fixtures/photo_512.png`, tolerance and
threshold per testing.md §4, listed in `golden_manifest.txt`:

```
hsl_red_hue_+0.5.png        hsl_red_hue_-0.5.png
hsl_red_saturation_+0.5.png hsl_red_saturation_-0.5.png
hsl_red_luminance_+0.5.png  hsl_red_luminance_-0.5.png
hsl_blue_saturation_+0.5.png hsl_blue_saturation_-0.5.png
```

The red band covers all three channels because the fixture's saturated red patch is the strongest
signal in it; the blue pair is the second band, so a bug that hard-codes one band's centre cannot
hide. The other 20 kinds are covered by the properties below rather than by 40 more PNGs that a
human would have to review one morning — the same trade adjust_detail.md makes when it ships
`sharpen_0.5` and asserts flat-area identity instead of a golden per amount.

**Properties (T54), on a bitmap the test builds in code** — one pixel per band centre at
S = 0.8 / L = 0.5, plus a neutral grey. No new file in `fixtures/`, which is human-committed
(testing.md §7):
- band weights sum to 1.0 ± 1e−5 for every hue in 0…360 at 1° steps;
- a band's weight at every other band's centre is exactly 0;
- value 0 is identity for all 24 kinds;
- moving one band's slider to ±1 leaves the other seven centres unchanged within 1/255;
- the neutral grey is unchanged within 1/255 for all 24 kinds at ±1;
- 채도 −1 leaves that band's pixel with R = G = B (±1) and no other pixel greyed;
- 색조 wraps: Magenta +1 lands past 360 and stays a valid colour, and `HslColor` round-trips
  RGB→HSL→RGB within 1/255;
- 휘도 +0.5 on the Red band raises the red pixel and leaves the grey alone.

**Sheet (T55):**
- screenshot goldens `mix_sheet_open` (빨강 selected, all zero) and `mix_sheet_band_selected`
  (파랑 selected, 채도 +40) — the second one is what proves the ring moves and the sliders re-read
  the newly selected band's stored values rather than keeping the previous band's;
- `light_sheet_open`, `color_sheet_open`, `detail_sheet_open` pass **without re-recording**;
- `editor_shell_default` re-recorded, and the commit message says why (an eighth strip item);
- tapping a chip changes which three kinds are on screen; the value shown comes from the document;
- a drag on a 혼합 slider produces one history entry after release, and undo restores the pre-drag
  value (the coalesce key is `adjust:$kind`, already keyed by kind);
- the swatch colour of Red is not the `accent` token (§7);
- with a selection active the 선택 영역에만 toggle appears and a 혼합 adjustment commits with a
  `maskId`.

**Planner (T56):**
- the recorded request body's `adjust` declaration lists exactly ten `kind` values, none of them
  HSL;
- the body carries the fifth declaration and the two instruction additions;
- one `adjust_color_range(color="blue", saturation=0.4, luminance=-0.2)` decodes to two
  `PlanStep.Adjust`s in hue → saturation → luminance order with `HslBlueSaturation` and
  `HslBlueLuminance`;
- an unknown `color` drops the call and later calls survive;
- all three channels absent yields no steps, and a plan of only that call is an empty plan rather
  than an error;
- `DirectSheet` renders "빨강 채도 40" from an `HslRedSaturation` step (the §8 helper, through the
  existing template).

## 11. Open items for a human

These are spec-level and the loop must not decide them:

1. **render.md line 54** says "Golden image per `AdjustKind` at +0.5 and −0.5". With 34 kinds that
   is no longer what the project does; §10 above is the rule for HSL kinds. render.md needs one
   sentence pointing here. CLAUDE.md freezes `specs/*.md` for the loop, so a human writes it.
2. **§7's two rulings** — the `editInk` ring instead of the accent, and 색조 appearing in two
   sheets — are decisions this spec takes on DESIGN.md's behalf. If either is wrong, it is cheaper
   to say so before T55 than after its goldens exist.

## 12. What this deliberately does not do

- **No per-band range editing** (Lightroom's eyedropper that redefines a band's boundaries). The
  eight centres are fixed. D15 if it is ever asked for.
- **No colour grading wheels** (shadow/midtone/highlight tinting). A different feature that shares
  none of this maths.
- **No folding of consecutive HSL ops** — §5 says why it is a maths change, not an optimisation.
  D14.
- **No 혼합 preset**. 자동 보정 does not exist yet either; presets belong with it, not here.
