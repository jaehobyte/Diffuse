package com.diffuse.core.ai.retouch

import android.graphics.Bitmap
import android.graphics.RectF
import java.nio.ByteBuffer

/**
 * `work/tasks.md` requirement 7. Detections to a candidate defect mask: a pure transform with no
 * model, no bitmap ownership and no restoration in it.
 *
 * The baseline is deliberately the plainest thing that can work: **rasterise the box interior,
 * dilate by nothing, grow by nothing, and do not round it into an ellipse.** Every one of those
 * would be a quality claim, and specs/skin_retouch_validation.md §1.1 has not produced the
 * comparison behind one yet. A bounding box is not a segmentation, so how much normal skin the
 * result sweeps in is measured (`scripts/retouch/detect_eval.py`) rather than assumed small.
 *
 * @param detections in [allowedMask]'s pixel coordinates — the same ROI they were decoded in.
 * @param allowedMask `ALPHA_8`, 0 or 255, at the ROI size: the **most** that may change, with
 * eyes, brows, lips, nostrils, hair and other people already excluded. Required, and read only:
 * a missing allowance is never substituted with "all of the face", so handing in an all-255 mask
 * has to be a deliberate act by the caller (requirement 7).
 * @param roiAlpha the ROI itself, when the source may have transparent pixels. The result excludes
 * them; the ROI's own alpha is not modified.
 * @return `ALPHA_8` at the ROI size, strictly 0 or 255, where 255 means "this pixel may change".
 * An all-zero mask is a normal outcome — and one that runs the restoration model zero times
 * (specs/skin_retouch_pipeline.md §1.1).
 */
internal fun candidateMask(
    detections: List<BlemishDetection>,
    allowedMask: Bitmap,
    roiAlpha: Bitmap? = null,
): Bitmap {
    require(allowedMask.config == Bitmap.Config.ALPHA_8) {
        "allowed mask must be ALPHA_8, was ${allowedMask.config}"
    }
    val width = allowedMask.width
    val height = allowedMask.height
    require(roiAlpha == null || (roiAlpha.width == width && roiAlpha.height == height)) {
        "roi is ${roiAlpha?.width}x${roiAlpha?.height}, allowed mask is ${width}x$height"
    }

    val allowed = allowedMask.readAlpha()
    val opaque = roiAlpha?.readOpacity(width, height)
    val output = ByteArray(width * height)
    for (detection in detections) {
        rasterise(detection.box, output, allowed, opaque, width, height)
    }

    val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
    mask.copyPixelsFromBuffer(ByteBuffer.wrap(output.withRowPadding(width, height, mask.rowBytes)))
    return mask
}

/**
 * `ALPHA_8` rows are padded to the bitmap's own stride, which is **not** `width` unless the width
 * happens to be aligned. `copyPixelsFrom/ToBuffer` move the padding too, so a `width * height`
 * buffer is rejected outright — and a face ROI is any width at all (`301` is a perfectly ordinary
 * one). Everything in this file works in unpadded `width * height` coordinates and converts at
 * the two bitmap boundaries.
 */
private fun ByteArray.withRowPadding(width: Int, height: Int, stride: Int): ByteArray {
    if (stride == width) return this
    val padded = ByteArray(stride * height)
    for (y in 0 until height) copyInto(padded, y * stride, y * width, (y + 1) * width)
    return padded
}

/**
 * One box's interior, intersected with the allowance and the opaque pixels.
 *
 * A pixel belongs to the box when its **centre** is inside it, so the columns run
 * `ceil(left - 0.5) .. ceil(right - 0.5) - 1`: half-open on the right, so two boxes sharing an
 * edge do not both claim the pixel on it. The same rule as `scripts/retouch/detector.py`.
 */
@Suppress("LongParameterList")
private fun rasterise(
    box: RectF,
    into: ByteArray,
    allowed: BooleanArray,
    opaque: BooleanArray?,
    width: Int,
    height: Int,
) {
    val x0 = pixelStart(box.left).coerceIn(0, width)
    val x1 = pixelStart(box.right).coerceIn(0, width)
    val y0 = pixelStart(box.top).coerceIn(0, height)
    val y1 = pixelStart(box.bottom).coerceIn(0, height)
    for (y in y0 until y1) {
        val row = y * width
        for (x in x0 until x1) {
            val index = row + x
            if (allowed[index] && (opaque == null || opaque[index])) into[index] = MASK_ON
        }
    }
}

private val MASK_ON = 255.toByte()

/** One byte per pixel, read back into unpadded `width * height` order (see [withRowPadding]). */
private fun Bitmap.readAlpha(): BooleanArray {
    val buffer = ByteBuffer.allocate(byteCount)
    copyPixelsToBuffer(buffer)
    val bytes = buffer.array()
    val stride = rowBytes
    return BooleanArray(width * height) { index ->
        val y = index / width
        bytes[y * stride + (index - y * width)].toInt() != 0
    }
}

private fun Bitmap.readOpacity(width: Int, height: Int): BooleanArray {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return BooleanArray(pixels.size) { isOpaque(pixels[it]) }
}
