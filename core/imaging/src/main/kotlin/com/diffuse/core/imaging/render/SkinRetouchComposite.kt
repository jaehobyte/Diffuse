package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * specs/skin_retouch_pipeline.md §5: one kind's input to the strength composite. [candidate] is
 * ARGB_8888 and [support] ALPHA_8 (0 or 255), both the base ROI's size; [strength] is 0..1.
 */
class SkinRetouchLayer(
    val candidate: Bitmap,
    val support: Bitmap,
    val strength: Float,
)

/**
 * specs/skin_retouch_pipeline.md §5:
 *
 * ```text
 * R = clamp(B + sum_k(sk * Pk * (Ck - B)), 0, 1)
 * ```
 *
 * per RGB channel in normalised sRGB, with the alpha taken from B. The sum is kept in 8-bit units
 * (the same expression scaled by 255, so no division rounds early), clamped once after the sum
 * and rounded to nearest once at the end. Layers are summed in list order, so the same inputs
 * always give the same pixels, and a zero strength removes exactly its own delta.
 *
 * Inputs are never modified. A size mismatch or a strength outside 0..1 is a programmer error
 * and throws [IllegalArgumentException], as [MaskBlend] does; it is not a runtime condition.
 */
object SkinRetouchComposite {

    private const val ALPHA_SHIFT = 24
    private const val ALPHA_MAX = 255
    private const val CHANNEL_MAX = 255f
    private const val CHANNEL_MASK = 0xFF
    private const val ALPHA_MASK = -0x1000000
    private val RGB_SHIFTS = intArrayOf(16, 8, 0)

    /** R for the ROI. All strengths 0 returns a copy equal to [base]. */
    fun composite(base: Bitmap, layers: List<SkinRetouchLayer>): Bitmap {
        requireMatching(base, layers)
        val width = base.width
        val height = base.height
        val out = IntArray(width * height)
        base.getPixels(out, 0, width, 0, 0, width, height)
        val active = layers.filter { it.strength > 0f }
        if (active.isNotEmpty()) {
            compositePixels(
                base = out,
                candidates = active.map { it.candidate.pixels() },
                supports = active.map { it.support.pixels() },
                strengths = FloatArray(active.size) { active[it].strength },
            ).copyInto(out)
        }
        return bitmapOf(out, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * The binary union (ALPHA_8, 0 or 255) of every layer with strength > 0, intersected with
     * the base's alpha > 0 — the support stored with the result (§6).
     */
    fun support(base: Bitmap, layers: List<SkinRetouchLayer>): Bitmap {
        requireMatching(base, layers)
        val width = base.width
        val height = base.height
        val basePixels = base.pixels()
        val supports = layers.filter { it.strength > 0f }.map { it.support.pixels() }
        val out = IntArray(width * height) { index ->
            val inside = basePixels[index] ushr ALPHA_SHIFT != 0 &&
                supports.any { it[index] ushr ALPHA_SHIFT != 0 }
            if (inside) ALPHA_MAX shl ALPHA_SHIFT else 0
        }
        return bitmapOf(out, width, height, Bitmap.Config.ALPHA_8)
    }

    /**
     * A copy of the full canvas [base] with [roiResult] written over [roi] — the canonical
     * working-size R the editor stores.
     */
    fun pasteIntoCanvas(base: Bitmap, roi: Rect, roiResult: Bitmap): Bitmap {
        requireRoi(base.width, base.height, roi, roiResult)
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(roiResult.pixels(), 0, roi.width(), roi.left, roi.top, roi.width(), roi.height())
        return out
    }

    /** The full-canvas ALPHA_8 support: [roiSupport] at [roi], empty everywhere else. */
    fun canvasSupport(width: Int, height: Int, roi: Rect, roiSupport: Bitmap): Bitmap {
        requireRoi(width, height, roi, roiSupport)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        out.setPixels(roiSupport.pixels(), 0, roi.width(), roi.left, roi.top, roi.width(), roi.height())
        return out
    }

    /** The pure core over packed ARGB ints; a support counts as set wherever its alpha is not 0. */
    internal fun compositePixels(
        base: IntArray,
        candidates: List<IntArray>,
        supports: List<IntArray>,
        strengths: FloatArray,
    ): IntArray = IntArray(base.size) { index ->
        val b = base[index]
        var pixel = b and ALPHA_MASK
        for (shift in RGB_SHIFTS) {
            val bc = (b ushr shift) and CHANNEL_MASK
            var sum = bc.toFloat()
            for (k in candidates.indices) {
                if (supports[k][index] ushr ALPHA_SHIFT != 0) {
                    val cc = (candidates[k][index] ushr shift) and CHANNEL_MASK
                    sum += strengths[k] * (cc - bc)
                }
            }
            pixel = pixel or (sum.coerceIn(0f, CHANNEL_MAX).roundToInt() shl shift)
        }
        pixel
    }

    private fun requireMatching(base: Bitmap, layers: List<SkinRetouchLayer>) {
        layers.forEach { layer ->
            require(layer.strength.isFinite() && layer.strength in 0f..1f) {
                "strength ${layer.strength} is outside 0..1"
            }
            require(layer.candidate.sameSize(base) && layer.support.sameSize(base)) {
                "every candidate and support must be the base's size"
            }
        }
    }

    private fun requireRoi(width: Int, height: Int, roi: Rect, roiBitmap: Bitmap) {
        require(roi.left >= 0 && roi.top >= 0 && roi.right <= width && roi.bottom <= height) {
            "the ROI $roi is outside the ${width}x$height canvas"
        }
        require(roi.width() == roiBitmap.width && roi.height() == roiBitmap.height) {
            "the ROI bitmap must be the ROI's size"
        }
    }

    private fun Bitmap.sameSize(other: Bitmap) = width == other.width && height == other.height

    private fun Bitmap.pixels(): IntArray =
        IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private fun bitmapOf(pixels: IntArray, width: Int, height: Int, config: Bitmap.Config): Bitmap =
        Bitmap.createBitmap(width, height, config).apply { setPixels(pixels, 0, width, 0, 0, width, height) }
}
