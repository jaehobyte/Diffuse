package com.diffuse.feature.editor.tools.direct

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.speech.FakeSpeechInput
import com.diffuse.core.ai.speech.SpeechState
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.ui.components.PromptMicTestTag
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.feature.editor.tools.prompt.VoicePromptBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode

/** specs/vibe_edit.md §3, §11, §12. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DirectSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val speech = FakeSpeechInput()
    private val submitted = mutableListOf<String>()

    @Test
    fun `the step list is built from the templates, never from the model's prose`() {
        show(DirectState(plan = TWO_STEP_PLAN))

        compose.onNodeWithTag(DirectStepsTestTag).assertExists()
        compose.onNodeWithText("1. 나무 선택").assertExists()
        compose.onNodeWithText("2. 선택 영역 채도 30").assertExists()
        // The prose a model might narrate never becomes a `PlanStep`, so it cannot be rendered.
        compose.onNodeWithText(PROSE).assertDoesNotExist()
    }

    @Test
    fun `an unmasked adjust and the argument-less steps read from their own templates`() {
        show(
            DirectState(
                plan = EditPlan(
                    listOf(
                        PlanStep.Adjust(AdjustKind.Exposure, -0.4f, masked = false),
                        PlanStep.Erase,
                        PlanStep.CutOut,
                    ),
                ),
            ),
        )

        compose.onNodeWithText("1. 노출 -40").assertExists()
        compose.onNodeWithText("2. 선택 영역 지우기").assertExists()
        compose.onNodeWithText("3. 배경 지우기").assertExists()
    }

    @Test
    fun `적용 is disabled until a plan arrives`() {
        show(DirectState())

        compose.onNodeWithText("적용").assertIsNotEnabled()
        compose.onNodeWithTag(DirectStepsTestTag).assertDoesNotExist()
    }

    @Test
    fun `적용 is enabled once a plan is there`() {
        show(DirectState(plan = TWO_STEP_PLAN))

        compose.onNodeWithText("적용").assertIsEnabled()
    }

    @Test
    fun `an unusable plan shows the hint under the bar and nothing else`() {
        show(DirectState(notUnderstood = true))

        compose.onNodeWithTag(DirectHintTestTag).assertExists()
        compose.onNodeWithText("무엇을 할지 모르겠어요. 다르게 말해보세요.").assertExists()
        compose.onNodeWithText("적용").assertIsNotEnabled()
    }

    @Test
    fun `the bar carries the 지시 placeholder`() {
        show(DirectState())

        compose.onNodeWithText("무엇을 바꿀까요? 예: 나무를 더 푸르게").assertExists()
    }

    /** specs/prompt_input.md §3, re-checked in this host. */
    @Test
    fun `a final speech result auto-submits`() {
        grantRecordAudio()
        show(DirectState())
        compose.onNodeWithTag(PromptMicTestTag).performClick()

        speech.emit(SpeechState.Final("나무를 더 푸르게"))
        compose.waitForIdle()

        assertEquals(listOf("나무를 더 푸르게"), submitted)
    }

    private fun show(state: DirectState) {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                DirectSheet(
                    state = state.copy(availability = Availability.Ready),
                    onCancel = {},
                    onApply = {},
                    promptBar = {
                        VoicePromptBar(
                            value = state.request,
                            onValueChange = {},
                            onSubmit = { submitted += it },
                            speech = speech,
                            placeholder = "무엇을 바꿀까요? 예: 나무를 더 푸르게",
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
        const val PROSE = "네, 나무를 푸르게 해드릴게요."
        val TWO_STEP_PLAN = EditPlan(
            listOf(
                PlanStep.Select("나무"),
                PlanStep.Adjust(AdjustKind.Saturation, 0.3f, masked = true),
            ),
        )
    }
}
