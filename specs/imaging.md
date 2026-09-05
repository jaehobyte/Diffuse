# specs/imaging.md — Image loading pipeline

Owner tasks: T04 (loader), T10 (base decode for render), T19 (import)
Module: `core/imaging/load`

## Purpose
Turn a picked image URI into a bitmap the editor can work with: bounded in size, correctly oriented, alpha intact, and failing with a typed error instead of an OOM crash. Everything downstream — render, history, export — assumes the loader already normalised these things.

## API
```kotlin
class ImageLoader(
    private val resolver: ContentResolver,
    private val dispatchers: DispatcherProvider,
) {
    suspend fun load(uri: Uri): Result<SourceImage>
    suspend fun decode(ref: ImageRef, targetLongEdgePx: Int): Result<Bitmap>
}

data class SourceImage(
    val bitmap: Bitmap,          // already downsampled and oriented
    val widthPx: Int,            // bitmap dimensions, after orientation
    val heightPx: Int,
    val sourceWidthPx: Int,      // as stored, before downsample and orientation
    val sourceHeightPx: Int,
    val mimeType: String,
    val hasAlpha: Boolean,
)
```
`Result` and the sealed `AppError` (`TooLarge`, `Unsupported`, `MissingSource`, `Io`) are the `core:common` types required by specs/architecture.md §9. **No task in tasks.md owns `core:common`; those types must exist before T04 can start.**

`load` is for import (a `content://` URI from the Photo Picker). `decode` is for re-reading a source already copied into app storage, and is what `Renderer` builds its base decode cache on (specs/render.md, Caching).

## Rules
- **Size bound.** `MAX_LONG_EDGE_PX = 4096`. Any image whose long edge exceeds it is downsampled so the long edge is at most 4096. Aspect ratio is preserved; the short edge is rounded to the nearest pixel.
- **Two-step downsample.** Read bounds with `inJustDecodeBounds`, pick the largest power-of-two `inSampleSize` that stays at or above the target, then scale exactly once to the final size. Decoding full size and then scaling is what causes the OOM this spec exists to prevent.
- **Never upscale.** An image already under the bound is returned at its stored size. `decode` with a `targetLongEdgePx` larger than the source returns the source size, matching specs/render.md.
- **EXIF orientation is applied to pixels**, via `androidx.exifinterface`, so nothing downstream carries an orientation flag. `widthPx`/`heightPx` are post-rotation; orientations 5–8 swap them. The returned bitmap has no residual orientation metadata.
- **Alpha is preserved.** Config is `ARGB_8888` always. `hasAlpha` reports whether the source actually carries transparency, so the canvas knows to draw the checkerboard (specs/canvas.md).
- **Threading.** Reading bytes is `Dispatchers.IO`; decode and scale are `Dispatchers.Default`. Both come from the injected `DispatcherProvider` so tests can substitute. Never the main thread (specs/architecture.md §5.4).
- **Cancellation** is checked between the bounds read, the sampled decode and the exact scale. A cancelled load throws `CancellationException` and recycles nothing the caller can see.

## Formats
JPEG, PNG and WebP are required. HEIF/AVIF are decoded when the platform supports them and reported as `Unsupported` when it does not — minSdk is 26 and HEIF decoding only arrives at API 28. Anything else is `Unsupported`. The format is taken from the decoded bounds, never from the file extension.

## Errors
| Condition | Result |
|---|---|
| `OutOfMemoryError` at any decode step | `Failure(TooLarge)` |
| Bounds decode yields no dimensions, or an undecodable/truncated file | `Failure(Unsupported)` |
| URI resolves to nothing, or the file is gone | `Failure(MissingSource)` |
| `IOException` reading the stream | `Failure(Io)` |

`OutOfMemoryError` is caught deliberately here, and only here. It is the one place where the alternative is a crash the user cannot recover from, and the recovery — reporting `TooLarge` — allocates nothing.

## Edge cases
- Zero-byte or truncated file: `Unsupported`, never a partially decoded bitmap.
- An image whose long edge is exactly 4096: returned untouched, no rescale.
- Extremely narrow images (e.g. 20000×10): the long edge still governs; the short edge may round to 1px but never 0.
- A `content://` URI the app has lost permission to read: `MissingSource`, not `Io`.
- Animated GIF/WebP: the first frame is decoded; animation is out of scope for v1.

## Tests
Fixtures are the read-only files in `fixtures/` (specs/testing.md §7).

- `huge_6000x4000.jpg` loads to a long edge of exactly 4096 with aspect ratio preserved, and `sourceWidthPx`/`sourceHeightPx` still report 6000×4000.
- `photo_12mp.jpg` carries EXIF orientation 6; the returned `widthPx`/`heightPx` are swapped relative to the stored dimensions, and a probe pixel confirms the rotation was applied to pixels rather than recorded as metadata.
- `transparent_256.png` returns `hasAlpha = true` and a bitmap with non-opaque pixels.
- `corrupt.jpg` returns `Failure(Unsupported)` and does not throw.
- A missing path returns `Failure(MissingSource)`.
- An injected `OutOfMemoryError` at the decode step returns `Failure(TooLarge)`; the test asserts no error escapes.
- `decode` with a target larger than the source does not upscale.
- Every test runs on the JVM with the injected test dispatcher; no test touches the main thread or the network.
