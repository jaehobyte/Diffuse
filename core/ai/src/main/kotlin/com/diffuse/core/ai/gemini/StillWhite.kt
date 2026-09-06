package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import android.graphics.Color

/**
 * T51's guard, shared by all three generative providers: the model sometimes answers with the
 * whitened input, unchanged. On the device that showed up as a flat white patch where the object
 * used to be. An area that is still white is not a result, so the call fails and the user can
 * retry rather than committing a hole to history.
 *
 * 지우기 and 채우기 ask about the masked region; 확대 asks about the border it padded
 * (specs/outpaint.md §5). One threshold, in one place — two copies of a number like this is how
 * two paths drift apart, the argument T50 already made about the erase margin.
 *
 * The one photo where this misfires is the one generative_erase.md §4 already calls out as
 * benign — a white wall, snow, an overexposed sky — and there the cost is a retry, not lost work.
 */
internal object StillWhite {

    /** Below this the model did paint something, even if it painted it badly. */
    const val THRESHOLD = 0.9f

    /** Within 2/255 of pure white, which is what a JPEG round trip leaves of #FFFFFF. */
    private const val NEAR_WHITE_CHANNEL = 253

    /** Sampled rather than exhaustive: this runs on the main result path, not in a test. */
    private const val SAMPLE_STEP = 4

    private const val ASKED = 0
    private const val WHITE = 1

    /**
     * @param region true for the pixels the model was asked to paint.
     * @return true when at least [THRESHOLD] of those pixels came back white.
     */
    fun fills(result: Bitmap, region: (Int, Int) -> Boolean): Boolean {
        val counts = IntArray(2)
        var y = 0
        while (y < result.height) {
            sampleRow(result, region, y, counts)
            y += SAMPLE_STEP
        }
        val asked = counts[ASKED]
        return asked > 0 && counts[WHITE].toFloat() / asked >= THRESHOLD
    }

    private fun sampleRow(
        result: Bitmap,
        region: (Int, Int) -> Boolean,
        y: Int,
        counts: IntArray,
    ) {
        var x = 0
        while (x < result.width) {
            if (region(x, y)) {
                counts[ASKED]++
                if (isNearWhite(result.getPixel(x, y))) counts[WHITE]++
            }
            x += SAMPLE_STEP
        }
    }

    private fun isNearWhite(pixel: Int): Boolean =
        Color.red(pixel) >= NEAR_WHITE_CHANNEL &&
            Color.green(pixel) >= NEAR_WHITE_CHANNEL &&
            Color.blue(pixel) >= NEAR_WHITE_CHANNEL
}
