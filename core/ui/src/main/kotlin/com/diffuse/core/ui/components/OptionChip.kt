package com.diffuse.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.theme.Dimens
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Shapes
import com.diffuse.core.ui.theme.Typography

private val ChipHorizontalPadding = 14.dp
private val ChipVerticalPadding = 6.dp

/**
 * DESIGN.md §4 (Chips): one choice among several. Unselected is a `surfaceRaised` pill with
 * white text; selected inverts to `selectionFill` with `selectionInk`, the way the Photo
 * Editor's selected circle and ratio chip do. No border. The touch target is 48dp; the label
 * grows it under a larger font scale.
 */
@Composable
fun OptionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalAppColors.current
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Box(
        modifier = modifier
            .heightIn(min = Dimens.touchTarget)
            .defaultMinSize(minWidth = Dimens.touchTarget)
            .clip(Shapes.pill)
            .background((if (selected) colors.selectionFill else colors.surfaceRaised).copy(alpha = alpha))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = ChipHorizontalPadding, vertical = ChipVerticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = if (selected) Typography.bodyStrong else Typography.bodyMd,
            color = (if (selected) colors.selectionInk else colors.ink).copy(alpha = alpha),
        )
    }
}
