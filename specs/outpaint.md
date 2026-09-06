# specs/outpaint.md — 확대 (outpainting)

Owner tasks: T63 (the op + the renderer), T64 (the provider), T65 (the tool)
Modules: `core/imaging/model` + `core/imaging/render`, `core/ai/gemini`,
`feature/editor/tools/expand`
Depends on: generative_erase.md, generative_fill.md, edit_model.md, render.md, crop.md,
canvas.md, ai_provider.md, DESIGN.md §4, §5
Decisions: ADR-013 (outpainting extends the source; see §3)

DESIGN.md §7 already names this feature: "Feature names are verbs: 배경 제거, 채우기, **확대**".

## 1. What it does
Grows the photo past its own edges and lets `gemini-2.5-flash-image` invent what was outside the
frame. The user drags a canvas edge outward, sees the new area as empty space, and taps 적용.

The motivating request is a ratio: a 4:3 photo that has to become 9:16 for a story. 자르기 answers
that by throwing pixels away; 확대 answers it by making more.

## 2. Why this op is different from every other one
Every operation before this one either preserves the canvas size (`Adjust`, `Mask`, `CutOut`,
`GenerativeErase`, `GenerativeFill`) or shrinks it (`Crop`). **`Outpaint` is the only one that
makes the canvas bigger**, and that is not a detail: `Crop.rect` is normalized against the source,
`Mask.maskRef` is a bitmap at working resolution, and both are silently wrong the moment the canvas
they were measured against changes size.

So the coordinate space has to be decided before anything else, and §3 is that decision.

## 3. The model: `Outpaint` extends the source (ADR-013)
```kotlin
/** [margins] are fractions of the source's width/height added on each side; each in 0f..MAX. */
data class Margins(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class Outpaint(
    override val id: String,
    val margins: Margins,
    val resultRef: ImageRef,   // outpaint_<id>.png — the whole expanded image, working resolution
) : Operation
```

Rules, all of them load-bearing:

- **At most one, and it is always first in `operations`.** `withOutpaint` inserts at index 0 and
  replaces an existing one. Everything after it therefore measures against one canvas — the
  expanded one — and no op ever has to ask which era it was created in.
- **The request is built from the bare source**, not the current preview: the document with its
  operations dropped, which `EditorViewModel.renderSource` already renders. A second 확대 recomputes
  from the bare source at the new margins rather than extending the previous answer, so margins
  never compound and quality never degrades by iteration.
- **The 확대 tool is disabled while the document holds any `Mask`, `CutOut`, `GenerativeErase` or
  `GenerativeFill` op**, with the reason shown as `expand_after_mask`. Those ops carry pixels or
  alpha sized to the un-extended canvas; re-basing them would mean resampling stored PNGs, which is
  a quality loss the user did not ask for. 확대 comes before 선택. This is the honest cost of §3 and
  it is one guard, not a rule scattered through the renderer.
- **An existing `Crop` is re-normalized, not dropped.** Committing an `Outpaint` maps the stored
  rect into the expanded space — pure arithmetic on `margins`, deterministic, and property-tested:

  ```
  scaleX = 1 / (1 + left + right)
  newRect.left = (oldRect.left + left) * scaleX      (and likewise for the other three)
  ```
  `angleDeg` is unaffected. A crop is four numbers with no pixels behind them, so unlike a mask it
  costs nothing to move.
- Each margin is clamped to `MAX_MARGIN_FRACTION = 0.5f` — half the corresponding dimension per
  side. Past that the model is inventing more picture than it was given and the answer stops
  resembling the photograph. One constant, named, with that sentence as its KDoc.
- `outpaint_<id>.png` is kept alive by the file store exactly as `erase_<id>.png` is.
- JSON root `v` stays **1**: adding an operation type is backward compatible, because edit_model.md
  drops unknown types with a warning and still loads the document.

### The two designs this rejects
**Flattening** — make the answer the new `source` and clear the operation list. It has no
coordinate problem at all and undo still works, because `HistoryStack` snapshots whole documents.
It was rejected on one number: the model returns roughly a megapixel, so flattening caps a 12 MP
photo's export at the model's output resolution. That loss is invisible in the editor, irreversible
after the next save, and applies to the **whole frame** rather than only the invented border.

