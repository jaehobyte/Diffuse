# specs/ai_provider.md — AI provider boundary

Owner task: T26
Module: `core:ai`
Depends on: architecture.md §6 (extension points)

## 1. Purpose
Put every model behind a small suspend interface so the editor never knows which runtime or model is underneath, tests run with a fake, and a future cloud or generative provider slots in without touching `feature:editor`.

## 2. Module
`core:ai` depends on `core:common` and on `core:imaging` **only for `ImageRef`**. It must not depend on Compose, Hilt-android UI, or any `feature:*`. `feature:editor` depends on `core:ai`. Add the edge to dependency-guard.

## 3. Interfaces (v2 ships segmentation only)
```kotlin
interface SegmentationProvider {
    val availability: StateFlow<Availability>
    suspend fun prepare(image: Bitmap): Result<ImageEmbedding>
    suspend fun segment(embedding: ImageEmbedding, prompt: PointPrompt): Result<SegMask>
}

sealed interface Availability {
    object Ready : Availability
    data class Unavailable(val reason: AppError) : Availability   // models missing, unsupported ABI, OOM
}

class ImageEmbedding internal constructor(
    val imageWidth: Int, val imageHeight: Int,     // original image size the embedding was computed for
    internal val payload: Any,                     // provider-private tensors; opaque to callers
) : Closeable

data class PointPrompt(val points: List<PointF> /* normalized 0..1 */, val labels: List<Boolean> /* true = foreground */) {
    init { require(points.size == labels.size && points.isNotEmpty()) }
}

data class SegMask(val alpha: Bitmap /* ALPHA_8, imageWidth × imageHeight */, val score: Float /* 0..1 */)
```

## 4. Contract
- `prepare` is expensive (hundreds of ms). Callers call it once per image and reuse the embedding for every `segment`. Providers may keep **at most one** embedding's worth of native memory; a second `prepare` releases the first.
- `segment` is cheap (tens of ms) and pure with respect to the embedding: same prompt → same mask.
- Both run on `DispatcherProvider.default`, never Main; both honor cancellation.
- Input `Bitmap` is `ARGB_8888` at working resolution (≤ 4096). Providers downscale internally; the returned mask is always at the **input image size**.
- Errors are `Result` with `AppError`: `Unsupported` (model/ABI), `Io` (model file), `TooLarge` (OOM), `Cancelled` is a `CancellationException`, not an error.
- `ImageEmbedding.close()` releases native memory; the editor closes it when the selection tool closes.

## 5. Fake
`FakeSegmentationProvider`:
- `availability = Ready`
- `prepare` returns after 10ms (configurable) with a payload of the image size only
- `segment` returns a filled circle of radius `0.2 × shortEdge` around the **first foreground point**; each background point subtracts a circle of radius `0.1 × shortEdge`; score 0.9
- `failNext(error)` to test error paths

Bound in `testFixtures`; all UI tests use it. No test may load the real model except `EdgeTamProviderTest`.

## 6. DI
```kotlin
@Module @InstallIn(SingletonComponent::class)
abstract class AiModule { @Binds abstract fun seg(impl: EdgeTamProvider): SegmentationProvider }
```
Tests replace it with `@TestInstallIn` binding the fake.

## 7. Future providers (do not implement)
`InpaintProvider`, `UpscaleProvider` will follow the same shape: `prepare`-free single `suspend fun run(...)`, `Availability`, `Result<...>`. A cloud provider implements the same interface with `availability` reflecting network state.
