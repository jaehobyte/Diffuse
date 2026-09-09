package com.diffuse.feature.editor.tools.retouch

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diffuse.core.ai.SkinRetouchKind
import com.diffuse.core.ui.components.EditSheet
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R

const val SkinRetouchSheetTestTag = "SkinRetouchSheet"
const val SkinRetouchStatusTestTag = "SkinRetouchStatus"

/** DESIGN.md §4: disabled controls drop to 38% alpha, keeping their colour. */
private const val DISABLED_ALPHA = 0.38f

/** Test tag per row, so a test can name the kind rather than the string it happens to have. */
fun skinRetouchRowTag(kind: SkinRetouchKind): String = "SkinRetouchRow:${kind.name}"

/** specs/skin_retouch.md §1: the four corrections, in the order the table lists them. */
@StringRes
internal fun SkinRetouchKind.labelRes(): Int = when (this) {
    SkinRetouchKind.Blemish -> R.string.skin_retouch_blemish
    SkinRetouchKind.Shine -> R.string.skin_retouch_shine
    SkinRetouchKind.DarkCircles -> R.string.skin_retouch_dark_circles
    SkinRetouchKind.ShavingShadow -> R.string.skin_retouch_shaving_shadow
}

/**
 * specs/skin_retouch.md §1, §4. 피부 보정's sheet while there is no engine behind it.
 *
 * It shows the four corrections and says, in one line, that the feature is not ready — which is
 * the accurate reason today: no `SkinRetouchProvider` is bound (`AiModule`), so nothing has been
 * asked about this photograph. §5's "얼굴 없음" and "분석 실패" are different states with different
 * sentences, and this is neither: entering the sheet analyses nothing and calls nothing.
 *
 * No slider and no face list yet. §4's four sliders arrive with the engine that would move them;
 * a control that cannot do anything is worse than the sentence that says so.
 */
@Composable
fun SkinRetouchSheet(
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    EditSheet(
        title = stringResource(R.string.skin_retouch_title),
        onCancel = onCancel,
        onApply = onApply,
        // §1: an unfinished kind stays visibly disabled rather than appearing to work, and with
        // none of the four finished there is nothing 적용 could commit.
        applyEnabled = false,
        modifier = modifier.testTag(SkinRetouchSheetTestTag),
    ) {
        Text(
            text = stringResource(R.string.skin_retouch_preparing),
            style = Typography.bodySm,
            color = colors.inkSecondary,
            modifier = Modifier.testTag(SkinRetouchStatusTestTag),
        )
        SkinRetouchKind.entries.forEach { KindRow(it) }
    }
}

/**
 * The name and its state. "미지원" is text rather than only 38% alpha because the alpha says
 * nothing to a screen reader.
 */
@Composable
private fun KindRow(kind: SkinRetouchKind) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().testTag(skinRetouchRowTag(kind)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(kind.labelRes()),
            style = Typography.bodyMd,
            color = colors.ink.copy(alpha = DISABLED_ALPHA),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.skin_retouch_unsupported),
            style = Typography.label,
            color = colors.inkSecondary.copy(alpha = DISABLED_ALPHA),
        )
    }
}
