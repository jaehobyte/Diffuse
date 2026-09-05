package com.diffuse.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** DESIGN.md §4 (Slider). */
private val TrackHeight = 4.dp
private val ThumbDiameter = 20.dp
private val ThumbStroke = 1.dp
private val CentreTickWidth = 2.dp
private val TouchTargetHeight = 48.dp
private val ValueColumnWidth = 64.dp

/** DESIGN.md §4 and specs/edit_model.md: 0 is neutral for every kind. */
private const val DEFAULT_VALUE = 0f

/** specs/adjust_light.md: sliders read as −100…100 rather than −1…1. */
private const val PERCENT_SCALE = 100f

const val AdjustSliderTestTag = "AdjustSlider"

/**
 * DESIGN.md §4: 4dp track, 20dp white thumb with a 1dp hairline stroke, the value pinned
 * to the right in `mono` rather than floating over the thumb, and a 2dp centre tick when
 * the adjustment is zero-centred. Double-tap returns to the neutral value.
 */
@Composable
fun AdjustSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    zeroCentered: Boolean,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * How the pinned value reads. The tools show −100…100 (specs/adjust_light.md); the
     * default keeps the raw range readable for anything that has no scale of its own.
     */
    format: (Float) -> String = { defaultFormat(it, zeroCentered) },
) {
    val colors = LocalAppColors.current
    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { ThumbDiameter.toPx() / 2f }

    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    val currentValue by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onChangeFinished)

    fun valueAt(x: Float): Float {
        val usable = (trackWidthPx - 2f * thumbRadiusPx).coerceAtLeast(1f)
        val fraction = ((x - thumbRadiusPx) / usable).coerceIn(0f, 1f)
        return range.start + fraction * (range.endInclusive - range.start)
    }

    Row(
        modifier = modifier
            .testTag(AdjustSliderTestTag)
            .fillMaxWidth()
            .height(TouchTargetHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(TouchTargetHeight)
                .sliderGestures(
                    onReset = {
                        change(DEFAULT_VALUE.coerceIn(range))
                        finished()
                    },
                    onDragTo = { x -> change(valueAt(x)) },
                    onFinished = { finished() },
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(TouchTargetHeight)) {
                trackWidthPx = size.width
                drawSlider(
                    fraction = fractionOf(currentValue, range),
                    zeroCentered = zeroCentered,
                    sliderColors = SliderColors(
                        track = colors.surfaceRaised,
                        tick = colors.inkSecondary,
                        stroke = colors.hairline,
                    ),
                )
            }
        }
        Text(
            text = format(value),
            style = Typography.mono,
            color = colors.inkSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = ValueColumnWidth),
        )
    }
}

@Composable
private fun Modifier.sliderGestures(
    onReset: () -> Unit,
    onDragTo: (Float) -> Unit,
    onFinished: () -> Unit,
): Modifier = this
    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReset() }) }
    .pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragEnd = onFinished,
            onDragCancel = onFinished,
        ) { pointer, _ -> onDragTo(pointer.position.x) }
    }

/** specs/adjust_light.md and friends: sliders read as whole numbers, signed when centred. */
fun percentFormat(zeroCentered: Boolean): (Float) -> String = { value ->
    val scaled = (value * PERCENT_SCALE).roundToInt()
    when {
        !zeroCentered -> "$scaled"
        scaled > 0 -> "+$scaled"
        else -> "$scaled"
    }
}

private fun fractionOf(value: Float, range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    return if (span <= 0f) 0f else ((value - range.start) / span).coerceIn(0f, 1f)
}

private fun defaultFormat(value: Float, zeroCentered: Boolean): String {
    val text = String.format(Locale.US, "%.2f", abs(value))
    return when {
        !zeroCentered -> text
        value > 0f -> "+$text"
        value < 0f -> "-$text"
        else -> " $text"
    }
}

/** DrawScope is itself a Density, so the dp values convert without one being passed in. */
private data class SliderColors(val track: Color, val tick: Color, val stroke: Color)

private fun DrawScope.drawSlider(
    fraction: Float,
    zeroCentered: Boolean,
    sliderColors: SliderColors,
) {
    val trackHeightPx = TrackHeight.toPx()
    val thumbRadiusPx = ThumbDiameter.toPx() / 2f
    val tickWidthPx = CentreTickWidth.toPx()
    val strokePx = ThumbStroke.toPx()
    val centreY = size.height / 2f

    drawRoundRect(
        color = sliderColors.track,
        topLeft = Offset(0f, centreY - trackHeightPx / 2f),
        size = Size(size.width, trackHeightPx),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeightPx / 2f),
    )

    if (zeroCentered) {
        drawRect(
            color = sliderColors.tick,
            topLeft = Offset(size.width / 2f - tickWidthPx / 2f, centreY - trackHeightPx / 2f),
            size = Size(tickWidthPx, trackHeightPx),
        )
    }

    val thumbX = thumbRadiusPx + fraction * (size.width - 2f * thumbRadiusPx)
    drawCircle(color = Color.White, radius = thumbRadiusPx, center = Offset(thumbX, centreY))
    drawCircle(
        color = sliderColors.stroke,
        radius = thumbRadiusPx - strokePx / 2f,
        center = Offset(thumbX, centreY),
        style = Stroke(width = strokePx),
    )
}
