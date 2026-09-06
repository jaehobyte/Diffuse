package com.diffuse.core.ai

import android.graphics.Bitmap
import android.graphics.Color
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * specs/generative_erase.md §4. Fills the masked region with the mean colour of a narrow band
 * just outside it, which is deterministic enough for `generative_erase_render` to be a stable
 * golden.
 */
class FakeEraseProvider : EraseProvider {

    private val _availability = MutableStateFlow<Availability>(Availability.Ready)
    override val availability: StateFlow<Availability> = _availability

    var eraseCount: Int = 0
        private set

    /** What the caller actually handed over. T50 dilates the selection before erasing. */
    var lastMask: Bitmap? = null
        private set

    /** T51: what the caller said was removed, so the model is not asked to draw it back. */
    var lastHint: String? = null
        private set

    /** Which frame the caller chose to show the model — adjusted or not. */
    var lastImage: Bitmap? = null
        private set

    private var nextError: AppError? = null

    fun failNext(error: AppError) {
        nextError = error
    }

    fun setAvailability(value: Availability) {
        _availability.value = value
    }

    override suspend fun erase(image: Bitmap, mask: Bitmap, hint: String?): Result<Bitmap> {
        nextError?.let { nextError = null; return Result.Failure(it) }
        require(image.width == mask.width && image.height == mask.height) {
            "mask must be the image's size"
        }
        eraseCount++
        lastImage = image
        lastMask = mask
        lastHint = hint
        val fill = meanColourOutsideMask(image, mask)
        val out = image.copy(Bitmap.Config.ARGB_8888, true)
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                if (MaskBitmaps.alphaAt(mask, x, y) != MaskBitmaps.CLEAR) out.setPixel(x, y, fill)
            }
        }
        return Result.Success(out)
    }

    private fun meanColourOutsideMask(image: Bitmap, mask: Bitmap): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (MaskBitmaps.alphaAt(mask, x, y) != MaskBitmaps.CLEAR) continue
                if (!nearMask(mask, x, y)) continue
                val pixel = image.getPixel(x, y)
                r += Color.red(pixel)
                g += Color.green(pixel)
                b += Color.blue(pixel)
                n++
            }
        }
        if (n == 0L) return Color.BLACK
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** True when any pixel within [BAND] is inside the mask, i.e. this is a border pixel. */
    private fun nearMask(mask: Bitmap, x: Int, y: Int): Boolean {
        for (dy in -BAND..BAND) {
            for (dx in -BAND..BAND) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until mask.width || ny !in 0 until mask.height) continue
                if (MaskBitmaps.alphaAt(mask, nx, ny) != MaskBitmaps.CLEAR) return true
            }
        }
        return false
    }

    private companion object {
        const val BAND = 4
    }
}
