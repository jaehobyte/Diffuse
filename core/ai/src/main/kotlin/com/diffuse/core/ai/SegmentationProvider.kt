package com.diffuse.core.ai

import android.graphics.Bitmap
import android.graphics.PointF
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import kotlinx.coroutines.flow.StateFlow

/**
 * specs/ai_provider.md §3. Whether a provider can be used at all, as opposed to whether a
 * single call succeeded. A failed prompt does not make a backend unavailable.
 */
sealed interface Availability {
    data object Ready : Availability
    data class Unavailable(val reason: AppError) : Availability
}

/**
 * An open inference session. Opaque: only the provider that issued it may interpret [imageId].
 *
 * [expiresAtEpochMs] is advisory. Expiry is absorbed by the provider (specs/segmentation.md §5),
 * so a caller never has to check it.
 */
data class SegSession(
    val imageId: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val expiresAtEpochMs: Long,
)

/**
 * Click prompt. [points] are normalized 0..1 against the image the session was opened for;
 * a `true` label marks foreground.
 */
data class PointPrompt(
    val points: List<PointF>,
    val labels: List<Boolean>,
) {
    init {
        require(points.isNotEmpty()) { "a point prompt needs at least one point" }
        require(points.size == labels.size) {
            "points and labels must be the same length, were ${points.size} and ${labels.size}"
        }
        require(points.all { it.x in 0f..1f && it.y in 0f..1f }) {
            "points must be normalized to 0..1"
        }
    }
}

/**
 * [alpha] is `ALPHA_8` at the working image's size and strictly binary: every pixel is 0 or 255.
 * specs/selection_tool.md §4 relies on that when merging, and there is no feathering in v2.
 */
data class SegMask(val alpha: Bitmap, val score: Float)

/**
 * specs/ai_provider.md §3. Nothing here says the model is remote; segmentation.md happens to
 * implement it over HTTP.
 */
interface SegmentationProvider {

    val availability: StateFlow<Availability>

    /**
     * Re-probes the backend and updates [availability]. specs/segmentation.md §7: availability is
     * checked when the tool is opened and when the settings change, never polled, so the caller
     * decides when it is worth a round trip.
     */
    suspend fun refresh()

    /** Expensive. Call once per image, then reuse the session for every prompt. */
    suspend fun open(image: Bitmap): Result<SegSession>

    suspend fun byPoints(session: SegSession, prompt: PointPrompt): Result<SegMask>

    /**
     * Concept segmentation: returns every instance matching a short noun phrase, ordered by
     * descending score. An empty list means the concept is absent, which is an answer and not
     * a failure.
     */
    suspend fun byText(session: SegSession, phrase: String): Result<List<SegMask>>

    /** Best effort. Losing the release only costs the backend a TTL, so this never fails. */
    suspend fun close(session: SegSession)
}
