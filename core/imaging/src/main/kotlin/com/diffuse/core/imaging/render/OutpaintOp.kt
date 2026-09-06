package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.diffuse.core.imaging.model.Margins
import kotlin.math.min

/**
 * specs/outpaint.md §4 step 2: the expanded canvas, the model's answer scaled to fill it, and the
 * decoded source drawn back over its interior.
 *
 * That order is the whole point of the op (§3): the original pixels survive at whatever resolution
 * they were decoded at, and only the invented border comes from the model's ~1024px answer.
 */
internal object OutpaintOp {

    /**
     * §4: the seam gets an alpha ramp this many pixels wide at working resolution, scaled
     * proportionally at full resolution.
     *
     * A hard edge is right for an erase, whose boundary follows an object's own outline. An
     * outpaint boundary is a perfect rectangle across the whole frame, and the model regenerates
     * the entire image, so its interior differs from the original everywhere — without the ramp
     * that difference appears as four straight lines.
     */
    const val OUTPAINT_BLEND_PX = 8

    private const val ALPHA_SHIFT = 24
    private const val RGB_MASK = 0x00FFFFFF
    private const val OPAQUE = 255

    /**
     * @param source the decoded photograph, at whatever size this render asked for.
     * @param result the model's whole expanded image, at working resolution.
     */
    fun apply(source: Bitmap, result: Bitmap, margins: Margins): Bitmap {
        val width = margins.expandedWidth(source.width)
        val height = margins.expandedHeight(source.height)
        val left = margins.padLeft(source.width)
        val top = margins.padTop(source.height)

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(result, null, Rect(0, 0, width, height), null)
        val ramped = ramped(source, blendPx(width, height, result))
        canvas.drawBitmap(ramped, left.toFloat(), top.toFloat(), null)
        if (ramped !== source) ramped.recycle()
        return out
    }

    /**
     * The stored result is the expanded canvas at working resolution, so the ratio between this
     * render and that file is exactly the factor §4 asks for — no external "what is working
     * resolution" constant, which the renderer has no way to know.
     */
    private fun blendPx(width: Int, height: Int, result: Bitmap): Int {
        val resultLongEdge = maxOf(result.width, result.height)
        if (resultLongEdge == 0) return OUTPAINT_BLEND_PX
        val scaled = OUTPAINT_BLEND_PX.toFloat() * maxOf(width, height) / resultLongEdge
        return scaled.toInt().coerceAtLeast(1)
    }

    /** Alpha ramping 0→1 across [blendPx] inward from every edge of the source. */
    private fun ramped(source: Bitmap, blendPx: Int): Bitmap {
        val width = source.width
        val height = source.height
        // A source narrower than two ramps has no interior to ramp towards; draw it as it is.
        val fits = blendPx > 0 && width > 2 * blendPx && height > 2 * blendPx
        val out = if (fits) source.copy(Bitmap.Config.ARGB_8888, true) else null
        if (out == null) return source
        // `copy` carries the source's opaque flag, and an opaque bitmap's alpha channel is
        // ignored when it is drawn — which is the whole ramp.
        out.setHasAlpha(true)
        val row = IntArray(width)
        for (y in 0 until height) {
            val vertical = edgeDistance(y, height, blendPx)
            // Rows deeper than the ramp only need their two ends touched.
            out.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val factor = min(vertical, edgeDistance(x, width, blendPx))
                if (factor < 1f) row[x] = row[x].withAlphaScaledBy(factor)
            }
            out.setPixels(row, 0, width, 0, y, width, 1)
        }
        return out
    }

    /** 0 at the edge, 1 once [blendPx] pixels inward. */
    private fun edgeDistance(index: Int, size: Int, blendPx: Int): Float {
        val inward = min(index, size - 1 - index)
        return (inward.toFloat() / blendPx).coerceIn(0f, 1f)
    }

    private fun Int.withAlphaScaledBy(factor: Float): Int {
        val alpha = ((this ushr ALPHA_SHIFT).coerceAtMost(OPAQUE) * factor).toInt()
        return (alpha shl ALPHA_SHIFT) or (this and RGB_MASK)
    }
}
