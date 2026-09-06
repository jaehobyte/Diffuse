package com.diffuse.feature.editor.tools.select

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.feature.editor.canvas.CanvasTransform
import com.diffuse.feature.editor.canvas.LocalCanvasTransform

/** DESIGN.md §4 State display: the same 60% black the loading overlay uses. */
private const val SCRIM_ALPHA = 0.6f

private val OutlineWidth = 1.dp
private val PointRadius = 4.dp
private val PointStroke = 1.dp

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
) {
    val transform = LocalCanvasTransform.current
    val density = LocalDensity.current
    // Tracing is O(pixels); the mask only changes when a prompt returns.
    val maskPath = remember(mask) { mask?.let(MaskOutline::pathOf) }

    Canvas(modifier = modifier.testTag(SelectionOverlayTestTag).fillMaxSize()) {
        if (mask == null || maskPath == null || transform.imageRect.isEmpty) return@Canvas
        val scaled = maskPath.scaledInto(mask, transform)
        val outlinePx = with(density) { OutlineWidth.toPx() }

        clipPath(scaled, clipOp = ClipOp.Difference) {
            drawRect(
                color = Color.Black.copy(alpha = SCRIM_ALPHA),
                topLeft = transform.imageRect.topLeft,
                size = transform.imageRect.size,
            )
        }
        drawPath(scaled, color = Tokens.accent, style = Stroke(width = outlinePx))
        drawPoints(points, labels, transform, density.density)
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
) {
    val radius = PointRadius.value * densityScale
    val stroke = PointStroke.value * densityScale
    val rect = transform.imageRect
    points.forEachIndexed { index, point ->
        // Points are normalized (specs/selection_tool.md §2), so the image rect is the mapping.
        val centre = Offset(rect.left + point.x * rect.width, rect.top + point.y * rect.height)
        val foreground = labels.getOrElse(index) { true }
        drawCircle(
            color = if (foreground) Tokens.accent else Color.White,
            radius = radius,
            center = centre,
        )
        drawCircle(
            color = Tokens.editHairline,
            radius = radius,
            center = centre,
            style = Stroke(width = stroke),
        )
    }
}
