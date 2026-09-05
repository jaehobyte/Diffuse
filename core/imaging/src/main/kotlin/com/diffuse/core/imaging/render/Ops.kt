package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.Operation

/**
 * specs/architecture.md §5.2: each [AdjustKind] maps to exactly one entry here, so adding
 * an adjustment is one function plus one tool definition and no switch statements
 * elsewhere. Keeping the maths in one place is also what makes the GPU port (D03)
 * a replacement of this file rather than a rewrite.
 */
interface OpRegistry {
    fun adjust(kind: AdjustKind): (Bitmap, Float) -> Bitmap
    fun crop(bitmap: Bitmap, operation: Operation.Crop): Bitmap
}

/**
 * The v1 CPU registry. The pixel maths arrives with the tasks that own each group and
 * that carry the golden tests proving it; until then an entry returns its input
 * unchanged, so the pipeline is exercised without pretending an adjustment happened.
 */
object Ops : OpRegistry {

    private val identity: (Bitmap, Float) -> Bitmap = { bitmap, _ -> bitmap }

    override fun adjust(kind: AdjustKind): (Bitmap, Float) -> Bitmap = when (kind) {
        // specs/adjust_light.md
        AdjustKind.Exposure -> LightOps::exposure
        AdjustKind.Contrast -> LightOps::contrast
        AdjustKind.Highlights -> LightOps::highlights
        AdjustKind.Shadows -> LightOps::shadows

        // specs/adjust_color.md
        AdjustKind.Temperature -> ColorOps::temperature
        AdjustKind.Tint -> ColorOps::tint
        AdjustKind.Saturation -> ColorOps::saturation
        AdjustKind.Vibrance -> ColorOps::vibrance

        // T16 (specs/adjust_detail.md)
        AdjustKind.Sharpen,
        AdjustKind.Vignette,
        -> identity
    }

    override fun crop(bitmap: Bitmap, operation: Operation.Crop): Bitmap =
        CropOp.apply(bitmap, operation)
}
