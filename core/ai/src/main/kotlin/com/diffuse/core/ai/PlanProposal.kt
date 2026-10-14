package com.diffuse.core.ai

/**
 * specs/vibe_edit.md §14. What a planning call proposes. Observation and goal are separate
 * fields on purpose: "얼굴이 주변보다 어둡다" is something the model saw, "얼굴을 강조하자" is a goal
 * the user asked for or the model suggested, and neither is allowed to stand in for the other.
 *
 * Every text field is short Korean plain text written by the model. It is shown as text only —
 * never as markup, a link or a command — and the execution step lines are still built locally
 * from [PlanStep] templates.
 */
data class PlanProposal(
    val outcome: PlanOutcome,
    /** §15: what the photo already does well and the one objective; required when ready. */
    val direction: PlanDirection? = null,
    val observations: List<Observation> = emptyList(),
    val intent: RequestIntent = RequestIntent(),
    val goals: List<PlanGoal> = emptyList(),
    val colors: List<ColorNote> = emptyList(),
    /** In execution order; non-empty exactly when [outcome] is [PlanOutcome.Ready]. */
    val workflow: List<WorkflowStep> = emptyList(),
    val limitations: List<String> = emptyList(),
    /** Why nothing should run; set for every outcome but [PlanOutcome.Ready]. */
    val reason: String? = null,
) {

    /** The steps `PlanRunner` executes, in order. */
    val plan: EditPlan get() = EditPlan(workflow.map { it.step })

    fun goal(id: String): PlanGoal? = goals.firstOrNull { it.id == id }
}

/**
 * [NoChange] is a judgement that the photo needs nothing — not a failure and not "I did not
 * understand". It, [NeedsClarification] and [Unsupported] all leave 적용 disabled.
 */
enum class PlanOutcome { Ready, NoChange, NeedsClarification, Unsupported }

/** The model's own estimate of how sure it is of what it saw — not a measurement. */
enum class Confidence { High, Medium, Low }

/** Something visible in the downscaled preview. [unknown] is what the preview cannot settle. */
data class Observation(
    val id: String,
    val subject: String,
    val note: String,
    val confidence: Confidence,
    val unknown: String? = null,
)

/** One element of the request; [explicit] is false when the model inferred it. */
data class IntentItem(val text: String, val explicit: Boolean)

/**
 * The machine-checkable kinds of hard constraint. "실루엣 유지" is [Other]: whether a plan keeps a
 * silhouette is a question about pixels, which the app cannot answer from the plan.
 *
 * specs/vibe_edit.md §17: [KeepBackground] keeps the colour, brightness and content outside the
 * selection — a requested crop still reframes. [KeepFrame] keeps the whole frame, so it and a crop
 * contradict. Colour comes in three strengths: [KeepHue] keeps hue families, [KeepColors] (the
 * default reading of "색은 바꾸지 마") keeps hue and saturation, [KeepExactColors] keeps RGB, which
 * any tonal change breaks.
 */
enum class ConstraintKind {
    SubjectOnly,
    KeepBackground,
    KeepFrame,
    KeepHue,
    KeepColors,
    KeepExactColors,
    Other,
}

data class Constraint(val text: String, val kind: ConstraintKind, val explicit: Boolean)

data class RequestIntent(
    val targets: List<IntentItem> = emptyList(),
    val wants: List<IntentItem> = emptyList(),
    val constraints: List<Constraint> = emptyList(),
)

/** [User] is a goal the request states; [Default] is one the model proposes without being asked. */
enum class GoalSource { User, Default }

/**
 * specs/vibe_edit.md §15: the photo's strengths and the **one** visual objective the plan serves,
 * before any tool. [source] is [GoalSource.Default] when the model inferred the objective — an
 * intention, not an observation. [success] is something a person can check by comparing the
 * result with this input, not a promise.
 */
data class PlanDirection(
    /** The main subject and the relationship worth keeping. */
    val subject: String,
    val strengths: List<String> = emptyList(),
    val preserve: List<String> = emptyList(),
    val objective: String,
    val success: String,
    val source: GoalSource,
)

/** Where a goal's steps may act: the whole photo, one selected thing, or one colour range. */
enum class GoalScope { Global, Subject, ColorRange }

data class PlanGoal(
    val id: String,
    val text: String,
    val source: GoalSource,
    val scope: GoalScope,
    /** What the goal is about, in the user's words, for [GoalScope.Subject]. */
    val target: String? = null,
    val observationIds: List<String> = emptyList(),
)

enum class ColorRole { Preserve, Emphasize, Background }

data class ColorNote(val role: ColorRole, val color: String, val target: String)

/**
 * One executable step and why it is there. [goalId] always names one of the proposal's goals.
 * [id] is assigned by the decoder in wire order (specs/vibe_edit.md §19) and never changes with
 * the proposal's order or text; empty means "the position in the proposal it was built with".
 */
data class WorkflowStep(
    val step: PlanStep,
    val goalId: String,
    val effect: String? = null,
    val caution: String? = null,
    val id: String = "",
)
