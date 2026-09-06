package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.EraseProvider
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
 * specs/generative_erase.md §7. The device calls Gemini itself (ADR-011). Since T60 the five
 * steps between `EraseProvider` and the wire live in `GeminiMaskedEdit`, which 채우기 shares; what
 * is left here is the sentence to send.
 */
@Singleton
class GeminiEraseProvider @Inject internal constructor(
    client: GeminiEraseClient,
    settings: GeminiSettings,
    dispatchers: DispatcherProvider,
) : EraseProvider {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val edit = GeminiMaskedEdit(client, dispatchers)

    override val availability: StateFlow<Availability> = settings.config
        .map(::geminiAvailability)
        .stateIn(scope, SharingStarted.Eagerly, geminiAvailability(settings.config.value))

    override suspend fun erase(image: Bitmap, mask: Bitmap, hint: String?): Result<Bitmap> =
        edit.run(image, mask, eraseInstruction(hint))
}
