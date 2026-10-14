package com.diffuse.core.ai

import android.graphics.Bitmap
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * specs/ai_provider.md §6. Returns [DEFAULT_PROPOSAL] — the request specs/vibe_edit.md was written
 * from — unless [next] or [failNext] overrides the following call. [hold] parks the next call
 * until [release]; with `ignoreCancellation` (the default) it parks in `NonCancellable`, so a test
 * can prove a late answer is dropped (§14).
 */
class FakePlanProvider : EditPlanProvider {

    private val _availability = MutableStateFlow<Availability>(Availability.Ready)
    override val availability: StateFlow<Availability> = _availability

    var planCount: Int = 0
        private set

    var lastRequest: PlanRequest? = null
        private set

    var lastImage: Bitmap? = null
        private set

    private var nextProposal: PlanProposal? = null
    private var nextError: AppError? = null
    private var gate: CompletableDeferred<Unit>? = null
    private var ignoreCancellation = true

    fun hold(ignoreCancellation: Boolean = true) {
        gate = CompletableDeferred()
        this.ignoreCancellation = ignoreCancellation
    }

    fun release() {
        gate?.complete(Unit)
        gate = null
    }

    fun next(proposal: PlanProposal) {
        nextProposal = proposal
    }

    /** A ready proposal whose every step serves one requested whole-photo goal. */
    fun next(plan: EditPlan) {
        nextProposal = ready(plan)
    }

    fun failNext(error: AppError) {
        nextError = error
    }

    fun setAvailability(value: Availability) {
        _availability.value = value
    }

    override suspend fun plan(image: Bitmap, request: PlanRequest): Result<PlanProposal> {
        nextError?.let { nextError = null; return Result.Failure(it) }
        planCount++
        lastRequest = request
        lastImage = image
        val proposal = nextProposal ?: DEFAULT_PROPOSAL
        nextProposal = null
        gate?.let { waiting ->
            if (ignoreCancellation) withContext(NonCancellable) { waiting.await() } else waiting.await()
        }
        return Result.Success(proposal)
    }

    companion object {
        val DEFAULT_PLAN = EditPlan(
            listOf(
                PlanStep.Select("나무", "n1"),
                PlanStep.Adjust(AdjustKind.Saturation, 0.3f, PlanTarget.New("n1")),
            ),
        )

        /** §15: the one objective a ready proposal carries. */
        val DEFAULT_DIRECTION = PlanDirection(
            subject = "나무와 주변 풍경",
            strengths = listOf("자연스러운 빛"),
            preserve = listOf("주변 색"),
            objective = "나무를 더 푸르게",
            success = "주변 색은 그대로이고 나무만 더 푸르게 보인다",
            source = GoalSource.User,
        )

        /** Observation and goal kept apart, the way §14 asks the model to answer. */
        val DEFAULT_PROPOSAL = PlanProposal(
            outcome = PlanOutcome.Ready,
            direction = DEFAULT_DIRECTION,
            observations = listOf(
                Observation("o1", "나무", "잎의 초록이 주변보다 흐릿해 보여요", Confidence.Medium),
            ),
            intent = RequestIntent(
                targets = listOf(IntentItem("나무", explicit = true)),
                wants = listOf(IntentItem("더 푸르게", explicit = true)),
                constraints = listOf(Constraint("나무만 바꾸기", ConstraintKind.SubjectOnly, explicit = false)),
            ),
            goals = listOf(
                PlanGoal("g1", "나무를 더 푸르게", GoalSource.User, GoalScope.Subject, "나무", listOf("o1")),
            ),
            colors = listOf(ColorNote(ColorRole.Emphasize, "초록", "나무")),
            workflow = DEFAULT_PLAN.steps.map { WorkflowStep(it, "g1") },
            limitations = listOf("축소본이라 잎의 세부 질감은 확인하기 어려워요"),
        )

        fun ready(plan: EditPlan): PlanProposal = PlanProposal(
            outcome = PlanOutcome.Ready,
            direction = DEFAULT_DIRECTION.copy(objective = "요청한 보정"),
            goals = listOf(PlanGoal("g1", "요청한 보정", GoalSource.User, GoalScope.Global)),
            workflow = plan.steps.map { WorkflowStep(it, "g1") },
        )
    }
}
