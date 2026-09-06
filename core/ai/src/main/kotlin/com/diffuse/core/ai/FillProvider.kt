package com.diffuse.core.ai

import android.graphics.Bitmap
import com.diffuse.core.common.Result
import kotlinx.coroutines.flow.StateFlow

/**
 * specs/generative_fill.md. Replaces whatever [mask] covers with something the **user named**.
 *
 * Separate from [EraseProvider] rather than a second method on it (specs/ai_provider.md §3): a
 * hint and a required prompt are different arguments, and one method taking both would carry an
 * unused parameter at every call site and force each fake to answer for behaviour it does not
 * implement. Behind them is one transport.
 */
interface FillProvider {

    val availability: StateFlow<Availability>

    /**
     * [mask] is `ALPHA_8` at [image]'s size; opaque pixels become [prompt].
     *
     * The returned bitmap is `ARGB_8888` at [image]'s size.
     */
    suspend fun fill(image: Bitmap, mask: Bitmap, prompt: String): Result<Bitmap>
}
