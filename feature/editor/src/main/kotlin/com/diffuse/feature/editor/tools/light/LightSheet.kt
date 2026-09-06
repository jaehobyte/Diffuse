package com.diffuse.feature.editor.tools.light

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.tools.AdjustSheet
import com.diffuse.feature.editor.tools.MaskOption

/** specs/adjust_light.md: 노출 / 대비 / 하이라이트 / 그림자, in that order. */
val LightKinds = listOf(
    AdjustKind.Exposure,
    AdjustKind.Contrast,
    AdjustKind.Highlights,
    AdjustKind.Shadows,
)

@Composable
fun LightSheet(
    document: EditDocument,
    onValueChange: (AdjustKind, Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
    maskOption: MaskOption = MaskOption.None,
) {
    AdjustSheet(
        title = stringResource(R.string.light_title),
        kinds = LightKinds,
        document = document,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        onCancel = onCancel,
        onApply = onApply,
        modifier = modifier,
        maskOption = maskOption,
    )
}
