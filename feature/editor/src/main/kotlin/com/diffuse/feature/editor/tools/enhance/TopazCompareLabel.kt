package com.diffuse.feature.editor.tools.enhance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.theme.Shapes
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R

const val TopazCompareLabelTestTag = "TopazCompareLabel"

/**
 * specs/topaz_enhancement.md §2: 전 / 후 on the canvas, so which frame is showing is said in
 * words and not only by the picture changing. DESIGN.md §4's dark-on-photo treatment.
 */
@Composable
fun BoxScope.TopazCompareLabel(comparing: Boolean) {
    Text(
        text = stringResource(if (comparing) R.string.topaz_before else R.string.topaz_after),
        style = Typography.label,
        color = Tokens.canvasOverlayInk,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(12.dp)
            .background(Color.Black.copy(alpha = LABEL_SCRIM_ALPHA), Shapes.pill)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag(TopazCompareLabelTestTag),
    )
}

private const val LABEL_SCRIM_ALPHA = 0.6f
