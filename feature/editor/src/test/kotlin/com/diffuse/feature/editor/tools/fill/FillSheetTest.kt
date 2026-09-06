package com.diffuse.feature.editor.tools.fill

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.speech.FakeSpeechInput
import com.diffuse.core.ai.speech.SpeechState
import com.diffuse.core.ui.components.PromptMicTestTag
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.feature.editor.tools.prompt.VoicePromptBar
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode

/** specs/generative_fill.md §6, §9. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FillSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val speech = FakeSpeechInput()
    private val submitted = mutableListOf<String>()

    @Test
    fun `적용 is disabled while the prompt is blank`() {
        show(FillState())

        compose.onNodeWithText("적용").assertIsNotEnabled()
    }

    @Test
    fun `whitespace alone is still a blank prompt`() {
        show(FillState(prompt = "   "))

        compose.onNodeWithText("적용").assertIsNotEnabled()
    }

    @Test
    fun `적용 is enabled once there is a noun to fill with`() {
        show(FillState(prompt = PROMPT))

        compose.onNodeWithText("적용").assertIsEnabled()
    }

    /** §6: while the call is in flight the sheet cannot commit a second one. */
    @Test
    fun `적용 is disabled while the call is in flight`() {
        show(FillState(prompt = PROMPT, busy = true))

        compose.onNodeWithText("적용").assertIsNotEnabled()
    }

    @Test
    fun `the bar carries the 채우기 placeholder`() {
        show(FillState())

        compose.onNodeWithText("무엇으로 채울까요? 예: 빨간 우산").assertExists()
    }

    /** §6: submitting from the IME Done key does what 적용 does. */
    @Test
    fun `a final speech result submits the prompt`() {
        grantRecordAudio()
        show(FillState())
        compose.onNodeWithTag(PromptMicTestTag).performClick()

        speech.emit(SpeechState.Final(PROMPT))
        compose.waitForIdle()

        assertEquals(listOf(PROMPT), submitted)
    }

    private fun show(state: FillState) {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                FillSheet(
                    state = state.copy(availability = Availability.Ready),
                    onCancel = {},
                    onApply = {},
                    promptBar = {
                        VoicePromptBar(
                            value = state.prompt,
                            onValueChange = {},
                            onSubmit = { submitted += it },
                            speech = speech,
                            placeholder = PLACEHOLDER,
                            enabled = !state.busy,
                        )
                    },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun grantRecordAudio() {
        Shadows.shadowOf(
            org.robolectric.RuntimeEnvironment.getApplication(),
        ).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
    }

    private companion object {
        const val PROMPT = "빨간 우산"
        const val PLACEHOLDER = "무엇으로 채울까요? 예: 빨간 우산"
    }
}
