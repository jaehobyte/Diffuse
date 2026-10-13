package com.diffuse.feature.editor.tools.direct

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.FakePlanProvider
import com.diffuse.core.ai.GoalSource
import com.diffuse.core.ai.PlanMode
import com.diffuse.core.ai.PlanOutcome
import com.diffuse.core.ai.PlanProposal
import com.diffuse.core.ai.speech.FakeSpeechInput
import com.diffuse.core.ui.ScreenshotOptions
import com.diffuse.core.ui.components.PromptBar
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.core.ui.theme.Tokens
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * specs/vibe_edit.md §12: the sheet goldens, plus §14's recommendation and review states. No render golden — this
 * feature adds no renderer path.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class DirectGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    /** §14: the sheet as it opens — the examples and the 보정 추천 entry. */
    @Test
    fun directSheetOpen() {
        show(DirectState(proposals = ProposalState(frameReady = true)))

        capture("direct_sheet_open")
    }

    @Test
    fun directRecommendLoading() {
        show(DirectState(proposals = ProposalState(ProposalPhase.Loading, PlanMode.Recommend, frameReady = true)))

        capture("direct_recommend_loading")
    }

    /** §14: a recommendation under review — observation, goal, what to keep, steps, checks. */
    @Test
    fun directRecommendReview() {
        show(
            DirectState(
                proposals = ProposalState(
                    ProposalPhase.Ready,
                    PlanMode.Recommend,
                    FakePlanProvider.DEFAULT_PROPOSAL.copy(
                        goals = FakePlanProvider.DEFAULT_PROPOSAL.goals.map { it.copy(source = GoalSource.Default) },
                    ),
                    frameReady = true,
                ),
            ),
        )

        capture("direct_recommend_review")
    }

    @Test
    fun directRecommendNoChange() {
        show(
            DirectState(
                proposals = ProposalState(
                    ProposalPhase.NoChange,
                    PlanMode.Recommend,
                    PlanProposal(PlanOutcome.NoChange, reason = "밝기와 색의 균형이 이미 좋아요"),
                    frameReady = true,
                ),
            ),
        )

        capture("direct_recommend_no_change")
    }

    @Test
    fun directPlanPreview() {
        show(
            DirectState(
                request = REQUEST,
                proposals = ProposalState(
                    ProposalPhase.Ready,
                    PlanMode.Direct,
                    FakePlanProvider.DEFAULT_PROPOSAL,
                    frameReady = true,
                ),
            ),
        )

        capture("direct_plan_preview")
    }

    private fun show(given: DirectState) {
        // The tool only opens with a key, so every golden is the enabled sheet.
        val state = given.copy(availability = Availability.Ready)
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                Box(modifier = Modifier.fillMaxSize().background(Tokens.editBackground)) {
                    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                        DirectSheet(
                            state = state,
                            onCancel = {},
                            onApply = {},
                            promptBar = {
                                PromptBar(
                                    value = state.request,
                                    onValueChange = {},
                                    onSubmit = {},
                                    placeholder = PLACEHOLDER,
                                    onMicClick = {},
                                )
                            },
                            suggestions = {
                                DirectSuggestionArea(
                                    state = state,
                                    speech = FakeSpeechInput(),
                                    onRecommend = {},
                                    onCancel = {},
                                    onHide = {},
                                    onPick = {},
                                )
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.onRoot().captureRoboImage(
            filePath = ScreenshotOptions.goldenPath(name),
            roborazziOptions = ScreenshotOptions.options,
        )
    }

    private companion object {
        const val REQUEST = "나무를 좀 더 푸르게 해줘"
        const val PLACEHOLDER = "무엇을 바꿀까요? 예: 나무를 더 푸르게"
    }
}
