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
 * specs/selection_tool.md §3, as it stands without the sheet: the session and the one point run
 * in progress. The mask a run produces is committed to the document the moment it lands
 * (`EditorViewModel.selectOnCanvas`), so nothing here waits for an Apply.
 */
data class SelectionState(
    val session: SegSession? = null,
    /** The current run's result: `ALPHA_8` at the preview's size, strictly binary. */
    val mask: Bitmap? = null,
    val points: List<PointF> = emptyList(),
    val labels: List<Boolean> = emptyList(),
    /** The upload the first tap started is on the wire. */
    val preparing: Boolean = false,
    /** A tap taken before the session was ready, segmented the moment it is. */
    val pending: PendingPoint? = null,
    /** A point prompt is in flight; the previous mask stays on screen until it lands (§5). */
    val busy: Boolean = false,
    val availability: Availability = Availability.Unavailable(AppError.Unavailable),
    /** specs/segmentation.md §6: the only way out of an unconfigured provider. */
    val showSettings: Boolean = false,
    val config: Sam3Config = Sam3Config("", ""),
    /**
     * specs/generative_erase.md §8: the same sheet carries the Gemini key. It is held here
     * rather than in `EraseState` because this is the state the sheet renders from, and there
     * is only one sheet.
     */
    val geminiApiKey: String = "",
    /** specs/topaz_enhancement.md §4: shown masked in the same sheet. */
    val topazApiKey: String = "",
    /** One-shot snackbar text, cleared once shown. DESIGN.md §4 forbids toasts. */
    @StringRes val message: Int? = null,
) {

    val hasMask: Boolean get() = mask != null

    val enabled: Boolean get() = availability is Availability.Ready

    /** DESIGN.md §7: AI work the user is waiting on always shows progress and a cancel button. */
    val working: Boolean get() = preparing

    val prompt: PointPrompt? get() = if (points.isEmpty()) null else PointPrompt(points, labels)

    fun withPoint(point: PointF, foreground: Boolean): SelectionState =
        copy(points = points + point, labels = labels + foreground)

    /** The run ends; the session stays so the next tap is instant. */
    fun cleared(): SelectionState = copy(mask = null, points = emptyList(), labels = emptyList())
}

/** A point tapped on the photo before the session opened, kept until it has. */
data class PendingPoint(val x: Float, val y: Float, val foreground: Boolean)
