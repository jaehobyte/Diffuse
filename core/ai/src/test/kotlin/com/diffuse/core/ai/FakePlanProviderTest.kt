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
        val plan = provider.plan(image(), "나무를 좀 더 푸르게 해줘").valueOrFail()

        assertEquals(
            listOf(
                PlanStep.Select("나무"),
                PlanStep.Adjust(AdjustKind.Saturation, 0.3f, masked = true),
            ),
            plan.steps,
        )
        assertEquals(1, provider.planCount)
    }

    @Test
    fun `next overrides exactly one call`() = runTest {
        val custom = EditPlan(listOf(PlanStep.CutOut))
        provider.next(custom)

        assertEquals(custom, provider.plan(image(), "배경 지워줘").valueOrFail())
        assertEquals(FakePlanProvider.DEFAULT_PLAN, provider.plan(image(), "다시").valueOrFail())
    }

    @Test
    fun `an empty plan is a valid answer`() = runTest {
        provider.next(EditPlan(emptyList()))

        assertEquals(emptyList<PlanStep>(), provider.plan(image(), "?").valueOrFail().steps)
    }

    @Test
    fun `failNext fails exactly one call`() = runTest {
        provider.failNext(AppError.Unavailable)

        assertEquals(Result.Failure(AppError.Unavailable), provider.plan(image(), "나무"))
        assertEquals(FakePlanProvider.DEFAULT_PLAN, provider.plan(image(), "나무").valueOrFail())
    }

    private fun image(): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    private fun <T> Result<T>.valueOrFail(): T = when (this) {
        is Result.Success -> value
        is Result.Failure -> throw AssertionError("expected success, got $error")
    }
}
