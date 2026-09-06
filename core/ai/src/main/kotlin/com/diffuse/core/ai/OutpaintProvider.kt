package com.diffuse.core.ai

import android.graphics.Bitmap
import com.diffuse.core.common.Result
import kotlinx.coroutines.flow.StateFlow

/**
 * specs/ai_provider.md §3. Fractions of the image's width and height added on each side; each
 * `>= 0`.
 *
 * `core:imaging` declares its own `Margins` for `Operation.Outpaint`. They are deliberately two
 * types: `core:ai` reaches into `core:imaging` for `AdjustKind` and nothing else (§2), and this
 * one is a request argument while that one is a coordinate space that a `Crop` re-normalizes
 * against. `feature:editor` converts, exactly as it does for `CropRatio` and `AspectPreset`.
 */
data class Margins(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * specs/outpaint.md §5. Invents what was outside the frame.
 *
 * Returns the **whole expanded image**, not just the new border: the model regenerates everything
 * it is shown, so there is no "just the border" to return. What keeps the photograph's own pixels
 * is the renderer drawing the source back over the interior (§4), not this interface.
 */
interface OutpaintProvider {

    val availability: StateFlow<Availability>

    /**
     * @return an `ARGB_8888` bitmap [margins] larger than the image that was sent, at the working
     * resolution generative_erase.md §7 downscales to.
     */
    suspend fun outpaint(image: Bitmap, margins: Margins): Result<Bitmap>
}
