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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.core.ui.theme.Typography

/**
 * DESIGN.md §4: 72dp black strip, 64dp items, 26dp icon, the selected one yellow; no indicator.
 * specs/tool_groups.md §2: the strip's geometry is the same at both levels.
 */
private val StripHeight = 72.dp
private val ItemWidth = 64.dp
private val IconSize = 26.dp
private val IconSlot = 40.dp
private val AiIconSize = 22.dp
private val LabelGap = 4.dp

/** DESIGN.md §4: disabled controls drop to 38% alpha, keeping their colour. */
private const val DISABLED_ALPHA = 0.38f

const val ToolStripTestTag = "EditorToolStrip"

/** DESIGN.md §2: the Photo Assist-style circle behind the AI parent, the one gradient allowed. */
private val AiCircleBrush = Brush.linearGradient(listOf(Tokens.aiGradientStart, Tokens.aiGradientEnd))

/**
 * DESIGN.md §4, work/decisions.md D107. One strip, one level: the manual tools and, last, the AI
 * circle that opens 지시. The plan-only tools have no item here, so the strip is one surface and
 * never changes its list (DESIGN.md §1).
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
    /** D107: 지시 is open or listening, so the AI circle's label is the yellow of a selected tool. */
    aiActive: Boolean = false,
    onAiClick: () -> Unit = {},
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
        items(stripItems()) { item ->
            StripItemView(
                item = item,
                selected = when (item) {
                    is StripItem.OfTool -> item.tool == selectedTool
                    StripItem.OpenAi -> aiActive
                },
                // The AI circle is never greyed: 지시 explains a missing key itself, with the
                // 서버 설정 sheet (specs/vibe_edit.md §10).
                enabled = item !is StripItem.OfTool || item.tool !in disabledTools,
                onClick = {
                    when (item) {
                        is StripItem.OfTool -> onToolClick(item.tool)
                        StripItem.OpenAi -> onAiClick()
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
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    // DESIGN.md §4: the selected tool is yellow, icon and label; the rest are white icons over
    // secondary labels. The AI circle keeps its white glyph and shows its state on the label.
    val iconTint = (if (selected) colors.accent else colors.ink).copy(alpha = alpha)
    val labelTint = (if (selected) colors.accent else colors.inkSecondary).copy(alpha = alpha)
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
        Box(
            modifier = Modifier
                .size(IconSlot)
                .clip(CircleShape)
                .then(if (item is StripItem.OpenAi) Modifier.background(AiCircleBrush, alpha = alpha) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon(),
                contentDescription = label,
                // §2: the AI parent is the gradient circle with a white glyph, like 포토 어시스트.
                tint = if (item is StripItem.OpenAi) colors.ink.copy(alpha = alpha) else iconTint,
                modifier = Modifier.size(if (item is StripItem.OpenAi) AiIconSize else IconSize),
            )
        }
        Box(modifier = Modifier.height(LabelGap))
        Text(text = label, style = Typography.labelSmall, color = labelTint)
    }
}
