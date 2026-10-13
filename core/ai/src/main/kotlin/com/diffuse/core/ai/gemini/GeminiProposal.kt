package com.diffuse.core.ai.gemini

import com.diffuse.core.ai.ColorNote
import com.diffuse.core.ai.ColorRole
import com.diffuse.core.ai.Confidence
import com.diffuse.core.ai.Constraint
import com.diffuse.core.ai.ConstraintKind
import com.diffuse.core.ai.GoalScope
import com.diffuse.core.ai.GoalSource
import com.diffuse.core.ai.IntentItem
import com.diffuse.core.ai.Observation
import com.diffuse.core.ai.PlanGoal
import com.diffuse.core.ai.PlanOutcome
import com.diffuse.core.ai.PlanProposal
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.RequestIntent
import com.diffuse.core.ai.WorkflowStep
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.HslChannel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull

/** A response that cannot be accepted whole. [message] says which rule it broke, for the log. */
internal class Unplannable(message: String) : Exception(message)

/**
 * specs/vibe_edit.md §14. Reads one `propose_edit` envelope into a [PlanProposal], **all or
 * nothing**: a missing required field, an unknown tool, a non-finite number, a dangling id or a
 * text past its cap refuses the whole proposal. This replaces §5's old "drop the bad call, keep
 * the rest" — a workflow that silently lost its select step would run its masked adjust on
 * whatever selection was left over.
 *
 * A finite value outside its range is still clamped, as before: the model meant "a lot".
 */
// One reader per envelope field and one typed accessor per JSON shape: splitting them across
// objects would only separate each rule from the field it guards.
@Suppress("TooManyFunctions")
internal object ProposalDecoder {

    fun decode(args: JsonObject): PlanProposal {
        val goals = args.objects(ARG_GOALS, ProposalLimits.GOALS).map(::goal)
        return PlanProposal(
            outcome = when (args.required(ARG_OUTCOME)) {
                OUTCOME_READY -> PlanOutcome.Ready
                OUTCOME_NO_CHANGE -> PlanOutcome.NoChange
                OUTCOME_NEEDS_CLARIFICATION -> PlanOutcome.NeedsClarification
                OUTCOME_UNSUPPORTED -> PlanOutcome.Unsupported
                else -> fail("unknown outcome")
            },
            observations = args.objects(ARG_OBSERVATIONS, ProposalLimits.OBSERVATIONS).map(::observation),
            intent = intent(args.optionalObject(ARG_INTENT)),
            goals = goals,
            colors = args.objects(ARG_COLOR_ROLES, ProposalLimits.COLORS).map(::color),
            workflow = args.objects(ARG_WORKFLOW, ProposalLimits.WORKFLOW).flatMap { workflow(it, goals) },
            limitations = args.strings(ARG_LIMITATIONS, ProposalLimits.LIMITATIONS),
            reason = args.optional(ARG_REASON),
        )
    }

    private fun observation(o: JsonObject) = Observation(
        id = o.required(ARG_ID),
        subject = o.required(ARG_SUBJECT),
        note = o.required(ARG_NOTE),
        confidence = when (o.required(ARG_CONFIDENCE)) {
            "high" -> Confidence.High
            "medium" -> Confidence.Medium
            "low" -> Confidence.Low
            else -> fail("unknown confidence")
        },
        unknown = o.optional(ARG_UNKNOWN),
    )

    private fun intent(o: JsonObject?): RequestIntent = if (o == null) {
        RequestIntent()
    } else {
        RequestIntent(
            targets = o.objects(ARG_TARGETS, ProposalLimits.INTENT_ITEMS).map(::intentItem),
            wants = o.objects(ARG_WANTS, ProposalLimits.INTENT_ITEMS).map(::intentItem),
            constraints = o.objects(ARG_CONSTRAINTS, ProposalLimits.INTENT_ITEMS).map { c ->
                Constraint(
                    text = c.required(ARG_TEXT),
                    kind = when (c.required(ARG_KIND)) {
                        "subject_only" -> ConstraintKind.SubjectOnly
                        "keep_background" -> ConstraintKind.KeepBackground
                        "keep_colors" -> ConstraintKind.KeepColors
                        "other" -> ConstraintKind.Other
                        else -> fail("unknown constraint kind")
                    },
                    explicit = c.requiredBoolean(ARG_EXPLICIT),
                )
            },
        )
    }

