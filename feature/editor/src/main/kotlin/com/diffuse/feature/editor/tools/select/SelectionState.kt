package com.diffuse.feature.editor.tools.select

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.annotation.StringRes
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.PointPrompt
import com.diffuse.core.ai.SegSession
import com.diffuse.core.ai.sam3.Sam3Config
import com.diffuse.core.common.AppError

/**
 * specs/selection_tool.md §3. Sheet state, not document state: nothing here reaches the
 * `EditDocument` until Apply.
 */
data class SelectionState(
    val session: SegSession? = null,
    /**
     * What is selected right now: [base] merged with the current run's result. `ALPHA_8` at the
     * preview's size, strictly binary. Null means nothing is selected yet.
     */
    val mask: Bitmap? = null,
    /** Everything merged before the current run. specs/selection_tool.md §4. */
    val base: Bitmap? = null,
    val mode: MergeMode = MergeMode.Add,
    /** Snapshots of [base], one per completed merge, so Undo takes back exactly one. */
    val merges: List<Bitmap?> = emptyList(),
    val points: List<PointF> = emptyList(),
    val labels: List<Boolean> = emptyList(),
    val inverted: Boolean = false,
    val lowConfidence: Boolean = false,
    val preparing: Boolean = false,
    val busy: Boolean = false,
    /** specs/prompt_input.md §4: what is typed or dictated, until it is submitted. */
    val phrase: String = "",
    /** A text prompt is in flight; unlike a point prompt it earns the progress overlay. */
    val phraseBusy: Boolean = false,
    /** The last phrase matched nothing. An answer, not an error (selection_tool.md §7). */
    val notFound: Boolean = false,
    val availability: Availability = Availability.Unavailable(AppError.Unavailable),
    /** specs/segmentation.md §6: the only way out of an unconfigured provider. */
    val showSettings: Boolean = false,
    val config: Sam3Config = Sam3Config("", ""),
    /** One-shot snackbar text, cleared once shown. DESIGN.md §4 forbids toasts. */
    @StringRes val message: Int? = null,
) {

    val hasMask: Boolean get() = mask != null

    val enabled: Boolean get() = availability is Availability.Ready

    /** DESIGN.md §7: AI work always shows progress and a cancel button. */
    val working: Boolean get() = preparing || phraseBusy

    val prompt: PointPrompt? get() = if (points.isEmpty()) null else PointPrompt(points, labels)

    fun withPoint(point: PointF, foreground: Boolean): SelectionState =
        copy(points = points + point, labels = labels + foreground)

    /** Drops the last point. specs/selection_tool.md §4: undo removes one step, not the lot. */
    fun withoutLastPoint(): SelectionState =
        if (points.isEmpty()) {
            this
        } else {
            copy(points = points.dropLast(1), labels = labels.dropLast(1))
        }

    /**
     * Ends the current run: what is on screen becomes the new [base], and the run's points go.
     * specs/selection_tool.md §4 does this on a mode switch and after a text prompt, so the two
     * mechanisms never fight over the same tap.
     */
    fun committingRun(): SelectionState = copy(
        base = mask,
        merges = merges + base,
        points = emptyList(),
        labels = emptyList(),
    )

    /** Takes back one merge. Null when there is nothing left to undo. */
    fun withoutLastMerge(): SelectionState? =
        if (merges.isEmpty()) {
            null
        } else {
            copy(base = merges.last(), mask = merges.last(), merges = merges.dropLast(1))
        }

    /** 지우기: the selection goes, the session stays so re-selecting is instant. */
    fun cleared(): SelectionState = copy(
        mask = null,
        base = null,
        merges = emptyList(),
        points = emptyList(),
        labels = emptyList(),
        inverted = false,
        lowConfidence = false,
        notFound = false,
    )

    companion object {
        /** specs/selection_tool.md §7: below this the user is nudged to add more points. */
        const val LOW_CONFIDENCE_SCORE = 0.3f
    }
}
