package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.FillProvider
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * specs/generative_fill.md §4. 채우기 is 지우기 pointed the other way: the same whitened image and
 * the same composite, with a sentence a person wrote instead of a constant.
 *
 * Everything between here and the wire is `GeminiMaskedEdit`, shared rather than forked — a
 * second white-fill, a second encoder or a second still-white threshold would be the signal the
 * design drifted (§2).
 */
@Singleton
class GeminiFillProvider @Inject internal constructor(
    client: GeminiEraseClient,
    settings: GeminiSettings,
    dispatchers: DispatcherProvider,
) : FillProvider {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val edit = GeminiMaskedEdit(client, dispatchers)

    override val availability: StateFlow<Availability> = settings.config
        .map(::geminiAvailability)
        .stateIn(scope, SharingStarted.Eagerly, geminiAvailability(settings.config.value))

    /**
     * §4: the blank-prompt guard is the one behind the guard. The sheet disables 적용 on a blank
     * prompt, so a user never meets this; a plan whose `fill_selection` arrived empty does.
     */
    override suspend fun fill(image: Bitmap, mask: Bitmap, prompt: String): Result<Bitmap> =
        if (prompt.isBlank()) {
            Result.Failure(AppError.Invalid("empty prompt"))
        } else {
            edit.run(image, mask, fillInstruction(prompt))
        }
}
