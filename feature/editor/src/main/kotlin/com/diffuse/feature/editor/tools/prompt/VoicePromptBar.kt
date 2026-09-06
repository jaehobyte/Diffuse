package com.diffuse.feature.editor.tools.prompt

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.diffuse.core.ai.speech.SpeechInput
import com.diffuse.core.ai.speech.SpeechState
import com.diffuse.core.ui.components.PromptBar
import com.diffuse.feature.editor.R
import com.diffuse.core.ui.R as CoreUiR
import kotlinx.coroutines.flow.collectLatest

/**
 * specs/prompt_input.md §3, §4. A [PromptBar] with the device recogniser behind its mic:
 * partials stream into the text, and a final result submits itself.
 *
 * Permission lives here rather than in the ViewModel because it is a UI contract — the launcher
 * needs a composition — and because the ViewModel has no business knowing about Android grants.
 */
@Composable
fun VoicePromptBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    speech: SpeechInput,
    modifier: Modifier = Modifier,
    /** specs/vibe_edit.md §3: forwarded so the 지시 sheet can name its own example. */
    placeholder: String = stringResource(CoreUiR.string.prompt_placeholder),
    enabled: Boolean = true,
    onMessage: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val speechState by speech.state.collectAsState()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // specs/prompt_input.md §3: a second denial hides the mic for the session rather than
    // asking again on every tap.
    var deniedOnce by remember { mutableStateOf(false) }
    var hideMic by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        granted = allowed
        if (allowed) {
            speech.start()
        } else {
            if (deniedOnce) hideMic = true
            deniedOnce = true
            onMessage(R.string.prompt_mic_permission)
        }
    }

    SpeechResults(speech, onValueChange, onSubmit, onMessage)

    val listening = speechState is SpeechState.Listening
    PromptBar(
        value = value,
        onValueChange = onValueChange,
        onSubmit = onSubmit,
        modifier = modifier,
        placeholder = placeholder,
        onMicClick = if (!speech.isAvailable || hideMic) {
            null
        } else {
            {
                when {
                    listening -> speech.stop()
                    granted -> speech.start()
                    else -> launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        },
        listening = listening,
        enabled = enabled,
    )
}

/**
 * specs/prompt_input.md §4. Partials fill the bar in; a final result submits itself, so the
 * user does not have to speak *and* tap send.
 */
@Composable
private fun SpeechResults(
    speech: SpeechInput,
    onValueChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onMessage: (Int) -> Unit,
) {
    LaunchedEffect(speech) {
        speech.state.collectLatest { state ->
            when (state) {
                is SpeechState.Listening ->
                    if (state.partial.isNotEmpty()) onValueChange(state.partial)
                is SpeechState.Final -> {
                    onValueChange(state.text)
                    onSubmit(state.text)
                    speech.reset()
                }
                is SpeechState.Failed -> {
                    onMessage(R.string.prompt_voice_failed)
                    speech.reset()
                }
                SpeechState.Idle -> Unit
            }
        }
    }
}
