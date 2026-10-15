package com.diffuse.feature.editor

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.theme.Tokens

/*
 * The motion of the Galaxy Photo Editor's AI "지우기" progress, measured frame by frame from
 * a screen recording of One UI 8.5 (SM-S948N): a pale light band sweeps down the whole
 * screen about every 3.1 s and lifts the scrim to roughly a quarter where it passes; a
 * three-star sparkle twinkles above the status line; when the result lands the scrim drops
 * in ~0.3 s and one brighter band sweeps the result once.
 */

/** One sweep of the light band, start to start, while the model is working. */
const val LIGHT_SWEEP_PERIOD_MS = 3100

/** The band travels for this share of the period and rests for the remainder. */
private const val LIGHT_SWEEP_TRAVEL = 0.7f

/** The band is taller than the screen, so its edges stay soft at every point of the pass. */
private const val LIGHT_SWEEP_BAND_HEIGHT = 1.2f

/** The lift at the band's centre over the 40% scrim; the measured peak was ~0.26. */
private const val LIGHT_SWEEP_ALPHA = 0.28f

/** The one-shot pass over the finished result is faster and brighter than the working band. */
const val RESULT_SWEEP_DURATION_MS = 650
private const val RESULT_SWEEP_ALPHA = 0.45f

/** The band peaks at its centre line and fades to nothing at both edges. */
private const val LIGHT_SWEEP_PEAK_STOP = 0.5f

/** The band's cast: white pushed toward cyan, which also tints the sparkle as it passes. */
internal val LightSweepColor = Color(0xFFD9FBFF)

/**
 * Draws the light band at [progress] (0 = fully above the top edge, 1 = fully below the
 * bottom edge). [peakAlpha] is the band's opacity at its centre line.
 */
fun DrawScope.drawLightSweep(progress: Float, peakAlpha: Float = LIGHT_SWEEP_ALPHA) {
    if (progress <= 0f || progress >= 1f) return
    val bandHeight = size.height * LIGHT_SWEEP_BAND_HEIGHT
    val top = -bandHeight + progress * (size.height + bandHeight)
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            LIGHT_SWEEP_PEAK_STOP to LightSweepColor.copy(alpha = peakAlpha),
            1f to Color.Transparent,
            startY = top,
            endY = top + bandHeight,
        ),
    )
}

/** The working band's position for an infinite 0..1 phase: travel, then rest off-screen. */
fun lightSweepProgress(phase: Float): Float = (phase / LIGHT_SWEEP_TRAVEL).coerceIn(0f, 1f)

/** The one brighter pass over the finished result, at [fraction] of its travel. */
fun DrawScope.drawResultSweep(fraction: Float) = drawLightSweep(fraction, RESULT_SWEEP_ALPHA)

@Composable
fun rememberLightSweepPhase(): Float {
    val transition = rememberInfiniteTransition(label = "lightSweep")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(LIGHT_SWEEP_PERIOD_MS, easing = LinearEasing)),
        label = "lightSweepPhase",
    )
    return phase
}

private val SparkleSize = 40.dp
private const val SPARKLE_MIN_SCALE = 0.55f

/** Each star twinkles on its own clock, so the cluster never pulses as one. */
private data class Star(val cx: Float, val cy: Float, val radius: Float, val periodMs: Int, val offsetMs: Int)

private val Stars = listOf(
    Star(cx = 0.40f, cy = 0.58f, radius = 0.40f, periodMs = 1100, offsetMs = 0),
    Star(cx = 0.80f, cy = 0.22f, radius = 0.18f, periodMs = 900, offsetMs = 300),
    Star(cx = 0.85f, cy = 0.80f, radius = 0.12f, periodMs = 800, offsetMs = 600),
)

/**
 * The Photo Editor's three four-point stars: one large, two small, each scaling between
 * [SPARKLE_MIN_SCALE] and full about its own centre.
 */
@Composable
fun SparkleIndicator(modifier: Modifier = Modifier, color: Color = Tokens.canvasOverlayInk) {
    val transition = rememberInfiniteTransition(label = "sparkle")
    val scales = Stars.map { star ->
        transition.animateFloat(
            initialValue = SPARKLE_MIN_SCALE,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(star.periodMs, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(star.offsetMs),
            ),
            label = "star",
        )
    }
    Canvas(modifier = modifier.size(SparkleSize)) {
        Stars.forEachIndexed { index, star ->
            val scale by scales[index]
            drawStar(
                center = Offset(star.cx * size.width, star.cy * size.height),
                radius = star.radius * size.minDimension * scale,
                color = color.copy(alpha = SPARKLE_MIN_SCALE + (1f - SPARKLE_MIN_SCALE) * scale),
            )
        }
    }
}

/** A four-point star: four quadratic arcs bent toward the centre. */
private fun DrawScope.drawStar(center: Offset, radius: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x, center.y, center.x + radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y + radius)
        quadraticTo(center.x, center.y, center.x - radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y - radius)
        close()
    }
    drawPath(path, color)
}
