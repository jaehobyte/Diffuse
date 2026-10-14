package com.diffuse.feature.editor.tools.enhance

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diffuse.core.ai.topaz.TopazPurpose
import com.diffuse.core.ui.components.AdjustSlider
import com.diffuse.core.ui.components.EditSheet
import com.diffuse.core.ui.components.HoldPill
import com.diffuse.core.ui.components.OptionChip
import com.diffuse.core.ui.components.SecondaryPill
import com.diffuse.core.ui.components.TertiaryPill
import com.diffuse.core.ui.components.percentFormat
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R

const val TopazSheetTestTag = "TopazSheet"
const val TopazSizeTestTag = "TopazSize"
const val TopazCostTestTag = "TopazCost"
const val TopazEstimateTestTag = "TopazEstimate"
const val TopazSaveTestTag = "TopazSave"
const val TopazCompareTestTag = "TopazCompare"
const val TopazStatusTestTag = "TopazStatus"
const val TopazOpenSettingsTestTag = "TopazOpenSettings"
const val TopazFaceTestTag = "TopazFace"

fun topazPurposeTag(purpose: TopazPurpose) = "TopazPurpose:${purpose.name}"

fun topazScaleTag(scale: Int) = "TopazScale:$scale"

/** What the sheet's callbacks reach; one parameter, the shape `MultiShotActions` uses. */
data class TopazActions(
    val onPurpose: (TopazPurpose) -> Unit = {},
    val onScale: (Int) -> Unit = {},
    val onFaceRecovery: (Boolean) -> Unit = {},
    val onSharpenStrength: (Float?) -> Unit = {},
    val onEstimate: () -> Unit = {},
    val onRun: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onOpenCopy: () -> Unit = {},
    val onCompare: (Boolean) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onClose: () -> Unit = {},
)

/**
 * specs/topaz_enhancement.md §2. Purpose and one-line description → options → sizes and cost →
 * status and compare → the pinned row. Setup is [취소 | 실행]; a current result is
 * [닫기 | 새 편집본으로 열기], with 고해상도 저장 as the result area's secondary action. One filled
 * primary either way.
 */
@Composable
fun TopazSheet(
    state: TopazState,
    hasSelection: Boolean,
    actions: TopazActions,
    modifier: Modifier = Modifier,
) {
    val group = state.group ?: return
    val showsResult = state.result != null && !state.stale
    EditSheet(
        title = stringResource(group.titleRes),
        onCancel = actions.onClose,
        onApply = if (showsResult) actions.onOpenCopy else actions.onRun,
        applyLabel = stringResource(if (showsResult) R.string.topaz_open_copy else R.string.topaz_run),
        applyEnabled = if (showsResult) state.phase == TopazPhase.Idle else state.canRun,
        cancelLabel = if (showsResult) stringResource(R.string.topaz_close) else null,
        modifier = modifier.testTag(TopazSheetTestTag),
    ) {
        PurposeSection(state, group, actions)
        OptionsSection(state, actions)
        SizeAndCostSection(state, hasSelection, actions)
        StatusSection(state, actions)
        ResultSection(state, actions)
    }
}

@Composable
private fun PurposeSection(state: TopazState, group: TopazGroup, actions: TopazActions) {
    val colors = LocalAppColors.current
    if (group.purposes.size > 1) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            group.purposes.forEach { purpose ->
                OptionChip(
                    text = stringResource(purpose.labelRes()),
                    selected = purpose == state.purpose,
                    onClick = { actions.onPurpose(purpose) },
                    enabled = !state.working,
                    modifier = Modifier.testTag(topazPurposeTag(purpose)),
                )
            }
        }
    }
    Text(stringResource(state.purpose.aboutRes()), style = Typography.bodyMd, color = colors.ink)
    if (state.purpose.generative) {
        Text(stringResource(R.string.topaz_generative_note), style = Typography.bodySm, color = colors.inkSecondary)
    }
}

