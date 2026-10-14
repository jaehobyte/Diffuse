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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.core.ui.theme.Typography
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** DESIGN.md §4 (Slider): the Photo Editor's ruler — ticks, no track, no thumb. */
private const val TICK_COUNT = 41
private const val MAJOR_EVERY = 5
private val TickWidth = 2.dp
private val MinorTickHeight = 8.dp
private val MajorTickHeight = 12.dp
private val CurrentTickHeight = 20.dp
private val ZeroDotDiameter = 4.dp
private val ZeroDotGap = 6.dp
private val EndInset = 8.dp
private val TouchTargetHeight = 48.dp
private val ValueColumnWidth = 64.dp

/** DESIGN.md §4 and specs/edit_model.md: 0 is neutral for every kind. */
private const val DEFAULT_VALUE = 0f

/** specs/adjust_light.md: sliders read as −100…100 rather than −1…1. */
private const val PERCENT_SCALE = 100f

const val AdjustSliderTestTag = "AdjustSlider"

/**
 * DESIGN.md §4: 41 ticks across the width, every fifth one taller and brighter, the current
 * value a taller `accent` tick, and a dot above the centre when the adjustment is zero-centred.
 * The value is pinned to the right in `mono` unless the host shows it elsewhere ([showValue]).
 * Double-tap returns to the neutral value. A drag reads the finger's absolute position.
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
    /** DESIGN.md §4 Adjust sheet: the selected circle already shows the number, so it hides this. */
    showValue: Boolean = true,
) {
    val colors = LocalAppColors.current
    val density = LocalDensity.current
    val insetPx = with(density) { EndInset.toPx() }

    var rulerWidthPx by remember { mutableFloatStateOf(0f) }
    val currentValue by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onChangeFinished)

    fun valueAt(x: Float): Float {
        val usable = (rulerWidthPx - 2f * insetPx).coerceAtLeast(1f)
        val fraction = ((x - insetPx) / usable).coerceIn(0f, 1f)
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
                rulerWidthPx = size.width
                drawRuler(
                    fraction = fractionOf(currentValue, range),
                    zeroCentered = zeroCentered,
                    rulerColors = RulerColors(
                        major = Tokens.sliderTickMajor,
                        minor = Tokens.sliderTickMinor,
                        current = colors.accent,
                    ),
                )
            }
        }
        if (showValue) {
            Text(
                text = format(value),
                style = Typography.mono,
                color = colors.inkSecondary,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = ValueColumnWidth),
            )
        }
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
private data class RulerColors(val major: Color, val minor: Color, val current: Color)

private fun DrawScope.drawRuler(
    fraction: Float,
    zeroCentered: Boolean,
    rulerColors: RulerColors,
) {
    val tickWidthPx = TickWidth.toPx()
    val insetPx = EndInset.toPx()
    val usable = size.width - 2f * insetPx
    val centreY = size.height / 2f

    fun tick(x: Float, heightPx: Float, color: Color) {
        drawRoundRect(
            color = color,
            topLeft = Offset(x - tickWidthPx / 2f, centreY - heightPx / 2f),
            size = Size(tickWidthPx, heightPx),
            cornerRadius = CornerRadius(tickWidthPx / 2f),
        )
    }

    repeat(TICK_COUNT) { i ->
        val x = insetPx + usable * i / (TICK_COUNT - 1)
        val major = i % MAJOR_EVERY == 0
        tick(
            x = x,
            heightPx = (if (major) MajorTickHeight else MinorTickHeight).toPx(),
            color = if (major) rulerColors.major else rulerColors.minor,
        )
    }

    if (zeroCentered) {
        drawCircle(
            color = rulerColors.major,
            radius = ZeroDotDiameter.toPx() / 2f,
            center = Offset(size.width / 2f, centreY - CurrentTickHeight.toPx() / 2f - ZeroDotGap.toPx()),
        )
    }

    tick(x = insetPx + fraction * usable, heightPx = CurrentTickHeight.toPx(), color = rulerColors.current)
}
