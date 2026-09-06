package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.Margins
import com.diffuse.core.ai.OutpaintProvider
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
import kotlin.math.abs

/**
 * specs/outpaint.md §5. 확대 is 지우기 with the white area outside the photograph instead of
 * inside it, so it goes out through the same `GeminiEraseClient.edit` seam behind its own
 * instruction.
 *
 * It does not reuse `GeminiMaskedEdit`: that path scales whatever comes back to the caller's
 * size, and here the answer's aspect is the thing being checked rather than assumed. What is
 * shared is everything that would otherwise drift — the encoder, the transport and
 * `StillWhite`'s threshold.
 */
@Singleton
class GeminiOutpaintProvider @Inject internal constructor(
    private val client: GeminiEraseClient,
    settings: GeminiSettings,
    private val dispatchers: DispatcherProvider,
) : OutpaintProvider {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    override val availability: StateFlow<Availability> = settings.config
        .map(::geminiAvailability)
        .stateIn(scope, SharingStarted.Eagerly, geminiAvailability(settings.config.value))

    override suspend fun outpaint(image: Bitmap, margins: Margins): Result<Bitmap> =
        withContext(dispatchers.io) {
            val scaled = GeminiImageCodec.downscale(image)
            val padded = WhitePad.apply(scaled, margins)
            val interior = WhitePad.interiorOf(scaled.width, scaled.height, margins)
            val jpeg = try {
                GeminiImageCodec.encode(padded)
            } finally {
                if (scaled !== image) scaled.recycle()
            }
            if (jpeg == null) {
                padded.recycle()
                return@withContext Result.Failure(AppError.TooLarge)
            }
            coroutineContext.ensureActive()

            val width = padded.width
            val height = padded.height
            padded.recycle()
            when (val outcome = client.edit(jpeg, OUTPAINT_INSTRUCTION)) {
                is GeminiEraseClient.Outcome.Success ->
                    extended(outcome.image, width, height, interior)
                is GeminiEraseClient.Outcome.Failure -> Result.Failure(outcome.error)
            }
        }

    /**
     * The answer is checked against the canvas that was sent and then resampled onto it, so the
     * returned bitmap is the expanded image at working resolution — which is what
     * `Operation.Outpaint.resultRef` stores (outpaint.md §3) and what the renderer's seam ramp
     * measures itself against (§4).
     */
    private fun extended(
        bytes: ByteArray,
        width: Int,
        height: Int,
        interior: Rect,
    ): Result<Bitmap> {
        val out = decodeOnto(bytes, width, height)
            ?: return Result.Failure(AppError.Unsupported)
        return if (StillWhite.fills(out) { x, y -> !interior.contains(x, y) }) {
            out.recycle()
            Result.Failure(AppError.Unavailable)
        } else {
            Result.Success(out)
        }
    }

    /** null when the answer did not decode at all, or came back at an aspect §5 refuses. */
    private fun decodeOnto(bytes: ByteArray, width: Int, height: Int): Bitmap? {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return if (aspectMatches(decoded, width, height)) {
            onCanvas(decoded, width, height)
        } else {
            decoded.recycle()
            null
        }
    }

    /** Bilinear, as generative_erase.md §7 step 5 is: this is photographic content. */
    private fun onCanvas(decoded: Bitmap, width: Int, height: Int): Bitmap {
        val argb = decoded.copy(Bitmap.Config.ARGB_8888, true) ?: decoded
        if (decoded !== argb) decoded.recycle()
        return if (argb.width == width && argb.height == height) {
            argb
        } else {
            Bitmap.createScaledBitmap(argb, width, height, true).also { argb.recycle() }
        }
    }

    /**
     * §5: the provider maps the answer onto a canvas whose aspect it already computed, so a model
     * that answered at a different ratio would shift the photograph the user did not ask to move.
     * generative_erase.md §11 accepted that risk without a guard because nothing outside its mask
     * could move; here the whole frame is at stake, so it is refused rather than scaled.
     */
    private fun aspectMatches(decoded: Bitmap, width: Int, height: Int): Boolean {
        if (decoded.width <= 0 || decoded.height <= 0) return false
        val asked = width.toFloat() / height
        return abs(decoded.width.toFloat() / decoded.height - asked) / asked <= ASPECT_TOLERANCE
    }

    private companion object {
        /** §5: "differs from the request by more than 2%". */
        const val ASPECT_TOLERANCE = 0.02f
    }
}
