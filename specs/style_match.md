# specs/style_match.md — 스타일 (named looks and reference matching)

Owner tasks: T72 (the catalog), T73 (the tool), T74 (the planner function), T75 (컬러 매칭)
Modules: `core/imaging/style`, `core/ai`, `feature/editor/tools/style`
Depends on: adjust_light.md, adjust_color.md, adjust_hsl.md, edit_model.md, vibe_edit.md,
ai_provider.md, prompt_input.md, DESIGN.md §2, §4
Amends: vibe_edit.md §4 (an eighth planner function), editor_shell.md (a tool)
Decisions: ADR-014 (a style is a list of `Adjust` ops, not a LUT; see §2)

## 1. What it does
Two ways to ask for a look, one thing they produce.

- **A name.** The user picks 웜 필름 from a list, or says "필름 느낌으로 바꿔줘" to the 지시 tool.
- **A photograph.** The user hands in a reference image and the app matches its look (컬러 매칭).

Both end as **ordinary `Operation.Adjust`s in the document**. Nothing about a style is a new kind
of pixel; it is a set of slider positions the user can then drag.

## 2. A style is adjustments, not a LUT (ADR-014)
The request that started this said "LUT 프리셋". The source it named —
`github.com/Dujjoncam/PhotoTune_v1`, `styles.json` — turns out to hold no `.cube` files at all: it
is **12 styles × 41 variants of Lightroom-shaped parameters**, each a map like
`{exposure: 0.45, shadows: 35, vibrance: 18, temperature: -4}` plus a 2D placement
(`warmth` −1..1 × `hardness` −1..1).

That is better than a LUT for this app, and the reason is edit_model.md's:

- A 3D LUT is one opaque op. Applying it and then wanting "a bit less warm" means either a second
  op fighting the first, or re-rendering from a different LUT. Slider positions are just
  positions — a style the user can immediately disagree with, one slider at a time.
- A LUT needs a new `Operation`, a new renderer path, a new file per preset in the APK, and it
  cannot answer "what did this actually change". Adjustments need **none** of that: `withAdjust`
  and the existing render pipeline already do all of it.
- The 지시 tool already speaks in `AdjustKind`. A style becomes a plan, not a special case.

**So a style is a `List<StyleParam>` that becomes `Adjust` ops on 적용.** A real LUT stays out of
scope; if one is ever wanted it is a separate op and a separate spec.

### The rejected alternative
**Bake each style into a `.cube` and ship the files.** It is what "LUT preset" literally asks for,
it is one code path for any look however it was authored, and it would let a designer hand over a
file rather than numbers. It loses re-editability, which is the whole argument above, and it makes
every style cost APK size. Revisit only if a look arrives that adjustments provably cannot express.

## 3. The catalog (T72)
`styles.json` is imported into the repo as an asset and parsed into:

```kotlin
data class StylePreset(
    val id: String,            // "film-warm"
    val nameRes: Int,          // 웜 필름 — strings.xml, not the JSON's Korean
    val warmth: Float,         // -1..1, styles.json axes.warmth
    val hardness: Float,       // -1..1
    val params: Map<AdjustKind, Float>,   // already in Diffuse's -1..1
    val variants: List<StyleVariant>,
)
```

Rules:

- **The JSON is the source of the numbers, not of the strings.** DESIGN.md §9 puts user-facing
  Korean in `strings.xml`; a style's `name`/`desc` live there and the JSON's are ignored. The `id`
  is the key that joins them, and an `id` with no string resource fails the build's own test rather
  than rendering a raw id.
- **`params` are converted at import, once.** `styles.json` uses Lightroom-ish scales (exposure in
  stops, most others −100..100); `Operation.Adjust` is −1..1. The conversion table is §3.1 and it
  is the only place either scale is named.
- `intensity` is not a parameter. It scales the whole preset: `value * intensity / 100`.
- A **variant** is the same shape with its own `params`; §6 shows them only after a style is
  chosen, because they are a second decision (film grain vs film colour), not a second style.

### 3.1 The parameter gap — this blocks T72 (see T71)
Diffuse's `AdjustKind` covers exposure, contrast, highlights, shadows, temperature, tint,
saturation, vibrance, sharpen, vignette and 24 HSL entries. `styles.json` also uses:

`blacks` · `whites` · `clarity` · `fade` · `s_curve` · `grain` · `color_grading` · `dehaze` ·
`bw_filter`

**All twelve styles use at least one of them.** Dropping the unmappable ones does not degrade a
style gracefully — it removes the thing that makes it that style. 페이디드 매트 without `fade` is
just a flat photo; 웜 필름 without `grain` and `color_grading` is a warm photo.

