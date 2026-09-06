package com.diffuse.feature.editor.tools.prompt

import android.Manifest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.speech.FakeSpeechInput
import com.diffuse.core.ai.speech.SpeechInput
import com.diffuse.core.ai.speech.SpeechState
import com.diffuse.core.common.AppError
import com.diffuse.core.ui.components.PromptMicTestTag
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode

/** specs/prompt_input.md §5. Everything runs on `FakeSpeechInput`; the OS recogniser is never used. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoicePromptBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val speech = FakeSpeechInput()
    private val submitted = mutableListOf<String>()
    private val messages = mutableListOf<Int>()

    @Test
    fun `tapping the mic with permission starts listening`() {
        grantRecordAudio()
        show()

        compose.onNodeWithTag(PromptMicTestTag).performClick()

        assertEquals(1, speech.starts)
        assertEquals(SpeechInput.KOREAN, speech.localeTag)
    }

    @Test
    fun `partial results stream into the field`() {
        grantRecordAudio()
        show()
        compose.onNodeWithTag(PromptMicTestTag).performClick()

        speech.emit(SpeechState.Listening("사"))
        compose.waitForIdle()
        speech.emit(SpeechState.Listening("사람"))
        compose.waitForIdle()

        assertEquals("사람", currentValue)
        assertEquals(emptyList<String>(), submitted)
    }

    @Test
    fun `a final result auto-submits`() {
        grantRecordAudio()
        show()
        compose.onNodeWithTag(PromptMicTestTag).performClick()

        speech.emit(SpeechState.Final("노란 버스"))
        compose.waitForIdle()

        assertEquals(listOf("노란 버스"), submitted)
        assertEquals("노란 버스", currentValue)
    }

    @Test
    fun `tapping again while listening stops without submitting`() {
        grantRecordAudio()
        show()
        compose.onNodeWithTag(PromptMicTestTag).performClick()
        speech.emit(SpeechState.Listening("사"))
        compose.waitForIdle()

        compose.onNodeWithTag(PromptMicTestTag).performClick()

        assertEquals(1, speech.stops)
        assertEquals(emptyList<String>(), submitted)
    }

    @Test
    fun `a recogniser failure reports a message and never submits`() {
        grantRecordAudio()
        show()
        compose.onNodeWithTag(PromptMicTestTag).performClick()

        speech.emit(SpeechState.Failed(AppError.Unavailable))
        compose.waitForIdle()

        assertTrue(messages.isNotEmpty())
        assertEquals(emptyList<String>(), submitted)
    }

    @Test
    fun `no mic renders when the device has no recogniser`() {
        speech.isAvailable = false
        show()

        compose.onNodeWithTag(PromptMicTestTag).assertDoesNotExist()
    }

    @Test
    fun `without permission the mic asks rather than listening`() {
        show()

        compose.onNodeWithTag(PromptMicTestTag).performClick()

        assertEquals(0, speech.starts)
    }

    // ---- helpers ---------------------------------------------------------

    private var currentValue: String = ""

    private fun grantRecordAudio() {
        Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun show() {
        compose.setContent {
            var value by remember { mutableStateOf("") }
            AppTheme(ThemeMode.Edit) {
                VoicePromptBar(
                    value = value,
                    onValueChange = {
                        value = it
                        currentValue = it
                    },
                    onSubmit = { submitted += it },
                    speech = speech,
                    onMessage = { messages += it },
                )
            }
        }
    }
}
