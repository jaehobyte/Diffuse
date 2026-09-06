package com.diffuse.core.ai.sam3

import android.graphics.Bitmap
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.PointPrompt
import com.diffuse.core.ai.SegMask
import com.diffuse.core.ai.SegSession
import com.diffuse.core.ai.SegmentationProvider
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * specs/segmentation.md §8. Holds at most one live session and the bytes that produced it,
 * which is what lets §5's expiry replay happen without the caller ever seeing a `410`.
 */
@Singleton
class Sam3SegmentationProvider @Inject internal constructor(
    private val client: Sam3Client,
    private val settings: Sam3ConfigSource,
) : SegmentationProvider {

    private val _availability = MutableStateFlow(availabilityFor(settings.current()))
    override val availability: StateFlow<Availability> = _availability

    private val lock = Mutex()

    /** The encoded upload is retained so an expired session can be replayed (§5). */
    private var live: LiveSession? = null

    private data class LiveSession(
        var imageId: String,
        val encoded: EncodedImage,
        val session: SegSession,
    )

    override suspend fun refresh() {
        val config = settings.current()
        if (!config.isConfigured) {
            _availability.value = Availability.Unavailable(NOT_CONFIGURED)
            return
        }
        _availability.value = when (client.health()) {
            is Sam3Outcome.Success -> Availability.Ready
            else -> Availability.Unavailable(AppError.Unavailable)
        }
    }

    override suspend fun open(image: Bitmap): Result<SegSession> = lock.withLock {
        closeLocked()

        val encoded = Sam3ImageCodec.encode(image)
            ?: return@withLock failed(AppError.TooLarge)

        when (val outcome = client.upload(encoded.bytes, Sam3Client.JPEG_MEDIA_TYPE)) {
            is Sam3Outcome.Success -> {
                // The session is described in the caller's terms: prompts are normalized, and
                // masks are scaled back to this size, so the upload's own size stays private.
                val session = SegSession(
                    imageId = outcome.value.imageId,
                    imageWidth = image.width,
                    imageHeight = image.height,
                    expiresAtEpochMs = outcome.value.expiresAtEpochMs,
                )
                live = LiveSession(outcome.value.imageId, encoded, session)
                _availability.value = Availability.Ready
                Result.Success(session)
            }
            // Nothing to replay: the session being opened is the one that expired.
            Sam3Outcome.SessionExpired -> failed(AppError.Unavailable)
            is Sam3Outcome.Failure -> failed(outcome.error)
        }
    }

    override suspend fun byPoints(session: SegSession, prompt: PointPrompt): Result<SegMask> =
        prompting(session) { imageId -> client.points(imageId, prompt) }
            .map { masks ->
                // multimask=true returns the model's candidates; api.md orders them by score.
                masks.firstOrNull()?.let { scaled(it, session) }
                    ?: return Result.Failure(AppError.Invalid("no mask returned"))
            }

    override suspend fun byText(session: SegSession, phrase: String): Result<List<SegMask>> {
        if (phrase.isBlank()) return Result.Failure(AppError.Invalid("empty phrase"))
        return prompting(session) { imageId -> client.text(imageId, phrase) }
            .map { masks -> masks.map { scaled(it, session) } }
    }

    override suspend fun close(session: SegSession) = lock.withLock { closeLocked() }

    /**
     * specs/segmentation.md §5. One replay, never a loop: re-upload the retained bytes, then
     * repeat the prompt against the new id.
     */
    private suspend fun prompting(
        session: SegSession,
        call: suspend (imageId: String) -> Sam3Outcome<List<RawMask>>,
    ): Result<List<RawMask>> = lock.withLock {
        val current = live
        if (current == null || current.session != session) {
            return@withLock failed(AppError.Invalid("prompt for a session that is not open"))
        }

        when (val first = call(current.imageId)) {
            is Sam3Outcome.Success -> succeed(first.value)
            is Sam3Outcome.Failure -> failed(first.error)
            Sam3Outcome.SessionExpired -> replay(current, call)
        }
    }

    private suspend fun replay(
        current: LiveSession,
        call: suspend (imageId: String) -> Sam3Outcome<List<RawMask>>,
    ): Result<List<RawMask>> =
        when (val upload = client.upload(current.encoded.bytes, Sam3Client.JPEG_MEDIA_TYPE)) {
            is Sam3Outcome.Success -> {
                current.imageId = upload.value.imageId
                when (val second = call(current.imageId)) {
                    is Sam3Outcome.Success -> succeed(second.value)
                    is Sam3Outcome.Failure -> failed(second.error)
                    // Expired twice in a row is a backend we cannot use, not a retry loop.
                    Sam3Outcome.SessionExpired -> failed(AppError.Unavailable)
                }
            }
            is Sam3Outcome.Failure -> failed(upload.error)
            Sam3Outcome.SessionExpired -> failed(AppError.Unavailable)
        }

    private suspend fun closeLocked() {
        live?.let { client.delete(it.imageId) }
        live = null
    }

    /** Masks arrive at the uploaded size; the caller only ever sees the working size. */
    private fun scaled(mask: RawMask, session: SegSession): SegMask {
        val alpha = mask.alpha
        if (alpha.width == session.imageWidth && alpha.height == session.imageHeight) {
            return SegMask(alpha, mask.score)
        }
        // Nearest neighbour: the mask is binary and must stay that way (ai_provider.md §3).
        val resized = Bitmap.createScaledBitmap(
            alpha,
            session.imageWidth,
            session.imageHeight,
            false,
        )
        alpha.recycle()
        return SegMask(resized, mask.score)
    }

    private fun succeed(masks: List<RawMask>): Result<List<RawMask>> {
        _availability.value = Availability.Ready
        return Result.Success(masks)
    }

    private fun failed(error: AppError): Result<Nothing> {
        // specs/segmentation.md §7: one rejected prompt is not a dead server, but an
        // unreachable or unauthenticated one is.
        if (error is AppError.Unavailable || error is AppError.Unauthorized) {
            _availability.value = Availability.Unavailable(error)
        }
        return Result.Failure(error)
    }

    private inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
        is Result.Success -> Result.Success(transform(value))
        is Result.Failure -> this
    }

    private companion object {
        val NOT_CONFIGURED = AppError.Invalid("SAM 3 base URL is not configured")

        fun availabilityFor(config: Sam3Config): Availability =
            if (config.isConfigured) Availability.Ready else Availability.Unavailable(NOT_CONFIGURED)
    }
}