**An ordinary in-list op** — let the canvas grow wherever the op happens to sit. It is the most
"non-destructive" reading, and it breaks `Crop`'s normalization rule, every stored mask's size and
the export maths at once, in exchange for an ordering freedom no user has asked for.

## 4. Rendering (T63)
`Renderer` gains one step ahead of the in-order walk T49 built:

1. Decode `source` at the requested size, as today.
2. **If `operations[0]` is an `Outpaint`:** allocate the expanded canvas
   (`w × (1 + left + right)`, `h × (1 + top + bottom)`, rounded), draw `resultRef` scaled to fill
   it, then draw the decoded source into its interior rect over the top.
3. Apply the remaining operations in list order, against the expanded canvas.
4. `Crop` last, as render.md already says.

Step 2 is why §3 is worth its guard: **the original pixels survive at whatever resolution they were
decoded at**, and only the invented border comes from the model's ~1024px answer. At export the
interior is the full-resolution photograph and the border is upscaled — soft, but it is generated
content, which has no sharper version anywhere.

**The seam gets an alpha ramp**, `OUTPAINT_BLEND_PX = 8` at working resolution, scaled
proportionally at full resolution: the source is drawn with alpha ramping 0→1 across that many
pixels inward from the interior edge. This is a deliberate departure from generative_erase.md §11,
which composites through a hard mask edge and is right to: an erase boundary follows an object's
own outline, where a hard cut reads as the object. An outpaint boundary is a perfect rectangle
across the whole frame, and `gemini-2.5-flash-image` regenerates the entire image (§11 again), so
the model's interior differs from the original *everywhere*. Without the ramp that difference
appears as four straight lines. One constant, one golden.

`onProgress` still reaches exactly 1f and never goes backwards; the composite is one step.

## 5. The wire (T64)
The mask trick generalizes. `WhiteFill` paints a region white; outpainting paints a **border**
white — the source composited onto a larger canvas whose new area is opaque `#FFFFFF`:

```kotlin
internal object WhitePad {
    /** Returns a new ARGB_8888 canvas [margins] larger than [image], the new area pure white. */
    fun apply(image: Bitmap, margins: Margins): Bitmap
}
```
It lives beside `WhiteFill` in `core/ai/gemini`, for the reason generative_erase.md §4 gives: it is
a detail of how one provider talks to one model, not a rendering operation.

`GeminiOutpaintProvider` posts through the **same** `GeminiEraseClient.edit(image, instruction)`
seam generative_fill.md §3 introduces. Instruction, an English `internal` constant:

```
The image has a solid pure-white border around a photograph. Extend the photograph into that
border so the whole image looks like one wider photograph taken from the same position: continue
the scene's geometry, horizon, lighting, texture, focus and grain outward. Do not add a new
subject, text or watermark. Do not alter the photograph inside the border. Return only the
edited image, at the same aspect ratio as the input.
```

The last clause matters more here than anywhere else: the provider maps the answer onto a canvas
whose aspect it already computed, so a model that returns a different ratio shifts the interior.
The provider therefore **rejects an answer whose aspect differs from the request by more than 2%**
with `Unsupported`, rather than scaling it and moving the user's photograph. generative_erase.md
§11 accepted that risk without a guard because nothing outside its mask could move; here the whole
frame is at stake, so the guard is written.

T51's still-white guard applies unchanged, measured over the **border** rather than a mask: an
answer whose new area comes back ≥ `WHITE_RESULT_THRESHOLD` white is `Unavailable`.

Error mapping is generative_erase.md §6, row for row. **No new `AppError` case.**

## 6. The tool (T65)
`Tool.Expand(R.string.editor_tool_expand, Icons.Rounded.OpenInFull, isAi = true)`, inserted after
`Tool.Fill`. `editor_shell_default` re-recorded for that reason alone.

