package com.diffuse.core.ai.retouch

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * `work/tasks.md` requirement 5. The grey the ROI is letterboxed with, and — the same value on
 * purpose — the background transparent pixels are composited over before the model sees them.
 *
 * One constant rather than two, so a picture with an alpha hole cannot end up with a background
 * the padding does not match. The caller's alpha itself is never touched
 * (specs/skin_retouch_pipeline.md §2).
 */
internal const val LETTERBOX_PAD_VALUE = 114

/** RGB. The model input is `[1, 3, S, S]` and the ROI's alpha is flattened, never carried. */
private const val CHANNELS = 3

/** 8-bit colour, and the ARGB word it is unpacked from. */
private const val MAX_CHANNEL = 255f
private const val CHANNEL_MASK = 0xFF
private const val CHANNEL_BITS = 8
private const val RED_SHIFT = 16
private const val ALPHA_SHIFT = 24

/** Half a pixel: the offset that puts a sample at a pixel's centre rather than its corner. */
private const val HALF_PIXEL = 0.5f

/**
 * How one ROI was fitted into the model's square input, and therefore how to get back out.
 *
 * [scale] is one number for both axes — that is what makes the fit aspect-preserving — and
 * [padLeft] / [padTop] are the **actual** padding rather than half the total, so an odd remainder
 * inverts exactly instead of landing half a pixel off. The remainder goes to the bottom and the
 * right; the Python reference (`scripts/retouch/detector.py`) states the same rule, and the two
 * are pinned to each other by `core/ai/src/test/resources/retouch/preprocess_parity.json`.
 */
internal data class LetterboxTransform(
    val roiWidth: Int,
    val roiHeight: Int,
    val modelSize: Int,
    val scaledWidth: Int,
    val scaledHeight: Int,
    val scale: Float,
    val padLeft: Int,
    val padTop: Int,
) {
    val padRight: Int get() = modelSize - scaledWidth - padLeft
    val padBottom: Int get() = modelSize - scaledHeight - padTop
}

/**
 * Fit `roiWidth x roiHeight` into `size x size`, centred, aspect preserved.
 *
 * Upscaling is allowed. specs/skin_retouch_pipeline.md §3 puts the smallest offered face at
 * 200px; refusing to scale up would hand the model a 640px canvas that is mostly grey.
 */
internal fun letterboxTransform(roiWidth: Int, roiHeight: Int, size: Int): LetterboxTransform {
    require(roiWidth > 0 && roiHeight > 0) { "roi must be non-empty, was ${roiWidth}x$roiHeight" }
    require(size > 0) { "model size must be positive, was $size" }
    val scale = min(size.toFloat() / roiWidth, size.toFloat() / roiHeight)
    val scaledWidth = floor(roiWidth * scale + HALF_PIXEL).toInt().coerceIn(1, size)
    val scaledHeight = floor(roiHeight * scale + HALF_PIXEL).toInt().coerceIn(1, size)
    return LetterboxTransform(
        roiWidth = roiWidth,
        roiHeight = roiHeight,
        modelSize = size,
        scaledWidth = scaledWidth,
        scaledHeight = scaledHeight,
        scale = scale,
        padLeft = (size - scaledWidth) / 2,
        padTop = (size - scaledHeight) / 2,
    )
}

/**
 * One ROI to the model's `[1, 3, size, size]` float32 NCHW input, RGB, in `0..1`.
 *
 * Written out — read the pixels, composite the alpha, resample, normalise — rather than routed
 * through `Bitmap.createScaledBitmap` and a `Canvas`, because `work/tasks.md` requirement 5 asks
 * Python and Android to agree on the interpolation, and the platform's filter is its own business.
 * The arithmetic here is the same half-pixel-centre bilinear the Python reference performs, so the
 * two agree to float rounding rather than approximately.
 *
 * The ROI is only read. [into] is filled and returned; passing the same buffer back on the next
 * call is what lets a repeated detection avoid reallocating 4.9 MB each time, and it is the
 * caller's job not to share one buffer between two concurrent runs.
 */
