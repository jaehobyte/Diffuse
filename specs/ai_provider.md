# specs/ai_provider.md — AI provider boundary

Owner tasks: T26 (segmentation), T39–T42 (erase), T44 (planning), T60 (fill), T64 (outpaint)
Module: `core:ai`
Depends on: architecture.md §6 (extension points), §9 (errors)
Decisions: ADR-009 (server-side SAM 3), ADR-011 (the device calls Gemini directly; supersedes
ADR-010, the sam3-server proxy), ADR-012 (one planning call, the app executes)

## 1. Purpose
Put every model behind a small suspend interface so the editor never knows which runtime is
underneath, tests run with fakes, and a different backend slots in without touching
`feature:editor`. In v2 both providers happen to be HTTP clients; nothing in the interfaces says so.

## 2. Module
`core:ai` depends on `core:common`, and on `core:imaging` for **`AdjustKind` alone**. It must not
depend on Compose, on Hilt-android UI, on Room, or on any `feature:*`. `feature:editor` depends on
`core:ai`.

That second edge is new in T44 and is worth being precise about, because this section was wrong
before it. T26–T42 wrote `core:ai` against `Bitmap` and never imported `ImageRef`, so `core/ai/
build.gradle.kts` declared **no** dependency on `core:imaging` at all — the "only for `ImageRef`"
this paragraph used to claim described an intention, not the build file. `EditPlanProvider` makes
the edge real: it returns steps naming an adjustment, and the honest type for one is the enum the
model layer already defines. Returning the wire's function name as a `String` instead would split
one validation across two modules and leave `PlanStep` unable to say what it means.

`AdjustKind` is a pure Kotlin enum with no Android dependency, and dependency-guard's allowlist has
permitted `:core:ai → :core:imaging` since T01, so T44 adds one line to a module build file and
nothing to the frozen root one. Nothing else in `core:imaging` may be reached for — no
`EditDocument`, no `Operation`, no renderer.

## 3. Interfaces
```kotlin
sealed interface Availability {
    object Ready : Availability
    data class Unavailable(val reason: AppError) : Availability   // not configured, server down, no network
}

/** A server-side inference session. Opaque handle; only the provider interprets [imageId]. */
data class SegSession(
    val imageId: String,
    val imageWidth: Int,          // pixel size of the bitmap that was uploaded
    val imageHeight: Int,
    val expiresAtEpochMs: Long,   // advisory only; expiry is absorbed by the provider, not the caller
)

data class PointPrompt(
    val points: List<PointF>,     // normalized 0..1 against the uploaded image
    val labels: List<Boolean>,    // true = foreground
) { init { require(points.size == labels.size && points.isNotEmpty()) } }

/** [alpha] is ALPHA_8 at exactly the working-image size, strictly binary (0 or 255). */
data class SegMask(val alpha: Bitmap, val score: Float /* 0..1 */)

interface SegmentationProvider {
    val availability: StateFlow<Availability>
    suspend fun refresh()                                          // re-probe, then update availability
    suspend fun open(image: Bitmap): Result<SegSession>
    suspend fun byPoints(session: SegSession, prompt: PointPrompt): Result<SegMask>
    suspend fun byText(session: SegSession, phrase: String): Result<List<SegMask>>
    suspend fun close(session: SegSession)
}

interface EraseProvider {
    val availability: StateFlow<Availability>
    /** [mask] is ALPHA_8 at [image]'s size. Opaque pixels are the region to erase. */
    suspend fun erase(image: Bitmap, mask: Bitmap, hint: String?): Result<Bitmap>
}

/** T60, generative_fill.md. Separate from [EraseProvider] — see the note below. */
interface FillProvider {
    val availability: StateFlow<Availability>
    /** [mask] is ALPHA_8 at [image]'s size. Opaque pixels become [prompt]. */
    suspend fun fill(image: Bitmap, mask: Bitmap, prompt: String): Result<Bitmap>
}

/** [left]…[bottom] are fractions of [image]'s width/height added on each side; each ≥ 0. */
data class Margins(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** T64, outpaint.md. Returns the whole expanded image, not just the new border. */
interface OutpaintProvider {
    val availability: StateFlow<Availability>
    suspend fun outpaint(image: Bitmap, margins: Margins): Result<Bitmap>
}

/** One step of a plan. Each maps onto a tool that already exists; see vibe_edit.md §4. */
sealed interface PlanStep {
    data class Select(val phrase: String) : PlanStep
    data class Adjust(val kind: AdjustKind, val value: Float, val masked: Boolean) : PlanStep
    object Erase : PlanStep
    object CutOut : PlanStep
    data class Fill(val prompt: String) : PlanStep                  // T62
    data class Crop(val ratio: CropRatio) : PlanStep                // T58
}

enum class CropRatio { Square, Portrait4x5, Story9x16, Landscape16x9 }

/** [steps] in execution order. Empty means the model declined to act — not a failure. */
data class EditPlan(val steps: List<PlanStep>)

interface EditPlanProvider {
    val availability: StateFlow<Availability>
    /** Decides which tools to run for [request]. One call, no session, no state. */
    suspend fun plan(image: Bitmap, request: String): Result<EditPlan>
}
```

