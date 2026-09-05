package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import kotlin.math.hypot
import kotlin.math.roundToInt

/** specs/adjust_detail.md §Math. */
internal object DetailOps {

    private const val SHARPEN_AMOUNT = 1.5f
    private const val VIGNETTE_STOPS = 0.6f
    private const val VIGNETTE_EDGE0 = 0.7f
    private const val VIGNETTE_EDGE1 = 1.0f

    /**
     * The kernel radius scales with resolution so a preview and a full render sharpen by
     * the same visual amount. specs/adjust_detail.md scales it by
     * `fullLongEdge / previewLongEdge`; the op only sees one bitmap, so the reference
     * preview size stands in for the denominator. Capped at 5x5 for cost, as the spec says.
     */
    private const val REFERENCE_PREVIEW_LONG_EDGE = 1080
    private const val MAX_RADIUS = 2

    /** `out = in + amount × (in − blur(in))`, `amount = v × 1.5` */
    fun sharpen(bitmap: Bitmap, value: Float): Bitmap {
        if (value == 0f) return bitmap
        val amount = value * SHARPEN_AMOUNT
        val radius = kernelRadiusFor(bitmap)
        val blurred = boxBlur(bitmap, radius)

        val width = bitmap.width
        val height = bitmap.height
        val original = IntArray(width * height)
        val blur = IntArray(width * height)
        bitmap.getPixels(original, 0, width, 0, 0, width, height)
        blurred.getPixels(blur, 0, width, 0, 0, width, height)

        val output = IntArray(width * height)
        for (index in original.indices) {
            val source = original[index]
            val soft = blur[index]
            output[index] = (source and ALPHA_ONLY) or packRgb(
                unsharp(channelOf(source, RED), channelOf(soft, RED), amount),
                unsharp(channelOf(source, GREEN), channelOf(soft, GREEN), amount),
                unsharp(channelOf(source, BLUE), channelOf(soft, BLUE), amount),
            )
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(output, 0, width, 0, 0, width, height)
        }
    }

    /** `rgb × 2^(−v × 0.6 × smoothstep(0.7, 1.0, d))` */
    fun vignette(bitmap: Bitmap, value: Float): Bitmap {
        if (value == 0f) return bitmap
        val width = bitmap.width
        val height = bitmap.height
        val centerX = (width - 1) / 2f
        val centerY = (height - 1) / 2f
        val cornerDistance = hypot(centerX, centerY)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val argb = pixels[index]
                val distance = hypot(x - centerX, y - centerY) / cornerDistance
                val weight = smoothstep(VIGNETTE_EDGE0, VIGNETTE_EDGE1, distance)
                val gain = exposureGain(-value * VIGNETTE_STOPS * weight)
                pixels[index] = (argb and ALPHA_ONLY) or packRgb(
                    channelOf(argb, RED) * gain,
                    channelOf(argb, GREEN) * gain,
                    channelOf(argb, BLUE) * gain,
                )
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun unsharp(source: Float, blurred: Float, amount: Float): Float =
        source + amount * (source - blurred)

    private fun kernelRadiusFor(bitmap: Bitmap): Int {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        val scaled = (longEdge.toFloat() / REFERENCE_PREVIEW_LONG_EDGE).roundToInt()
        return scaled.coerceIn(1, MAX_RADIUS)
    }

    /** Separable box blur; the two passes are what keep sharpen affordable at full res. */
    private fun boxBlur(bitmap: Bitmap, radius: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val source = IntArray(width * height)
        bitmap.getPixels(source, 0, width, 0, 0, width, height)
        val horizontal = IntArray(width * height)
        blurAxis(source, horizontal, Axis(width, height, radius, horizontal = true))
        val vertical = IntArray(width * height)
        blurAxis(horizontal, vertical, Axis(width, height, radius, horizontal = false))
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(vertical, 0, width, 0, 0, width, height)
        }
    }

    private class Axis(
        val width: Int,
        val height: Int,
        val radius: Int,
        val horizontal: Boolean,
    ) {
        val length: Int get() = if (horizontal) width else height
        val lines: Int get() = if (horizontal) height else width

        fun index(line: Int, position: Int): Int =
            if (horizontal) line * width + position else position * width + line
    }

    private fun blurAxis(source: IntArray, target: IntArray, axis: Axis) {
        for (line in 0 until axis.lines) {
            for (position in 0 until axis.length) {
                val index = axis.index(line, position)
                target[index] = (source[index] and ALPHA_ONLY) or average(source, axis, line, position)
            }
        }
    }

    private fun average(source: IntArray, axis: Axis, line: Int, position: Int): Int {
        var red = 0
        var green = 0
        var blue = 0
        var samples = 0
        for (offset in -axis.radius..axis.radius) {
            val sampled = (position + offset).coerceIn(0, axis.length - 1)
            val argb = source[axis.index(line, sampled)]
            red += (argb shr RED) and MASK
            green += (argb shr GREEN) and MASK
            blue += (argb shr BLUE) and MASK
            samples++
        }
        return ((red / samples) shl RED) or ((green / samples) shl GREEN) or (blue / samples)
    }

    private fun channelOf(argb: Int, shift: Int): Float = ((argb shr shift) and MASK) / CHANNEL_MAX

    private const val RED = 16
    private const val GREEN = 8
    private const val BLUE = 0
    private const val MASK = 0xFF
    private const val ALPHA_ONLY = -0x1000000
}
