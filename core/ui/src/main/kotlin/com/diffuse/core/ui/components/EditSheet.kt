package com.diffuse.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.R
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography

/** DESIGN.md §4 (Sheet). */
private val HorizontalPadding = 12.dp
private val VerticalPadding = 12.dp
private val SectionSpacing = 16.dp

/** The canvas is always at least half visible, so the sheet stops at 45% of the screen. */
private const val MAX_HEIGHT_FRACTION = 0.45f

const val EditSheetTestTag = "EditSheet"

/**
 * DESIGN.md §4: the Photo Editor has no sheet surface — a tool's controls sit on the same black
 * as the canvas surround. So this is a black panel with no corners, shadow, edge or handle: a
 * small centred title (the Photo Editor's `밝기`), the content, and a pinned [취소 | 적용] row
 * of text buttons, 적용 in the yellow accent.
 */
@Composable
fun EditSheet(
    title: String,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
    /** Export calls its commit "저장" rather than "적용" (specs/export.md). */
    applyLabel: String? = null,
    /** specs/selection_tool.md §6: Apply is disabled until there is something to apply. */
    applyEnabled: Boolean = true,
    /** specs/topaz_enhancement.md §2: a result's sheet closes with "닫기" rather than "취소". */
    cancelLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalAppColors.current
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * MAX_HEIGHT_FRACTION

    Column(
        modifier = modifier
            .testTag(EditSheetTestTag)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .background(colors.surface)
            // specs/editor_shell.md: a tap on the sheet's empty area (padding, the gap between
            // 취소 and 적용) must not fall through to the tool strip it covers. A pointer-input node
            // makes the whole sheet a hit target, so siblings beneath it never get the touch; the
            // sheet's own buttons, sliders and scroll still receive it first. Nothing is consumed.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
            .navigationBarsPadding()
            .padding(horizontal = HorizontalPadding, vertical = VerticalPadding),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        Text(
            text = title,
            style = Typography.label,
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Column(
            modifier = Modifier
                .weight(weight = 1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing),
            content = content,
        )
        ActionRow(onCancel, onApply, applyLabel, applyEnabled, cancelLabel)
    }
}

@Composable
private fun ActionRow(
    onCancel: () -> Unit,
    onApply: () -> Unit,
    applyLabel: String?,
    applyEnabled: Boolean,
    cancelLabel: String?,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TertiaryPill(
            text = cancelLabel ?: stringResource(R.string.sheet_cancel),
            onClick = onCancel,
            color = colors.ink,
        )
        Spacer(modifier = Modifier.weight(1f))
        // DESIGN.md §4: the commit is a text button in the accent, like the Photo Editor's 저장.
        TertiaryPill(
            text = applyLabel ?: stringResource(R.string.sheet_apply),
            onClick = onApply,
            enabled = applyEnabled,
            color = colors.accent,
        )
    }
}
