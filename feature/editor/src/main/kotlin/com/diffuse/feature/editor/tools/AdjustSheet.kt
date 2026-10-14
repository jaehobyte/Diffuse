package com.diffuse.feature.editor.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.ui.components.AdjustSlider
import com.diffuse.core.ui.components.EditSheet
import com.diffuse.core.ui.components.percentFormat
import com.diffuse.core.ui.theme.Dimens
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R
import kotlin.math.abs
import kotlin.math.max

const val MaskedOnlyToggleTestTag = "MaskedOnly"

/** DESIGN.md §4 Adjust sheet. */
private val CircleSpacing = 12.dp
private val CircleIconSize = 24.dp
private const val FULL_TURN_DEG = 360f
private const val TOP_DEG = -90f

/** The sliders show the masked value when the toggle is on, so the sheet never lies. */
private fun activeMaskId(document: EditDocument, option: MaskOption): String? =
    if (option.available && option.maskedOnly) document.activeMaskId else null

/** Test tag of the one slider, by the kind it is adjusting right now. */
fun adjustSliderTag(kind: AdjustKind): String = "AdjustSlider:${kind.name}"

/** Test tag of a kind's circle, so a test can bring that kind under the slider. */
fun adjustKindTag(kind: AdjustKind): String = "AdjustKind:${kind.name}"

/**
 * specs/adjust_color.md: "The sheet composable is the same generic AdjustSheet(kinds); do
 * not duplicate it." Light, Color and Detail differ only in title and kind list.
 *
 * DESIGN.md §4: the Photo Editor's 색조 layout — the active kind's name, one circle per kind, and
 * a single ruler slider for the active one. Which kind is active is view state; the values live
 * in [document] and are read on every recomposition, so re-opening shows what is stored.
 */
@Composable
fun AdjustSheet(
    title: String,
    kinds: List<AdjustKind>,
    document: EditDocument,
    onValueChange: (AdjustKind, Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
    maskOption: MaskOption = MaskOption.None,
    /**
     * specs/adjust_hsl.md §6: 혼합 puts its band chips here. Empty by default, so the three
     * sheets that came first render exactly as they did.
     */
    header: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = LocalAppColors.current
    // A new kind list (혼합 changing band) starts again at its first kind.
    var active by rememberSaveable(kinds) { mutableStateOf(kinds.first()) }
    val maskId = activeMaskId(document, maskOption)
    EditSheet(
        title = title,
        onCancel = onCancel,
        onApply = onApply,
        modifier = modifier,
    ) {
        if (maskOption.available) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.adjust_masked_only),
                    style = Typography.bodyMd,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = maskOption.maskedOnly,
                    onCheckedChange = maskOption.onMaskedOnlyChange,
                    modifier = Modifier.testTag(MaskedOnlyToggleTestTag),
                )
            }
        }
        header()
        Text(
            text = stringResource(active.labelRes()),
            style = Typography.bodyStrong,
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        CircleRow(
            kinds = kinds,
            active = active,
            valueOf = { document.adjustValue(it, maskId) },
            onSelect = { active = it },
        )
        Column(modifier = Modifier.testTag(adjustSliderTag(active))) {
            AdjustSlider(
                value = document.adjustValue(active, maskId),
                range = active.range,
                zeroCentered = active.isZeroCentered(),
                onChange = { onValueChange(active, it) },
                onChangeFinished = onValueChangeFinished,
                format = percentFormat(active.isZeroCentered()),
                showValue = false,
            )
        }
    }
}

@Composable
private fun CircleRow(
    kinds: List<AdjustKind>,
    active: AdjustKind,
    valueOf: (AdjustKind) -> Float,
    onSelect: (AdjustKind) -> Unit,
) {
    // Centred when the circles fit, scrolling when they do not — the Photo Editor's row.
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(CircleSpacing),
        ) {
            kinds.forEach { kind ->
                AdjustCircle(
                    kind = kind,
                    value = valueOf(kind),
                    selected = kind == active,
                    onClick = { onSelect(kind) },
                )
            }
        }
    }
}

/**
 * DESIGN.md §4: unselected is a `surfaceRaised` circle with a white glyph, or the number when the
 * value is not neutral; selected inverts to white, with the number over an `accentSoft` wedge
 * that sweeps from 12 o'clock — clockwise for a positive value, anticlockwise for a negative.
 */
@Composable
private fun AdjustCircle(
    kind: AdjustKind,
    value: Float,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val label = stringResource(kind.labelRes())
    val neutral = value == 0f
    val glyph = if (selected) colors.selectionInk else colors.ink
    Box(
        modifier = Modifier
            .testTag(adjustKindTag(kind))
            .size(Dimens.adjustCircle)
            .clip(CircleShape)
            .background(if (selected) colors.selectionFill else colors.surfaceRaised)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected && !neutral) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(
                    color = Tokens.accentSoft,
                    startAngle = TOP_DEG,
                    sweepAngle = FULL_TURN_DEG * wedgeFraction(kind, value),
                    useCenter = true,
                )
            }
        }
        if (neutral) {
            Icon(
                imageVector = kind.icon(),
                contentDescription = label,
                tint = glyph,
                modifier = Modifier.size(CircleIconSize),
            )
        } else {
            Text(
                text = percentFormat(kind.isZeroCentered())(value),
                style = Typography.bodyStrong,
                color = glyph,
            )
        }
    }
}

/** Signed share of the kind's full swing, so a −32 reads as a 32% wedge turning the other way. */
private fun wedgeFraction(kind: AdjustKind, value: Float): Float {
    val swing = max(abs(kind.range.start), abs(kind.range.endInclusive))
    return if (swing <= 0f) 0f else (value / swing).coerceIn(-1f, 1f)
}

/** Zero-centred kinds span negative values; the one-sided kinds start at 0. */
internal fun AdjustKind.isZeroCentered(): Boolean = range.start < 0f
