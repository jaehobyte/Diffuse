package com.diffuse.feature.editor

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.components.TertiaryPill
import com.diffuse.core.ui.theme.OverlayTextShadow
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.core.ui.theme.Typography

private const val SCRIM_ALPHA = 0.4f

/** The Photo Editor swaps its status line in about a third of a second. */
private const val LABEL_CROSSFADE_MS = 350

const val SelectionOverlayProgressTestTag = "SelectionProgressOverlay"
const val SelectionCancelTestTag = "SelectionCancel"

/**
 * DESIGN.md §4 (State display) and §7: 40% scrim, a progress indicator, one line of
 * `canvasOverlayInk` text and a cancel button, for the whole time the model is working. The
 * controls' black `editInk` would vanish on the scrim, so neither uses it.
 *
 * The indicator follows the Galaxy Photo Editor's AI 지우기 motion (see AiWorkingMotion.kt):
 * the backend streams no progress, so a light band sweeps the scrim and a sparkle twinkles
 * instead of a spinner. The band is drawn over the content, so it brightens the sparkle and
 * the text as it passes, as the Photo Editor's does.
 */
@Composable
fun SelectionProgressOverlay(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    @androidx.annotation.StringRes labelRes: Int = R.string.select_preparing,
) {
    val sweepPhase = rememberLightSweepPhase()
    Box(
        modifier = modifier
            .testTag(SelectionOverlayProgressTestTag)
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black.copy(alpha = SCRIM_ALPHA)) }
            .drawWithContent {
                drawContent()
                drawLightSweep(lightSweepProgress(sweepPhase))
            },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            SparkleIndicator()
            Box(modifier = Modifier.height(12.dp))
            Crossfade(targetState = labelRes, animationSpec = tween(LABEL_CROSSFADE_MS), label = "busyLabel") { res ->
                Text(
                    text = stringResource(res),
                    style = Typography.bodyMd.copy(shadow = OverlayTextShadow),
                    color = Tokens.canvasOverlayInk,
                )
            }
            Box(modifier = Modifier.height(12.dp))
            TertiaryPill(
                text = stringResource(R.string.select_cancel_work),
                onClick = onCancel,
                color = Tokens.canvasOverlayInk,
                modifier = Modifier.testTag(SelectionCancelTestTag),
            )
        }
    }
}

/**
 * The Photo Editor's finishing touch: once the scrim is gone, one brighter band sweeps the
 * result from top to bottom. Draws only; it takes no input. Runs once per change of
 * [runKey] after the first composition.
 */
@Composable
fun ResultRevealSweep(runKey: Int, modifier: Modifier = Modifier) {
    val fraction = remember { Animatable(1f) }
    LaunchedEffect(runKey) {
        if (runKey == 0) return@LaunchedEffect
        fraction.snapTo(0f)
        fraction.animateTo(1f, tween(RESULT_SWEEP_DURATION_MS, easing = LinearEasing))
    }
    Box(modifier = modifier.fillMaxSize().drawBehind { drawResultSweep(fraction.value) })
}