    private fun intentItem(o: JsonObject) = IntentItem(o.required(ARG_TEXT), o.requiredBoolean(ARG_EXPLICIT))

    private fun goal(o: JsonObject) = PlanGoal(
        id = o.required(ARG_ID),
        text = o.required(ARG_TEXT),
        source = when (o.required(ARG_SOURCE)) {
            "user" -> GoalSource.User
            "default" -> GoalSource.Default
            else -> fail("unknown goal source")
        },
        scope = when (o.required(ARG_SCOPE)) {
            "global" -> GoalScope.Global
            "subject" -> GoalScope.Subject
            "color_range" -> GoalScope.ColorRange
            else -> fail("unknown goal scope")
        },
        target = o.optional(ARG_TARGET),
        observationIds = o.strings(ARG_OBSERVATION_IDS, ProposalLimits.OBSERVATIONS),
    )

    private fun color(o: JsonObject) = ColorNote(
        role = when (o.required(ARG_ROLE)) {
            "preserve" -> ColorRole.Preserve
            "emphasize" -> ColorRole.Emphasize
            "background" -> ColorRole.Background
            else -> fail("unknown colour role")
        },
        color = o.required(ARG_COLOR),
        target = o.required(ARG_TARGET),
    )

    /** One workflow item; `adjust_color_range` becomes up to three steps, all for its goal. */
    private fun workflow(o: JsonObject, goals: List<PlanGoal>): List<WorkflowStep> {
        val goalId = o.required(ARG_GOAL_ID)
        val goal = goals.firstOrNull { it.id == goalId } ?: fail("step names no goal")
        val args = o.optionalObject(ARG_ARGS) ?: JsonObject(emptyMap())
        val effect = o.optional(ARG_EFFECT)
        val caution = o.optional(ARG_CAUTION)
        return steps(o.required(ARG_TOOL), args, goal.scope == GoalScope.Subject)
            .map { WorkflowStep(it, goalId, effect, caution) }
    }

    /**
     * The eight tools, with the argument rules their separate declarations carried. `masked`
     * left out follows the goal: a subject goal acts inside its selection, any other does not.
     */
    private fun steps(tool: String, args: JsonObject, subject: Boolean): List<PlanStep> = when (tool) {
        FN_SELECT_REGION -> listOf(PlanStep.Select(args.required(ARG_PHRASE)))
        FN_ADJUST -> {
            val kind = adjustKindOf(args.required(ARG_KIND)) ?: fail("unknown adjust kind")
            val value = args.number(ARG_VALUE) ?: fail("adjust without a value")
            listOf(PlanStep.Adjust(kind, kind.coerce(value), args.flag(ARG_MASKED) ?: subject))
        }
        FN_ADJUST_COLOR_RANGE -> colorRange(args, subject)
        FN_ERASE_SELECTION -> listOf(PlanStep.Erase)
        FN_CUT_OUT_SELECTION -> listOf(PlanStep.CutOut)
        FN_FILL_SELECTION -> listOf(PlanStep.Fill(args.required(ARG_PROMPT)))
        FN_CROP_RATIO -> listOf(PlanStep.Crop(cropRatioOf(args.required(ARG_RATIO)) ?: fail("unknown ratio")))
        FN_APPLY_STYLE -> listOf(style(args))
        else -> fail("unknown tool")
    }

