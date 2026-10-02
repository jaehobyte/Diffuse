package com.diffuse.feature.editor.tools.retouch

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.diffuse.core.ai.SkinRetouchKind
import com.diffuse.core.ui.components.AdjustSlider
import com.diffuse.core.ui.components.EditSheet
import com.diffuse.core.ui.components.TertiaryPill
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R
import java.util.Locale

const val SkinRetouchSheetTestTag = "SkinRetouchSheet"
const val SkinRetouchStatusTestTag = "SkinRetouchStatus"
const val SkinRetouchServerTestTag = "SkinRetouchServer"
const val SkinRetouchPreviewTestTag = "SkinRetouchPreview"
const val SkinRetouchSettingsTestTag = "SkinRetouchSettings"
const val SkinRetouchRetryTestTag = "SkinRetouchRetry"

/** DESIGN.md §4: disabled controls drop to 38% alpha, keeping their colour. */
private const val DISABLED_ALPHA = 0.38f

/** Test tag per row, so a test can name the kind rather than the string it happens to have. */
fun skinRetouchRowTag(kind: SkinRetouchKind): String = "SkinRetouchRow:${kind.name}"

fun skinRetouchSliderTag(kind: SkinRetouchKind): String = "SkinRetouchSlider:${kind.name}"

fun skinRetouchFaceTag(index: Int): String = "SkinRetouchFace:$index"

/** specs/skin_retouch.md §1: the four corrections, in the order the table lists them. */
@StringRes
internal fun SkinRetouchKind.labelRes(): Int = when (this) {
    SkinRetouchKind.Blemish -> R.string.skin_retouch_blemish
    SkinRetouchKind.Shine -> R.string.skin_retouch_shine
    SkinRetouchKind.DarkCircles -> R.string.skin_retouch_dark_circles
    SkinRetouchKind.ShavingShadow -> R.string.skin_retouch_shaving_shadow
}

/** The sheet's callbacks, bundled so the composable stays under detekt's parameter ceiling. */
@Suppress("LongParameterList") // A plain bundle of the sheet's eight actions.
class SkinRetouchActions(
    val onSelectFace: (String) -> Unit,
    val onStrengthChange: (SkinRetouchKind, Int) -> Unit,
    val onPreview: () -> Unit,
    val onRetryAnalysis: () -> Unit,
    val onRecheckServer: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onCancel: () -> Unit,
    val onApply: () -> Unit,
)

/**
 * specs/skin_retouch.md §3–§6. Where the pixels go, the face, four independent strengths,
 * 미리보기, and the fixed 취소 | 적용 row of `EditSheet` (45% height, inner scroll).
 *
 * Nothing here uploads by itself: entering the sheet analyses the face on the device, and the
 * selected face's ROI goes to the server only when 미리보기 is pressed (§6). A kind the server has not
 * enabled, or this face cannot support, is shown with its reason and no slider (§1).
 */
@Composable
fun SkinRetouchSheet(
    state: SkinRetouchState,
    actions: SkinRetouchActions,
    modifier: Modifier = Modifier,
) {
    EditSheet(
        title = stringResource(R.string.skin_retouch_title),
        onCancel = actions.onCancel,
        onApply = actions.onApply,
        applyEnabled = state.canApply,
        modifier = modifier.testTag(SkinRetouchSheetTestTag),
    ) {
        ServerRow(state, actions)
        AnalysisRow(state, actions)
        if (state.faces.size > 1) FaceRow(state, actions.onSelectFace)
        SkinRetouchKind.entries.forEach { KindRow(state, it, actions.onStrengthChange) }
        if (state.analysis == SkinAnalysis.Ready) PreviewRow(state, actions.onPreview)
    }
}

/** §6: the processing location and the destination, before anything is sent. */
@Composable
private fun ServerRow(state: SkinRetouchState, actions: SkinRetouchActions) {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.skin_retouch_location, state.serverHost.ifBlank { "-" }),
            style = Typography.bodySm,
            color = colors.inkSecondary,
        )
        Text(
            text = stringResource(serverStatusRes(state.server)),
            style = Typography.bodySm,
            color = if (state.server == SkinServerStatus.Ready) colors.inkSecondary else colors.ink,
            modifier = Modifier.testTag(SkinRetouchServerTestTag),
        )
        if (state.server != SkinServerStatus.Ready && state.server != SkinServerStatus.Checking) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!state.needsSettings) {
                    TertiaryPill(
                        text = stringResource(R.string.skin_retouch_recheck),
                        onClick = actions.onRecheckServer,
                        modifier = Modifier.testTag(SkinRetouchRetryTestTag),
                    )
                }
                TertiaryPill(
                    text = stringResource(R.string.skin_retouch_settings),
                    onClick = actions.onOpenSettings,
                    modifier = Modifier.testTag(SkinRetouchSettingsTestTag),
                )
            }
        }
    }
}

