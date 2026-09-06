package com.diffuse.core.ai.speech

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** specs/prompt_input.md §5: every voice test runs on this, never on the OS recogniser. */
class FakeSpeechInput(override var isAvailable: Boolean = true) : SpeechInput {

    private val _state = MutableStateFlow<SpeechState>(SpeechState.Idle)
    override val state: StateFlow<SpeechState> = _state

    var starts: Int = 0
        private set
    var stops: Int = 0
        private set
    var localeTag: String? = null
        private set

    override fun start(localeTag: String) {
        starts++
        this.localeTag = localeTag
        _state.value = SpeechState.Listening(partial = "")
    }

    override fun stop() {
        stops++
        _state.value = SpeechState.Idle
    }

    override fun reset() {
        _state.value = SpeechState.Idle
    }

    fun emit(state: SpeechState) {
        _state.value = state
    }
}
