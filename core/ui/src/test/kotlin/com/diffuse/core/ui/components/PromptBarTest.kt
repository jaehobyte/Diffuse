package com.diffuse.core.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** specs/prompt_input.md §2, §5. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PromptBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val submitted = mutableListOf<String>()
    private var micClicks = 0

    @Test
    fun `send is disabled while the field is blank`() {
        show(initial = "")

        compose.onNodeWithTag(PromptSendTestTag).assertIsNotEnabled()
    }

    @Test
    fun `send stays disabled for whitespace alone`() {
        show(initial = "   ")

        compose.onNodeWithTag(PromptSendTestTag).assertIsNotEnabled()
    }

    @Test
    fun `send submits the trimmed value`() {
        show(initial = "  사람  ")

        compose.onNodeWithTag(PromptSendTestTag).assertIsEnabled()
        compose.onNodeWithTag(PromptSendTestTag).performClick()

        assertEquals(listOf("사람"), submitted)
    }

    @Test
    fun `the IME Done action submits too`() {
        show(initial = "")
        compose.onNodeWithTag(PromptFieldTestTag).performTextInput("하늘")

        compose.onNodeWithTag(PromptFieldTestTag).performImeAction()

        assertEquals(listOf("하늘"), submitted)
    }

    @Test
    fun `the placeholder shows only while the field is empty`() {
        show(initial = "")

        compose.onNodeWithText("무엇을 선택할까요? 예: 사람, 하늘").assertExists()
        compose.onNodeWithTag(PromptFieldTestTag).performTextInput("사람")
        compose.onNodeWithText("무엇을 선택할까요? 예: 사람, 하늘").assertDoesNotExist()
    }

    @Test
    fun `no mic renders when the host passes none`() {
        show(initial = "", withMic = false)

        compose.onNodeWithTag(PromptMicTestTag).assertDoesNotExist()
    }

    @Test
    fun `the mic becomes a stop button while listening`() {
        show(initial = "", listening = true)

        compose.onNodeWithContentDescription("그만 듣기").assertExists()
        compose.onNodeWithTag(PromptMicTestTag).performClick()

        assertEquals(1, micClicks)
    }

    @Test
    fun `a disabled bar submits nothing`() {
        show(initial = "사람", enabled = false)

        compose.onNodeWithTag(PromptSendTestTag).assertIsNotEnabled()
        assertEquals(emptyList<String>(), submitted)
    }

    private fun show(
        initial: String,
        withMic: Boolean = true,
        listening: Boolean = false,
        enabled: Boolean = true,
    ) {
        compose.setContent {
            var value by mutableStateOf(initial)
            AppTheme(ThemeMode.Edit) {
                PromptBar(
                    value = value,
                    onValueChange = { value = it },
                    onSubmit = { submitted += it },
                    onMicClick = if (withMic) ({ micClicks++ }) else null,
                    listening = listening,
                    enabled = enabled,
                )
            }
        }
    }
}
