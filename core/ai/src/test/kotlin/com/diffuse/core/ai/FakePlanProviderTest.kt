package com.diffuse.core.ai

import android.graphics.Bitmap
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** specs/ai_provider.md §6. */
@RunWith(RobolectricTestRunner::class)
class FakePlanProviderTest {

    private val provider = FakePlanProvider()

    @Test
    fun `the default plan is the two-step story vibe_edit was specified from`() = runTest {
        val proposal = provider.plan(image(), direct("나무를 좀 더 푸르게 해줘")).valueOrFail()

        assertEquals(PlanOutcome.Ready, proposal.outcome)
        assertEquals(
            listOf(
                PlanStep.Select("나무", "n1"),
                PlanStep.Adjust(AdjustKind.Saturation, 0.3f, PlanTarget.New("n1")),
            ),
            proposal.plan.steps,
        )
        assertEquals(1, provider.planCount)
    }

    @Test
    fun `next overrides exactly one call`() = runTest {
        val custom = EditPlan(listOf(PlanStep.Select("subject", "n1"), PlanStep.CutOut("n1")))
        provider.next(custom)

        assertEquals(custom, provider.plan(image(), direct("배경 지워줘")).valueOrFail().plan)
        assertEquals(FakePlanProvider.DEFAULT_PLAN, provider.plan(image(), direct("다시")).valueOrFail().plan)
    }

    @Test
    fun `it records the request it was asked`() = runTest {
        val request = PlanRequest("", PlanMode.Recommend, PlanContext(hasSelection = true))

        provider.plan(image(), request)

        assertEquals(request, provider.lastRequest)
    }

    @Test
    fun `failNext fails exactly one call`() = runTest {
        provider.failNext(AppError.Unavailable)

        assertEquals(Result.Failure(AppError.Unavailable), provider.plan(image(), direct("나무")))
        assertEquals(FakePlanProvider.DEFAULT_PLAN, provider.plan(image(), direct("나무")).valueOrFail().plan)
    }

    private fun direct(text: String) = PlanRequest(text, PlanMode.Direct)

    private fun image(): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    private fun <T> Result<T>.valueOrFail(): T = when (this) {
        is Result.Success -> value
        is Result.Failure -> throw AssertionError("expected success, got $error")
    }
}
