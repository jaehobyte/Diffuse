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
import com.diffuse.core.ai.PlanContext
import com.diffuse.core.ai.PlanDirection
import com.diffuse.core.ai.PlanGoal
import com.diffuse.core.ai.PlanOutcome
import com.diffuse.core.ai.PlanProposal
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.PlanTarget
import com.diffuse.core.ai.RequestIntent
import com.diffuse.core.ai.WorkflowStep
import com.diffuse.core.ai.consumes
import com.diffuse.core.ai.topaz.TopazJob
import com.diffuse.core.ai.topaz.TopazOptions
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.HslChannel
import com.diffuse.core.imaging.style.atIntensity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlin.math.abs

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

    /**
     * @param context the snapshot the request was built from: a target ref the model writes
     * resolves against it and nothing else (specs/vibe_edit.md §15).
     */
    fun decode(args: JsonObject, context: PlanContext = PlanContext()): PlanProposal {
        val goals = args.objects(ARG_GOALS, ProposalLimits.GOALS).map(::goal)
        return PlanProposal(
            direction = args.optionalObject(ARG_DIRECTION)?.let(::direction),
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
            workflow = args.objects(ARG_WORKFLOW, ProposalLimits.WORKFLOW)
                .flatMapIndexed { index, item -> workflow(item, index, goals, context) },
            limitations = args.strings(ARG_LIMITATIONS, ProposalLimits.LIMITATIONS),
            reason = args.optional(ARG_REASON),
        )
    }

    private fun direction(o: JsonObject) = PlanDirection(
        subject = o.required(ARG_SUBJECT),
        strengths = o.strings(ARG_STRENGTHS, ProposalLimits.DIRECTION_ITEMS),
        preserve = o.strings(ARG_PRESERVE, ProposalLimits.DIRECTION_ITEMS),
        objective = o.required(ARG_OBJECTIVE),
        success = o.required(ARG_SUCCESS),
        source = source(o.required(ARG_SOURCE)),
    )

    private fun source(wire: String) = when (wire) {
        "user" -> GoalSource.User
        "default" -> GoalSource.Default
        else -> fail("unknown goal source")
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
                        "keep_frame" -> ConstraintKind.KeepFrame
                        "keep_hue" -> ConstraintKind.KeepHue
                        "keep_colors" -> ConstraintKind.KeepColors
                        "keep_exact_colors" -> ConstraintKind.KeepExactColors
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
        source = source(o.required(ARG_SOURCE)),
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

    /**
     * One workflow item; `adjust_color_range` becomes up to three steps, all for its goal. Each step
     * gets its id here, from its wire position (`s3`, or `s3.1`… for a colour range's channels), so
     * a later reorder or a toggle never re-identifies it (specs/vibe_edit.md §19).
     */
    private fun workflow(o: JsonObject, index: Int, goals: List<PlanGoal>, context: PlanContext): List<WorkflowStep> {
        val goalId = o.required(ARG_GOAL_ID)
        if (goals.none { it.id == goalId }) fail("step names no goal")
        val args = o.optionalObject(ARG_ARGS) ?: JsonObject(emptyMap())
        val effect = o.optional(ARG_EFFECT)
        val caution = o.optional(ARG_CAUTION)
        // §15: there is no `masked`; a local step names its target. An old-style `masked=true`
        // would be a step the model thinks is local and the app would run on the whole photo.
        if (args.flag(ARG_MASKED) == true) fail("masked is not an argument; a local step names its target")
        val steps = steps(o.required(ARG_TOOL), args, context)
        return steps.mapIndexed { k, step ->
            val id = if (steps.size == 1) "s${index + 1}" else "s${index + 1}.${k + 1}"
            WorkflowStep(step, goalId, effect, caution, id)
        }
    }

    /** The nine tools, with the argument rules their separate declarations carried. */
    private fun steps(tool: String, args: JsonObject, context: PlanContext): List<PlanStep> = when (tool) {
        FN_SELECT_REGION -> listOf(
            PlanStep.Select(args.required(ARG_PHRASE), args.required(ARG_TARGET), args.flag(ARG_MULTIPLE) ?: false),
        )
        FN_ADJUST -> listOf(adjust(args, context))
        FN_ADJUST_COLOR_RANGE -> colorRange(args, context)
        FN_ERASE_SELECTION -> listOf(PlanStep.Erase(args.required(ARG_TARGET)))
        FN_CUT_OUT_SELECTION -> listOf(PlanStep.CutOut(args.required(ARG_TARGET)))
        FN_FILL_SELECTION -> listOf(PlanStep.Fill(args.required(ARG_PROMPT), args.required(ARG_TARGET)))
        FN_CROP_RATIO, FN_EXPAND_TO_RATIO -> listOf(ratioStep(tool, args))
        FN_APPLY_STYLE -> listOf(style(args))
        FN_TOPAZ_ENHANCE -> listOf(topaz(args))
        else -> fail("unknown tool")
    }

    /** §4.1 and outpaint.md: the same closed ratio, removed from the frame or added around it. */
    private fun ratioStep(tool: String, args: JsonObject): PlanStep {
        val ratio = cropRatioOf(args.required(ARG_RATIO)) ?: fail("unknown ratio")
        return if (tool == FN_CROP_RATIO) PlanStep.Crop(ratio) else PlanStep.Expand(ratio)
    }

    private fun adjust(args: JsonObject, context: PlanContext): PlanStep.Adjust {
        val kind = adjustKindOf(args.required(ARG_KIND)) ?: fail("unknown adjust kind")
        val value = args.number(ARG_VALUE) ?: fail("adjust without a value")
        return PlanStep.Adjust(kind, kind.coerce(value), args.optional(ARG_TARGET)?.let { target(it, context) })
    }

    /** §15: a ref the snapshot lists is that selection; anything else must be a declared label. */
    private fun target(wire: String, context: PlanContext): PlanTarget =
        context.target(wire)?.let { PlanTarget.Existing(it.ref, it.maskId) } ?: PlanTarget.New(wire)

    /**
     * specs/style_match.md §6: 0…100, clamped. The strength is **required** — "a look named
     * means all of it" was a default nobody measured (§18).
     */
    private fun style(args: JsonObject): PlanStep.Style {
        val style = styleIdOf(args.required(ARG_STYLE)) ?: fail("unknown style")
        val intensity = args.number(ARG_INTENSITY) ?: fail("style without an intensity")
        return PlanStep.Style(style, intensity.toInt().coerceIn(0, MAX_STYLE_INTENSITY))
    }

    /** §16: the purpose and its options; whether they fit the purpose is `ProposalCheck`'s. */
    private fun topaz(args: JsonObject): PlanStep.Topaz {
        val purpose = topazPurposeOf(args.required(ARG_PURPOSE)) ?: fail("unknown topaz purpose")
        val scale = args.number(ARG_SCALE)?.let { scale ->
            scale.toInt().takeIf { it.toFloat() == scale } ?: fail("scale is not a whole number")
        }
        return PlanStep.Topaz(
            purpose,
            TopazOptions(
                scale = scale ?: purpose.defaultScale,
                faceRecovery = args.flag(ARG_FACE_RECOVERY) ?: false,
                sharpenStrength = args.number(ARG_SHARPEN_STRENGTH),
            ),
        )
    }

    /**
     * specs/adjust_hsl.md §8, vibe_edit.md §15: whole-photo only, hue → saturation → luminance.
     * A channel left out keeps its value; an explicit 0 resets it, and is no step when it is
     * already 0.
     */
    private fun colorRange(args: JsonObject, context: PlanContext): List<PlanStep> {
        if (args.optional(ARG_TARGET) != null) fail("colour range takes no target")
        val band = hslBandOf(args.required(ARG_COLOR)) ?: fail("unknown colour range")
        val steps = HslChannel.entries.mapNotNull { channel ->
            val kind = hslKindOf(band, channel)
            val current = context.adjustments.firstOrNull { it.kind == kind && it.targetRef == null }?.value ?: 0f
            args.number(channel.wireName)?.takeUnless { it == 0f && current == 0f }?.let { value ->
                PlanStep.Adjust(kind, kind.coerce(value))
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
 * specs/vibe_edit.md §14–§17. The mechanical half of the rules the instruction states: a decoded
 * proposal whose structure contradicts itself, the request or the snapshot is refused. Whether the
 * model *understood* the photo — or classified a constraint well — is not checkable here; the
 * rendered result is compared with the same input by the user (§18).
 */
// One rule family per function; each returns the first violation or null.
@Suppress("TooManyFunctions")
internal object ProposalCheck {

    /**
     * @param context the snapshot the request was built from — the targets a step may name and
     * the values and style effects the simulation starts from.
     * @return the first rule [proposal] breaks for a request whose text is [requestText], or null.
     */
    fun violation(proposal: PlanProposal, requestText: String, context: PlanContext = PlanContext()): String? =
        envelope(proposal, requestText, context) ?: topazJob(proposal, context) ?: selectionViolation(proposal, context)

    /**
     * specs/vibe_edit.md §19: the rules that hold for any part of a proposal — the ones a user's
     * choice of items is checked against again. The envelope rules (outcome, direction, goal ids)
     * and the Topaz options are the original answer's and are not re-asked of a choice; whether
     * the hand-off can run on the document it gets is the item's block, checked when it is known.
     */
    fun selectionViolation(proposal: PlanProposal, context: PlanContext = PlanContext()): String? =
        topaz(proposal)
            ?: targets(proposal, context)
            ?: constraints(proposal)
            ?: StateSimulation(context).violation(proposal.workflow.map { it.step })

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun envelope(proposal: PlanProposal, requestText: String, context: PlanContext): String? {
        val ids = proposal.observations.map { it.id }
        val goalIds = proposal.goals.map { it.id }
        val steps = proposal.workflow
        val ready = proposal.outcome == PlanOutcome.Ready
        return when {
            ids.size != ids.toSet().size || goalIds.size != goalIds.toSet().size -> "duplicate id"
            proposal.goals.any { goal -> goal.observationIds.any { it !in ids } } -> "goal names no observation"
            ready && steps.none { it.step !is PlanStep.Select } -> "ready without an edit"
            ready && proposal.direction == null -> "ready without a direction"
            !ready && steps.isNotEmpty() -> "a workflow that is not ready"
            !ready && proposal.reason == null -> "no reason"
            requestText.isBlank() && proposal.goals.any { it.source == GoalSource.User } ->
                "a user goal without a request"
            requestText.isBlank() && proposal.direction?.source == GoalSource.User ->
                "a user direction without a request"
            steps.count { it.step is PlanStep.Crop } > 1 -> "more than one crop"
            steps.count { it.step is PlanStep.Expand } > 1 -> "more than one expand"
            steps.any { it.step is PlanStep.Expand } && steps.any { it.step is PlanStep.Crop } -> "expand with a crop"
            steps.any { it.step is PlanStep.Expand } && steps.any { it.step is PlanStep.CutOut } ->
                "expand with a cut-out"
            // outpaint.md §3: an `Outpaint` goes under every stored mask and patch, so it is refused
            // for a photo that already holds one rather than re-based over it.
            steps.any { it.step is PlanStep.Expand } && !context.expandable ->
                "expand on a photo already holding a selection"
            steps.count { it.step is PlanStep.Style } > 1 -> "more than one style"
            steps.count { it.step is PlanStep.Topaz } > 1 -> "more than one topaz"
            // §19: a default goal may name any tool; what it may not do is leave its scope.
            else -> steps.firstNotNullOfOrNull { item ->
                val goal = proposal.goal(item.goalId) ?: return@firstNotNullOfOrNull "step names no goal"
                if (goal.scope.allows(item.step)) null else "${item.step.name} outside its ${goal.scope} goal"
            }
        }
    }

    /**
     * §16, §19: a Topaz step is the plan's last hand-off — beside local steps, whole-photo, never
     * under an explicit constraint it cannot keep and never after a cut-out (which leaves the
     * transparency no purpose takes). A missing key is not a refusal: the item says so and the
     * user may turn it off.
     */
    private fun topaz(proposal: PlanProposal): String? {
        val steps = proposal.workflow
        if (steps.none { it.step is PlanStep.Topaz }) return null
        val constraints = proposal.explicitConstraints
        return when {
            steps.any { it.step is PlanStep.CutOut } -> "topaz after a cut-out"
            constraints.any { it in TOPAZ_FORBIDDEN } -> "topaz under a preservation constraint"
            else -> null
        }
    }

    /**
     * §16: the options are ones the purpose offers and the snapshot's input fits it —
     * `TopazJob.problem()` decides, with the snapshot's working size when it is known.
     */
    private fun topazJob(proposal: PlanProposal, context: PlanContext): String? {
        val step = proposal.workflow.firstNotNullOfOrNull { it.step as? PlanStep.Topaz } ?: return null
        val capability = context.topaz
        val job = TopazJob(
            purpose = step.purpose,
            options = step.options,
            inputWidth = capability?.inputWidth ?: 1,
            inputHeight = capability?.inputHeight ?: 1,
            inputHasAlpha = capability?.hasAlpha == true,
        )
        return job.problem()?.let { "topaz $it" }
    }

    /**
     * §15: every target is explicit. A new label is declared once by a `Select`, never shadows a
     * snapshot ref, and is used afterwards; erase, cut-out and fill consume the selection made
     * right before them — what `PlanRunner` holds as the current mask.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun targets(proposal: PlanProposal, context: PlanContext): String? {
        val steps = proposal.workflow.map { it.step }
        val declared = mutableSetOf<String>()
        var current: String? = null
        steps.forEachIndexed { index, step ->
            when (step) {
                is PlanStep.Select -> {
                    if (context.target(step.label) != null) return "a new selection reuses a snapshot ref"
                    if (!declared.add(step.label)) return "a label declared twice"
                    if (steps.drop(index + 1).none { it.uses(step.label) }) return "a selection nothing uses"
                    current = step.label
                }
                is PlanStep.Adjust -> {
                    val target = step.target
                    if (target is PlanTarget.New && target.label !in declared) return "an undeclared target"
                }
                is PlanStep.Erase, is PlanStep.CutOut, is PlanStep.Fill -> {
                    val label = step.consumed
                    if (label !in declared) return "an undeclared target"
                    if (label != current) return "${step.name} on a selection that is not the current one"
                }
                is PlanStep.Crop, is PlanStep.Expand, is PlanStep.Style, is PlanStep.Topaz -> Unit
            }
        }
        return null
    }

    /**
     * §17: each explicit constraint as the steps it forbids. Classifying the request is the model's.
     * §19: a constraint the model only inferred (`explicit=false`) is its guess at the user's taste;
     * it is shown as such and never blocks a proposal.
     */
    private fun constraints(proposal: PlanProposal): String? {
        val kinds = proposal.explicitConstraints
        return proposal.workflow.firstNotNullOfOrNull { item ->
            val step = item.step
            kinds.firstOrNull { !it.allows(step) }?.let { "${step.name} under $it" }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun ConstraintKind.allows(step: PlanStep): Boolean = when (this) {
        // Outside the selection stays: local steps and a requested crop only. A cut-out deletes
        // the outside, so it is the one local step these refuse.
        ConstraintKind.SubjectOnly, ConstraintKind.KeepBackground ->
            step is PlanStep.Select || step is PlanStep.Crop || step is PlanStep.Erase || step is PlanStep.Fill ||
                (step is PlanStep.Adjust && step.masked)
        ConstraintKind.KeepFrame -> step !is PlanStep.Crop && step !is PlanStep.CutOut
        ConstraintKind.KeepHue -> !step.changesHue && !step.isGenerative
        ConstraintKind.KeepColors -> !step.changesHue && !step.changesSaturation && !step.isGenerative
        ConstraintKind.KeepExactColors -> step is PlanStep.Select || step is PlanStep.Crop
        ConstraintKind.Other -> true
    }

    private fun GoalScope.allows(step: PlanStep): Boolean = when (this) {
        GoalScope.Subject -> step is PlanStep.Select || step.consumesSelection
        GoalScope.Global -> step is PlanStep.Style || step is PlanStep.Crop || step is PlanStep.Expand ||
            step is PlanStep.Topaz || (step is PlanStep.Adjust && !step.masked)
        GoalScope.ColorRange -> step is PlanStep.Adjust && !step.masked && step.kind.hsl != null
    }

    private val PlanProposal.explicitConstraints: Set<ConstraintKind>
        get() = intent.constraints.filter { it.explicit }.map { it.kind }.toSet()

    private val PlanStep.consumesSelection: Boolean
        get() = when (this) {
            is PlanStep.Adjust -> masked
            is PlanStep.Erase, is PlanStep.CutOut, is PlanStep.Fill -> true
            is PlanStep.Select, is PlanStep.Crop, is PlanStep.Expand, is PlanStep.Style, is PlanStep.Topaz -> false
        }

    private val PlanStep.consumed: String?
        get() = when (this) {
            is PlanStep.Erase -> target
            is PlanStep.CutOut -> target
            is PlanStep.Fill -> target
            else -> null
        }

    private fun PlanStep.uses(label: String): Boolean = consumes(label)

    private val PlanStep.isGenerative: Boolean
        get() = this is PlanStep.Erase || this is PlanStep.Fill || this is PlanStep.Expand || this is PlanStep.Topaz

    private val PlanStep.changesHue: Boolean
        get() = this is PlanStep.Style ||
            (this is PlanStep.Adjust && (kind in HUE_KINDS || kind.hsl?.channel == HslChannel.Hue))

    private val PlanStep.changesSaturation: Boolean
        get() = this is PlanStep.Adjust && (kind in SATURATION_KINDS || kind.hsl != null)

    private val PlanStep.name: String get() = this::class.simpleName.orEmpty()

    private val HUE_KINDS = setOf(AdjustKind.Temperature, AdjustKind.Tint)
    private val SATURATION_KINDS = setOf(AdjustKind.Saturation, AdjustKind.Vibrance)

    /** §16: Topaz processes the whole photo and may redraw it; none of these can be kept. */
    private val TOPAZ_FORBIDDEN = setOf(
        ConstraintKind.SubjectOnly,
        ConstraintKind.KeepBackground,
        ConstraintKind.KeepHue,
        ConstraintKind.KeepColors,
        ConstraintKind.KeepExactColors,
    )
}

/**
 * specs/vibe_edit.md §15: the plan's steps replayed on the snapshot's values with the editor's
 * own rules — `withAdjust` replaces one `(kind, target)` setting, a style replaces exactly the
 * values its catalog entry carries at that intensity (`atIntensity`, the function the 스타일
 * sheet and `PlanRunner` use). What it refuses is a setting the plan writes twice, a value a
 * later style throws away, an adjust that repeats or reverses the style before it, and a style
 * that writes nothing. An adjust on a kind the style does not carry is independent; one that goes
 * further in the style's own direction is a deliberate final step.
 *
 * When the snapshot carries no style effects the style's values are unknown, and nothing is
 * claimed about overlap with it — the style's name is never read as its effect.
 */
internal class StateSimulation(private val context: PlanContext) {

    private data class Written(val byStyle: Boolean, val value: Float, val before: Float)

    /** The final `(kind, target key)` values the steps leave, and the first rule they broke. */
    data class Replay(val values: Map<Pair<AdjustKind, String?>, Float>, val violation: String?)

    fun violation(steps: List<PlanStep>): String? = replay(steps).violation

    /**
     * Replays every step to the end — the values are what the editor would hold, which a test
     * compares with `EditDocument.withAdjust` — and records the first violation on the way.
     * A setting a step leaves at 0 is gone, as `withAdjust` removes it.
     */
    fun replay(steps: List<PlanStep>): Replay {
        val run = Run(context.adjustments.associate { (it.kind to it.targetRef) to it.value }.toMutableMap())
        steps.forEach { step ->
            when (step) {
                is PlanStep.Adjust -> run.adjust(step)
                is PlanStep.Style -> context.styles.firstOrNull { it.style == step.style }
                    ?.let { run.style(it.values.atIntensity(step.intensity)) }
                else -> Unit
            }
        }
        return Replay(run.values.filterValues { it != 0f }, run.violation)
    }

    /** One replay's state: the values so far, who wrote each in this plan, the first violation. */
    private inner class Run(val values: MutableMap<Pair<AdjustKind, String?>, Float>) {
        private val written = mutableMapOf<Pair<AdjustKind, String?>, Written>()
        var violation: String? = null
            private set

        fun adjust(step: PlanStep.Adjust) {
            val key = step.kind to step.target?.key
            written[key]?.let { previous -> flag(overlap(previous, step.value)) }
            written[key] = Written(byStyle = false, value = step.value, before = values[key] ?: 0f)
            values[key] = step.value
        }

        fun style(scaled: Map<AdjustKind, Float>) {
            if (scaled.isEmpty()) flag("a style that changes nothing")
            scaled.forEach { (kind, value) ->
                val key = kind to null
                if (written[key]?.byStyle == false) flag("an adjust the style then replaces")
                written[key] = Written(byStyle = true, value = value, before = values[key] ?: 0f)
                values[key] = value
            }
        }

        private fun overlap(previous: Written, value: Float): String? {
            val styleDirection = direction(previous.value - previous.before)
            return when {
                !previous.byStyle -> "the same setting set twice"
                abs(value - previous.value) < SAME -> "an adjust that repeats the style"
                styleDirection != 0 && direction(value - previous.value) != styleDirection ->
                    "an adjust that reverses the style"
                else -> null
            }
        }

        private fun flag(rule: String?) {
            if (violation == null) violation = rule
        }
    }

    private val PlanTarget.key: String
        get() = when (this) {
            is PlanTarget.Existing -> ref
            is PlanTarget.New -> "new:$label"
        }

    private fun direction(delta: Float): Int = when {
        delta > SAME -> 1
        delta < -SAME -> -1
        else -> 0
    }

    private companion object {
        /** Two final settings this close are the same slider position (the sheets show 1/100). */
        const val SAME = 0.01f
    }
}

private fun fail(reason: String): Nothing = throw Unplannable(reason)
