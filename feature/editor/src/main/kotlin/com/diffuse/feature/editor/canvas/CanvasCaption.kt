package com.diffuse.feature.editor.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.theme.Shapes
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.core.ui.theme.Typography

const val CanvasCaptionTestTag = "CanvasCaption"

/**
 * One line over the bottom of the photo, in DESIGN.md §4's dark-on-photo treatment: the same
 * pill `TopazCompareLabel` uses at the top. It is where the canvas says what a tap will do, or
 * what it is hearing.
 */
@Composable
fun BoxScope.CanvasCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = Typography.label,
        color = Tokens.canvasOverlayInk,
        modifier = modifier
            .align(Alignment.BottomCenter)
            .padding(12.dp)
            .background(Color.Black.copy(alpha = CAPTION_SCRIM_ALPHA), Shapes.pill)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(CanvasCaptionTestTag),
    )
}

private const val CAPTION_SCRIM_ALPHA = 0.6f
