package com.diffuse.feature.editor.tools.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.tools.AdjustSheet

/** specs/adjust_detail.md: 선명도 / 비네트, both one-sided, so no centre tick. */
val DetailKinds = listOf(AdjustKind.Sharpen, AdjustKind.Vignette)

@Composable
fun DetailSheet(
    document: EditDocument,
    onValueChange: (AdjustKind, Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AdjustSheet(
        title = stringResource(R.string.detail_title),
        kinds = DetailKinds,
        document = document,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        onCancel = onCancel,
        onApply = onApply,
        modifier = modifier,
    )
}
