package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.diffuse.core.ai.Availability
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * specs/generative_erase.md §7's five steps, shared by 지우기 and 채우기: downscale the pair, paint
 * the masked region white, send one image with the caller's instruction, scale the answer back,
 * and refuse an answer that is still a hole.
 *
 * The two tools differ only in the sentence they send (specs/generative_fill.md §2, §3), so this
 * is where they meet. The still-white guard itself moved to `StillWhite` when 확대 needed it over
 * a border rather than a mask (outpaint.md §5); the threshold is still in one place.
 */
internal class GeminiMaskedEdit(
    private val client: GeminiEraseClient,
    private val dispatchers: DispatcherProvider,
) {

    /** [mask]'s opaque pixels are the region to replace. Its size must equal [image]'s. */
    suspend fun run(image: Bitmap, mask: Bitmap, instruction: String): Result<Bitmap> =
        withContext(dispatchers.io) {
            if (image.width != mask.width || image.height != mask.height) {
                return@withContext Result.Failure(AppError.Invalid("mask must be the image's size"))
            }
            val jpeg = whitenedJpeg(image, mask)
                ?: return@withContext Result.Failure(AppError.TooLarge)
            coroutineContext.ensureActive()

            when (val outcome = client.edit(jpeg, instruction)) {
                is GeminiEraseClient.Outcome.Success -> filled(decode(outcome.image, image), mask)
                is GeminiEraseClient.Outcome.Failure -> Result.Failure(outcome.error)
            }
        }

    /** §7 steps 2–3: downscale the pair, paint the hole, compress what is actually sent. */
    private fun whitenedJpeg(image: Bitmap, mask: Bitmap): ByteArray? {
        val scaled = GeminiImageCodec.downscale(image)
        val scaledMask = GeminiImageCodec.downscaleMask(mask, scaled.width, scaled.height)
        val whitened = WhiteFill.apply(scaled, scaledMask)
        return try {
            GeminiImageCodec.encode(whitened)
        } finally {
            whitened.recycle()
            if (scaled !== image) scaled.recycle()
            if (scaledMask !== mask) scaledMask.recycle()
        }
    }

    /**
     * §7 step 5. The model answers at roughly a megapixel whatever it was given, so the result
     * is scaled back **bilinearly** to the size the caller asked about.
     */
    private fun decode(bytes: ByteArray, like: Bitmap): Result<Bitmap> {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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

    /** T51's guard, in `StillWhite` since T64 so 확대 measures its border at the same threshold. */
    private fun filled(result: Result<Bitmap>, mask: Bitmap): Result<Bitmap> = when (result) {
        is Result.Failure -> result
        is Result.Success -> if (
            StillWhite.fills(result.value) { x, y -> (mask.getPixel(x, y) ushr ALPHA_SHIFT) != 0 }
        ) {
            Result.Failure(AppError.Unavailable)
        } else {
            result
        }
    }

    private companion object {
        const val ALPHA_SHIFT = 24
    }
}

/**
 * specs/generative_erase.md §7: derived from the key with **no probe**. The cheapest useful call
 * to Gemini is a real generation, billed to the user's key, so availability answers "is this
 * configured" and reachability is discovered by the one call the user actually asked for. A
 * deliberate departure from segmentation.md §7, where `/healthz` is free.
 *
 * Shared by every provider on this host, because they share the credential.
 */
internal fun geminiAvailability(config: GeminiConfig): Availability =
    if (config.isConfigured) {
        Availability.Ready
    } else {
        Availability.Unavailable(AppError.Invalid("no api key"))
    }
