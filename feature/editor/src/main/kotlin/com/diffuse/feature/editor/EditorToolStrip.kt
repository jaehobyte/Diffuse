package com.diffuse.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.core.ui.theme.Typography

/** specs/tool_groups.md §2: the strip's geometry is the same at both levels. */
/** DESIGN.md §4: 72dp strip, 64dp items, 24dp icon, 2dp accent indicator when selected. */
private val StripHeight = 72.dp
private val ItemWidth = 64.dp
private val IconSize = 24.dp
private val IndicatorHeight = 2.dp
private val IndicatorWidth = 24.dp
private val AiDotSize = 6.dp

/** DESIGN.md §4: disabled controls drop to 38% alpha, keeping their colour. */
private const val DISABLED_ALPHA = 0.38f

const val ToolStripTestTag = "EditorToolStrip"

/**
 * specs/tool_groups.md §2. One strip, two levels: [level] decides which list is bound to it.
 * Height, item size and scrolling are the same at both — no second row appears, so the strip is
 * still one surface with one accent (DESIGN.md §1).
 */
@Composable
fun EditorToolStrip(
    selectedTool: Tool?,
    onToolClick: (Tool) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * specs/selection_tool.md §1: a tool whose provider is unavailable is greyed, but stays
     * tappable so it can explain itself in a snackbar.
     */
    disabledTools: Set<Tool> = emptySet(),
    level: ToolGroup = ToolGroup.Root,
    onLevelChange: (ToolGroup) -> Unit = {},
    /** work/decisions.md T79: which ordering of [level] to bind. The geometry is unchanged. */
    profile: ToolMenuProfile = ToolMenuProfile.General,
) {
    val colors = LocalAppColors.current
    LazyRow(
        modifier = modifier
            .testTag(ToolStripTestTag)
            .fillMaxWidth()
            .height(StripHeight)
            .background(colors.surface),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(stripItems(level, profile)) { item ->
            StripItemView(
                item = item,
                selected = item is StripItem.OfTool && item.tool == selectedTool,
                // §4: the AI parent is never itself disabled, even when every child is — a parent
                // that cannot be tapped hides the reason its children cannot be.
                enabled = item !is StripItem.OfTool || item.tool !in disabledTools,
                onClick = {
                    when (item) {
                        is StripItem.OfTool -> onToolClick(item.tool)
                        StripItem.OpenAi -> onLevelChange(ToolGroup.Ai)
                        StripItem.Back -> onLevelChange(ToolGroup.Root)
                    }
                },
            )
        }
    }
}

@Composable
private fun StripItemView(
    item: StripItem,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    // §2: ← is `editInk`, not accent, and is a tool-shaped item so the row stays one rhythm.
    val base = when {
        selected -> Tokens.accent
        item is StripItem.Back -> colors.ink
        else -> colors.inkSecondary
    }
    val tint = if (enabled) base else base.copy(alpha = DISABLED_ALPHA)
    val label = stringResource(item.labelRes())

    Column(
        modifier = Modifier
            .width(ItemWidth)
            .height(StripHeight)
            .clickable(role = Role.Tab, onClick = onClick)
            .testTag(label),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(
                imageVector = item.icon(),
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(IconSize),
            )
            // §2: the dot marks the parent, and no child — inside the AI level it marks nothing.
            if (item is StripItem.OpenAi) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(AiDotSize)
                        .clip(CircleShape)
                        .background(Tokens.accent.copy(alpha = if (enabled) 1f else DISABLED_ALPHA)),
                )
            }
        }
        Text(text = label, style = Typography.label, color = tint)
        Box(
            modifier = Modifier
                .size(width = IndicatorWidth, height = IndicatorHeight)
                .background(if (selected) Tokens.accent else Color.Transparent),
        )
    }
}
