package com.diffuse.feature.editor.tools.direct

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.CropRatio
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.PromptSuggestionId
import com.diffuse.core.ai.speech.FakeSpeechInput
import com.diffuse.core.ai.speech.SpeechState
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.ui.components.PromptMicTestTag
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.feature.editor.tools.prompt.VoicePromptBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.unit.dp

/** specs/vibe_edit.md §3, §11, §12. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DirectSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val speech = FakeSpeechInput()
    private val submitted = mutableListOf<String>()
    private val picked = mutableListOf<String>()
    private var finds = 0
    private var cancels = 0
    private var hides = 0

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

    /** specs/adjust_hsl.md §8: a 혼합 step needs its band, because there is no chip beside it. */
    @Test
    fun `a colour range step names the band as well as the channel`() {
        show(
            DirectState(
                plan = EditPlan(
                    listOf(
                        PlanStep.Adjust(AdjustKind.HslRedSaturation, 0.4f, masked = false),
                    ),
                ),
            ),
        )

        compose.onNodeWithText("1. 빨강 채도 40").assertExists()
    }

    /** T52: the phrase is English on the wire, so it is English in the template too. */
    @Test
    fun `an English phrase renders through the Korean template`() {
        show(
            DirectState(
                plan = EditPlan(
                    listOf(
                        PlanStep.Select("bus"),
                        PlanStep.Erase,
                    ),
                ),
            ),
        )

        compose.onNodeWithText("1. bus 선택").assertExists()
        compose.onNodeWithText("2. 선택 영역 지우기").assertExists()
    }

    /** specs/vibe_edit.md §4.1: the ratio reads in the 자르기 sheet's own chip characters. */
    @Test
    fun `a crop step names the ratio the 자르기 chips use`() {
        show(
            DirectState(
                plan = EditPlan(
                    listOf(
                        PlanStep.Select("bus"),
                        PlanStep.Crop(CropRatio.Story9x16),
                    ),
                ),
            ),
        )

        compose.onNodeWithText("1. bus 선택").assertExists()
        compose.onNodeWithText("2. 9:16 비율로 자르기").assertExists()
    }

    /** specs/generative_fill.md §8, §11: the prompt is English and the template is Korean. */
    @Test
    fun `a fill step names what will be put there`() {
        show(
            DirectState(
                plan = EditPlan(
                    listOf(
                        PlanStep.Select("chair"),
                        PlanStep.Fill("a red umbrella"),
                    ),
                ),
            ),
        )

        compose.onNodeWithText("1. chair 선택").assertExists()
        compose.onNodeWithText("2. 선택 영역에 a red umbrella 채우기").assertExists()
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

    // ---- §14, the suggestion area ------------------------------------------

    @Test
    fun `the first suggestion state shows the general examples and the explicit request`() {
        showSuggestions(DirectState(suggestions = SuggestionState(frameReady = true)))

        compose.onNodeWithText("이렇게 말해보세요").assertExists()
        compose.onNodeWithText("이 사진에는 이런 방향도 좋아요").assertDoesNotExist()
        listOf("조금 더 밝게", "따뜻한 분위기로", "색감을 자연스럽게").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("현재 사진의 축소본을 Gemini에 보내 추천해요").assertExists()
        compose.onNodeWithTag(DirectSuggestionFindTestTag).assertIsEnabled().performScrollTo().performClick()
        assertEquals(1, finds)
    }

    @Test
    fun `a suggestion request waits for the photo to be ready`() {
        showSuggestions(DirectState(suggestions = SuggestionState(frameReady = false)))

        compose.onNodeWithTag(DirectSuggestionFindTestTag).assertIsNotEnabled()
        compose.onNodeWithText("사진을 준비하는 중").assertExists()
    }

    @Test
    fun `a suggestion in progress says so and can be cancelled`() {
        showSuggestions(
            DirectState(suggestions = SuggestionState(phase = SuggestionPhase.Loading, frameReady = true)),
        )

        compose.onNodeWithText("사진에 맞는 문장을 찾는 중").assertExists()
        compose.onNodeWithTag(DirectSuggestionCancelTestTag).performScrollTo().performClick()
        assertEquals(1, cancels)
        // The bar stays usable: the examples are still there to tap.
        compose.onNodeWithText("조금 더 밝게").assertExists()
    }

    @Test
    fun `picking an example while a suggestion runs keeps the progress and its cancel`() {
        showSuggestions(
            DirectState(
                request = "사진 전체를 조금 더 밝게 해줘",
                requestSource = RequestSource.Suggestion,
                suggestions = SuggestionState(phase = SuggestionPhase.Loading, frameReady = true),
            ),
        )

        compose.onNodeWithText("사진에 맞는 문장을 찾는 중").assertExists()
        compose.onNodeWithTag(DirectSuggestionCancelTestTag).performScrollTo().performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun `tailored suggestions replace the examples under their own title`() {
        showSuggestions(tailored(PromptSuggestionId.LiftShadows, PromptSuggestionId.Cool))

        compose.onNodeWithText("이 사진에는 이런 방향도 좋아요").assertExists()
        compose.onNodeWithText("어두운 부분을 밝게").assertExists()
        compose.onNodeWithText("시원한 분위기로").assertExists()
        compose.onNodeWithText("조금 더 밝게").assertDoesNotExist()
        compose.onNodeWithTag(DirectSuggestionFindTestTag).assertDoesNotExist()
    }

    @Test
    fun `an empty or failed suggestion answer falls back to the examples with a retry`() {
        showSuggestions(
            DirectState(suggestions = SuggestionState(phase = SuggestionPhase.Empty, frameReady = true)),
        )
        compose.onNodeWithText("다른 방향을 직접 말해보세요").assertExists()
        compose.onNodeWithText("다시 찾기").assertExists()
        compose.onNodeWithText("조금 더 밝게").assertExists()
    }

    @Test
    fun `a failed suggestion answer offers a retry and the examples`() {
        showSuggestions(
            DirectState(suggestions = SuggestionState(phase = SuggestionPhase.Failed, frameReady = true)),
        )

        compose.onNodeWithText("다시 찾기").assertExists()
        compose.onNodeWithText("이렇게 말해보세요").assertExists()
    }

    @Test
    fun `each suggestion label fills its own reviewed sentence`() {
        val expected = mapOf(
            PromptSuggestionId.Brighten to "사진 전체를 조금 더 밝게 해줘",
            PromptSuggestionId.LiftShadows to "사진 전체의 어두운 부분을 조금 밝게 해줘",
            PromptSuggestionId.SoftenHighlights to "사진 전체의 너무 밝은 부분을 조금 어둡게 해줘",
            PromptSuggestionId.Warm to "사진 전체의 색감을 조금 따뜻하게 해줘",
            PromptSuggestionId.Cool to "사진 전체의 색감을 조금 차갑게 해줘",
            PromptSuggestionId.NaturalColor to "사진 전체의 과한 채도를 조금 낮춰줘",
            PromptSuggestionId.VividColor to "사진 전체의 채도를 조금 높여줘",
            PromptSuggestionId.FilmWarm to "사진 전체에 따뜻한 필름 스타일을 약하게 적용해줘",
        )
        var state by androidx.compose.runtime.mutableStateOf(tailored(PromptSuggestionId.Brighten))
        showSuggestions { state }

        expected.forEach { (id, sentence) ->
            state = tailored(id)
            compose.waitForIdle()
            // The pill sits in a horizontal row inside the sheet's own scroll, which the default
            // Robolectric screen clips; the click action is the pill's own handler.
            compose.onNodeWithTag(id.wire).performSemanticsAction(SemanticsActions.OnClick)
            assertEquals(id.wire, sentence, picked.last())
        }
        assertEquals(expected.size, picked.size)
    }

    @Test
    fun `a suggestion pill is a 48dp target that says what a tap does`() {
        showSuggestions(DirectState(suggestions = SuggestionState(frameReady = true)))

        compose.onNodeWithContentDescription("조금 더 밝게. 사진 전체가 어둡게 느껴진다면. 누르면 문장이 입력돼요")
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `an unedited suggestion draft keeps the list and says it can be changed`() {
        showSuggestions(
            DirectState(
                request = "사진 전체를 조금 더 밝게 해줘",
                requestSource = RequestSource.Suggestion,
                suggestions = SuggestionState(frameReady = true),
            ),
        )

        compose.onNodeWithTag(DirectSuggestionsTestTag).assertExists()
        compose.onNodeWithText("문장을 바꿔도 좋아요").assertExists()
    }

    @Test
    fun `typed text and 숨기기 both remove the suggestion area`() {
        var state by androidx.compose.runtime.mutableStateOf(
            DirectState(suggestions = SuggestionState(frameReady = true)),
        )
        showSuggestions { state }

        compose.onNodeWithTag(DirectSuggestionHideTestTag).performClick()
        assertEquals(1, hides)
        state = state.copy(suggestions = state.suggestions.copy(hidden = true))
        compose.waitForIdle()
        compose.onNodeWithTag(DirectSuggestionsTestTag).assertDoesNotExist()

        state = DirectState(request = "하늘을", suggestions = SuggestionState(frameReady = true))
        compose.waitForIdle()
        compose.onNodeWithTag(DirectSuggestionsTestTag).assertDoesNotExist()
    }

    @Test
    fun `listening hides the suggestion area and a final result still submits`() {
        grantRecordAudio()
        showSuggestions(DirectState(suggestions = SuggestionState(frameReady = true)))
        compose.onNodeWithTag(DirectSuggestionsTestTag).assertExists()

        compose.onNodeWithTag(PromptMicTestTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(DirectSuggestionsTestTag).assertDoesNotExist()

        speech.emit(SpeechState.Final("나무를 더 푸르게"))
        compose.waitForIdle()
        assertEquals(listOf("나무를 더 푸르게"), submitted)
        assertEquals(emptyList<String>(), picked)
    }

    private fun tailored(vararg ids: PromptSuggestionId) = DirectState(
        suggestions = SuggestionState(phase = SuggestionPhase.Tailored, ids = ids.toList(), frameReady = true),
    )

    private fun showSuggestions(state: DirectState) = showSuggestions { state }

    private fun showSuggestions(state: () -> DirectState) {
        compose.setContent {
            val current = state().copy(availability = Availability.Ready)
            AppTheme(ThemeMode.Edit) {
                DirectSheet(
                    state = current,
                    onCancel = {},
                    onApply = {},
                    promptBar = {
                        VoicePromptBar(
                            value = current.request,
                            onValueChange = {},
                            onSubmit = { submitted += it },
                            speech = speech,
                        )
                    },
                    suggestions = {
                        DirectSuggestionArea(
                            state = current,
                            speech = speech,
                            onFind = { finds++ },
                            onCancel = { cancels++ },
                            onHide = { hides++ },
                            onPick = { picked += it },
                        )
                    },
                )
            }
        }
        compose.waitForIdle()
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
