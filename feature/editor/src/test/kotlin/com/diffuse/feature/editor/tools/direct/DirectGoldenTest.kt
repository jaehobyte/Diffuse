package com.diffuse.feature.editor.tools.direct

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.graphics.Bitmap
import com.diffuse.core.ai.Availability
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.ai.FakePlanProvider
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.ai.topaz.TopazPurpose
import com.diffuse.core.ai.topaz.TopazOptions
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.EditPlan
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
import com.diffuse.feature.editor.tools.enhance.TopazResult
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

    /**
     * §19: each item a switch row — an erase named by what it removes, two independent adjusts
     * (one turned off), and a Topaz hand-off with its cost on the row.
     */
    @Test
    fun directPlanToggles() {
        val proposal = FakePlanProvider.ready(
            EditPlan(
                listOf(
                    PlanStep.Select("sign", "n1"),
                    PlanStep.Erase("n1"),
                    PlanStep.Adjust(AdjustKind.Exposure, 0.12f),
                    PlanStep.Adjust(AdjustKind.Temperature, 0.05f),
                    PlanStep.Topaz(TopazPurpose.Denoise, TopazOptions(scale = 1)),
                ),
            ),
        )
        show(
            DirectState(
                request = REQUEST,
                proposals = ProposalState(ProposalPhase.Ready, PlanMode.Direct, proposal, frameReady = true),
                chosen = setOf("s2", "s3", "s5"),
            ),
        )
        compose.onNodeWithTag(directItemTag("s4")).performScrollTo()
        compose.waitForIdle()

        capture("direct_plan_toggles")
    }

    /** §18, §19: an existing selection of unknown meaning on the overlay, waiting for 선택 확인 or 중단. */
    @Test
    fun directCandidateConfirm() {
        show(
            readyState().copy(
                candidate = CandidateState(
                    CandidatePhase.Confirming,
                    check = TargetCheck(null, Bitmap.createBitmap(4, 4, Bitmap.Config.ALPHA_8), "m"),
                ),
            ),
        )

        capture("direct_candidate_confirm")
    }

    /** §19: the whole result with its Topaz step — compare, 고해상도 저장, 새 편집본으로 열기. */
    @Test
    fun directCandidateTopazResult() {
        val proposal = FakePlanProvider.ready(
            EditPlan(
                listOf(
                    PlanStep.Adjust(AdjustKind.Exposure, 0.2f),
                    PlanStep.Topaz(TopazPurpose.Denoise, TopazOptions(scale = 1)),
                ),
            ),
        )
        show(
            DirectState(
                request = REQUEST,
                proposals = ProposalState(ProposalPhase.Ready, PlanMode.Direct, proposal, frameReady = true),
                candidate = CandidateState(
                    CandidatePhase.Review,
                    document = EditDocument("p", ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L),
                    reducible = true,
                    topaz = TopazResult(
                        file = java.io.File("/topaz/output_1.jpg"),
                        width = 4000,
                        height = 3000,
                        mimeType = "image/jpeg",
                        purpose = TopazPurpose.Denoise,
                        options = TopazOptions(scale = 1),
                        after = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).asImageBitmap(),
                        credits = 1,
                    ),
                ),
            ),
        )
        compose.onNodeWithTag(DirectSaveTestTag).performScrollTo()
        compose.waitForIdle()

        capture("direct_candidate_topaz_result")
    }

    /** §18: the candidate under review — compare, 강도 줄이기, the local findings, 적용. */
    @Test
    fun directCandidateReview() {
        show(
            readyState().copy(
                candidate = CandidateState(
                    CandidatePhase.Review,
                    document = EditDocument("p", ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L),
                    reducible = true,
                    findings = setOf(CandidateFinding.Generated),
                ),
            ),
        )

        capture("direct_candidate_review")
    }

    private fun readyState() = DirectState(
        request = REQUEST,
        proposals = ProposalState(
            ProposalPhase.Ready,
            PlanMode.Direct,
            FakePlanProvider.DEFAULT_PROPOSAL,
            frameReady = true,
        ),
    )

    private fun show(given: DirectState) {
        // The tool only opens with a key, so every golden is the enabled sheet; §19: a proposal
        // arrives with every item on and a candidate is built for the choice, as the controller does.
        val chosen = given.copy(availability = Availability.Ready).let {
            if (it.chosen.isEmpty()) it.copy(chosen = it.items.map { item -> item.id }.toSet()) else it
        }
        val state = chosen.copy(
            candidate = chosen.candidate?.let { if (it.chosen.isEmpty()) it.copy(chosen = chosen.localChosen) else it },
        )
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
