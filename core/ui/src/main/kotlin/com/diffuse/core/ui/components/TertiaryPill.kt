package com.diffuse.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.Role
import com.diffuse.core.ui.theme.Dimens
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Shapes
import com.diffuse.core.ui.theme.Typography

/**
 * DESIGN.md §4 `tertiary`: transparent fill, `inkSecondary` label. Cancel / close.
 *
 * @param color overrides the label for the two places the default is wrong: a link action
 * (`AppColors.link`) and a cancel drawn over a dark progress scrim (`Tokens.canvasOverlayInk`).
 */
@Composable
fun TertiaryPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Color.Unspecified,
) {
    val colors = LocalAppColors.current
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Box(
        modifier = modifier
            .heightIn(min = Dimens.buttonHeight)
            .defaultMinSize(minWidth = Dimens.touchTarget)
            .clip(Shapes.pill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = PillHorizontalPadding, vertical = PillVerticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = Typography.bodyStrong,
            color = color.takeOrElse { colors.inkSecondary }.copy(alpha = alpha),
        )
    }
}
