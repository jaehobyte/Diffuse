package com.diffuse.feature.editor.tools.direct

import android.graphics.Bitmap
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import com.diffuse.feature.editor.tools.underTheAdjustments

/**
 * specs/vibe_edit.md §19. What one proposal session already paid for: the selections its plan
 * found and the generated patches, kept so that turning an item off and on again never calls a
 * server twice for the same input. (The Topaz results are kept by `DirectController`, keyed by
 * the full candidate they were rendered from — a local `Adjust` changes that input, so this key
 * is not theirs.) It belongs to `DirectController`'s current proposal session
 * and is cleared with it; it is never shared with another document — every run of a session starts
 * from the same input document and frame.
 *
 * Written from the runner's IO thread and cleared from the main one, hence the locks.
 */
class PlanCache {

    /** A found selection: its stored mask and the frame-sized alpha the runner holds. */
    data class Selected(val maskId: String, val ref: ImageRef, val mask: Bitmap)

    /**
     * Exactly what a generative step was given: the step (prompt included), the phrase the eraser
     * was told is gone, and the document's operations **without** its adjustments — the frame the
     * provider is shown is rendered from those alone — plus the selection it consumed.
     */
    data class Key(val step: PlanStep, val phrase: String?, val frame: List<Operation>, val activeMaskId: String?) {
        constructor(step: PlanStep, phrase: String?, document: EditDocument) : this(
            step,
            phrase,
            document.operations.filterNot { it is Operation.Adjust },
            document.activeMaskId,
        )
    }

    private val selections = mutableMapOf<PlanStep.Select, Selected>()
    private val generated = mutableMapOf<Key, List<Operation>>()

    /**
     * outpaint.md §3 in a plan: the one `Outpaint` an expand step paid for. Keyed by the step
     * alone — it is always the first step, built from the bare source, so nothing before it can
     * change its input — and reapplied through `withOutpaint`, which keeps it first and re-bases
     * a crop; `reapply`'s append would not.
     */
    private val outpaints = mutableMapOf<PlanStep.Expand, Operation.Outpaint>()

    @Synchronized
    fun selection(step: PlanStep.Select): Selected? = selections[step]

    @Synchronized
    fun putSelection(step: PlanStep.Select, maskId: String, ref: ImageRef, mask: Bitmap) {
        selections[step] = Selected(maskId, ref, mask)
    }

    @Synchronized
    fun generated(key: Key): List<Operation>? = generated[key]

    @Synchronized
    fun putGenerated(key: Key, added: List<Operation>) {
        generated[key] = added
    }

    @Synchronized
    fun outpaint(step: PlanStep.Expand): Operation.Outpaint? = outpaints[step]

    @Synchronized
    fun putOutpaint(step: PlanStep.Expand, outpaint: Operation.Outpaint) {
        outpaints[step] = outpaint
    }

    @Synchronized
    fun clear() {
        selections.clear()
        generated.clear()
        outpaints.clear()
    }

    companion object {
        /** The operations a step added to [before], in the order it added them. */
        fun added(before: EditDocument, after: EditDocument): List<Operation> {
            val ids = before.operations.map { it.id }.toSet()
            return after.operations.filterNot { it.id in ids }
        }

        /**
         * The same document `EraseCommit`/`FillCommit` build from [document] and these files: the
         * reference mask appended, the active selection unchanged, the generated op appended and
         * then moved under the adjustments (`underTheAdjustments`).
         */
        fun reapply(document: EditDocument, added: List<Operation>): EditDocument {
            val (masks, generatedOps) = added.partition { it is Operation.Mask }
            return document.copy(operations = document.operations + masks + generatedOps).underTheAdjustments()
        }
    }
}
