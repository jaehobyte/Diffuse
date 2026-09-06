package com.diffuse.feature.editor

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

const val SelectionOverlayProgressTestTag = "SelectionProgressOverlay"
const val SelectionCancelTestTag = "SelectionCancel"

/**
 * DESIGN.md §4 (State display) and §7: 40% scrim, an accent spinner, one line of text and a
 * cancel button, for the whole time the model is working.
 *
 * Unlike export there is no progress to report — the backend does not stream one — so the
 * indicator spins.
 */
@Composable
fun SelectionProgressOverlay(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .testTag(SelectionOverlayProgressTestTag)
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Tokens.accent)
        Box(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.select_preparing),
            style = Typography.bodyMd,
            color = Tokens.editInk,
        )
        Box(modifier = Modifier.height(12.dp))
        TertiaryPill(
            text = stringResource(R.string.select_cancel_work),
            onClick = onCancel,
            modifier = Modifier.testTag(SelectionCancelTestTag),
        )
    }
}
