# specs/ai_provider.md — AI provider boundary

Owner tasks: T26 (segmentation), T37 (erase)
Module: `core:ai`
Depends on: architecture.md §6 (extension points), §9 (errors)
Decisions: ADR-009 (server-side SAM 3), ADR-010 (generative editing via the sam3-server proxy)

## 1. Purpose
Put every model behind a small suspend interface so the editor never knows which runtime is
underneath, tests run with fakes, and a different backend slots in without touching
`feature:editor`. In v2 both providers happen to be HTTP clients; nothing in the interfaces says so.

## 2. Module
`core:ai` depends on `core:common` and on `core:imaging` **only for `ImageRef`**. It must not depend
on Compose, on Hilt-android UI, or on any `feature:*`. `feature:editor` depends on `core:ai`. Add the
edge to dependency-guard.

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
```

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

No test may reach an external host. `Sam3Client` tests use `MockWebServer` on localhost
(CLAUDE.md hard limits, as amended by T25).

## 7. DI
```kotlin
@Module @InstallIn(SingletonComponent::class)
abstract class AiModule {
    @Binds abstract fun seg(impl: Sam3SegmentationProvider): SegmentationProvider
    @Binds abstract fun erase(impl: Sam3EraseProvider): EraseProvider
}
```
Tests replace it with `@TestInstallIn` binding the fakes.

## 8. Future providers (do not implement)
`UpscaleProvider`, `GenerativeFillProvider` follow the same shape: `Availability`, one suspend
entry point, `Result<...>`. `SpeechInput` deliberately does **not** live here in this form — it is a
device service with a streaming state, specified in prompt_input.md §3.
