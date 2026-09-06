package com.diffuse.core.ai.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.diffuse.core.common.AppError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * specs/prompt_input.md §3. Recognition happens through the OS, so the app writes no network
 * code for it and needs no key.
 */
@Singleton
class AndroidSpeechInput @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechInput {

    private val _state = MutableStateFlow<SpeechState>(SpeechState.Idle)
    override val state: StateFlow<SpeechState> = _state

    override val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    private var recognizer: SpeechRecognizer? = null

    override fun start(localeTag: String) {
        if (!isAvailable) {
            _state.value = SpeechState.Failed(AppError.Unsupported)
            return
        }
        stop()
        _state.value = SpeechState.Listening(partial = "")
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(Listener())
            startListening(intentFor(localeTag))
        }
    }

    override fun stop() {
        recognizer?.run {
            stopListening()
            destroy()
        }
        recognizer = null
    }

    override fun reset() {
        _state.value = SpeechState.Idle
    }

    private fun intentFor(localeTag: String) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

    private inner class Listener : RecognitionListener {

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { _state.value = SpeechState.Listening(it) }
        }

        override fun onResults(results: Bundle?) {
            val text = firstResult(results)
            _state.value = if (text.isNullOrBlank()) {
                SpeechState.Failed(AppError.Invalid("no speech recognized"))
            } else {
                SpeechState.Final(text)
            }
            stop()
        }

        override fun onError(error: Int) {
            _state.value = SpeechState.Failed(mapError(error))
            stop()
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun firstResult(bundle: Bundle?): String? = bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
    }

    private companion object {
        /** specs/prompt_input.md §3. */
        fun mapError(error: Int): AppError = when (error) {
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            -> AppError.Unavailable

            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> AppError.Invalid("no speech recognized")

            else -> AppError.Io(IllegalStateException("SpeechRecognizer error $error"))
        }
    }
}
