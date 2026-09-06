package com.diffuse.core.ai

import android.graphics.Bitmap
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * specs/auto_enhance.md §8. A fixed plan per style, so a golden asserts the **rendering** of a
 * plan and never the model's taste — what MonetGPT actually chooses is a device question, and
 * testing.md §7 has no fixture that could answer it.
 */
class FakeAutoEnhanceProvider : AutoEnhanceProvider {

    private val _availability = MutableStateFlow<Availability>(Availability.Ready)
    override val availability: StateFlow<Availability> = _availability

    var enhanceCount: Int = 0
        private set

    var lastStyle: AutoStyle? = null
        private set

    var lastImageWidth: Int = 0
        private set

    private var nextError: AppError? = null

    fun failNext(error: AppError) {
        nextError = error
    }

    fun setAvailability(value: Availability) {
        _availability.value = value
    }

    override suspend fun enhance(
        image: Bitmap,
        style: AutoStyle,
    ): Result<AutoEnhanceProvider.Plan> {
        nextError?.let { nextError = null; return Result.Failure(it) }
        enhanceCount++
        lastStyle = style
        lastImageWidth = image.width
        return Result.Success(AutoEnhanceProvider.Plan(PLANS.getValue(style), REASON))
    }

    companion object {
        const val REASON = "Opened the shadows and warmed the whole frame."

        val PLANS: Map<AutoStyle, Map<AdjustKind, Float>> = mapOf(
            AutoStyle.Balanced to mapOf(
                AdjustKind.Exposure to 0.15f,
                AdjustKind.Shadows to 0.3f,
                AdjustKind.Contrast to 0.1f,
            ),
            AutoStyle.Vibrant to mapOf(
                AdjustKind.Saturation to 0.4f,
                AdjustKind.Contrast to 0.3f,
                AdjustKind.Blacks to -0.2f,
            ),
            AutoStyle.Retro to mapOf(
                AdjustKind.Fade to 0.35f,
                AdjustKind.Temperature to 0.25f,
                AdjustKind.Highlights to -0.2f,
            ),
        )
    }
}
