package com.diffuse.feature.editor.tools.crop

import android.graphics.RectF
import com.diffuse.core.imaging.model.EditDocument

/**
 * The crop tool's working state. specs/crop.md keeps the 90° steps and the straighten in
 * one `angleDeg`, so they are held apart here and combined only on the way out.
 */
data class CropState(
    val rect: RectF = RectF(0f, 0f, 1f, 1f),
    val preset: AspectPreset = AspectPreset.Free,
    val quarterTurns: Int = 0,
    val straightenDeg: Float = 0f,
) {

    val angleDeg: Float get() = quarterTurns * QUARTER + straightenDeg

    fun rotated(quarters: Int): CropState =
        copy(quarterTurns = Math.floorMod(quarterTurns + quarters, TURNS_PER_CIRCLE))

    /** Straightening shrinks the rect so it stays inside the rotated image. */
    fun straightened(degrees: Float, canvasAspect: Float): CropState {
        val clamped = degrees.coerceIn(STRAIGHTEN_MIN_DEG, STRAIGHTEN_MAX_DEG)
        return copy(
            straightenDeg = clamped,
            rect = CropGeometry.shrinkToFit(rect, clamped, canvasAspect),
        )
    }

    fun withPreset(preset: AspectPreset, canvasAspect: Float): CropState = copy(
        preset = preset,
        rect = CropGeometry.shrinkToFit(
            CropGeometry.applyPreset(rect, preset, canvasAspect),
            straightenDeg,
            canvasAspect,
        ),
    )

    fun applyTo(document: EditDocument): EditDocument = document.withCrop(rect, angleDeg)

    companion object {
        private const val QUARTER = 90f
        private const val TURNS_PER_CIRCLE = 4
        private const val FULL_TURN = 360f

        /** Re-opening the tool shows the existing crop (specs/crop.md). */
        fun from(document: EditDocument): CropState {
            val crop = document.crop() ?: return CropState()
            return CropState(
                rect = RectF(crop.rect),
                quarterTurns = quarterTurnsOf(crop.angleDeg),
                straightenDeg = crop.angleDeg - quarterTurnsOf(crop.angleDeg) * QUARTER,
            )
        }

        private fun quarterTurnsOf(angleDeg: Float): Int {
            val normalised = ((angleDeg % FULL_TURN) + FULL_TURN) % FULL_TURN
            return (Math.round(normalised / QUARTER)) % TURNS_PER_CIRCLE
        }
    }
}
