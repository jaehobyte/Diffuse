# specs/prompt_input.md — Prompt bar and voice input

Owner tasks: T34 (`PromptBar`), T35 (voice), T36 (prompt → mask)
Modules: `core:ui/components` (the composable), `core/ai/speech` (the device service)
Depends on: DESIGN.md §4 (prompt bar row, added in T25), selection_tool.md, segmentation.md

## 1. Purpose
One text field that carries a short natural-language phrase to whichever tool is open. In v2 the
only consumer is the selection tool's `byText`. It is specified as a reusable component because the
generative tools (T38, D10) take the same input, not because reuse was speculated at.

The phrase is a **short noun phrase**, not a sentence — `"신발"`, `"노란 버스"`. This matters:
SAM 3's text endpoint is concept segmentation, and a sentence degrades it. The placeholder says so.

## 2. `PromptBar` (T34)
```kotlin
@Composable fun PromptBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onMicClick: (() -> Unit)?,     // null hides the mic entirely
    listening: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
)
```
Styling comes only from `Tokens.kt`:

| Property | Value |
|---|---|
| Height | 48dp |
| Corner radius | 16dp (the pill radius; DESIGN.md §4 "every button is a pill" extends to this) |
| Fill | `editSurfaceRaised` (the bar only ever appears in Edit mode) |
| Text | `bodyMd` in `editInk`; placeholder in `editInkSecondary` |
| Horizontal padding | 12dp; 8dp gap between the icons and the text |
| Mic icon | 24dp, leading, 48dp hit area, `editInk` at rest |
| Send icon | 24dp, trailing, 48dp hit area, `editInk` — **never `accent`**, see below |
| Disabled | 38% alpha, color unchanged (DESIGN.md §4) |

Behavior:
- Send is enabled only when `value.trim()` is non-blank. The IME action is `Done` and submits.
- `onSubmit` receives the trimmed value. The bar does not clear itself; the host decides.
- The send icon is **not** accent. DESIGN.md §1 allows one accent per surface at rest, and in every
  sheet that is the Apply pill; letting a prompt bar take it would make one sheet commit differently
  from all the others. Send reads as actionable through its enabled state and the IME Done key.
- `listening = true` tints the mic `accent` — the transient exception in DESIGN.md §1. **A fill
  change only**: no glow, no pulse, no animation (§7 forbids glow). While listening, the send icon is
  replaced by a stop icon in the same slot.
- Every string is Korean and lives in `strings.xml`: placeholder `"무엇을 선택할까요? 예: 사람, 하늘"`,
  and content descriptions for the mic, stop, and send icons.

Goldens: `prompt_bar_empty`, `prompt_bar_filled`, `prompt_bar_listening`.

## 3. Voice input (T35)
```kotlin
sealed interface SpeechState {
    object Idle : SpeechState
    data class Listening(val partial: String) : SpeechState
    data class Final(val text: String) : SpeechState
    data class Failed(val error: AppError) : SpeechState
}

interface SpeechInput {
    val state: StateFlow<SpeechState>
    fun start(localeTag: String = "ko-KR")
    fun stop()
}
```
`SpeechInput` sits in `core/ai/speech` but is **not** an `ai_provider.md` provider: it has no
`Availability` flow and no suspend entry point, because it is a streaming device service rather
than a request/response model.

`AndroidSpeechInput` wraps `android.speech.SpeechRecognizer`:
- `EXTRA_PARTIAL_RESULTS = true`, so `Listening.partial` streams into the bar as the user speaks.
- Recognition happens through the OS. **No network code is written in the app for this.**
- `SpeechRecognizer.isRecognitionAvailable(context) == false` → the host passes `onMicClick = null`
  and the mic never renders.
- Recognizer errors map to `AppError`: `ERROR_NETWORK` / `ERROR_SERVER` → `Unavailable`,
  `ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT` → `Invalid`, everything else → `Io`.

Permission:
- `RECORD_AUDIO` is declared in the manifest and requested **at the first mic tap**, never at launch.
- Denied once → Korean snackbar "마이크 권한이 필요해요."; the mic stays tappable for a retry.
- Denied permanently (`shouldShowRequestPermissionRationale == false` after a denial) → the mic is
  hidden for the rest of the session. No settings-deep-link nagging.

`FakeSpeechInput` drives every test: `emit(Listening("사"))`, `emit(Final("사람"))`, `emit(Failed(...))`.

## 4. Flow: prompt or speech → mask (T36)
This is the end-to-end behavior the feature exists for.

```
type a phrase ──┐
                ├─▶ onSubmit(phrase) ─▶ byText(session, phrase) ─▶ union(masks) ─▶ merge by mode
speak ─▶ Final ─┘                                                                  (selection_tool.md §4)
```
- A `Final` speech result **auto-submits**; the user does not have to tap send. `Listening.partial`
  only updates the visible text.
- While a prompt is in flight: the bar is disabled, and the progress overlay shows with a cancel
  button (DESIGN.md §7 "always show progress and a cancel button during AI work"). Cancelling
  leaves `accumulated` exactly as it was.
- `byText` returning an empty list → the `NotFound` hint from selection_tool.md §7. Not an error,
  not a snackbar.
- A failed prompt → Korean snackbar on a dark surface; the bar keeps its text so the user can retry
  without retyping.
- The bar clears on a successful merge, and only then.

## 5. Tests
- `PromptBar`: send disabled on blank and on whitespace-only; IME Done submits the trimmed value;
  `listening` swaps send for stop; `onMicClick = null` renders no mic. Plus the three goldens.
- Voice with `FakeSpeechInput`: partial updates the text; `Final` auto-submits; `Failed` shows a
  snackbar and leaves the text; stop while listening returns to `Idle` without submitting.
- Permission: granted → `start` called; denied → snackbar and the mic still present; permanently
  denied → mic gone.
- Flow with both fakes: type → merged mask; speak → merged mask; empty result → hint; failure →
  snackbar with the text preserved; cancel mid-flight → `accumulated` unchanged.
- Golden `select_prompt_result`.
