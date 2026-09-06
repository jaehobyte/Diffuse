package com.diffuse.core.ai

import android.graphics.Bitmap
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.min

/**
 * specs/ai_provider.md §6. Deterministic stand-in used by every UI test; no test outside
 * `Sam3ClientTest` may touch a socket (CLAUDE.md hard limits).
 */
class FakeSegmentationProvider(
    private val openDelayMs: Long = 10L,
) : SegmentationProvider {

    private val _availability = MutableStateFlow<Availability>(Availability.Ready)
    override val availability: StateFlow<Availability> = _availability

    /** Sessions opened and not yet closed, so tests can assert the provider releases them. */
    val openSessions: MutableList<SegSession> = mutableListOf()

    var openCount: Int = 0
        private set

    private var nextError: AppError? = null

    fun failNext(error: AppError) {
        nextError = error
    }

    fun setAvailability(value: Availability) {
        _availability.value = value
    }

    var refreshCount: Int = 0
        private set

    override suspend fun refresh() {
        refreshCount++
    }

    override suspend fun open(image: Bitmap): Result<SegSession> {
        takeError()?.let { return Result.Failure(it) }
        openCount++
        val session = SegSession(
            imageId = "fake-$openCount",
            imageWidth = image.width,
            imageHeight = image.height,
            expiresAtEpochMs = Long.MAX_VALUE,
        )
        // Registered *before* the delay, the way a real backend creates the session and only
        // then sends the response: a caller that gives up in between has still cost one.
        openSessions += session
        delay(openDelayMs)
        return Result.Success(session)
    }

    override suspend fun byPoints(session: SegSession, prompt: PointPrompt): Result<SegMask> {
        takeError()?.let { return Result.Failure(it) }
        val shortEdge = min(session.imageWidth, session.imageHeight).toFloat()
        val foreground = prompt.points.filterIndexed { i, _ -> prompt.labels[i] }
        if (foreground.isEmpty()) {
            return Result.Success(
                SegMask(MaskBitmaps.empty(session.imageWidth, session.imageHeight), score = 0f),
            )
        }
        val first = foreground.first()
        val mask = MaskBitmaps.circle(
            width = session.imageWidth,
            height = session.imageHeight,
            centreX = first.x * session.imageWidth,
            centreY = first.y * session.imageHeight,
            radius = FOREGROUND_RADIUS * shortEdge,
        )
        prompt.points.filterIndexed { i, _ -> !prompt.labels[i] }.forEach { point ->
            MaskBitmaps.subtractCircle(
                bitmap = mask,
                centreX = point.x * session.imageWidth,
                centreY = point.y * session.imageHeight,
                radius = BACKGROUND_RADIUS * shortEdge,
            )
        }
        return Result.Success(SegMask(mask, score = SCORE))
    }

    override suspend fun byText(session: SegSession, phrase: String): Result<List<SegMask>> {
        takeError()?.let { return Result.Failure(it) }
        if (phrase.isBlank()) return Result.Failure(AppError.Invalid("empty phrase"))
        if (phrase.trim() == ABSENT_PHRASE) return Result.Success(emptyList())

        val shortEdge = min(session.imageWidth, session.imageHeight).toFloat()
        // Centres derived from the phrase so a given word always segments the same way.
        val seed = abs(phrase.trim().hashCode())
        val masks = List(INSTANCE_COUNT) { index ->
            val fraction = ((seed / (index + 1)) % 100) / 100f
            val centreX = (INSTANCE_INSET + fraction * INSTANCE_SPREAD) * session.imageWidth
            val centreY = (INSTANCE_INSET + index * INSTANCE_STEP) * session.imageHeight
            SegMask(
                alpha = MaskBitmaps.circle(
                    width = session.imageWidth,
                    height = session.imageHeight,
                    centreX = centreX,
                    centreY = centreY,
                    radius = TEXT_RADIUS * shortEdge,
                ),
                score = if (index == 0) SCORE else LOWER_SCORE,
            )
        }
        return Result.Success(masks)
    }

    override suspend fun close(session: SegSession) {
        openSessions.remove(session)
    }

    private fun takeError(): AppError? = nextError.also { nextError = null }

    private companion object {
        const val FOREGROUND_RADIUS = 0.2f
        const val BACKGROUND_RADIUS = 0.1f
        const val TEXT_RADIUS = 0.12f
        const val SCORE = 0.9f
        const val LOWER_SCORE = 0.7f
        const val INSTANCE_COUNT = 2
        const val INSTANCE_INSET = 0.25f
        const val INSTANCE_SPREAD = 0.5f
        const val INSTANCE_STEP = 0.3f
        const val ABSENT_PHRASE = "없음"
    }
}