@Composable
private fun OptionsSection(state: TopazState, actions: TopazActions) {
    val purpose = state.purpose
    val colors = LocalAppColors.current
    if (purpose.scales.size > 1) {
        Text(stringResource(R.string.topaz_scale_label), style = Typography.label, color = colors.inkSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            purpose.scales.forEach { scale ->
                OptionChip(
                    text = stringResource(R.string.topaz_scale, scale),
                    selected = scale == state.options.scale,
                    onClick = { actions.onScale(scale) },
                    enabled = !state.working && state.job?.fitsOutput(purpose, scale) != false,
                    modifier = Modifier.testTag(topazScaleTag(scale)),
                )
            }
        }
        if (purpose.scales.any { state.job?.fitsOutput(purpose, it) == false }) {
            Text(stringResource(R.string.topaz_scale_limited), style = Typography.bodySm, color = colors.inkSecondary)
        }
    }
    if (purpose.faceRecovery) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.topaz_face),
                style = Typography.bodyMd,
                color = colors.ink,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = state.options.faceRecovery,
                onCheckedChange = actions.onFaceRecovery,
                enabled = !state.working,
                modifier = Modifier.testTag(TopazFaceTestTag),
            )
        }
        Text(stringResource(R.string.topaz_face_note), style = Typography.bodySm, color = colors.inkSecondary)
    }
    if (purpose.manualSharpen) StrengthOption(state, actions)
}

/** §3: Autopilot by default; 직접 조절 reveals the one documented strength. */
@Composable
private fun StrengthOption(state: TopazState, actions: TopazActions) {
    val colors = LocalAppColors.current
    val strength = state.options.sharpenStrength
    Text(stringResource(R.string.topaz_strength_label), style = Typography.label, color = colors.inkSecondary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OptionChip(
            text = stringResource(R.string.topaz_strength_auto),
            selected = strength == null,
            onClick = { actions.onSharpenStrength(null) },
            enabled = !state.working,
        )
        OptionChip(
            text = stringResource(R.string.topaz_strength_manual),
            selected = strength != null,
            onClick = { if (strength == null) actions.onSharpenStrength(DEFAULT_STRENGTH) },
            enabled = !state.working,
        )
    }
    if (strength != null) {
        AdjustSlider(
            value = strength,
            range = 0f..1f,
            zeroCentered = false,
            onChange = { actions.onSharpenStrength(it) },
            onChangeFinished = {},
            format = percentFormat(false),
        )
    }
}

@Composable
private fun SizeAndCostSection(state: TopazState, hasSelection: Boolean, actions: TopazActions) {
    val colors = LocalAppColors.current
    state.job?.let { job ->
        Text(
            text = stringResource(
                R.string.topaz_size, job.inputWidth, job.inputHeight, job.resultWidth, job.resultHeight,
            ),
            style = Typography.mono,
            color = colors.inkSecondary,
            modifier = Modifier.testTag(TopazSizeTestTag),
        )
    }
    Text(stringResource(R.string.topaz_scope), style = Typography.bodySm, color = colors.inkSecondary)
    if (hasSelection) {
        Text(stringResource(R.string.topaz_scope_selection), style = Typography.bodySm, color = colors.inkSecondary)
    }
    if (state.reopensSmaller) {
        Text(stringResource(R.string.topaz_reedit_note), style = Typography.bodySm, color = colors.inkSecondary)
    }
    Text(
        stringResource(R.string.topaz_cost),
        style = Typography.bodySm,
        color = colors.ink,
        modifier = Modifier.testTag(TopazCostTestTag),
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(TopazEstimateTestTag)) {
        when (val estimate = state.estimate) {
            TopazEstimateState.NotChecked -> TertiaryPill(
                text = stringResource(R.string.topaz_estimate_check),
                onClick = actions.onEstimate,
                enabled = state.configured && state.job != null && state.blocker == null,
            )
            TopazEstimateState.Checking -> EstimateText(R.string.topaz_estimate_checking)
            is TopazEstimateState.Known -> Text(
                stringResource(R.string.topaz_estimate_known, estimate.credits),
                style = Typography.bodyMd,
                color = colors.ink,
            )
            TopazEstimateState.Unknown -> EstimateText(R.string.topaz_estimate_unknown)
        }
    }
}

@Composable
private fun EstimateText(@StringRes res: Int) {
    Text(stringResource(res), style = Typography.bodyMd, color = LocalAppColors.current.inkSecondary)
}

