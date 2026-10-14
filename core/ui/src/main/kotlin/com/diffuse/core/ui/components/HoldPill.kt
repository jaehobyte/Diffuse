package com.diffuse.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.diffuse.core.ui.theme.Dimens
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Shapes
import com.diffuse.core.ui.theme.Typography

/**
 * DESIGN.md §7: compare is **held**, not toggled — the `secondary` pill's look with a press-and-hold
 * action. [onHold] is true from the pointer going down until it goes up or the gesture is
 * cancelled (a scroll taking it, the sheet closing, the pill leaving composition), and is never
 * left true.
 *
 * The pill owns its pointer input: a `clickable` underneath would consume the down before a hold
 * detector on the outside saw it, which is how a real press once did nothing (review R1).
 * TalkBack cannot hold, so its click action flips [held] and [shown] — which frame is showing — is
 * announced as the state.
 */
@Composable
fun HoldPill(
    text: String,
    held: Boolean,
    shown: String,
    onHold: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val hold by rememberUpdatedState(onHold)
    val current by rememberUpdatedState(held)
    Box(
        modifier = modifier
            .heightIn(min = Dimens.buttonHeight)
            .defaultMinSize(minWidth = Dimens.touchTarget)
            .clip(Shapes.pill)
            .background(colors.surfaceSecondary)
            // The pressed state a ripple would draw, held for as long as the press is.
            .background(if (held) colors.ink.copy(alpha = PRESSED_ALPHA) else Color.Transparent)
            .semantics {
                role = Role.Button
                stateDescription = shown
                onClick(text) {
                    hold(!current)
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    hold(true)
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        hold(false)
                    }
                }
            }
            .padding(horizontal = PillHorizontalPadding, vertical = PillVerticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = Typography.bodyStrong, color = colors.ink)
    }
}

private const val PRESSED_ALPHA = 0.08f
