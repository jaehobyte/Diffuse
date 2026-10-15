package com.diffuse.feature.editor.tools.direct

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.diffuse.core.ai.speech.SpeechState
import com.diffuse.feature.editor.EditorUiState
import com.diffuse.feature.editor.EditorViewModel
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.Tool

/**
 * 지시 as the Photo Editor's assistant works: tapping it listens, and the sheet opens on what
 * was heard. What the route needs to know and do about that listening.
 */
class Dictation(
    /** The recogniser is ours right now — from the tap until a result, a failure or a stop. */
    val listening: Boolean,
    /** What has been heard so far, for the canvas caption. */
    val partial: String,
    private val available: Boolean,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
) {
    /**
     * Whether the strip's 지시 tap belongs to listening rather than to the sheet: nothing is
     * open, the planner has its key, and the device can hear. Otherwise the ViewModel's own
     * handling applies — the sheet, or the 서버 설정 sheet for a missing key.
     */
    fun takesTap(state: EditorUiState): Boolean =
        available && state.selectedTool == null && state.direct.enabled

    /** A second tap on 지시 while it listens stops it; nothing is submitted. */
    fun toggle() = if (listening) onStop() else onStart()

    fun stop() = onStop()
}

/** 지시 heard a sentence: its sheet opens now, with the plan already asked for. */
private fun submit(viewModel: EditorViewModel, request: String) {
    if (request.isBlank()) return
    viewModel.onToolClick(Tool.Direct)
    if (viewModel.uiState.value.selectedTool == Tool.Direct) viewModel.direct.submit(request)
}

/**
 * The listening itself. Permission lives here rather than in the ViewModel for the reason
 * `VoicePromptBar` gives: the launcher needs a composition. While this listens, no sheet — and
 * so no prompt bar with its own collector — is composed, so a result has exactly one consumer.
 */
@Composable
fun rememberDictation(viewModel: EditorViewModel): Dictation {
    val context = LocalContext.current
    val speech = viewModel.speech
    val speechState by speech.state.collectAsState()
    // Ours, not the recogniser's: a bar inside a sheet may be listening on its own account.
    var active by rememberSaveable { mutableStateOf(false) }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        granted = allowed
        if (allowed) {
            speech.start()
        } else {
            active = false
            viewModel.direct.showMessage(R.string.prompt_mic_permission)
        }
    }

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var heard = false
        speech.state.collect { state ->
            when (state) {
                is SpeechState.Listening -> heard = true
                is SpeechState.Final -> {
                    speech.reset()
                    active = false
                    submit(viewModel, state.text)
                }
                is SpeechState.Failed -> {
                    speech.reset()
                    active = false
                    viewModel.direct.showMessage(R.string.prompt_voice_failed)
                }
                // Idle after Listening is a stop; Idle before it is the moment before start.
                SpeechState.Idle -> if (heard) active = false
            }
        }
    }

    return Dictation(
        listening = active,
        partial = (speechState as? SpeechState.Listening)?.partial.orEmpty().takeIf { active }.orEmpty(),
        available = speech.isAvailable,
        onStart = {
            active = true
            if (granted) speech.start() else launcher.launch(Manifest.permission.RECORD_AUDIO)
        },
        onStop = {
            speech.stop()
            active = false
        },
    )
}
