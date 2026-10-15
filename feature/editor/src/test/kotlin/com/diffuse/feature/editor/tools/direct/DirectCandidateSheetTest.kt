package com.diffuse.feature.editor.tools.direct

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.FakePlanProvider
import com.diffuse.core.ai.PlanMode
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.PlanTarget
import com.diffuse.core.ai.topaz.TopazFailure
import com.diffuse.core.ai.topaz.TopazOptions
import com.diffuse.core.ai.topaz.TopazPurpose
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.feature.editor.tools.enhance.TopazPhase
import com.diffuse.feature.editor.tools.enhance.TopazResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** specs/vibe_edit.md §15–§18: what the sheet says about the direction, targets and the candidate. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DirectCandidateSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    @Test
    fun `the direction reads first, a default one marked as such`() {
        val inferred = FakePlanProvider.DEFAULT_DIRECTION.copy(source = com.diffuse.core.ai.GoalSource.Default)
        show(ready(FakePlanProvider.DEFAULT_PROPOSAL.copy(direction = inferred)))

        compose.onNodeWithTag(DirectDirectionTestTag).assertExists()
        compose.onNodeWithText("[추천 기본값] 목표: 나무를 더 푸르게").assertExists()
        compose.onNodeWithText("비교 기준: 주변 색은 그대로이고 나무만 더 푸르게 보인다").performScrollTo().assertExists()
    }

    /** §15: a new selection and an existing one are different edits, and say so. */
    @Test
    fun `new and existing selections read apart`() {
        show(
            ready(
                FakePlanProvider.ready(
                    EditPlan(
                        listOf(
                            PlanStep.Adjust(AdjustKind.Exposure, 0.3f, PlanTarget.Existing("t1", "m")),
                            PlanStep.Select("hair", "n1"),
                            PlanStep.Adjust(AdjustKind.Contrast, 0.1f, PlanTarget.New("n1")),
                        ),
                    ),
                ),
            ),
        )

        compose.onNodeWithText("기존 선택 영역 노출 30 (현재 값을 바꿔요)").performScrollTo().assertExists()
        compose.onNodeWithText("hair 영역 대비 10").performScrollTo().assertExists()
        compose.onNodeWithText("대상: hair").performScrollTo().assertExists()
    }

    /** §16, §19: a Topaz plan runs from the same primary, and says what it costs before it does. */
    @Test
    fun `a Topaz plan says where it runs and what it costs`() {
        show(ready(FakePlanProvider.ready(EditPlan(listOf(PlanStep.Topaz(TopazPurpose.Denoise, TopazOptions(1)))))))

        compose.onNodeWithText("화질 개선 · 노이즈 제거 · 1배 · 얼굴 복원 끔").performScrollTo().assertExists()
        compose.onNodeWithText("유료 작업", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("크레딧이 사용돼요", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("선택한 보정 실행").assertIsEnabled().performClick()
        assertEquals(listOf("apply"), calls)
    }

    @Test
    fun `a generative plan says it calls a paid model once`() {
        show(
            ready(
                FakePlanProvider.ready(EditPlan(listOf(PlanStep.Select("bus", "n1"), PlanStep.Erase("n1")))),
            ),
        )

        compose.onNodeWithText("비교나 되돌리기로는 다시 호출하지 않아요", substring = true).performScrollTo().assertExists()
    }

    /** §19: only an existing selection of unknown meaning waits; it says why and how to go on. */
    @Test
    fun `while a selection waits, the primary confirms it and the secondary stops`() {
        show(
            ready().copy(
                candidate = CandidateState(
                    CandidatePhase.Confirming,
                    check = TargetCheck(null, Bitmap.createBitmap(2, 2, Bitmap.Config.ALPHA_8), "m"),
                ),
            ),
        )

        compose.onNodeWithTag(DirectConfirmTestTag).assertExists()
        compose.onNodeWithText("기존 선택 영역이에요. 이번 요청의 대상인지 확인해 주세요").assertExists()
        compose.onNodeWithText("보정을 끄고 다시 실행할 수도 있어요", substring = true).assertExists()
        compose.onNodeWithText("선택 확인").assertIsEnabled().performClick()
        compose.onNodeWithText("중단").performClick()
        assertEquals(listOf("apply", "cancel"), calls)
    }

    @Test
    fun `while the candidate is built nothing can be applied`() {
        show(ready().copy(candidate = CandidateState(CandidatePhase.Running, step = 0, steps = 2)))

        compose.onNodeWithText("보정을 적용하는 중 (1/2)").assertExists()
        compose.onNodeWithText("선택한 보정 실행").assertIsNotEnabled()
    }

    @Test
    fun `the review holds to compare, halves, and applies`() {
        show(ready().copy(candidate = review(reducible = true)))

        compose.onNodeWithTag(DirectCandidateTestTag).assertExists()
        val compare = compose.onNodeWithTag(DirectCompareTestTag).performScrollTo()
        compare.performTouchInput { down(center) }
        compare.performTouchInput { up() }
        compare.performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag(DirectReduceTestTag).performScrollTo().performClick()
        compose.onNodeWithText("적용").assertIsEnabled().performClick()
        compose.onNodeWithText("되돌리기").performClick()

        assertEquals(listOf("compare:true", "compare:false", "compare:true", "reduce", "apply", "cancel"), calls)
        compare.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "후"))
    }

    @Test
    fun `a change outside the selection under keep-background blocks 적용`() {
        show(ready().copy(candidate = review(findings = setOf(CandidateFinding.ProtectedChanged))))

        compose.onNodeWithText("선택 밖 픽셀이 바뀌었어요", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("적용").assertIsNotEnabled()
    }

    // ---- §19 the Topaz step in the same review --------------------------------

    @Test
    fun `the Topaz step's stage is said as the run's last step`() {
        show(
            ready(TOPAZ_PLAN).copy(
                candidate = CandidateState(
                    CandidatePhase.Running,
                    step = 1,
                    steps = 2,
                    topazPhase = TopazPhase.Uploading,
                ),
            ),
        )

        compose.onNodeWithText("화질 개선 · 업로드 중 (2/2)").assertExists()
        compose.onNodeWithText("선택한 보정 실행").assertIsNotEnabled()
    }

    @Test
    fun `a Topaz result is compared here, saved in full or applied`() {
        show(ready(TOPAZ_PLAN).copy(candidate = review().copy(topaz = RESULT, resolved = listOf("sky"))))

        compose.onNodeWithText("Topaz 화질 개선까지 반영된 결과예요", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("결과 64×48").performScrollTo().assertExists()
        compose.onNodeWithText("AI가 찾은 대상: sky", substring = true).performScrollTo().assertExists()
        compose.onNodeWithTag(DirectCompareTestTag).performScrollTo().assertExists()
        compose.onNodeWithTag(DirectSaveTestTag).performScrollTo().performClick()
        compose.onNodeWithText("적용").assertIsEnabled().performClick()

        assertEquals(listOf("save", "apply"), calls)
    }

    @Test
    fun `a Topaz step that must run again shows no 후, nothing to keep, and one way to run it`() {
        show(ready(TOPAZ_PLAN).copy(candidate = review()))

        compose.onNodeWithText("화질 개선 결과가 아직 없어요", substring = true).performScrollTo().assertExists()
        compose.onNodeWithTag(DirectCompareTestTag).assertDoesNotExist()
        compose.onNodeWithTag(DirectSaveTestTag).assertDoesNotExist()
        compose.onNodeWithText("선택한 보정 다시 실행").assertIsEnabled()
    }

    @Test
    fun `a Topaz failure says why and that turning it off keeps the local result`() {
        show(ready(TOPAZ_PLAN).copy(candidate = review().copy(topazFailure = TopazFailure.Server)))

        compose.onNodeWithText("화질 개선 항목을 끄면 보정 결과를 검토하고 적용할 수 있어요", substring = true)
            .performScrollTo().assertExists()
        compose.onNodeWithText("선택한 보정 다시 실행").assertIsEnabled()
    }

    // ---- fixtures ---------------------------------------------------------

    private fun ready(proposal: com.diffuse.core.ai.PlanProposal = FakePlanProvider.DEFAULT_PROPOSAL) = DirectState(
        availability = Availability.Ready,
        request = "요청",
        proposals = ProposalState(ProposalPhase.Ready, PlanMode.Direct, proposal, frameReady = true),
    )

    private fun review(reducible: Boolean = false, findings: Set<CandidateFinding> = emptySet()) = CandidateState(
        CandidatePhase.Review,
        document = EditDocument("p", ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L),
        reducible = reducible,
        findings = findings,
    )

    /**
     * §19: as the controller builds it — every item on, and a candidate built for that choice
     * unless the test says otherwise.
     */
    private fun show(given: DirectState) {
        val chosen = given.copy(chosen = given.items.map { it.id }.toSet())
        val state = chosen.copy(
            candidate = chosen.candidate?.let { if (it.chosen.isEmpty()) it.copy(chosen = chosen.localChosen) else it },
        )
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                DirectSheet(
                    state = state,
                    onCancel = { calls += "cancel" },
                    onApply = { calls += "apply" },
                    onCompare = { calls += "compare:$it" },
                    onReduce = { calls += "reduce" },
                    onSave = { calls += "save" },
                )
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        val TOPAZ_PLAN = FakePlanProvider.ready(
            EditPlan(
                listOf(
                    PlanStep.Adjust(AdjustKind.Exposure, 0.1f),
                    PlanStep.Topaz(TopazPurpose.Denoise, TopazOptions(scale = 1)),
                ),
            ),
        )
        val RESULT = TopazResult(
            file = java.io.File("/topaz/output_1.jpg"),
            width = 64,
            height = 48,
            mimeType = "image/jpeg",
            purpose = TopazPurpose.Denoise,
            options = TopazOptions(scale = 1),
            after = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).asImageBitmap(),
            credits = 1,
        )
    }
}
