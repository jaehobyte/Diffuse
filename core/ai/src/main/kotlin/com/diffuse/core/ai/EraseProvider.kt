package com.diffuse.core.ai

import android.graphics.Bitmap
import com.diffuse.core.common.Result
import kotlinx.coroutines.flow.StateFlow

/**
 * specs/generative_erase.md. Removes whatever [mask] covers and fills the hole from the
 * surroundings.
 */
interface EraseProvider {

    val availability: StateFlow<Availability>

    /**
     * [mask] is `ALPHA_8` at [image]'s size; opaque pixels are the region to erase.
     * [hint] is an optional short phrase describing what is being removed.
     *
     * The returned bitmap is `ARGB_8888` at [image]'s size.
     */
    suspend fun erase(image: Bitmap, mask: Bitmap, hint: String?): Result<Bitmap>
}