    /** specs/style_match.md §6: 0…100, clamped; a look named with no strength means all of it. */
    private fun style(args: JsonObject): PlanStep.Style {
        val style = styleIdOf(args.required(ARG_STYLE)) ?: fail("unknown style")
        val intensity = args.number(ARG_INTENSITY)?.toInt()?.coerceIn(0, FULL_STYLE_INTENSITY)
        return PlanStep.Style(style, intensity ?: FULL_STYLE_INTENSITY)
    }

    /** specs/adjust_hsl.md §8: hue → saturation → luminance; a zero channel is no step. */
    private fun colorRange(args: JsonObject, subject: Boolean): List<PlanStep> {
        val band = hslBandOf(args.required(ARG_COLOR)) ?: fail("unknown colour range")
        val masked = args.flag(ARG_MASKED) ?: subject
        val steps = HslChannel.entries.mapNotNull { channel ->
            args.number(channel.wireName)?.takeIf { it != 0f }?.let { value ->
                val kind = hslKindOf(band, channel)
                PlanStep.Adjust(kind, kind.coerce(value), masked)
            }
        }
        return steps.ifEmpty { fail("colour range without a change") }
    }

    // ---- typed reads: each one refuses rather than defaults ----------------

    private fun JsonObject.primitive(key: String): JsonPrimitive? = when (val value = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value
        else -> fail("$key is not a value")
    }

    private fun JsonObject.optional(key: String): String? {
        val value = primitive(key) ?: return null
        if (!value.isString) fail("$key is not text")
        return value.content.trim().takeIf { it.isNotEmpty() }?.also(::checkLength)
    }

    private fun JsonObject.required(key: String): String = optional(key) ?: fail("missing $key")

    private fun JsonObject.flag(key: String): Boolean? =
        primitive(key)?.let { it.booleanOrNull ?: fail("$key is not a boolean") }

    private fun JsonObject.requiredBoolean(key: String): Boolean = flag(key) ?: fail("missing $key")

    /** A number that is present must be finite; absence is the caller's to decide. */
    private fun JsonObject.number(key: String): Float? {
        val value = primitive(key) ?: return null
        val number = value.floatOrNull ?: fail("$key is not a number")
        return number.takeIf { it.isFinite() } ?: fail("$key is not finite")
    }

    private fun JsonObject.optionalObject(key: String): JsonObject? = when (val value = this[key]) {
        null, JsonNull -> null
        is JsonObject -> value
        else -> fail("$key is not an object")
    }

    private fun JsonObject.array(key: String, max: Int): List<JsonElement> = when (val value = this[key]) {
        null, JsonNull -> emptyList()
        is JsonArray -> value.also { if (it.size > max) fail("too many $key") }
        else -> fail("$key is not a list")
    }

    private fun JsonObject.objects(key: String, max: Int): List<JsonObject> =
        array(key, max).map { it as? JsonObject ?: fail("$key holds a non-object") }

    private fun JsonObject.strings(key: String, max: Int): List<String> = array(key, max).map {
        val text = (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()
        if (text.isNullOrEmpty()) fail("$key holds a non-text")
        text.also(::checkLength)
    }

    private fun checkLength(text: String) {
        if (text.length > ProposalLimits.TEXT_CHARS) fail("text over ${ProposalLimits.TEXT_CHARS} characters")
    }
}

/**
 * specs/vibe_edit.md §14. The mechanical half of the rules the instruction states: a decoded
 * proposal whose structure contradicts itself or the request is refused. Whether the model
 * *understood* the photo is not checkable here and is left to the image evaluation.
 */
internal object ProposalCheck {

