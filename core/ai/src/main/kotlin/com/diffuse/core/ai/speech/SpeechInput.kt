package com.diffuse.core.ai.speech

import com.diffuse.core.common.AppError
import kotlinx.coroutines.flow.StateFlow

/** specs/prompt_input.md §3. */
sealed interface SpeechState {
    data object Idle : SpeechState

    /** Partial results stream in as the user speaks, so the bar fills in live. */
    data class Listening(val partial: String) : SpeechState

    data class Final(val text: String) : SpeechState

    data class Failed(val error: AppError) : SpeechState
}

/**
 * specs/prompt_input.md §3. Deliberately **not** an `ai_provider.md` provider: it has no
 * `Availability` flow and no suspend entry point, because it is a streaming device service
 * rather than a request/response model.
 */
interface SpeechInput {

    val state: StateFlow<SpeechState>

    /** False when the device has no recogniser at all; the host then renders no mic. */
    val isAvailable: Boolean

    fun start(localeTag: String = KOREAN)

    fun stop()

    /** Returns to [SpeechState.Idle] after a terminal state has been consumed. */
    fun reset()

    companion object {
        const val KOREAN = "ko-KR"
    }
}
