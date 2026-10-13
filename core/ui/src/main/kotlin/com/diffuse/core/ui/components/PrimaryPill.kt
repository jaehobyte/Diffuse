package com.diffuse.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.diffuse.core.ui.theme.Dimens
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Shapes
import com.diffuse.core.ui.theme.Typography

/**
 * DESIGN.md §4: full pills, 48dp by default; disabled is 38% alpha, same colour. The height is
 * a minimum, so a label enlarged by the system font scale grows the pill instead of clipping.
 */
internal val PillHorizontalPadding = 16.dp
internal val PillVerticalPadding = 8.dp
internal const val DISABLED_ALPHA = 0.38f
private val PressedShade = Color.Black.copy(alpha = 0.16f)

/**
 * DESIGN.md §4 `primary`: `primaryFill` with an onAccent label — white on the brighter accent
 * would miss 4.5:1. At most one filled primary per surface (§1).
 */
@Composable
fun PrimaryPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalAppColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // accentPressed is the fill's own value, so pressing darkens the fill a step instead.
    val fill = if (pressed) PressedShade.compositeOver(colors.primaryFill) else colors.primaryFill
    val alpha = if (enabled) 1f else DISABLED_ALPHA

    Box(
        modifier = modifier
            .heightIn(min = Dimens.buttonHeight)
            .defaultMinSize(minWidth = Dimens.touchTarget)
            .clip(Shapes.pill)
            .background(fill.copy(alpha = fill.alpha * alpha))
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = PillHorizontalPadding, vertical = PillVerticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = Typography.bodyStrong,
            color = colors.onAccent.copy(alpha = alpha),
        )
    }
}
