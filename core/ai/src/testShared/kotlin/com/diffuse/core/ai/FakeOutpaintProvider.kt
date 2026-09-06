package com.diffuse.core.ai

import android.graphics.Bitmap
import android.graphics.Color
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * specs/outpaint.md §8. Answers with the expanded canvas the real provider would: the image
 * copied into its interior and a flat, deliberately un-white border, so a test can tell the
 * invented area from the photograph by colour alone.
 */
class FakeOutpaintProvider : OutpaintProvider {

    private val _availability = MutableStateFlow<Availability>(Availability.Ready)
    override val availability: StateFlow<Availability> = _availability

    var outpaintCount: Int = 0
        private set

    var lastMargins: Margins? = null
        private set

    private var nextError: AppError? = null

    fun failNext(error: AppError) {
        nextError = error
    }

    fun setAvailability(value: Availability) {
        _availability.value = value
    }

    override suspend fun outpaint(image: Bitmap, margins: Margins): Result<Bitmap> {
        nextError?.let { nextError = null; return Result.Failure(it) }
        outpaintCount++
        lastMargins = margins

        val left = (margins.left * image.width).roundToInt()
        val top = (margins.top * image.height).roundToInt()
        val width = left + image.width + (margins.right * image.width).roundToInt()
        val height = top + image.height + (margins.bottom * image.height).roundToInt()

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.eraseColor(BORDER)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                out.setPixel(left + x, top + y, image.getPixel(x, y))
            }
        }
        return Result.Success(out)
    }

    companion object {
        /** Never near-white, so the real provider's border guard would have nothing to catch. */
        val BORDER: Int = Color.rgb(30, 90, 150)
    }
}