**Overlay** (canvas.md's single overlay slot, claimed the way 자르기 claims it):
- The photo shrinks to leave room, and the pending margins are drawn as `editBackground` with the
  8dp `canvasCheckerA`/`canvasCheckerB` pattern DESIGN.md §2 already defines for transparency — this area has no
  pixels yet, and the checkerboard is the app's existing word for that.
- Four edge handles, 24dp, `editInk`, on the midpoint of each edge. **They drag outward only**; an
  inward drag clamps at 0. Shrinking is 자르기's job and the two tools do not overlap.
- Dragging past `MAX_MARGIN_FRACTION` stops at it. No rubber-band, no snap.
- Touch targets 48dp (DESIGN.md §5), and a drag outside a handle pans the canvas, exactly as the
  crop overlay behaves.

**Sheet** (max 45%, DESIGN.md §4):
```
확대                                            headingLg
비율  4:3 → 9:16                                 mono, right-aligned  (§7)
                                    [취소 | 적용]  Apply is the sheet's one accent
```
- The ratio readout is `mono`, DESIGN.md §3's role for "pixel coordinates, file size, HEX values" —
  a computed number, not prose.
- 적용 is disabled while every margin is 0.
- **No prompt bar.** 확대 continues a scene the model can already see; there is nothing for a person
  to name. That is 채우기's job (generative_fill.md §10).
- The progress overlay shows `expand_working` with a cancel button. Cancelling leaves the document
  byte-for-byte untouched.
- On failure the sheet stays open with the margins intact.

Disabled states:

| State | String | Action |
|---|---|---|
| the document holds a `Mask`, `CutOut`, `GenerativeErase` or `GenerativeFill` | `expand_after_mask` | — |
| key is blank | `expand_needs_key` | opens the 서버 설정 sheet |
| failure, `detail` starts with `blocked:` | `expand_blocked` | — |
| the answer came back still white, or at a different aspect | `expand_failed` | — |

## 7. Strings (T65)
| Key | Value |
|---|---|
| `editor_tool_expand` | 확대 |
| `expand_title` | 확대 |
| `expand_ratio` | 비율 %1$s → %2$s |
| `expand_working` | 바깥을 그리는 중 |
| `expand_after_mask` | 선택이나 생성 작업 전에 먼저 확대해주세요 |
| `expand_needs_key` | 설정에서 Gemini API 키를 입력해주세요 |
| `expand_blocked` | 이 이미지는 편집할 수 없어요 |
| `expand_failed` | 바깥을 그리지 못했어요 |

## 8. Tests
- `MarginsTest` / `OutpaintGeometryTest`: the expanded size rounds consistently; a margin clamps at
  `MAX_MARGIN_FRACTION`; the crop re-normalization is the identity when every margin is 0, and a
  round trip through a 0.25 margin puts a centre-anchored rect back where it started.
- `WhitePadTest`: the new border is exactly `0xFFFFFFFF`; the interior equals the input pixel for
  pixel; the input bitmap is not mutated; zero margins return a pixel-identical copy.
- `GeminiOutpaintProviderTest`: the bytes on the wire are the **padded** image (decode the recorded
  body and sample a border pixel); an answer at a different aspect fails with `Unsupported` and
  commits nothing; a still-white border fails; `availability` flips with the key.
- Renderer: `outpaint_render` golden; a golden proving the interior is the **source's** pixels and
  not the model's, sampled well inside the ramp; `[Outpaint, Adjust]` applies the adjustment across
  the whole expanded canvas; `Renderer.full` composes the stored PNG rather than dropping it.
- Document: round-trip with an `Outpaint`; a second `withOutpaint` replaces rather than appends and
  stays at index 0; committing one re-normalizes an existing `Crop`; an unknown op still drops with
  the document loading.
- Tool: the handles drag outward only; 적용 disabled at zero margins; the mask-op guard greys the
  tool with `expand_after_mask`; a blank key opens the 서버 설정 sheet; cancelling commits nothing.
- Goldens (UI): `expand_overlay`, `expand_sheet_open`. `editor_shell_default` re-recorded for the
  new strip item only.

## 9. What this does not do
- **No planner function.** 확대 is deliberately absent from vibe_edit.md §4's catalog in v1. Asked
  to make a photo 9:16, a model has to choose between throwing pixels away and inventing them, and
  that choice belongs to the person who took the photograph. `crop_ratio` is the answer the planner
  gets (vibe_edit.md §4); 확대 stays a manual tool. Revisit only with a device run showing users
  ask for it by sentence.
- **No margin presets.** A "9:16으로" chip would be `crop_ratio`'s shape pointed the other way, and
  it needs the drag to exist first so the user can correct it. D16 if it is ever wanted.
- **No compounding.** A second 확대 recomputes from the bare source (§3), so the model never
  extends its own invention.
- **No prompt** (§6).
