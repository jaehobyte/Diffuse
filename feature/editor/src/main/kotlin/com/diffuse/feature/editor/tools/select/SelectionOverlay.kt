package com.diffuse.feature.editor.tools.select

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.feature.editor.LightSweepColor
import com.diffuse.feature.editor.canvas.CanvasTransform
import com.diffuse.feature.editor.canvas.LocalCanvasTransform

/** DESIGN.md §4 State display: the same 60% black the loading overlay uses. */
private const val SCRIM_ALPHA = 0.6f

private val OutlineWidth = 1.dp
private val PointRadius = 4.dp
private val PointStroke = 1.dp

/*
 * The Galaxy Photo Editor's segmentation reveal, measured from a screen recording of One UI 8.5
 * (SM-S948N) AI 지우개: the tapped object first frosts over — a pale fill rising for about half a
 * second — then a cyan-white band sweeps down through it over about a second and a half, and
 * what it leaves behind is the selection's steady presentation. The band and its cast are the
 * ones AiWorkingMotion.kt already draws for the working overlay.
 */

/** Frost, then sweep: the whole reveal. */
const val MASK_REVEAL_MS = 1_800

/** The share of the reveal the frost takes before the band starts down. */
private const val FROST_SHARE = 0.25f
private const val FROST_ALPHA = 0.55f

/** The band is a quarter of the mask tall, so it reads as a pass and not a flash. */
private const val SWEEP_BAND_SHARE = 0.25f
private const val SWEEP_ALPHA = 0.6f
private const val SWEEP_PEAK_STOP = 0.5f

/** The scrim outside the selection rises with the first mask rather than snapping in. */
private const val SCRIM_FADE_MS = 450

/** While the server segments a tap, the latest point breathes: one ring out per cycle. */
private const val PULSE_MS = 900
private const val PULSE_REACH = 3f

const val SelectionOverlayTestTag = "SelectionOverlay"

/**
 * specs/selection_tool.md §5. Everything outside the selection is darkened, its edge carries a
 * 1dp accent outline, and each point shows where the user tapped.
 *
 * The scrim and the outline are the same path — one clipped away, one stroked — so they can
 * never drift apart.
 */
@Composable
fun SelectionOverlay(
    mask: Bitmap?,
    points: List<PointF>,
    labels: List<Boolean>,
    modifier: Modifier = Modifier,
    /** Play the reveal each time a new mask lands; off, the mask simply appears. */
    reveal: Boolean = false,
    /** A prompt is in flight for the latest point, which pulses until the answer lands. */
    busy: Boolean = false,
) {
    val transform = LocalCanvasTransform.current
    val density = LocalDensity.current
    // Tracing is O(pixels); the mask only changes when a prompt returns.
    val maskPath = remember(mask) { mask?.let(MaskOutline::pathOf) }
    val revealProgress = rememberRevealProgress(mask, reveal)
    val scrimAlpha by animateFloatAsState(
        targetValue = if (mask != null) SCRIM_ALPHA else 0f,
        animationSpec = tween(SCRIM_FADE_MS),
        label = "selectionScrim",
    )
    val pulse = rememberPulsePhase(busy)

    Canvas(modifier = modifier.testTag(SelectionOverlayTestTag).fillMaxSize()) {
        if (transform.imageRect.isEmpty) return@Canvas
        if (mask != null && maskPath != null) {
            val scaled = maskPath.scaledInto(mask, transform)
            drawScrim(scaled, transform.imageRect, scrimAlpha)
            drawReveal(scaled, revealProgress, with(density) { OutlineWidth.toPx() })
        }
        drawPoints(points, labels, transform, density.density, pulse)
    }
}

/** 0 at the moment a new mask lands, 1 once the reveal is over; 1 outright when not revealing. */
@Composable
private fun rememberRevealProgress(mask: Bitmap?, reveal: Boolean): Float {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(mask, reveal) {
        if (reveal && mask != null) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(MASK_REVEAL_MS, easing = LinearEasing))
        } else {
            progress.snapTo(1f)
        }
    }
    return progress.value
}

