package com.diffuse.feature.editor.tools.color

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.tools.AdjustSheet

/** specs/adjust_color.md: 온도 / 색조 / 채도 / 생동감. */
val ColorKinds = listOf(
    AdjustKind.Temperature,
    AdjustKind.Tint,
    AdjustKind.Saturation,
    AdjustKind.Vibrance,
)

@Composable
fun ColorSheet(
    document: EditDocument,
    onValueChange: (AdjustKind, Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AdjustSheet(
        title = stringResource(R.string.color_title),
        kinds = ColorKinds,
        document = document,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        onCancel = onCancel,
        onApply = onApply,
        modifier = modifier,
    )
}
