package com.diffuse.core.ai

import android.graphics.Bitmap
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** specs/ai_provider.md §6, specs/style_match.md §9. */
class FakeMatchStyleProvider(
    ready: Boolean = true,
) : MatchStyleProvider {

    private val _availability = MutableStateFlow<Availability>(
        if (ready) Availability.Ready else Availability.Unavailable(AppError.Invalid("no api key")),
    )
    override val availability: StateFlow<Availability> = _availability

    var matchCount: Int = 0
        private set

    /** The reference the last call was given, so a test can say which image was sent. */
    var lastReference: Bitmap? = null
        private set

    private var failure: AppError? = null

    fun failNext(error: AppError) {
        failure = error
    }

    fun setReady(ready: Boolean) {
        _availability.value =
            if (ready) Availability.Ready else Availability.Unavailable(AppError.Invalid("no key"))
    }

    override suspend fun match(
        image: Bitmap,
        reference: Bitmap,
    ): Result<Map<AdjustKind, Float>> {
        matchCount++
        lastReference = reference
        failure?.let {
            failure = null
            return Result.Failure(it)
        }
        return Result.Success(ADJUSTMENTS)
    }

    companion object {
        /** A warm, contrasty answer — enough kinds that a test can see the whole map land. */
        val ADJUSTMENTS = mapOf(
            AdjustKind.Exposure to 0.2f,
            AdjustKind.Contrast to 0.3f,
            AdjustKind.Temperature to 0.25f,
            AdjustKind.Saturation to -0.1f,
        )
    }
}
