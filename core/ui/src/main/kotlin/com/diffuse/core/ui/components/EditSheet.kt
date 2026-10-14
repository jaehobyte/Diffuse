package com.diffuse.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.R
import com.diffuse.core.ui.theme.Elevation
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.core.ui.theme.Typography

/** DESIGN.md §4 (Bottom sheet). */
private val SheetCornerRadius = 28.dp
private val HandleWidth = 36.dp
private val HandleHeight = 4.dp
private val HorizontalPadding = 12.dp
private val VerticalPadding = 12.dp
private val SectionSpacing = 16.dp

/** DESIGN.md §2: a faint top-to-bottom glass gradient; no blur behind it. */
private val GlassBrush = Brush.verticalGradient(listOf(Tokens.sheetGlassTop, Tokens.sheetGlassBottom))

/** The canvas is always at least half visible, so the sheet stops at 45% of the screen. */
private const val MAX_HEIGHT_FRACTION = 0.45f

const val EditSheetTestTag = "EditSheet"

/**
 * DESIGN.md §4: 28dp top corners on a glass gradient with a 1dp light edge and the §6
 * 10dp shadow, a drag handle, content, and a pinned [Cancel | Apply] row where Apply is
 * the primary pill.
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
    val shape = RoundedCornerShape(topStart = SheetCornerRadius, topEnd = SheetCornerRadius)
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * MAX_HEIGHT_FRACTION

    Column(
        modifier = modifier
            .testTag(EditSheetTestTag)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            // The shadow is drawn before the clip, or the clip would cut it away.
            .shadow(elevation = Elevation.sheetShadow, shape = shape, clip = false)
            .clip(shape)
            // The sheet covers the tool strip, and through the 94% glass alone the strip's icons
            // and labels showed behind 취소 | 적용 (seen on device). DESIGN.md §6 backs content with
            // an opaque light surface when legibility suffers, so the glass tints canvasLight.
            .background(Tokens.canvasLight)
            .background(GlassBrush)
            .border(width = Elevation.sheetEdgeWidth, color = Tokens.sheetEdge, shape = shape)
            // specs/editor_shell.md: a tap on the sheet's empty area (padding, the gap between
            // 취소 and 적용) must not fall through to the tool strip it covers. A pointer-input node
            // makes the whole sheet a hit target, so siblings beneath it never get the touch; the
            // sheet's own buttons, sliders and scroll still receive it first. Nothing is consumed.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
            .navigationBarsPadding()
            .padding(horizontal = HorizontalPadding, vertical = VerticalPadding),
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
        ActionRow(onCancel, onApply, applyLabel, applyEnabled, cancelLabel)
    }
}

@Composable
private fun ColumnScope.DragHandle() {
    Box(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .size(width = HandleWidth, height = HandleHeight)
            .clip(RoundedCornerShape(HandleHeight / 2))
            .background(Tokens.sheetHandle),
    )
}

@Composable
private fun ActionRow(
    onCancel: () -> Unit,
    onApply: () -> Unit,
    applyLabel: String?,
    applyEnabled: Boolean,
    cancelLabel: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TertiaryPill(text = cancelLabel ?: stringResource(R.string.sheet_cancel), onClick = onCancel)
        Spacer(modifier = Modifier.weight(1f))
        PrimaryPill(
            text = applyLabel ?: stringResource(R.string.sheet_apply),
            onClick = onApply,
            enabled = applyEnabled,
        )
    }
}