@Composable
private fun AnalysisRow(state: SkinRetouchState, actions: SkinRetouchActions) {
    val colors = LocalAppColors.current
    val res = when (state.analysis) {
        SkinAnalysis.Analyzing -> R.string.skin_retouch_analyzing
        SkinAnalysis.NoFace -> R.string.skin_retouch_no_face
        SkinAnalysis.Failed -> R.string.skin_retouch_analysis_failed
        SkinAnalysis.None, SkinAnalysis.Ready -> null
    } ?: return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(res),
            style = Typography.bodyMd,
            color = colors.ink,
            modifier = Modifier.weight(1f).testTag(SkinRetouchStatusTestTag),
        )
        if (state.analysis == SkinAnalysis.Failed) {
            TertiaryPill(text = stringResource(R.string.skin_retouch_retry), onClick = actions.onRetryAnalysis)
        }
    }
}

/** §3: thumbnails of the faces found; one session is one face. Locked while a request runs. */
@Composable
private fun FaceRow(state: SkinRetouchState, onSelect: (String) -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.faces.forEachIndexed { index, face ->
            val selected = face.id == state.selectedFaceId
            val label = stringResource(R.string.skin_retouch_face, index + 1)
            val shape = RoundedCornerShape(FACE_RADIUS)
            val thumbnail = face.thumbnail
            val base = Modifier
                .testTag(skinRetouchFaceTag(index))
                .size(FACE_SIZE)
                .clip(shape)
                .border(FACE_RING, if (selected) colors.ink else Color.Transparent, shape)
                .semantics { this.selected = selected }
                .clickable(enabled = !state.busy, role = Role.RadioButton, onClickLabel = label) { onSelect(face.id) }
            if (thumbnail != null) {
                Image(bitmap = thumbnail, contentDescription = label, contentScale = ContentScale.Crop, modifier = base)
            } else {
                Text(text = label, style = Typography.label, color = colors.ink, modifier = base)
            }
        }
    }
}

/** "미지원" and the face's own reasons are text, because 38% alpha says nothing to a screen reader. */
@Composable
private fun KindRow(state: SkinRetouchState, kind: SkinRetouchKind, onChange: (SkinRetouchKind, Int) -> Unit) {
    val colors = LocalAppColors.current
    val enabled = state.kindEnabled(kind)
    Column(
        modifier = Modifier.fillMaxWidth().testTag(skinRetouchRowTag(kind)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(kind.labelRes()),
                style = Typography.bodyMd,
                color = if (enabled) colors.ink else colors.ink.copy(alpha = DISABLED_ALPHA),
                modifier = Modifier.weight(1f),
            )
            val reason = state.kindReason(kind)
                ?: if (state.analysis != SkinAnalysis.Ready) R.string.skin_retouch_unsupported else null
            if (!enabled && reason != null) {
                Text(
                    text = stringResource(reason),
                    style = Typography.label,
                    color = colors.inkSecondary.copy(alpha = DISABLED_ALPHA),
                )
            }
        }
        if (enabled) {
            AdjustSlider(
                value = (state.strengths[kind] ?: 0).toFloat(),
                range = 0f..SkinRetouchState.MAX_STRENGTH.toFloat(),
                zeroCentered = false,
                onChange = { onChange(kind, it.toInt()) },
                onChangeFinished = {},
                format = { String.format(Locale.US, "%.0f", it) },
                modifier = Modifier.testTag(skinRetouchSliderTag(kind)),
            )
        }
    }
}

@Composable
private fun PreviewRow(state: SkinRetouchState, onPreview: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(
                if (state.needsPreview) R.string.skin_retouch_needs_preview else R.string.skin_retouch_local_strength,
            ),
            style = Typography.bodySm,
            color = colors.inkSecondary,
            modifier = Modifier.weight(1f),
        )
        TertiaryPill(
            text = stringResource(R.string.skin_retouch_preview),
            onClick = onPreview,
            enabled = state.canPreview,
            modifier = Modifier.testTag(SkinRetouchPreviewTestTag),
        )
    }
}

private val FACE_SIZE = 48.dp
private val FACE_RADIUS = 12.dp
private val FACE_RING = 2.dp
