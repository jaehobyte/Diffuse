package com.diffuse.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.components.TertiaryPill
import com.diffuse.core.ui.theme.LocalAppColors

/**
 * DESIGN.md §4: the Photo Editor's bar — black, ↶ ↷ ◐ on the left, `원본 복원` and `저장` as text
 * buttons on the right, and no back arrow (system back leaves).
 */
private val TopBarHeight = 56.dp
private val TopBarPadding = 8.dp
private val IconSize = 24.dp
/** DESIGN.md §5: 48dp touch target around the 24dp icon, which is what `IconButton` uses. */
private val IconButtonSize = 48.dp
private const val DISABLED_ALPHA = 0.38f

const val TopBarTestTag = "EditorTopBar"
const val CompareTestTag = "EditorCompare"
const val ExportTestTag = "EditorExport"

@Composable
fun EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    canReset: Boolean,
    canCompare: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    onCompareChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier
            .testTag(TopBarTestTag)
            .fillMaxWidth()
            .background(colors.surface)
            .statusBarsPadding()
            // A minimum, so the 48dp text buttons can grow with a large font instead of clipping.
            .heightIn(min = TopBarHeight)
            .padding(horizontal = TopBarPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The icons carry the weight so the text buttons are measured at their own width first:
        // a Korean label squeezed into whatever is left over is what "내보내기" once got.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarIcon(Icons.AutoMirrored.Rounded.Undo, R.string.editor_undo, canUndo, onUndo)
            BarIcon(Icons.AutoMirrored.Rounded.Redo, R.string.editor_redo, canRedo, onRedo)
            CompareButton(enabled = canCompare, onCompareChange = onCompareChange)
        }

        val reset = stringResource(R.string.editor_reset)
        TertiaryPill(
            text = reset,
            onClick = onReset,
            enabled = canReset,
            modifier = Modifier.testTag(reset),
        )
        TertiaryPill(
            text = stringResource(R.string.editor_export),
            onClick = onExport,
            modifier = Modifier.testTag(ExportTestTag),
        )
    }
}

@Composable
private fun BarIcon(
    icon: ImageVector,
    labelRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val label = stringResource(labelRes)
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(label)) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = colors.ink.copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
            modifier = Modifier.size(IconSize),
        )
    }
}

/** DESIGN.md §7: hold to compare with the original is the single comparison gesture. */
@Composable
private fun CompareButton(enabled: Boolean, onCompareChange: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    val label = stringResource(R.string.editor_compare)
    Icon(
        imageVector = Icons.Rounded.Compare,
        contentDescription = label,
        tint = colors.ink.copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
        modifier = Modifier
            .testTag(CompareTestTag)
            .size(IconButtonSize)
            .padding((IconButtonSize - IconSize) / 2)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        onCompareChange(true)
                        tryAwaitRelease()
                        onCompareChange(false)
                    },
                )
            },
    )
}
