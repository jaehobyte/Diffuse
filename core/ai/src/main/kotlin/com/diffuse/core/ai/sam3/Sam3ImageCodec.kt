package com.diffuse.core.ai.sam3

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** The bytes actually uploaded, and the size the server will report masks at. */
internal data class EncodedImage(val bytes: ByteArray, val width: Int, val height: Int) {

    override fun equals(other: Any?): Boolean =
        this === other || (other is EncodedImage && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()
}

/**
 * specs/segmentation.md §3. A 2048px mask is more than enough for a hard-edged selection and
 * keeps the upload well inside the server's 20MB cap.
 */
internal object Sam3ImageCodec {

    const val MAX_LONG_EDGE = 2048
    const val MAX_UPLOAD_BYTES = 20 * 1024 * 1024
    private const val QUALITY = 90
    private const val FALLBACK_QUALITY = 75

    /** @return null when even the fallback quality exceeds the upload cap. */
    fun encode(image: Bitmap): EncodedImage? {
        val scaled = downscale(image)
        return try {
            compress(scaled, QUALITY)?.takeIf { it.bytes.size <= MAX_UPLOAD_BYTES }
                ?: compress(scaled, FALLBACK_QUALITY)?.takeIf { it.bytes.size <= MAX_UPLOAD_BYTES }
        } finally {
            if (scaled !== image) scaled.recycle()
        }
    }

    private fun downscale(image: Bitmap): Bitmap {
        val longEdge = maxOf(image.width, image.height)
        if (longEdge <= MAX_LONG_EDGE) return image
        val ratio = MAX_LONG_EDGE.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            image,
            (image.width * ratio).roundToInt().coerceAtLeast(1),
            (image.height * ratio).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun compress(image: Bitmap, quality: Int): EncodedImage? {
        val out = ByteArrayOutputStream()
        if (!image.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
        return EncodedImage(out.toByteArray(), image.width, image.height)
    }
}
