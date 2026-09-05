package com.diffuse.feature.export

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.components.TertiaryPill
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.core.ui.theme.Typography

private const val SCRIM_ALPHA = 0.4f

const val ExportOverlayTestTag = "ExportProgressOverlay"
const val ExportCancelTestTag = "ExportCancel"

/**
 * DESIGN.md §4 (State display) and §7: always show progress and a cancel button during
 * long work. The indicator is bound to the render's own progress rather than spinning
 * indefinitely.
 */
@Composable
fun ExportProgressOverlay(
    progress: Float,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .testTag(ExportOverlayTestTag)
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            color = Tokens.accent,
        )
        Box(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.export_saving),
            style = Typography.bodyMd,
            color = Tokens.editInk,
        )
        Box(modifier = Modifier.height(12.dp))
        TertiaryPill(
            text = stringResource(R.string.export_cancel),
            onClick = onCancel,
            modifier = Modifier.testTag(ExportCancelTestTag),
        )
    }
}