internal fun preprocess(
    roi: Bitmap,
    size: Int,
    into: FloatArray = FloatArray(CHANNELS * size * size),
): Pair<FloatArray, LetterboxTransform> {
    require(into.size == CHANNELS * size * size) {
        "buffer is ${into.size}, need ${CHANNELS * size * size}"
    }
    val transform = letterboxTransform(roi.width, roi.height, size)
    val source = IntArray(roi.width * roi.height)
    roi.getPixels(source, 0, roi.width, 0, 0, roi.width, roi.height)
    java.util.Arrays.fill(into, LETTERBOX_PAD_VALUE / MAX_CHANNEL)

    // Sample positions are per row and per column, so the inner loop does not redo the divides.
    val columns = SampleAxis(transform.scaledWidth, roi.width)
    val rows = SampleAxis(transform.scaledHeight, roi.height)
    for (y in 0 until transform.scaledHeight) {
        resampleRow(source, roi.width, into, size, transform, columns, rows, y)
    }
    return into to transform
}

/**
 * Bilinear sample positions with half-pixel centres and clamped edges.
 *
 * `(dst + 0.5) * src / dst - 0.5`, clamped to `0..src-1`. Stated once here and once in the Python
 * reference; a mismatch would move every box by a fraction of a pixel in a way that only ever
 * shows up on odd sizes.
 */
private class SampleAxis(destination: Int, source: Int) {
    val low = IntArray(destination)
    val high = IntArray(destination)
    val weight = FloatArray(destination)

    init {
        val ratio = source.toFloat() / destination
        for (i in 0 until destination) {
            val centre = ((i + HALF_PIXEL) * ratio - HALF_PIXEL).coerceIn(0f, (source - 1).toFloat())
            val floorIndex = floor(centre).toInt().coerceIn(0, source - 1)
            low[i] = floorIndex
            high[i] = min(floorIndex + 1, source - 1)
            weight[i] = centre - floorIndex
        }
    }
}

@Suppress("LongParameterList")
private fun resampleRow(
    source: IntArray,
    sourceWidth: Int,
    into: FloatArray,
    size: Int,
    transform: LetterboxTransform,
    columns: SampleAxis,
    rows: SampleAxis,
    y: Int,
) {
    val plane = size * size
    val topRow = rows.low[y] * sourceWidth
    val bottomRow = rows.high[y] * sourceWidth
    val weightY = rows.weight[y]
    val destinationRow = (transform.padTop + y) * size + transform.padLeft
    for (x in 0 until transform.scaledWidth) {
        val left = columns.low[x]
        val right = columns.high[x]
        val weightX = columns.weight[x]
        val destination = destinationRow + x
        for (channel in 0 until CHANNELS) {
            // ARGB_8888: red occupies bits 16-23, then green, then blue.
            val shift = RED_SHIFT - channel * CHANNEL_BITS
            val top = lerp(flat(source[topRow + left], shift), flat(source[topRow + right], shift), weightX)
            val bottom =
                lerp(flat(source[bottomRow + left], shift), flat(source[bottomRow + right], shift), weightX)
            into[channel * plane + destination] = lerp(top, bottom, weightY) / MAX_CHANNEL
        }
    }
}

/** One channel of an ARGB word, composited over [LETTERBOX_PAD_VALUE] with straight alpha. */
private fun flat(argb: Int, shift: Int): Float {
    val alpha = (argb ushr ALPHA_SHIFT and CHANNEL_MASK) / MAX_CHANNEL
    val channel = (argb ushr shift and CHANNEL_MASK).toFloat()
    return channel * alpha + LETTERBOX_PAD_VALUE * (1f - alpha)
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** First pixel column whose centre is at or past [edge]. Shared by the mask rasteriser. */
internal fun pixelStart(edge: Float): Int = ceil(edge - HALF_PIXEL).toInt()

/** The opaque bit of an ARGB word, for the candidate mask's alpha exclusion. */
internal fun isOpaque(argb: Int): Boolean = (argb ushr ALPHA_SHIFT) != 0
