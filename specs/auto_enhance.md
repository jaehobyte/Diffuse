# specs/auto_enhance.md — 자동 보정 (MonetGPT)

Owner tasks: T76 (the provider), T77 (the tool)
Modules: `core/ai/monet`, `feature/editor/tools/auto`
Depends on: ai_provider.md, segmentation.md (the server-config pattern), adjust_light.md,
adjust_color.md, adjust_hsl.md, edit_model.md
Amends: editor_shell.md (a tool)
Decisions: ADR-015 (the server returns parameters, not pixels; see §2)

## 1. What it does
One tap: the photo is sent to a server running **MonetGPT**, which answers with a retouching plan,
and the plan lands in the document as ordinary `Adjust` ops the user can then drag.

MonetGPT is Dutt et al., *"MonetGPT: Solving Puzzles Enhances MLLMs' Image Retouching Skills"*,
SIGGRAPH 2025 (arXiv:2505.06176, `github.com/niladridutt/monetgpt`, MIT). It is an MLLM taught to
reason about retouching operations by solving visual puzzles; it critiques a photograph, proposes a
plan, and emits parameter values for it. Its own words for why that shape: unlike generative
editing it "preserves object identity and provides explainable results".

## 2. Parameters, not pixels (ADR-015)
MonetGPT's own pipeline ends in GIMP: `inference_cli.py` renders the edited file. **We do not use
that half.** The server runs the MLLM and returns its JSON; the device renders it.

Three reasons, in order:

- **It is already our renderer's job.** Diffuse's `Adjust` ops cover almost all of MonetGPT's
  operation set (§3) at full export resolution. Asking a server to render loses the resolution, the
  colour management and the eleven adjust goldens that say the maths is right.
- **The result stays editable.** A returned photograph is one opaque op — style_match.md §2's
  argument, and the same conclusion. Auto 보정 that a user cannot then disagree with is a filter,
  not a boost.
- **It is a smaller server.** No GIMP, no 16-bit TIFF round trip, no file storage. An
  OpenAI-compatible endpoint in front of the released model, which is what `llm/` already is.

**So the server is the model, and only the model.** This is the same split segmentation.md made for
SAM 3: the device sends an image and receives a description of an edit, never the edit itself.

## 3. The operation set, and the two that are missing
MonetGPT emits Lightroom operation names in three groups (`configs/inference_config.yaml`):

| Group | Operations | Diffuse |
|---|---|---|
| white-balance-tone-contrast | Exposure, Contrast, Highlights, Shadows, **Blacks**, **Whites** | 4 of 6 |
| color-temperature | Temperature, Tint, Saturation | all |
| hsl | Hue/Saturation/Luminance × {red, orange, yellow, green, aqua, blue, purple, magenta} | **all 24** |

The HSL half is an exact match — the same eight bands, the same three channels, the same names
adjust_hsl.md §10 already gave `AdjustKind`. That is a coincidence worth noticing: it means
T54's 24 entries were the right 24.

**`Blacks` and `Whites` do not exist in `AdjustKind`.** They are the tone curve's endpoints, the
siblings of `Highlights` and `Shadows`, and style_match.md §3.1 needs them too. T71 adds them, and
**this spec's tasks depend on T71** rather than dropping two of six tone operations and calling the
result an auto boost.

**Values are −100..+100** — the inference prompt says so verbatim: "All adjustment values are
scaled between -100 and +100." `Operation.Adjust` is −1..1, so the conversion is `/100` and a value
outside the range clamps. One place, named, and the same table style_match.md §3.1 owns.

## 4. The wire (T76)
Mirrors segmentation.md's config exactly, because it is the same kind of thing: a self-hosted
model behind a URL the user pastes in.

```
POST {baseUrl}/v1/chat/completions          OpenAI-compatible, which is what monetgpt/llm serves
  Authorization: Bearer <token>             optional; blank means no header
```
The request is MonetGPT's own two-stage shape, collapsed to what we need:

1. the photo, downscaled to **1280** on its long edge (`inference_config.yaml`'s `max_dimension`),
   PNG, base64;
2. the style instruction — `balanced`, `vibrant` or `retro`, the three the released config carries;
3. the JSON instruction, verbatim from `inference/core.py`.

- `baseUrl` and `token` live in the 서버 설정 sheet beside SAM 3's, and default to **blank**. No
  address is shipped: generative_erase.md §2's rule, one server over.
- Availability is a `/health` probe like SAM 3's, **not** the Gemini "is a key present" rule — this
  server is the user's own and reachability is the real question.
- Timeouts: connect 10 s, read 120 s. Two MLLM turns on a photograph is slower than one generation,
  and this is the call where that is expected. Cancellable.
- The answer is JSON in a text part; `extract_json_from_response`'s tolerance (find the outermost
  braces) is reimplemented, because a reasoning model narrates.

### Error mapping
generative_erase.md §6 row for row. **No new `AppError` case.** Plus one row of its own:

| Condition | `AppError` |
|---|---|
| the JSON parses but names no operation we recognise | `Unsupported` |

