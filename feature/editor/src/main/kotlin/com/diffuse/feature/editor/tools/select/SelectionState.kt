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
    /** `ALPHA_8` at the preview's size. Null means nothing is selected yet. */
    val mask: Bitmap? = null,
    val points: List<PointF> = emptyList(),
    val labels: List<Boolean> = emptyList(),
    val inverted: Boolean = false,
    val lowConfidence: Boolean = false,
    val preparing: Boolean = false,
    val busy: Boolean = false,
    val availability: Availability = Availability.Unavailable(AppError.Unavailable),
    /** specs/segmentation.md §6: the only way out of an unconfigured provider. */
    val showSettings: Boolean = false,
    val config: Sam3Config = Sam3Config("", ""),
    /** One-shot snackbar text, cleared once shown. DESIGN.md §4 forbids toasts. */
    @StringRes val message: Int? = null,
) {

    val hasMask: Boolean get() = mask != null

    val enabled: Boolean get() = availability is Availability.Ready

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

    /** 지우기: the selection goes, the session stays so re-selecting is instant. */
    fun cleared(): SelectionState =
        copy(mask = null, points = emptyList(), labels = emptyList(), inverted = false, lowConfidence = false)

    companion object {
        /** specs/selection_tool.md §7: below this the user is nudged to add more points. */
        const val LOW_CONFIDENCE_SCORE = 0.3f
    }
}
