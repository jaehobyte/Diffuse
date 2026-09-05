package com.diffuse.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.RestartAlt
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
import com.diffuse.core.ui.components.PrimaryPill
import com.diffuse.core.ui.theme.LocalAppColors

/** DESIGN.md §4: 56dp top bar on editSurface; ← left, undo/redo centre, compare + export right. */
private val TopBarHeight = 56.dp
private val TopBarPadding = 8.dp
private val IconSize = 24.dp
private const val DISABLED_ALPHA = 0.38f

const val TopBarTestTag = "EditorTopBar"
const val CompareTestTag = "EditorCompare"

@Composable
fun EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    canReset: Boolean,
    canCompare: Boolean,
    onBack: () -> Unit,
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
            .height(TopBarHeight)
            .padding(horizontal = TopBarPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TopBarPadding),
    ) {
        BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, R.string.editor_back, true, onBack)
        BarIcon(Icons.AutoMirrored.Rounded.Undo, R.string.editor_undo, canUndo, onUndo)
        BarIcon(Icons.AutoMirrored.Rounded.Redo, R.string.editor_redo, canRedo, onRedo)
        BarIcon(Icons.Rounded.RestartAlt, R.string.editor_reset, canReset, onReset)

        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))

        CompareButton(enabled = canCompare, onCompareChange = onCompareChange)
        PrimaryPill(
            text = stringResource(R.string.editor_export),
            onClick = onExport,
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
            .size(TopBarHeight)
            .padding(TopBarPadding * 2)
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
