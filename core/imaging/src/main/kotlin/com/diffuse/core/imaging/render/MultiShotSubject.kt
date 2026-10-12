package com.diffuse.core.imaging.render

import android.graphics.Bitmap

/**
 * specs/multishot.md §5: the one place a binary selection becomes a soft subject.
 *
 * The subject keeps the photograph's RGB and gets `photoAlpha × feathered(mask)` as its alpha. The
 * feather is a fixed box of [FEATHER_RADIUS_PX] in working pixels, applied once here and baked into
 * the PNG, so the renderer never softens it again. The binary `SegMask` contract and the selection
 * tool's masks are untouched: this reads a mask, it does not change what one is.
 */
object MultiShotSubject {

    const val FEATHER_RADIUS_PX = 2

    private const val ALPHA_SHIFT = 24
    private const val ALPHA_MAX = 255
    private const val RGB_MASK = 0x00FFFFFF
    private const val WINDOW = 2 * FEATHER_RADIUS_PX + 1
    private const val WINDOW_AREA = WINDOW * WINDOW

    /**
     * @param photo the added photograph at working size, EXIF-upright.
     * @param mask `ALPHA_8`, binary, at any size; it is stretched nearest-neighbour to [photo].
     * @return an `ARGB_8888` bitmap the photo's size. Rows are processed one at a time, so the only
     * whole-frame buffer beside the two bitmaps is one byte per pixel.
     */
    fun compose(photo: Bitmap, mask: Bitmap): Bitmap {
        val width = photo.width
        val height = photo.height
        val horizontal = horizontalSums(mask, width, height)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            photo.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                var sum = 0
                for (dy in -FEATHER_RADIUS_PX..FEATHER_RADIUS_PX) {
                    sum += horizontal[(y + dy).coerceIn(0, height - 1) * width + x]
                }
                val coverage = (sum * ALPHA_MAX + WINDOW_AREA / 2) / WINDOW_AREA
                val photoAlpha = row[x] ushr ALPHA_SHIFT
                val alpha = (photoAlpha * coverage + ALPHA_MAX / 2) / ALPHA_MAX
                row[x] = (alpha shl ALPHA_SHIFT) or (row[x] and RGB_MASK)
            }
            out.setPixels(row, 0, width, 0, y, width, 1)
        }
        return out
    }

    /** Mask row [maskY], nearest-neighbour stretched to [set]'s width, as 0/1. */
    private fun stretchRow(mask: Bitmap, maskY: Int, maskRow: IntArray, set: IntArray) {
        mask.getPixels(maskRow, 0, mask.width, 0, maskY, mask.width, 1)
        for (x in set.indices) {
            val maskX = (x.toLong() * mask.width / set.size).toInt()
            set[x] = if (maskRow[maskX] ushr ALPHA_SHIFT != 0) 1 else 0
        }
    }

    /** Per pixel, how many of the [WINDOW] mask samples along its row are set (0..5). */
    private fun horizontalSums(mask: Bitmap, width: Int, height: Int): ByteArray {
        val sums = ByteArray(width * height)
        val maskRow = IntArray(mask.width)
        val set = IntArray(width)
        var cachedRow = -1
        for (y in 0 until height) {
            val maskY = (y.toLong() * mask.height / height).toInt()
            if (maskY != cachedRow) {
                stretchRow(mask, maskY, maskRow, set)
                cachedRow = maskY
            }
            val offset = y * width
            for (x in 0 until width) {
                var sum = 0
                for (dx in -FEATHER_RADIUS_PX..FEATHER_RADIUS_PX) {
                    sum += set[(x + dx).coerceIn(0, width - 1)]
                }
                sums[offset + x] = sum.toByte()
            }
        }
        return sums
    }
}
