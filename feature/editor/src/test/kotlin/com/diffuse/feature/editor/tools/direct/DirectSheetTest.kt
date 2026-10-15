package com.diffuse.feature.editor.tools.direct

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
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
import com.diffuse.core.ai.PlanTarget
import com.diffuse.core.ai.Confidence
import com.diffuse.core.ai.FakePlanProvider
import com.diffuse.core.ai.GoalScope
import com.diffuse.core.ai.GoalSource
import com.diffuse.core.ai.Observation
import com.diffuse.core.ai.PlanGoal
import com.diffuse.core.ai.PlanMode
import com.diffuse.core.ai.PlanOutcome
import com.diffuse.core.ai.PlanProposal
import com.diffuse.core.ai.TopazBlock
import com.diffuse.core.ai.WorkflowStep
import com.diffuse.core.ai.topaz.TopazOptions
import com.diffuse.core.ai.topaz.TopazPurpose
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
import org.robolectric.annotation.Config
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
    private val recommends = mutableListOf<Boolean>()
    private var cancels = 0
    private var hides = 0

    @Test
    fun `the step list is built from the templates, never from the model's prose`() {
        show(planned(TWO_STEP_PLAN))

        compose.onNodeWithTag(DirectStepsTestTag).assertExists()
        // §19: a selection is not a row of its own; the item names what it acts on.
        compose.onNodeWithText("나무 새로 선택 (기존 선택과 겹치면 보정이 쌓여요)").assertDoesNotExist()
        compose.onNodeWithText("나무 영역 채도 30").assertExists()
        compose.onNodeWithText("대상: 나무").assertExists()
        // The prose a model might narrate never becomes a `PlanStep`, so it cannot be rendered.
        compose.onNodeWithText(PROSE).assertDoesNotExist()
    }

    @Test
    fun `an unmasked adjust and the argument-less steps read from their own templates`() {
        show(
            planned(
                plan = EditPlan(
                    listOf(
                        PlanStep.Adjust(AdjustKind.Exposure, -0.4f),
                        PlanStep.Select("bus", "n1"),
                        PlanStep.CutOut("n1"),
                    ),
                ),
            ),
        )

        compose.onNodeWithText("노출 -40").assertExists()
        compose.onNodeWithText("대상: 사진 전체").assertExists()
        compose.onNodeWithText("선택 영역 밖 지우기 (투명)").assertExists()
    }

    /** specs/adjust_hsl.md §8: a 혼합 step needs its band, because there is no chip beside it. */
    @Test
    fun `a colour range step names the band as well as the channel`() {
        show(
            planned(
                plan = EditPlan(
                    listOf(
                        PlanStep.Adjust(AdjustKind.HslRedSaturation, 0.4f),
                    ),
                ),
            ),
        )

        compose.onNodeWithText("빨강 채도 40").assertExists()
    }

    /** T52: the phrase is English on the wire, so it is English in the template too. */
    @Test
    fun `an English phrase renders through the Korean template`() {
        show(
            planned(
                plan = EditPlan(
                    listOf(
                        PlanStep.Select("bus", "n1"),
                        PlanStep.Erase("n1"),
                    ),
                ),
            ),
        )

        // §19: the correction itself — "bus 지우기" — not the tool's category.
        compose.onNodeWithText("bus 지우기").assertExists()
        compose.onNodeWithText("대상: bus").assertExists()
    }

    /** specs/vibe_edit.md §4.1: the ratio reads in the 자르기 sheet's own chip characters. */
    @Test
    fun `a crop step names the ratio the 자르기 chips use`() {
        show(
            planned(
                plan = EditPlan(
                    listOf(
                        PlanStep.Crop(CropRatio.Story9x16),
                    ),
                ),
            ),
        )

        compose.onNodeWithText("9:16 비율로 자르기").assertExists()
    }

    /** outpaint.md in a plan: the same chip characters as the crop, the other way round. */
    @Test
    fun `an expand step names the ratio it grows to`() {
        show(planned(plan = EditPlan(listOf(PlanStep.Expand(CropRatio.Story9x16)))))

        compose.onNodeWithText("9:16 비율로 확대").assertExists()
        compose.onNodeWithText("생성 보정", substring = true).assertExists()
    }

    /** specs/generative_fill.md §8, §11: the prompt is English and the template is Korean. */
    @Test
    fun `a fill step names what will be put there`() {
        show(
            planned(
                plan = EditPlan(
                    listOf(
                        PlanStep.Select("chair", "n1"),
                        PlanStep.Fill("a red umbrella", "n1"),
                    ),
                ),
            ),
        )

        compose.onNodeWithText("선택 영역에 a red umbrella 채우기").assertExists()
        compose.onNodeWithText("대상: chair").assertExists()
        compose.onNodeWithText("생성 보정 · 실행 때 유료 생성 호출을 사용해요").assertExists()
    }

    @Test
    fun `선택한 보정 실행 is disabled until a plan arrives`() {
        show(DirectState())

        compose.onNodeWithText("선택한 보정 실행").assertIsNotEnabled()
        compose.onNodeWithTag(DirectStepsTestTag).assertDoesNotExist()
    }

    @Test
    fun `선택한 보정 실행 is enabled once a plan is there`() {
        show(planned(TWO_STEP_PLAN))

        compose.onNodeWithText("선택한 보정 실행").assertIsEnabled()
    }

    @Test
    fun `an unusable plan shows the hint under the bar and nothing else`() {
        show(DirectState(proposals = ProposalState(phase = ProposalPhase.NotUnderstood)))

        compose.onNodeWithTag(DirectHintTestTag).assertExists()
        compose.onNodeWithText("무엇을 할지 모르겠어요. 다르게 말해보세요.").assertExists()
        compose.onNodeWithText("선택한 보정 실행").assertIsNotEnabled()
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

    // ---- §14, the area under the bar and the review ------------------------

    @Test
    fun `the first state shows the examples and the 보정 추천 entry with its notice`() {
        showSuggestions(DirectState(proposals = ProposalState(frameReady = true)))

        compose.onNodeWithText("이렇게 말해보세요").assertExists()
        ExampleSentence.entries.forEach { compose.onNodeWithTag(it.tag).assertExists() }
        compose.onNodeWithText("현재 사진의 축소본과 입력한 요청을 Gemini에 보내 추천해요").assertExists()
        compose.onNodeWithTag(DirectRecommendTestTag).assertIsEnabled().performScrollTo().performClick()
        assertEquals(listOf(false), recommends)
        assertEquals("an example is not sent", emptyList<String>(), submitted)
    }

    @Test
    fun `typed text hides the examples but keeps 보정 추천`() {
        showSuggestions(DirectState(request = "얼굴만 밝게", proposals = ProposalState(frameReady = true)))

        compose.onNodeWithTag(ExampleSentence.Brighten.tag).assertDoesNotExist()
        compose.onNodeWithTag(DirectRecommendTestTag).assertIsEnabled()
    }

    @Test
    fun `보정 추천 waits for the photo to be ready`() {
        showSuggestions(DirectState(proposals = ProposalState(frameReady = false)))

        compose.onNodeWithTag(DirectRecommendTestTag).assertIsNotEnabled()
        compose.onNodeWithText("사진을 준비하는 중").assertExists()
    }

    @Test
    fun `a recommendation in progress says so inline and can be cancelled`() {
        showSuggestions(
            DirectState(
                proposals = ProposalState(phase = ProposalPhase.Loading, mode = PlanMode.Recommend, frameReady = true),
            ),
        )

        compose.onNodeWithText("현재 사진을 살펴보는 중").assertExists()
        compose.onNodeWithContentDescription("보정 추천 받기 취소").assertExists()
        compose.onNodeWithTag(DirectRecommendCancelTestTag).performScrollTo().performClick()
        assertEquals(1, cancels)
        compose.onNodeWithText("선택한 보정 실행").assertIsNotEnabled()
    }

    @Test
    fun `a failed recommendation offers an explicit retry`() {
        showSuggestions(
            DirectState(
                proposals = ProposalState(phase = ProposalPhase.Failed, mode = PlanMode.Recommend, frameReady = true),
            ),
        )

        compose.onNodeWithText("추천을 받지 못했어요").assertExists()
        compose.onNodeWithText("다시 시도").performScrollTo().performClick()
        assertEquals(listOf(false), recommends)
    }

    @Test
    fun `an answered recommendation asks again only through 다시 추천`() {
        showSuggestions(recommended(FakePlanProvider.DEFAULT_PROPOSAL))

        compose.onNodeWithText("다시 추천").performScrollTo().performClick()
        assertEquals(listOf(true), recommends)
    }

    @Test
    fun `observations, goals, what to keep, steps and checks are separate sections`() {
        showSuggestions(recommended(FakePlanProvider.DEFAULT_PROPOSAL))

        compose.onNodeWithTag(DirectObservationsTestTag).assertExists()
        compose.onNodeWithText("나무: 잎의 초록이 주변보다 흐릿해 보여요 (확신 보통)").assertExists()
        compose.onNodeWithTag(DirectGoalsTestTag).assertExists()
        compose.onNodeWithText("[사용자 요청] 나무를 더 푸르게 · 나무").assertExists()
        compose.onNodeWithText("살릴 색: 초록 · 나무").assertExists()
        compose.onNodeWithTag(DirectPreserveTestTag).assertExists()
        compose.onNodeWithText("나무만 바꾸기 (추정)").assertExists()
        compose.onNodeWithTag(DirectStepsTestTag).assertExists()
        compose.onNodeWithText("나무 영역 채도 30").assertExists()
        compose.onNodeWithTag(DirectChecksTestTag).assertExists()
        compose.onNodeWithText("축소본이라 잎의 세부 질감은 확인하기 어려워요").assertExists()
        compose.onNodeWithText("선택한 보정 실행").assertIsEnabled()
    }

    @Test
    fun `a default goal says it is the recommendation's default`() {
        showSuggestions(
            recommended(
                PlanProposal(
                    outcome = PlanOutcome.Ready,
                    goals = listOf(PlanGoal("g1", "원래 분위기를 지키며 얼굴만 조금", GoalSource.Default, GoalScope.Subject)),
                    workflow = listOf(
                        WorkflowStep(PlanStep.Select("face", "n1"), "g1"),
                        WorkflowStep(
                            PlanStep.Adjust(AdjustKind.Exposure, 0.2f, PlanTarget.New("n1")),
                            "g1",
                            effect = "얼굴 형태가 보여요",
                            caution = "노이즈가 늘 수 있어요",
                        ),
                    ),
                ),
            ),
        )

        compose.onNodeWithText("[추천 기본값] 원래 분위기를 지키며 얼굴만 조금").assertExists()
        compose.onNodeWithText("face 영역 노출 20").assertExists()
        compose.onNodeWithText("예상: 얼굴 형태가 보여요").assertExists()
        compose.onNodeWithText("주의: 노이즈가 늘 수 있어요").assertExists()
        compose.onNodeWithText("예상 효과는 실행 전 예측이며 결과를 보장하지 않아요").assertExists()
    }

    @Test
    fun `no_change shows its reason, no steps and leaves 실행 off`() {
        showSuggestions(
            DirectState(
                proposals = ProposalState(
                    phase = ProposalPhase.NoChange,
                    mode = PlanMode.Recommend,
                    proposal = PlanProposal(PlanOutcome.NoChange, reason = "지금도 균형이 좋아요"),
                    frameReady = true,
                ),
            ),
        )

        compose.onNodeWithTag(DirectOutcomeTestTag).assertExists()
        compose.onNodeWithText("지금 사진에는 바꿀 것이 없어요").assertExists()
        compose.onNodeWithText("지금도 균형이 좋아요").assertExists()
        compose.onNodeWithTag(DirectStepsTestTag).assertDoesNotExist()
        compose.onNodeWithText("선택한 보정 실행").assertIsNotEnabled()
    }

    @Test
    fun `needs_clarification and unsupported read as their own outcome`() {
        var state by androidx.compose.runtime.mutableStateOf(
            DirectState(
                proposals = ProposalState(
                    phase = ProposalPhase.NeedsClarification,
                    proposal = PlanProposal(PlanOutcome.NeedsClarification, reason = "누구인지 알 수 없어요"),
                    frameReady = true,
                ),
            ),
        )
        showSuggestions { state }
        compose.onNodeWithText("조금 더 알려주세요").assertExists()
        compose.onNodeWithText("누구인지 알 수 없어요").assertExists()

        state = DirectState(
            proposals = ProposalState(
                phase = ProposalPhase.Unsupported,
                proposal = PlanProposal(PlanOutcome.Unsupported, reason = "정밀한 자르기는 직접 해주세요"),
                frameReady = true,
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithText("지금 도구로는 할 수 없어요").assertExists()
        compose.onNodeWithText("선택한 보정 실행").assertIsNotEnabled()
    }

    /** §14: model text is plain text — markup and links are shown as characters, not rendered. */
    @Test
    fun `model text is shown as plain text`() {
        val markup = "<b>굵게</b> [링크](https://example.com)"
        showSuggestions(
            recommended(
                FakePlanProvider.DEFAULT_PROPOSAL.copy(limitations = listOf(markup)),
            ),
        )

        compose.onNodeWithText(markup).assertExists()
    }

    @Test
    @Config(fontScale = 2f)
    fun `a long review scrolls inside the sheet and 실행 stays reachable at a large font`() {
        val long = "가".repeat(240)
        val proposal = FakePlanProvider.DEFAULT_PROPOSAL.copy(
            observations = List(8) { Observation("o$it", "영역$it", long, Confidence.Low) },
            limitations = List(5) { "제한 $it $long" } + "마지막 확인 사항",
        )
        show(recommended(proposal))

        compose.onNodeWithText("선택한 보정 실행").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("마지막 확인 사항").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the 보정 추천 entry is reachable from the keyboard`() {
        showSuggestions(DirectState(proposals = ProposalState(frameReady = true)))

        compose.onNodeWithTag(DirectRecommendTestTag).assertHasClickAction()
        compose.onNodeWithTag(DirectRecommendTestTag).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(false), recommends)
    }

    @Test
    fun `each example label fills its own reviewed sentence and sends nothing`() {
        showSuggestions(DirectState(proposals = ProposalState(frameReady = true)))

        mapOf(
            ExampleSentence.Brighten to "사진 전체를 조금 더 밝게 해줘",
            ExampleSentence.Warm to "사진 전체의 색감을 조금 따뜻하게 해줘",
            ExampleSentence.NaturalColor to "사진 전체의 과한 채도를 조금 낮춰줘",
        ).forEach { (example, sentence) ->
            // The pill sits in a horizontal row inside the sheet's own scroll, which the default
            // Robolectric screen clips; the click action is the pill's own handler.
            compose.onNodeWithTag(example.tag).performSemanticsAction(SemanticsActions.OnClick)
            assertEquals(sentence, picked.last())
        }
        assertEquals(emptyList<String>(), submitted)
        assertEquals(emptyList<Boolean>(), recommends)
    }

    @Test
    fun `an example pill is a 48dp target that says what a tap does`() {
        showSuggestions(DirectState(proposals = ProposalState(frameReady = true)))

        compose.onNodeWithTag(ExampleSentence.Brighten.tag).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("조금 더 밝게. 사진 전체가 어둡게 느껴진다면. 누르면 문장이 입력돼요").assertExists()
    }

    @Test
    fun `an unedited example keeps the list and says it can be changed`() {
        showSuggestions(
            DirectState(
                request = "사진 전체를 조금 더 밝게 해줘",
                requestSource = RequestSource.Suggestion,
                proposals = ProposalState(frameReady = true),
            ),
        )

        compose.onNodeWithTag(ExampleSentence.Warm.tag).assertExists()
        compose.onNodeWithText("문장을 바꿔도 좋아요").assertExists()
    }

    @Test
    fun `숨기기 hides the examples, not 보정 추천`() {
        var state by androidx.compose.runtime.mutableStateOf(DirectState(proposals = ProposalState(frameReady = true)))
        showSuggestions { state }

        compose.onNodeWithTag(DirectSuggestionHideTestTag).performScrollTo().performClick()
        assertEquals(1, hides)
        state = state.copy(suggestions = state.suggestions.copy(hidden = true))
        compose.waitForIdle()
        compose.onNodeWithTag(ExampleSentence.Brighten.tag).assertDoesNotExist()
        compose.onNodeWithTag(DirectRecommendTestTag).assertExists()
    }

    @Test
    fun `listening hides the suggestion area and a final result still submits`() {
        grantRecordAudio()
        showSuggestions(DirectState(proposals = ProposalState(frameReady = true)))
        compose.onNodeWithTag(DirectSuggestionsTestTag).assertExists()

        compose.onNodeWithTag(PromptMicTestTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(DirectSuggestionsTestTag).assertDoesNotExist()

        speech.emit(SpeechState.Final("나무를 더 푸르게"))
        compose.waitForIdle()
        assertEquals(listOf("나무를 더 푸르게"), submitted)
        assertEquals(emptyList<String>(), picked)
    }

    // ---- §19, items the user turns on or off ---------------------------------

    @Test
    fun `each item is one switch row with its state, label and a 48dp target`() {
        val toggled = mutableListOf<String>()
        show(planned(TWO_ADJUSTS).let { it.copy(chosen = it.chosen - "s2") }, onToggle = { toggled += it })

        compose.onNodeWithTag(directItemTag("s1"))
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "적용함"))
        compose.onNodeWithTag(directItemTag("s2"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "적용 안 함"))
        compose.onNodeWithText("선택한 보정 1/2개").assertExists()

        compose.onNodeWithTag(directItemTag("s2")).performScrollTo().performClick()

        assertEquals(listOf("s2"), toggled)
    }

    @Test
    fun `with every item off the sheet asks for a choice and 실행 is off`() {
        show(planned(TWO_ADJUSTS).copy(chosen = emptySet()))

        compose.onNodeWithText("적용할 보정을 선택하세요").assertExists()
        compose.onNodeWithText("선택한 보정 실행").assertIsNotEnabled()
    }

    @Test
    fun `a blocked Topaz item says how to fix it and blocks only while it is on`() {
        val topaz = EditPlan(
            listOf(
                PlanStep.Adjust(AdjustKind.Exposure, 0.1f),
                PlanStep.Topaz(TopazPurpose.Denoise, TopazOptions(scale = 1)),
            ),
        )
        var state by androidx.compose.runtime.mutableStateOf(
            planned(topaz).copy(blocks = mapOf("s2" to TopazBlock.NoKey)),
        )
        show { state }

        compose.onNodeWithText("Topaz API 키가 없어요. 서버 설정에서 키를 입력하거나 이 항목을 끄세요").assertExists()
        compose.onNodeWithText("Topaz 유료 · 선택한 보정 실행 시 마지막에 자동으로 처리해요").assertExists()
        compose.onNodeWithText("선택한 보정 실행").assertIsNotEnabled()

        state = state.copy(chosen = setOf("s1"))
        compose.waitForIdle()
        compose.onNodeWithText("선택한 보정 실행").assertIsEnabled()
    }

    @Test
    fun `only Topaz on runs through the same primary, and says it reaches Topaz and its credits`() {
        val topaz = EditPlan(
            listOf(
                PlanStep.Adjust(AdjustKind.Exposure, 0.1f),
                PlanStep.Topaz(TopazPurpose.Denoise, TopazOptions(scale = 1)),
            ),
        )
        show(planned(topaz).copy(chosen = setOf("s2")))

        compose.onNodeWithText("선택한 보정 실행").assertIsEnabled()
        compose.onNodeWithTag(DirectRunNoteTestTag).performScrollTo()
            .assertTextContains("Topaz 화질 개선까지 자동으로", substring = true)
            .assertTextContains("크레딧", substring = true)
        compose.onNodeWithText("화질 개선에서 확인").assertDoesNotExist()
    }

    @Test
    fun `in a 지시, an addition the AI proposed on its own says so`() {
        val proposal = FakePlanProvider.ready(TWO_ADJUSTS).let { ready ->
            ready.copy(
                goals = ready.goals + PlanGoal("g2", "대비", GoalSource.Default, GoalScope.Global),
                workflow = listOf(ready.workflow[0], ready.workflow[1].copy(goalId = "g2")),
            )
        }
        show(DirectState(proposals = ProposalState(ProposalPhase.Ready, PlanMode.Direct, proposal)).allChosen())

        compose.onNodeWithText("요청 외에 AI가 추가로 제안한 보정이에요").assertExists()
    }

    @Test
    fun `toggles are locked while a candidate is being built`() {
        show(planned(TWO_ADJUSTS).copy(candidate = CandidateState(CandidatePhase.Running, steps = 2)))

        compose.onNodeWithTag(directItemTag("s1")).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `a stale candidate asks for another run`() {
        show(planned(TWO_ADJUSTS).copy(candidate = CandidateState(CandidatePhase.Stale, chosen = setOf("s1"))))

        compose.onNodeWithText("선택이 바뀌어 다시 실행해야 해요").assertExists()
        compose.onNodeWithText("선택한 보정 다시 실행").assertIsEnabled()
    }

    @Test
    @Config(fontScale = 2f)
    fun `at a large font every toggle can be scrolled to and 실행 stays reachable`() {
        val many = EditPlan(AdjustKind.entries.filter { it.hsl == null }.take(8).map { PlanStep.Adjust(it, 0.1f) })
        show(planned(many))

        compose.onNodeWithText("선택한 보정 실행").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag(directItemTag("s8")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("선택한 보정 실행").assertIsDisplayed()
    }

    private fun DirectState.allChosen() = copy(chosen = items.map { it.id }.toSet())

    private fun recommended(proposal: PlanProposal) = DirectState(
        proposals = ProposalState(ProposalPhase.Ready, PlanMode.Recommend, proposal, frameReady = true),
    ).allChosen()

    private fun planned(plan: EditPlan) = DirectState(
        proposals = ProposalState(ProposalPhase.Ready, PlanMode.Direct, FakePlanProvider.ready(plan)),
    ).allChosen()

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
                            onRecommend = { recommends += it },
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

    private fun show(state: DirectState, onToggle: (String) -> Unit = {}) = show(onToggle) { state }

    private fun show(onToggle: (String) -> Unit = {}, current: () -> DirectState) {
        compose.setContent {
            val state = current()
            AppTheme(ThemeMode.Edit) {
                DirectSheet(
                    state = state.copy(availability = Availability.Ready),
                    onCancel = {},
                    onApply = {},
                    onToggle = onToggle,
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
        val TWO_ADJUSTS = EditPlan(
            listOf(PlanStep.Adjust(AdjustKind.Exposure, 0.1f), PlanStep.Adjust(AdjustKind.Temperature, 0.05f)),
        )
        val TWO_STEP_PLAN = EditPlan(
            listOf(
                PlanStep.Select("나무", "n1"),
                PlanStep.Adjust(AdjustKind.Saturation, 0.3f, PlanTarget.New("n1")),
            ),
        )
    }
}
