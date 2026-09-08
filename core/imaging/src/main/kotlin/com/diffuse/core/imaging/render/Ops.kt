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

/** The v1 CPU registry: one entry per [AdjustKind], plus the crop. */
object Ops : OpRegistry {

    /**
     * specs/adjust_hsl.md §3: the 24 혼합 kinds are dispatched by their target in one branch,
     * so adding a band or a channel adds no entry here.
     */
    override fun adjust(kind: AdjustKind): (Bitmap, Float) -> Bitmap {
        val hsl = kind.hsl
        if (hsl != null) return { bitmap, value -> HslOps.apply(hsl, bitmap, value) }
        return globalAdjust(kind)
    }

    /**
     * One branch per kind, split by the spec that owns it — T71 took the single `when` past
     * detekt's complexity ceiling, and a dispatch table is exactly the shape that metric
     * misjudges. Splitting it by family costs three null checks per adjust and reads as the
     * three specs it actually is.
     */
    private fun globalAdjust(kind: AdjustKind): (Bitmap, Float) -> Bitmap =
        lightAdjust(kind)
            ?: colorAdjust(kind)
            ?: detailAdjust(kind)
            ?: error("$kind carries an HslTarget and is handled by HslOps")

    /** specs/adjust_light.md, and T71's four tone controls. */
    private fun lightAdjust(kind: AdjustKind): ((Bitmap, Float) -> Bitmap)? = when (kind) {
        AdjustKind.Exposure -> LightOps::exposure
        AdjustKind.Contrast -> LightOps::contrast
        AdjustKind.Highlights -> LightOps::highlights
        AdjustKind.Shadows -> LightOps::shadows
        AdjustKind.Blacks -> LightOps::blacks
        AdjustKind.Whites -> LightOps::whites
        AdjustKind.Fade -> LightOps::fade
        AdjustKind.SCurve -> LightOps::sCurve
        else -> null
    }

    /** specs/adjust_color.md */
    private fun colorAdjust(kind: AdjustKind): ((Bitmap, Float) -> Bitmap)? = when (kind) {
        AdjustKind.Temperature -> ColorOps::temperature
        AdjustKind.Tint -> ColorOps::tint
        AdjustKind.Saturation -> ColorOps::saturation
        AdjustKind.Vibrance -> ColorOps::vibrance
        else -> null
    }

    /** specs/adjust_detail.md, and T71's [DetailOps.clarity]. */
    private fun detailAdjust(kind: AdjustKind): ((Bitmap, Float) -> Bitmap)? = when (kind) {
        AdjustKind.Sharpen -> DetailOps::sharpen
        AdjustKind.Vignette -> DetailOps::vignette
        AdjustKind.Clarity -> DetailOps::clarity
        else -> null
    }

    override fun crop(bitmap: Bitmap, operation: Operation.Crop): Bitmap =
        CropOp.apply(bitmap, operation)
}
