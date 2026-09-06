package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
                is GeminiEraseClient.Outcome.Success -> decode(outcome.image, image)
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

    private companion object {
        fun availabilityFor(config: GeminiConfig): Availability =
            if (config.isConfigured) {
                Availability.Ready
            } else {
                Availability.Unavailable(AppError.Invalid("no api key"))
            }
    }
}
