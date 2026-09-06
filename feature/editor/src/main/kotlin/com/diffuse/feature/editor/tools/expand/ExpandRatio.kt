package com.diffuse.feature.editor.tools.expand

import com.diffuse.core.imaging.model.Margins
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * specs/outpaint.md §6's readout: "비율 4:3 → 9:16". The point of 확대 is a ratio the user is
 * aiming at (§1), so the sheet has to name the one they are leaving and the one they are getting.
 *
 * Reduced by gcd, a 4000×3000 source gives 4:3 and almost anything else gives its own pixel
 * counts back. So this takes the **nearest** ratio whose denominator fits [MAX_TERM] instead,
 * which is what makes 9:16 read as 9:16 rather than as 2251:4000.
 */
internal const val MAX_TERM = 20

/** The aspect after [margins] are added to a [width] × [height] photo. */
fun expandedAspect(width: Float, height: Float, margins: Margins): Float {
    val expandedWidth = width * (1f + margins.left + margins.right)
    val expandedHeight = height * (1f + margins.top + margins.bottom)
    return if (expandedHeight <= 0f) 1f else expandedWidth / expandedHeight
}

/** [aspect] as `w:h`, to the nearest ratio with terms small enough for a person to read. */
fun ratioText(aspect: Float): String {
    if (aspect <= 0f || !aspect.isFinite()) return "1:1"
    var bestNumerator = 1
    var bestDenominator = 1
    var bestError = Float.MAX_VALUE
    for (denominator in 1..MAX_TERM) {
        val numerator = (aspect * denominator).roundToInt().coerceAtLeast(1)
        val error = abs(numerator.toFloat() / denominator - aspect)
        // Strictly better, so the smallest terms that achieve the best fit are the ones kept.
        if (error < bestError) {
            bestError = error
            bestNumerator = numerator
            bestDenominator = denominator
        }
    }
    return "$bestNumerator:$bestDenominator"
}
