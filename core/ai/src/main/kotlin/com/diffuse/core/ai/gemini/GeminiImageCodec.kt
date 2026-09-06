package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * specs/generative_erase.md §7. 1024 rather than segmentation's 2048: `gemini-2.5-flash-image`
 * returns roughly a megapixel whatever it is given, so sending more only pays to have the model
 * downsample it, and we would then upscale from its output anyway.
 */
internal object GeminiImageCodec {

    const val MAX_LONG_EDGE = 1024
    const val MAX_UPLOAD_BYTES = 20 * 1024 * 1024

    private const val QUALITY = 90
    private const val FALLBACK_QUALITY = 75

    /** Bilinear: this is photographic content, and the caller owns the returned bitmap. */
    fun downscale(image: Bitmap): Bitmap {
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

    /**
     * Nearest neighbour, because the mask is binary (segmentation.md §1) and must stay binary —
     * a filtered edge would put half-transparent pixels into `WhiteFill`'s test and blur the
     * hole the model is asked to fill.
     */
    fun downscaleMask(mask: Bitmap, width: Int, height: Int): Bitmap =
        if (mask.width == width && mask.height == height) {
            mask
        } else {
            Bitmap.createScaledBitmap(mask, width, height, false)
        }

    /** @return null when even the fallback quality exceeds the upload cap. */
    fun encode(image: Bitmap): ByteArray? =
        compress(image, QUALITY)?.takeIf { it.size <= MAX_UPLOAD_BYTES }
            ?: compress(image, FALLBACK_QUALITY)?.takeIf { it.size <= MAX_UPLOAD_BYTES }

    private fun compress(image: Bitmap, quality: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        if (!image.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
        return out.toByteArray()
    }
}
