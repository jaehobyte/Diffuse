# specs/canvas.md — Editor canvas

Owner tasks: T05 (base), T11 (compare), T15 (crop overlay sits above it)
Module: `feature/editor/canvas`

## Purpose
Display the current preview bitmap and handle viewport gestures (zoom/pan). The canvas never mutates the document.

## Public API
```kotlin
@Composable
fun EditorCanvas(
    bitmap: ImageBitmap?,          // preview from Renderer; null = loading
    viewport: CanvasViewport,      // hoisted
    onViewportChange: (CanvasViewport) -> Unit,
    overlay: (@Composable BoxScope.() -> Unit)? = null,   // crop overlay etc., receives image rect via CompositionLocal
    modifier: Modifier = Modifier,
)

data class CanvasViewport(val scale: Float, val offset: Offset, val fitScale: Float)
```
The canvas exposes `LocalCanvasTransform` (screen ↔ image pixel coordinates) so overlays like crop can map touches without duplicating math.

## Layout
- Fills the space between the top bar and the tool strip.
- Background `editBackground`. Minimum 16dp margin around the fitted image.
- Transparency rendered as an 8dp checkerboard (`canvasCheckerA/B`) behind the bitmap, clipped to the image rect.
- While `bitmap == null`, draw only the background.

## Gestures
| Gesture | Effect |
|---|---|
| Pinch | Scale around the pinch centroid. Clamp to `[0.5 × fitScale, 8 × fitScale]` |
| One- or two-finger drag | Pan. Clamp so at least 25% of the image stays on screen |
| Double-tap | If `scale == fitScale` → 2 × fitScale centered on the tap; else → fit |

- When an `overlay` is present and it consumes the touch (crop handles), the canvas does not pan. Overlay reports consumption via `PointerInputChange.consume()`.
- Fit is recomputed when the canvas size or bitmap size changes; if the user had not zoomed, stay fitted.
- No inertia/fling.

## Rendering
- `drawImage` with `FilterQuality.High` when `scale < fitScale × 2`, `FilterQuality.None` above.
- The Renderer supplies preview-sized bitmaps; the canvas never receives more than ~2× its pixel size.

## Edge cases
- Aspect > 10:1: fit still respects margins; min scale clamp applies.
- Bitmap swap while zoomed (undo): keep viewport if dimensions unchanged; refit if changed (crop).
- Config change: viewport survives via `rememberSaveable`.

## Tests
- `CanvasGestureTest`: zoom clamp both ends, double-tap toggle, pan clamp.
- `CanvasTransformTest`: screen→image conversion at fit, 2×, and with offset.
- Goldens: `canvas_fit`, `canvas_zoomed`, `canvas_transparent`.
