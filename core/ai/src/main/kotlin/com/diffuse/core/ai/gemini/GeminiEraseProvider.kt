package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.EraseProvider
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * specs/generative_erase.md §7. The device calls Gemini itself (ADR-011), so this is the whole
 * distance between `EraseProvider` and the wire: downscale, paint the hole white, send one
 * image, scale the answer back.
 */
@Singleton
class GeminiEraseProvider @Inject internal constructor(
    private val client: GeminiEraseClient,
    settings: GeminiSettings,
    private val dispatchers: DispatcherProvider,
) : EraseProvider {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    /**
     * §7: derived from the key with **no probe**. The cheapest useful call to Gemini is a real
     * generation, billed to the user's key, so availability answers "is this configured" and
     * reachability is discovered by the one call the user actually asked for. A deliberate
     * departure from segmentation.md §7, where `/healthz` is free.
     */
    override val availability: StateFlow<Availability> = settings.config
        .map(::availabilityFor)
        .stateIn(scope, SharingStarted.Eagerly, availabilityFor(settings.config.value))

    override suspend fun erase(image: Bitmap, mask: Bitmap, hint: String?): Result<Bitmap> =
        withContext(dispatchers.io) {
            if (image.width != mask.width || image.height != mask.height) {
                return@withContext Result.Failure(AppError.Invalid("mask must be the image's size"))
            }
            val jpeg = whitenedJpeg(image, mask)
                ?: return@withContext Result.Failure(AppError.TooLarge)
            coroutineContext.ensureActive()

            when (val outcome = client.erase(jpeg, hint)) {
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

    /**
     * T51: the model sometimes answers with the whitened input, unchanged — on the device that
     * showed up as a flat white patch where the object used to be. A patch that is still white is
     * not a result, so it fails and the user can retry rather than committing a hole to history.
     *
     * The one photo where this misfires is the one §4 already calls out as benign — a white wall,
     * snow, an overexposed sky — and there the cost is a retry, not lost work.
     */
    private fun filled(result: Result<Bitmap>, mask: Bitmap): Result<Bitmap> = when (result) {
        is Result.Failure -> result
        is Result.Success ->
            if (stillAHole(result.value, mask)) Result.Failure(AppError.Unavailable) else result
    }

    /** Sampled every [SAMPLE_STEP] pixels: this runs on the main result path, not in a test. */
    private fun stillAHole(result: Bitmap, mask: Bitmap): Boolean {
        val counts = IntArray(2)
        var y = 0
        while (y < result.height) {
            sampleRow(result, mask, y, counts)
            y += SAMPLE_STEP
        }
        val masked = counts[MASKED_COUNT]
        return masked > 0 && counts[WHITE_COUNT].toFloat() / masked >= WHITE_RESULT_THRESHOLD
    }

    private fun sampleRow(result: Bitmap, mask: Bitmap, y: Int, counts: IntArray) {
        var x = 0
        while (x < result.width) {
            val inside = (mask.getPixel(x, y) ushr ALPHA_SHIFT) != 0
            if (inside) counts[MASKED_COUNT]++
            if (inside && isNearWhite(result.getPixel(x, y))) counts[WHITE_COUNT]++
            x += SAMPLE_STEP
        }
    }

    private fun isNearWhite(pixel: Int): Boolean =
        Color.red(pixel) >= NEAR_WHITE_CHANNEL &&
            Color.green(pixel) >= NEAR_WHITE_CHANNEL &&
            Color.blue(pixel) >= NEAR_WHITE_CHANNEL

    private companion object {
        /** Within 2/255 of pure white, which is what a JPEG round trip leaves of #FFFFFF. */
        const val NEAR_WHITE_CHANNEL = 253

        /** Below this the model did fill something, even if it filled it badly. */
        const val WHITE_RESULT_THRESHOLD = 0.9f

        const val SAMPLE_STEP = 4
        const val ALPHA_SHIFT = 24
        const val MASKED_COUNT = 0
        const val WHITE_COUNT = 1

        fun availabilityFor(config: GeminiConfig): Availability =
            if (config.isConfigured) {
                Availability.Ready
            } else {
                Availability.Unavailable(AppError.Invalid("no api key"))
            }
    }
}