So the catalog **cannot** ship before the tone ops exist. T71 adds them in the order that buys the
most:

| Kind | Why first | Also needed by |
|---|---|---|
| `Blacks`, `Whites` | the tone curve's endpoints, siblings of the two Diffuse already has | auto_enhance.md §3 |
| `Fade` | a black lift; three styles are defined by it | — |
| `SCurve` | one contrast curve, cheaper than chaining two adjusts | — |
| `Clarity` | midtone local contrast; six styles use it | — |

`grain`, `color_grading`, `dehaze` and `bw_filter` are **out of T71's scope**: grain is noise
synthesis, colour grading is three tints rather than a scalar, dehaze is a research problem, and
`bw_filter` serves one style. A style using only those degrades to its remaining parameters, and
`work/decisions.md` records which ones did.

## 4. 스타일 the tool (T73)
`Tool.Style(editor_tool_style, Icons.Rounded.AutoAwesomeMosaic)`. **Not `isAi = true`**: picking a
named style calls nothing and costs nothing. 컬러 매칭 (§5) is the AI half and lives inside the
sheet, not in the strip.

Sheet, per DESIGN.md §4:
```
스타일                                          headingLg
[사진에서 가져오기]                              secondary pill (§5)
[ 원본 ][ 클린 브라이트 ][ 웜 필름 ][ … ]         scrolling row of preview tiles
강도  ────●────────  70                          slider, mono value
                                    [취소 | 적용]  Apply is the sheet's one accent
```

- The tiles are the **user's own photo** at 96dp, rendered through each preset at preview
  resolution — a swatch of someone else's photograph is a lie about what the style will do.
  Rendering twelve of them is the one expensive thing here; §7 says how.
- 원본 is first and always present: a style is a thing you can leave.
- The 강도 slider is the preset's `intensity`, 0..100, default 100, `mono` value (DESIGN.md §3).
- Selecting a tile applies it **live** to the canvas, as the adjust sheets already do. 취소 restores
  the snapshot, 적용 commits the ops as one history entry.
- Variants (§3) appear as a second row **only** once a style is selected, labelled 세부.

## 5. 컬러 매칭 — a reference photograph (T75)
The 사진에서 가져오기 pill opens the system picker. What comes back is never committed, never
saved, and never leaves the device except as one request.

Two steps, in this order, because the second is only worth its round trip when the first fails:

1. **Match locally.** Measure the reference — its tone distribution and its colour cast — estimate
   the same parameters a preset carries, and score every preset by weighted distance in the
   normalized parameter space. This is `PhotoTune_v1/reference_style.py`'s `match_style_map`, and
   it is the same maths. If the nearest preset is within `STYLE_MATCH_THRESHOLD`, offer it as a
   selected tile with 참조와 비슷한 스타일 and stop. **No model call.**
2. **Ask the model.** Otherwise send both images to `gemini-2.5-flash` — the planner's model, not
   the image model — and ask for parameters, not pixels.

### The instruction (English `internal` constant, generative_erase.md §5's rule)
```
You are given two photographs. The first is the photo being edited. The second is a reference
whose look the user wants to copy. Return only the colour and tone adjustments that would move
the first photograph towards the reference's look: its exposure, contrast, tonal distribution,
white balance, saturation and per-colour treatment. Do not describe the reference's subject,
composition or content, and do not suggest adding or removing anything: the user wants the
reference's grade, not its picture. Values are scaled between -100 and +100.
```

- **Structured output, not prose.** The same `FunctionDeclaration` machinery vibe_edit.md §4 uses,
  with one function `match_style` whose arguments are the `AdjustKind` wire names. A model that
  answers in sentences has answered wrongly, and the client drops it.
- The response is clamped per kind and divided by 100, and the result is presented as a **13th
  tile** labelled 참조, selectable and adjustable like any other. It is never applied silently.
- `MatchStyleProvider` in `core:ai`, beside the other four (ai_provider.md §3). Availability is
  the Gemini key, no probe, as generative_erase.md §7 argues.
- Errors map to generative_erase.md §6 row for row. **No new `AppError` case.**

**Why parameters and not `gemini-2.5-flash-image`.** The image model would return a graded
photograph, which is one op that cannot be re-edited (§2 again) and which regenerates the whole
frame. Asking for numbers keeps the user in the same sliders they already have.

## 6. `apply_style` — the planner's eighth function (T74)
```
apply_style(style: enum{<the catalog's ids>}, intensity: number)
```
- Declared in `GeminiPlanCatalog` with the ids as a **closed enum**, for vibe_edit.md §4.1's
  reason: the model names a look, it does not invent numbers.