/** §2: missing key, what blocks 실행, a failure with its way forward, and the run's phase. */
@Composable
private fun StatusSection(state: TopazState, actions: TopazActions) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier.testTag(TopazStatusTestTag),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val line = when {
            state.phase == TopazPhase.Processing && state.progress != null ->
                stringResource(R.string.topaz_progress, state.progress)
            state.phase != TopazPhase.Idle -> stringResource(state.phase.labelRes)
            state.failure != null ->
                stringResource(messageFor(state.failure)) + " · " + stringResource(R.string.topaz_retry_hint)
            else -> state.blocker?.let { stringResource(it) }
        }
        line?.let { Text(it, style = Typography.bodyMd, color = colors.ink) }
        if (!state.configured) {
            SecondaryPill(
                text = stringResource(R.string.topaz_open_settings),
                onClick = actions.onOpenSettings,
                modifier = Modifier.testTag(TopazOpenSettingsTestTag),
            )
        }
    }
}

/** §2, §5: the result's size, whether it is stale, compare, and 고해상도 저장. */
@Composable
private fun ResultSection(state: TopazState, actions: TopazActions) {
    val result = state.result ?: return
    val colors = LocalAppColors.current
    Text(
        stringResource(R.string.topaz_result_size, result.width, result.height),
        style = Typography.mono,
        color = colors.ink,
    )
    if (state.stale) {
        Text(stringResource(R.string.topaz_result_stale), style = Typography.bodySm, color = colors.ink)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompareButton(state.comparing, actions.onCompare)
        SecondaryPill(
            text = stringResource(if (state.saved) R.string.topaz_save_again else R.string.topaz_save),
            onClick = actions.onSave,
            enabled = state.phase == TopazPhase.Idle,
            modifier = Modifier.testTag(TopazSaveTestTag),
        )
    }
    Text(stringResource(R.string.topaz_copy_note), style = Typography.bodySm, color = colors.inkSecondary)
}

/**
 * DESIGN.md §7, §2: held is 전, released or cancelled is 후; TalkBack's click flips it (review R1:
 * `HoldPill` owns the press, so a real touch reaches it).
 */
@Composable
private fun CompareButton(comparing: Boolean, onCompare: (Boolean) -> Unit) {
    HoldPill(
        text = stringResource(R.string.topaz_compare),
        held = comparing,
        shown = stringResource(if (comparing) R.string.topaz_before else R.string.topaz_after),
        onHold = onCompare,
        modifier = Modifier.testTag(TopazCompareTestTag),
    )
}

@StringRes
internal fun TopazPurpose.labelRes(): Int = when (this) {
    TopazPurpose.Upscale -> R.string.topaz_purpose_upscale
    TopazPurpose.Sharpen -> R.string.topaz_purpose_sharpen
    TopazPurpose.Denoise -> R.string.topaz_purpose_denoise
    TopazPurpose.Restore -> R.string.topaz_purpose_restore
    TopazPurpose.Creative -> R.string.topaz_purpose_creative
    TopazPurpose.DustScratch -> R.string.topaz_purpose_dust
    TopazPurpose.Lighting -> R.string.topaz_purpose_lighting
    TopazPurpose.ColorBalance -> R.string.topaz_purpose_balance
    TopazPurpose.Colorize -> R.string.topaz_purpose_colorize
}

@StringRes
private fun TopazPurpose.aboutRes(): Int = when (this) {
    TopazPurpose.Upscale -> R.string.topaz_about_upscale
    TopazPurpose.Sharpen -> R.string.topaz_about_sharpen
    TopazPurpose.Denoise -> R.string.topaz_about_denoise
    TopazPurpose.Restore -> R.string.topaz_about_restore
    TopazPurpose.Creative -> R.string.topaz_about_creative
    TopazPurpose.DustScratch -> R.string.topaz_about_dust
    TopazPurpose.Lighting -> R.string.topaz_about_lighting
    TopazPurpose.ColorBalance -> R.string.topaz_about_balance
    TopazPurpose.Colorize -> R.string.topaz_about_colorize
}

private const val DEFAULT_STRENGTH = 0.5f
