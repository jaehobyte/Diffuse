package com.diffuse.core.ai

import android.graphics.Bitmap
import android.graphics.Color
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * specs/generative_fill.md §9. Fills the masked region with a flat colour derived from the
 * prompt, so a given prompt always produces the same pixels and a golden can depend on it — and
 * so a test can tell one prompt's result from another's.
 */
class FakeFillProvider : FillProvider {

    private val _availability = MutableStateFlow<Availability>(Availability.Ready)
    override val availability: StateFlow<Availability> = _availability

    var fillCount: Int = 0
        private set

    /** What the caller actually handed over, so a test can assert on the mask and the words. */
    var lastMask: Bitmap? = null
        private set

    var lastPrompt: String? = null
        private set

    private var nextError: AppError? = null

    fun failNext(error: AppError) {
        nextError = error
    }

    fun setAvailability(value: Availability) {
        _availability.value = value
    }

    override suspend fun fill(image: Bitmap, mask: Bitmap, prompt: String): Result<Bitmap> {
        nextError?.let { nextError = null; return Result.Failure(it) }
        require(image.width == mask.width && image.height == mask.height) {
            "mask must be the image's size"
        }
        if (prompt.isBlank()) return Result.Failure(AppError.Invalid("empty prompt"))
        fillCount++
        lastMask = mask
        lastPrompt = prompt
        val fill = colourOf(prompt)
        val out = image.copy(Bitmap.Config.ARGB_8888, true)
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                if (MaskBitmaps.alphaAt(mask, x, y) != MaskBitmaps.CLEAR) out.setPixel(x, y, fill)
            }
        }
        return Result.Success(out)
    }

    /** Deterministic, and never near-white, so the real provider's guard has nothing to catch. */
    private fun colourOf(prompt: String): Int {
        val seed = prompt.trim().hashCode()
        return Color.rgb(
            (seed and CHANNEL_MASK) % CHANNEL_CEILING,
            ((seed shr BYTE) and CHANNEL_MASK) % CHANNEL_CEILING,
            ((seed shr (BYTE * 2)) and CHANNEL_MASK) % CHANNEL_CEILING,
        )
    }

    private companion object {
        const val CHANNEL_MASK = 0xFF
        const val CHANNEL_CEILING = 200
        const val BYTE = 8
    }
}
