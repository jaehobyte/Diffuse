package com.diffuse.core.ai

import com.diffuse.core.ai.gemini.ProposalCheck
import com.diffuse.core.ai.topaz.TopazFailure
import com.diffuse.core.ai.topaz.TopazJob
import com.diffuse.core.ai.topaz.TopazOptions

/**
 * specs/vibe_edit.md §19. One effect the user can turn on or off: every step but a `Select`.
 * A selection is not an effect — it is what an item [needs], and it runs exactly when one of the
 * items that consume it is on. [id] is the step's own id, so moving a row or rewording it never
 * moves the user's choice onto another row.
 */
data class PlanItem(
    val id: String,
    val item: WorkflowStep,
    /** The `Select` steps this item consumes, in workflow order. */
    val needs: List<WorkflowStep> = emptyList(),
) {
    val step: PlanStep get() = item.step
}

/** The proposal's steps with every id filled in: the decoder's, else the position (§19). */
private val PlanProposal.identified: List<WorkflowStep>
    get() = workflow.mapIndexed { index, step -> if (step.id.isEmpty()) step.copy(id = "s${index + 1}") else step }

/** §19: the toggleable items of a proposal, in workflow order. */
val PlanProposal.items: List<PlanItem>
    get() {
        val steps = identified
        val selects = steps.filter { it.step is PlanStep.Select }
        return steps.filterNot { it.step is PlanStep.Select }.map { item ->
            PlanItem(item.id, item, selects.filter { item.step.consumes((it.step as PlanStep.Select).label) })
        }
    }

/**
 * §19: the proposal the user's choice leaves — the chosen items and the selections they need, in
 * the original order, every id kept. An item that is not chosen is gone from it: it is not run,
 * rendered or committed. The original proposal is never changed; the choice is the caller's.
 */
fun PlanProposal.selected(chosen: Set<String>): PlanProposal {
    val kept = items.filter { it.id in chosen }
    val ids = kept.map { it.id }.toSet() + kept.flatMap { item -> item.needs.map { it.id } }
    return copy(workflow = identified.filter { it.id in ids })
}

/**
 * §19: the rules the original proposal passed, run again on [selected] — targets declared before
 * use, erase/cut-out/fill on the selection right before them, the user's explicit constraints and
 * the state simulation. Null when the choice can run. Turning items off never makes a structurally
 * valid proposal invalid by these rules; the check is here so that claim is tested, not assumed.
 */
fun PlanProposal.selectionProblem(context: PlanContext = PlanContext()): String? =
    ProposalCheck.selectionViolation(this, context)

/**
 * §16, §19: why [step] cannot be handed to the Topaz sheet for an input described by
 * [capability], or null. A missing key is the user's to fix in settings; the rest is
 * `TopazJob.problem()` with the real size when it is known, for the options the sheet will
 * actually open with — a purpose's defaults replace options it does not offer.
 */
fun topazBlock(step: PlanStep.Topaz, capability: TopazCapability?): TopazBlock? {
    val offered = step.options.takeIf { TopazJob(step.purpose, it, 1, 1).problem() == null }
    val job = TopazJob(
        purpose = step.purpose,
        options = offered ?: TopazOptions.defaultsFor(step.purpose),
        inputWidth = capability?.inputWidth ?: 1,
        inputHeight = capability?.inputHeight ?: 1,
        inputHasAlpha = capability?.hasAlpha == true,
    )
    return when {
        capability != null && !capability.configured -> TopazBlock.NoKey
        else -> job.problem()?.let { TopazBlock.Unavailable(it) }
    }
}

/** §19: what keeps a Topaz item from being handed off, shown on its row. */
sealed interface TopazBlock {
    data object NoKey : TopazBlock

    data class Unavailable(val failure: TopazFailure) : TopazBlock
}

/** Whether [this] acts on the selection a `Select` declared as [label]. */
internal fun PlanStep.consumes(label: String): Boolean = when (this) {
    is PlanStep.Adjust -> (target as? PlanTarget.New)?.label == label
    is PlanStep.Erase -> target == label
    is PlanStep.CutOut -> target == label
    is PlanStep.Fill -> target == label
    is PlanStep.Select, is PlanStep.Crop, is PlanStep.Style, is PlanStep.Topaz -> false
}