@Composable
private fun rememberPulsePhase(busy: Boolean): Float {
    if (!busy) return 0f
    val transition = rememberInfiniteTransition(label = "pointPulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing)),
        label = "pointPulsePhase",
    )
    return phase
}

private fun DrawScope.drawScrim(path: Path, imageRect: Rect, alpha: Float) {
    if (alpha <= 0f) return
    clipPath(path, clipOp = ClipOp.Difference) {
        drawRect(color = Color.Black.copy(alpha = alpha), topLeft = imageRect.topLeft, size = imageRect.size)
    }
}

/**
 * The reveal at [progress]: the frost fills the selection first, then the band's centre line
 * travels from above the selection's top edge to below its bottom one, frost below the line,
 * the finished outline above it.
 */
private fun DrawScope.drawReveal(path: Path, progress: Float, outlinePx: Float) {
    val outline = Stroke(width = outlinePx)
    if (progress >= 1f) {
        drawPath(path, color = Tokens.accent, style = outline)
        return
    }
    val bounds = path.getBounds()
    val frost = (progress / FROST_SHARE).coerceIn(0f, 1f)
    val sweep = ((progress - FROST_SHARE) / (1f - FROST_SHARE)).coerceIn(0f, 1f)
    val bandHeight = bounds.height * SWEEP_BAND_SHARE
    val line = bounds.top - bandHeight / 2 + sweep * (bounds.height + bandHeight)
    clipPath(path) {
        drawRect(
            color = Color.White.copy(alpha = FROST_ALPHA * frost),
            topLeft = Offset(bounds.left, line),
            size = Size(bounds.width, (bounds.bottom - line).coerceAtLeast(0f)),
        )
        if (sweep > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    SWEEP_PEAK_STOP to LightSweepColor.copy(alpha = SWEEP_ALPHA),
                    1f to Color.Transparent,
                    startY = line - bandHeight / 2,
                    endY = line + bandHeight / 2,
                ),
                topLeft = Offset(bounds.left, line - bandHeight / 2),
                size = Size(bounds.width, bandHeight),
            )
        }
    }
    if (sweep > 0f) {
        clipRect(bottom = line) { drawPath(path, color = Tokens.accent, style = outline) }
    }
}

/**
 * The path is traced in image pixels; this puts it on screen. Stroking after the transform
 * rather than inside it keeps the outline 1dp wide at any zoom (specs/selection_tool.md §5).
 */
private fun Path.scaledInto(mask: Bitmap, transform: CanvasTransform): Path {
    val rect = transform.imageRect
    val matrix = Matrix().apply {
        translate(rect.left, rect.top)
        scale(rect.width / mask.width, rect.height / mask.height)
    }
    return Path().also { it.addPath(this) }.apply { transform(matrix) }
}

private fun DrawScope.drawPoints(
    points: List<PointF>,
    labels: List<Boolean>,
    transform: CanvasTransform,
    densityScale: Float,
    pulse: Float,
) {
    val radius = PointRadius.value * densityScale
    val stroke = PointStroke.value * densityScale
    val rect = transform.imageRect
    points.forEachIndexed { index, point ->
        // Points are normalized (specs/selection_tool.md §2), so the image rect is the mapping.
        val centre = Offset(rect.left + point.x * rect.width, rect.top + point.y * rect.height)
        val foreground = labels.getOrElse(index) { true }
        val fill = if (foreground) Tokens.accent else Color.White
        if (pulse > 0f && index == points.lastIndex) {
            drawCircle(
                color = fill.copy(alpha = 1f - pulse),
                radius = radius * (1f + (PULSE_REACH - 1f) * pulse),
                center = centre,
                style = Stroke(width = stroke),
            )
        }
        drawCircle(color = fill, radius = radius, center = centre)
        // A dark rim, so the white background point still reads over a bright photo.
        drawCircle(
            color = Tokens.editBackground,
            radius = radius,
            center = centre,
            style = Stroke(width = stroke),
        )
    }
}