    /** @return the first rule [proposal] breaks for a request whose text is [requestText], or null. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun violation(proposal: PlanProposal, requestText: String): String? {
        val ids = proposal.observations.map { it.id }
        val goalIds = proposal.goals.map { it.id }
        val steps = proposal.workflow
        val constraints = proposal.intent.constraints.map { it.kind }.toSet()
        when {
            ids.size != ids.toSet().size || goalIds.size != goalIds.toSet().size -> return "duplicate id"
            proposal.goals.any { goal -> goal.observationIds.any { it !in ids } } -> return "goal names no observation"
            proposal.outcome == PlanOutcome.Ready && steps.none { it.step !is PlanStep.Select } ->
                return "ready without an edit"
            proposal.outcome != PlanOutcome.Ready && steps.isNotEmpty() -> return "a workflow that is not ready"
            proposal.outcome != PlanOutcome.Ready && proposal.reason == null -> return "no reason"
            requestText.isBlank() && proposal.goals.any { it.source == GoalSource.User } ->
                return "a user goal without a request"
            steps.count { it.step is PlanStep.Crop } > 1 -> return "more than one crop"
            steps.count { it.step is PlanStep.Style } > 1 -> return "more than one style"
            steps.any { it.step is PlanStep.Style } && steps.any { it.isGlobalAdjust } ->
                return "an adjust that repeats the style"
        }
        steps.forEachIndexed { index, item ->
            val goal = proposal.goal(item.goalId) ?: return "step names no goal"
            val step = item.step
            if (step.needsRequest && goal.source != GoalSource.User) return "${step.name} that was not requested"
            if (!goal.scope.allows(step)) return "${step.name} outside its ${goal.scope} goal"
            val ownSelection = steps.take(index).any { it.goalId == item.goalId && it.step is PlanStep.Select }
            if (step.consumesSelection && !ownSelection) return "${step.name} without its own selection"
            if (step is PlanStep.Select && steps.drop(index + 1).takeWhile { it.step !is PlanStep.Select }
                    .none { it.step.consumesSelection }
            ) {
                return "a selection nothing uses"
            }
            val subjectOnly = ConstraintKind.SubjectOnly in constraints || ConstraintKind.KeepBackground in constraints
            if (!step.isLocal && subjectOnly) return "${step.name} beyond a subject-only constraint"
            if (step.changesColor && ConstraintKind.KeepColors in constraints) return "${step.name} under keep colours"
        }
        return null
    }

    private fun GoalScope.allows(step: PlanStep): Boolean = when (this) {
        GoalScope.Subject -> step is PlanStep.Select || step.consumesSelection
        GoalScope.Global -> step is PlanStep.Style || step is PlanStep.Crop ||
            (step is PlanStep.Adjust && !step.masked)
        GoalScope.ColorRange -> step is PlanStep.Adjust && !step.masked && step.kind.hsl != null
    }

    private val WorkflowStep.isGlobalAdjust: Boolean
        get() = (step as? PlanStep.Adjust)?.let { !it.masked && it.kind.hsl == null } == true

    /** What only an explicit request may ask for (§14): a look, a shape, a removal or a fill. */
    private val PlanStep.needsRequest: Boolean
        get() = this is PlanStep.Style || this is PlanStep.Crop || this is PlanStep.Erase ||
            this is PlanStep.CutOut || this is PlanStep.Fill

    private val PlanStep.consumesSelection: Boolean
        get() = when (this) {
            is PlanStep.Adjust -> masked
            PlanStep.Erase, PlanStep.CutOut, is PlanStep.Fill -> true
            is PlanStep.Select, is PlanStep.Crop, is PlanStep.Style -> false
        }

    /** Stays inside a selection: a crop only reframes, so it changes no tone outside one either. */
    private val PlanStep.isLocal: Boolean
        get() = this is PlanStep.Select || this is PlanStep.Crop || consumesSelection

    private val PlanStep.changesColor: Boolean
        get() = this is PlanStep.Style ||
            (this is PlanStep.Adjust && (kind.hsl != null || kind in COLOR_KINDS))

    private val PlanStep.name: String get() = this::class.simpleName.orEmpty()

    private val COLOR_KINDS = setOf(
        AdjustKind.Temperature,
        AdjustKind.Tint,
        AdjustKind.Saturation,
        AdjustKind.Vibrance,
    )
}

private fun fail(reason: String): Nothing = throw Unplannable(reason)