- `PlanStep.Style(id, intensity)` in `core:ai`. `PlanRunner` resolves the id to a preset at the
  `feature:editor` boundary — `core:ai` does not carry the catalog, the same edge `CropRatio` has.
- `validate` gains **no clause**: a style consumes no selection.
- One instruction rule: a request naming a *look* ("필름 느낌", "빈티지하게", "인스타 감성") is
  `apply_style`; a request naming a *change* ("더 따뜻하게") stays `adjust`. One worked example.
- An unknown id drops the step and later steps survive, per §5.
- The step renders as `direct_step_style` with the preset's own Korean name.

## 7. Rendering twelve tiles
The tiles are the only new performance question, and the answer is to make it a small one:

- One decode. The tiles share a single 96dp bitmap of the current document, rendered once, and each
  tile applies its preset to **that**, not to the full preview.
- The adjust maths is per-pixel and 96dp is ~9k pixels, so twelve tiles are ~110k pixel operations —
  under the preview budget architecture.md §8 sets, by two orders of magnitude.
- Tiles are computed off the main thread and appear as they finish, in catalog order. A tile that
  is not ready yet shows flat `surfaceCard`, as browse.md's image tiles do — **no skeleton
  shimmer** (DESIGN.md §4).

## 8. Strings
| Key | Value |
|---|---|
| `editor_tool_style` | 스타일 |
| `style_title` | 스타일 |
| `style_none` | 원본 |
| `style_intensity` | 강도 |
| `style_variants` | 세부 |
| `style_from_photo` | 사진에서 가져오기 |
| `style_reference` | 참조 |
| `style_reference_near` | 참조와 비슷한 스타일 |
| `style_matching` | 참조 스타일을 읽는 중 |
| `style_needs_key` | 설정에서 Gemini API 키를 입력해주세요 |
| `style_failed` | 참조 스타일을 읽지 못했어요 |
| plus one `style_name_<id>` per catalog entry | 클린 브라이트, 내추럴, … |

## 9. Tests
- `StyleCatalogTest`: every preset parses; every `id` has a `style_name_<id>` string; every
  parameter maps to an `AdjustKind` or is on §3.1's recorded drop list; `intensity` scales linearly
  and 0 is the identity.
- `StyleParamsTest`: §3.1's conversion table round-trips, and every converted value is inside
  −1..1 — a preset that clamps is a bug in the table, not in the preset.
- `StyleMatchTest`: the local matcher ranks a reference measured *from a preset's own output* as
  that preset, first, at distance ~0. That is the one property that says the maths is right.
- `MatchStyleProviderTest`: the recorded body carries both images and the §5 instruction; a prose
  answer is dropped; a value outside ±100 clamps; error mapping row for row; availability flips
  with the key.
- Planner: §12's list plus `apply_style` decoding, an unknown id dropping the step, and the
  recorded body declaring eight functions.
- Tool: selecting a tile changes the canvas and commits nothing; 적용 writes one history entry
  holding every `Adjust` the preset carries; 취소 restores byte-for-byte; the 강도 slider scales
  what is committed.
- Goldens: `style_sheet_open`, `style_sheet_selected`, and one render golden per preset that
  survives §3.1 — a style is a promise about pixels, and a golden is how that promise is kept.

## 10. What this does not do
- **No LUT files** (§2).
- **No style authoring.** The catalog ships with the app; there is no "save my look".
- **No per-region styling.** A style is global. Making it obey a selection is the masked-adjust
  toggle's job (selection_tool.md §8.1) and is deliberately not wired here until someone asks.
- **The reference photograph is never stored.** It is read, sent once if §5 step 2 runs, and
  dropped. It is not a project, not a source, and not in the document.

## 11. Open decisions — a human answers these
1. **Is `styles.json` ours to ship?** `PhotoTune_v1` is a private repo with no LICENSE file. If it
   is the same author's, this is a copy within one project and nothing more is needed; if not,
   T72 blocks. The `_doc` field cites Karayev (BMVC 2014) and Ma (ICASSP 2018) as the *derivation
   basis* for the axis placement, not as data, so those are citations rather than dependencies.
2. **Twelve styles or fewer.** The catalog is what §3.1's gap is measured against; narrowing it to
   the styles that survive T71 intact is the cheaper answer and the worse one. Decide before T72,
   because it changes the enum `apply_style` declares.
3. **Does 컬러 매칭 belong in this sheet or its own tool?** It is here because it produces the same
   thing, and because a 13th tile is a smaller idea than an 11th tool (tool_groups.md exists for
   the opposite reason). If a device run says people cannot find it, it becomes a tool.