`EditPlanProvider` chooses a workflow; it never returns pixels. Executing the steps is
`feature:editor`'s job (vibe_edit.md §9), which is what keeps this interface as small as the other
two and lets a different planner — or a hand-written one — slot in behind it.

**Three generative interfaces, one transport.** `EraseProvider`, `FillProvider` and
`OutpaintProvider` all end up in `GeminiEraseClient.edit(image, instruction)` behind three
instruction constants (generative_fill.md §3). They are still three interfaces rather than one
method with a mode argument, because each takes different arguments — a hint, a required prompt, a
`Margins` — and a single method would carry two unused parameters at every call site and force every
fake to answer for behaviour it does not implement. The duplication is four lines of interface; the
alternative is a parameter list nobody can read.

`PlanStep.Crop` carries `CropRatio`, an enum defined here rather than in `feature:editor`, for the
same reason `Adjust` carries `AdjustKind` (§2): the plan model must be able to say what it means.
It maps to `AspectPreset` at the `feature:editor` boundary; `core:ai` does not reach for crop
geometry.

`EraseProvider` says nothing about *how* the hole is described to a model. Painting the masked
region white is `GeminiEraseProvider`'s private business (generative_erase.md §4), so swapping the
backend under this interface leaves `EraseController` and `FakeEraseProvider` untouched.

## 4. Contract
- `open` is expensive (a full upload plus one backbone pass). Callers call it once per image and
  reuse the session for every prompt. A provider keeps **at most one** live session; a second `open`
  closes the first.
- `byPoints` and `byText` are cheap relative to `open` and are pure with respect to the session:
  the same prompt returns the same mask.
- `byText` returns **zero or more** masks, ordered by descending score. An empty list is a valid
  answer — the concept is absent — not a failure.
- Every call runs on `DispatcherProvider.io` and honors cancellation. `CancellationException`
  propagates; it is never an `AppError`.
- Input bitmaps are `ARGB_8888` at working resolution (≤ 4096). Providers downscale internally if a
  transport limit demands it; returned masks are always at the **input image size**.
- Session expiry is the provider's problem, not the caller's. A caller never sees a "session
  expired" error; see segmentation.md §5.
- `close` is best-effort and never fails the caller. Losing the release only costs the server a TTL.
- `refresh` is how `availability` becomes true rather than assumed. Probing costs a round trip, so
  the provider never polls: the caller refreshes when the tool opens and when the settings change
  (segmentation.md §7). Providers may also update `availability` as a side effect of a call that
  proves the backend is or is not reachable.

## 5. Errors
`Result.Failure(AppError)`, using the cases in architecture.md §9:

| Case | Raised when |
|---|---|
| `Invalid(detail)` | the prompt was rejected — empty phrase, coordinate outside 0..1, mismatched labels |
| `Unauthorized` | the configured token is missing or wrong |
| `Unavailable` | the backend is not ready, out of memory, or unreachable |
| `TooLarge` | the image exceeds the transport limit even after downscaling |
| `Unsupported` | the backend rejected the media type |
| `Io(cause)` | transport or decode failure |

## 6. Fakes
`FakeSegmentationProvider` (in `core:ai` test fixtures, used by every UI test):
- `availability = Ready`
- `open` returns after 10 ms (configurable) with a session whose size is the bitmap's
- `byPoints` returns a filled circle of radius `0.2 × shortEdge` around the **first foreground
  point**, minus a circle of radius `0.1 × shortEdge` at each background point; score `0.9`
- `byText` returns two deterministic circles whose centres are derived from `phrase.hashCode()`,
  scores `0.9` and `0.7`; the phrase `"없음"` returns an empty list
- `failNext(error)` makes the next call fail

`FakeEraseProvider`: fills the mask region with the mean color of the pixels in a 4px band just
outside the mask. Deterministic, so goldens are stable.

`FakePlanProvider`: `availability = Ready`; `next(plan)` sets what the following call returns and
`failNext(error)` makes it fail. The default plan is
`EditPlan(listOf(Select("나무"), Adjust(Saturation, 0.3f, masked = true)))` — the request vibe_edit.md
was specified from, so the goldens read as that story.

No test reaches an external host. The `Sam3Client`, `GeminiEraseClient` and `GeminiPlanClient`
tests use `MockWebServer` on localhost; all three take their base URL as a constructor seam, which
is what makes that possible for a client whose production host is a public one.

## 7. DI
```kotlin
@Module @InstallIn(SingletonComponent::class)
abstract class AiModule {
    @Binds abstract fun seg(impl: Sam3SegmentationProvider): SegmentationProvider
    @Binds abstract fun erase(impl: GeminiEraseProvider): EraseProvider
    @Binds abstract fun plan(impl: GeminiPlanProvider): EditPlanProvider
}
```
Tests replace it with `@TestInstallIn` binding the fakes.

## 8. Future providers (do not implement)
`UpscaleProvider`, `GenerativeFillProvider` follow the same shape: `Availability`, one suspend
entry point, `Result<...>`. `SpeechInput` deliberately does **not** live here in this form — it is a
device service with a streaming state, specified in prompt_input.md §3.
