package com.diffuse.core.ai.erase

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.EraseProvider
import com.diffuse.core.ai.sam3.EncodedImage
import com.diffuse.core.ai.sam3.Sam3Client
import com.diffuse.core.ai.sam3.Sam3ImageCodec
import com.diffuse.core.ai.sam3.Sam3SegmentationProvider
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * specs/generative_erase.md §4. The app's entire knowledge of generative editing is one
 * endpoint on a server it already authenticates against; it never holds a Gemini key.
 */
@Singleton
class Sam3EraseProvider @Inject internal constructor(
    private val client: Sam3EraseClient,
    /**
     * §4: availability mirrors segmentation's — same base URL, same token, same `/healthz`.
     * A separate probe would be a second reason for the same answer.
     */
    segmentation: Sam3SegmentationProvider,
) : EraseProvider {

    override val availability: StateFlow<Availability> = segmentation.availability

    override suspend fun erase(image: Bitmap, mask: Bitmap, hint: String?): Result<Bitmap> {
        val sizesMatch = image.width == mask.width && image.height == mask.height
        val encoded = if (sizesMatch) Sam3ImageCodec.encode(image) else null
        return when {
            !sizesMatch -> Result.Failure(AppError.Invalid("mask must be the image's size"))
            encoded == null -> Result.Failure(AppError.TooLarge)
            else -> send(encoded, mask, hint, image)
        }
    }

    private suspend fun send(
        encoded: EncodedImage,
        mask: Bitmap,
        hint: String?,
        original: Bitmap,
    ): Result<Bitmap> {
        // The mask travels at the uploaded image's size; the server pairs them by position.
        val scaled = if (encoded.width == mask.width && encoded.height == mask.height) {
            mask
        } else {
            // Nearest neighbour: the mask is binary and must stay that way.
            Bitmap.createScaledBitmap(mask, encoded.width, encoded.height, false)
        }
        return when (val outcome = client.erase(encoded.bytes, MaskPng.encode(scaled), hint)) {
            is Sam3EraseClient.Outcome.Success -> decode(outcome.png, original)
            is Sam3EraseClient.Outcome.Failure -> Result.Failure(outcome.error)
        }
    }

    /** The result comes back at the uploaded size and is scaled to the caller's (§7). */
    private fun decode(png: ByteArray, like: Bitmap): Result<Bitmap> {
        val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: return Result.Failure(AppError.Unsupported)
        val argb = decoded.copy(Bitmap.Config.ARGB_8888, true) ?: decoded
        if (decoded !== argb) decoded.recycle()
        return Result.Success(
            if (argb.width == like.width && argb.height == like.height) {
                argb
            } else {
                Bitmap.createScaledBitmap(argb, like.width, like.height, true)
                    .also { argb.recycle() }
            },
        )
    }
}
