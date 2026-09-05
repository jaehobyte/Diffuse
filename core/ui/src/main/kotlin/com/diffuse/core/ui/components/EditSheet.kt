package com.diffuse.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.R
import com.diffuse.core.ui.theme.Elevation
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography

/** DESIGN.md §4 (Bottom sheet). */
private val SheetCornerRadius = 24.dp
private val HandleWidth = 32.dp
private val HandleHeight = 4.dp
private val SheetPadding = 16.dp
private val SectionSpacing = 12.dp

/** The canvas is always at least half visible, so the sheet stops at 45% of the screen. */
private const val MAX_HEIGHT_FRACTION = 0.45f

const val EditSheetTestTag = "EditSheet"

/**
 * DESIGN.md §4: 24dp top corners, drag handle, content, and a pinned
 * [Cancel | Apply] row where Apply is the primary pill.
 */
@Composable
fun EditSheet(
    title: String,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
    /** Export calls its commit "저장" rather than "적용" (specs/export.md). */
    applyLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(topStart = SheetCornerRadius, topEnd = SheetCornerRadius)
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * MAX_HEIGHT_FRACTION

    Column(
        modifier = modifier
            .testTag(EditSheetTestTag)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .clip(shape)
            .background(colors.surface)
            // DESIGN.md §6: in dark mode a 1dp hairline replaces the shadow.
            .border(width = Elevation.darkBorderWidth, color = colors.hairline, shape = shape)
            .navigationBarsPadding()
            .padding(SheetPadding),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        DragHandle()
        Text(text = title, style = Typography.headingLg, color = colors.ink)
        Column(
            modifier = Modifier
                .weight(weight = 1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing),
            content = content,
        )
        ActionRow(onCancel = onCancel, onApply = onApply, applyLabel = applyLabel)
    }
}

@Composable
private fun ColumnScope.DragHandle() {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .size(width = HandleWidth, height = HandleHeight)
            .clip(RoundedCornerShape(HandleHeight / 2))
            .background(colors.hairline),
    )
}

@Composable
private fun ActionRow(onCancel: () -> Unit, onApply: () -> Unit, applyLabel: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TertiaryPill(text = stringResource(R.string.sheet_cancel), onClick = onCancel)
        Spacer(modifier = Modifier.weight(1f))
        PrimaryPill(text = applyLabel ?: stringResource(R.string.sheet_apply), onClick = onApply)
    }
}
