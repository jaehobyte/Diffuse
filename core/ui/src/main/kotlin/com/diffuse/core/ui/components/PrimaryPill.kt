package com.diffuse.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography

/** DESIGN.md §4: pills at radius 16dp, 40dp tall; disabled is 38% alpha, same colour. */
private val PillRadius = 16.dp
private val PillHeight = 40.dp
private val PillHorizontalPadding = 16.dp
private const val DISABLED_ALPHA = 0.38f

/**
 * DESIGN.md §4 `primary`: accent fill, onAccent label. One per screen.
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
    val fill = if (pressed) colors.accentPressed else colors.accent
    val alpha = if (enabled) 1f else DISABLED_ALPHA

    Box(
        modifier = modifier
            .height(PillHeight)
            .defaultMinSize(minWidth = PillHeight)
            .clip(RoundedCornerShape(PillRadius))
            .background(fill.copy(alpha = fill.alpha * alpha))
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = PillHorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = Typography.bodyStrong,
            color = colors.onAccent.copy(alpha = alpha),
        )
    }
}
