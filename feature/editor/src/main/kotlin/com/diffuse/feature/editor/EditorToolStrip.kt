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
        items(Tool.entries) { tool ->
            ToolItem(
                tool = tool,
                selected = tool == selectedTool,
                enabled = tool !in disabledTools,
                onClick = { onToolClick(tool) },
            )
        }
    }
}

@Composable
private fun ToolItem(tool: Tool, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val base = if (selected) Tokens.accent else colors.inkSecondary
    val tint = if (enabled) base else base.copy(alpha = DISABLED_ALPHA)
    val label = stringResource(tool.labelRes)

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
                imageVector = tool.icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(IconSize),
            )
            if (tool.isAi) {
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