## 5. `AutoEnhanceProvider` (ai_provider.md §3)
```kotlin
enum class AutoStyle { Balanced, Vibrant, Retro }

interface AutoEnhanceProvider {
    val availability: StateFlow<Availability>
    /** The adjustments to apply, already in -1..1. Never pixels. */
    suspend fun enhance(image: Bitmap, style: AutoStyle): Result<Map<AdjustKind, Float>>
}
```
`AdjustKind` is the one type `core:ai` reaches into `core:imaging` for (ai_provider.md §2), so this
adds no new module edge. `FakeAutoEnhanceProvider` in `core/ai/src/testShared` returns a fixed,
deterministic map, so a golden can depend on it.

## 6. 자동 the tool (T77)
`Tool.Auto(editor_tool_auto, Icons.Rounded.AutoFixNormal, isAi = true)`.

It has **no sheet before the call** — like 지우기, tapping it runs (generative_erase.md §5). What
it does have is an **after**: the result arrives as a preview with a three-chip row and one bar.

```
자동 보정                                        headingLg
[ 균형 ][ 선명 ][ 레트로 ]                        chips; changing one re-runs
강도  ────●────────  100                         slider, mono value
지시  노출을 올리고 그림자를 열었어요               bodySm, the model's own reason
                                    [취소 | 적용]  Apply is the sheet's one accent
```

- The plan applies **live** while the sheet is open, so 강도 is a slider on a result the user is
  already looking at.
- 강도 scales every returned value by `intensity / 100`, exactly as a style's does
  (style_match.md §3). The slider is local; changing it costs no call.
- Changing the **chip** costs a call, and says so by showing the progress overlay again.
- The 지시 line is the model's stated reason, translated by nobody: MonetGPT answers in English and
  we show it in English, in `bodySm` in `editInkSecondary`. It is evidence, not copy. *(Open
  decision 2.)*
- 적용 commits **one history entry** holding every `Adjust`, so one undo removes the whole boost.
- Cancelling commits nothing. A failure leaves the sheet open with the previous chip's result.

Disabled states:

| State | String | Action |
|---|---|---|
| `baseUrl` is blank | `auto_needs_server` | opens the 서버 설정 sheet |
| the probe failed | `auto_unreachable` | — |
| the answer named no operation we know | `auto_failed` | — |

## 7. Strings
| Key | Value |
|---|---|
| `editor_tool_auto` | 자동 |
| `auto_title` | 자동 보정 |
| `auto_style_balanced` | 균형 |
| `auto_style_vibrant` | 선명 |
| `auto_style_retro` | 레트로 |
| `auto_intensity` | 강도 |
| `auto_reason` | 지시 |
| `auto_working` | 사진을 살펴보는 중 |
| `auto_needs_server` | 설정에서 자동 보정 서버 주소를 입력해주세요 |
| `auto_unreachable` | 자동 보정 서버에 연결하지 못했어요 |
| `auto_failed` | 보정할 내용을 찾지 못했어요 |

## 8. Tests
- `MonetClientTest`: the recorded body carries the downscaled PNG and the §4 instruction; a JSON
  answer wrapped in prose parses; an answer naming an unknown operation drops **that** entry and
  keeps the rest; an answer naming none is `Unsupported`; values outside ±100 clamp; error mapping
  row for row; availability follows the probe.
- `AutoEnhanceProviderTest`: every operation name in MonetGPT's three groups maps to an
  `AdjustKind` or is on §3's recorded miss list — the test that will fail the day the model's
  vocabulary changes.
- Tool: a blank address opens the 서버 설정 sheet; 적용 writes one history entry holding every
  adjustment; one undo removes all of them; 강도 scales what is committed and costs no second call;
  changing the chip costs one; cancelling commits nothing.
- Goldens: `auto_sheet_open`, `auto_sheet_result`.
- **No golden of a real result.** `FakeAutoEnhanceProvider`'s map is fixed, so the golden asserts
  the *rendering* of a plan, not the model's taste. What the model actually chooses is a device
  question, and testing.md §7 has no fixture that could answer it.

## 9. What this does not do
- **No local model.** MonetGPT is a fine-tuned MLLM; it runs on the user's server or not at all.
  This is ADR-007/008's conclusion for segmentation, reached again.
- **No crops, no regional edits.** The paper says so itself: MonetGPT "supports a limited set of
  global operations, excluding crops or regional edits". `masked=false` always, and the auto boost
  never touches `activeMaskId`.
- **No auto-on-import.** Nothing is boosted without a tap. persistence.md's documents stay the
  photograph the user imported until they say otherwise.

## 10. Open decisions — a human answers these
1. **Who runs the server?** SAM 3 already needs one and has no shipped address. A second
   self-hosted service is a second thing that must be up for a tool to be tappable. Sharing one
   host is an ops decision, not a code one.
2. **The 지시 line in English.** The model reasons in English and DESIGN.md §9 wants Korean on
   screen. Translating it costs a second round trip on every boost; showing it in English puts a
   model-authored English string in front of a Korean user; dropping it hides the one thing that
   makes this explainable rather than magic. This spec picks *show it in English* and flags it,
   the way vibe_edit.md §4's open question about English nouns is still flagged.
3. **Three styles or one.** The released config carries `balanced`, `vibrant`, `retro`. Three chips
   is three times the calls and a decision the user has to make before seeing anything. Shipping
   `balanced` alone and adding chips later is the smaller product.
