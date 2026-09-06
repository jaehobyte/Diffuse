package com.diffuse.feature.editor.tools.style

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.diffuse.core.imaging.style.StylePreset
import com.diffuse.core.ui.components.AdjustSlider
import com.diffuse.core.ui.components.EditSheet
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R
import java.util.Locale

const val StyleSheetTestTag = "StyleSheet"
const val StyleTileRowTestTag = "StyleTiles"
const val StyleVariantRowTestTag = "StyleVariants"

fun styleTileTag(id: String): String = "StyleTile-$id"

/**
 * specs/style_match.md §4. Tiles of the user's own photograph, 원본 first, a 세부 row that appears
 * only once a style is chosen, and one 강도 slider.
 *
 * 사진에서 가져오기 (§5) is not here: it is T75's, and the sheet is laid out to take it above the
 * tiles when it arrives.
 */
@Composable
fun StyleSheet(
    state: StyleState,
    onSelect: (String?) -> Unit,
    onVariantSelect: (String?) -> Unit,
    onIntensityChange: (Int) -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EditSheet(
        title = stringResource(R.string.style_title),
        onCancel = onCancel,
        onApply = onApply,
        applyEnabled = state.canApply,
        modifier = modifier.testTag(StyleSheetTestTag),
    ) {
        TileRow(state = state, onSelect = onSelect)
        // §3: a variant is a second decision — film grain versus film colour — so it is offered
        // only after the first one is made.
        state.preset?.let { VariantRow(preset = it, state = state, onSelect = onVariantSelect) }
        IntensityRow(intensity = state.intensity, onChange = onIntensityChange)
    }
}

@Composable
private fun TileRow(state: StyleState, onSelect: (String?) -> Unit) {
    Row(
        modifier = Modifier
            .testTag(StyleTileRowTestTag)
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StyleTile(
            id = STYLE_NONE_ID,
            label = stringResource(R.string.style_none),
            image = state.tiles[STYLE_NONE_ID],
            selected = state.selected == null,
            onClick = { onSelect(null) },
        )
        state.presets.forEach { preset ->
            StyleTile(
                id = preset.id,
                label = stringResource(styleNameRes(preset.id)),
                image = state.tiles[preset.id],
                selected = state.selected == preset.id,
                onClick = { onSelect(preset.id) },
            )
        }
    }
}

@Composable
private fun VariantRow(
    preset: StylePreset,
    state: StyleState,
    onSelect: (String?) -> Unit,
) {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.style_variants),
            style = Typography.label,
            color = colors.inkSecondary,
        )
        Row(
            modifier = Modifier
                .testTag(StyleVariantRowTestTag)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            preset.variants.forEach { variant ->
                VariantChip(
                    label = stringResource(styleVariantNameRes(variant.id)),
                    selected = state.variant == variant.id,
                    onClick = { onSelect(variant.id.takeIf { it != state.variant }) },
                )
            }
        }
    }
}

@Composable
private fun IntensityRow(intensity: Int, onChange: (Int) -> Unit) {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.style_intensity),
            style = Typography.label,
            color = colors.inkSecondary,
        )
        AdjustSlider(
            value = intensity.toFloat(),
            range = 0f..STYLE_INTENSITY_MAX.toFloat(),
            // DESIGN.md §4: only a zero-centred adjustment gets the centre tick, and 강도
            // runs 0…100 — its neutral end is the left one.
            zeroCentered = false,
            onChange = { onChange(it.toInt()) },
            onChangeFinished = {},
            format = { String.format(Locale.US, "%.0f", it) },
        )
    }
}

/**
 * DESIGN.md §4 (Image tile): 16dp corners, no text over the tile — the name goes below it — and a
 * flat fill while loading with **no** skeleton shimmer. Edit mode has no `surfaceCard`; its step in
 * the surface scale is `editSurfaceRaised`, which is what `colors.surfaceRaised` is here.
 */
@Composable
private fun StyleTile(
    id: String,
    label: String,
    image: ImageBitmap?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(TILE_RADIUS)
    Column(
        modifier = Modifier
            .testTag(styleTileTag(id))
            .clickable(role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .size(TILE_SIZE)
                .clip(shape)
                .background(colors.surfaceRaised)
                // specs/adjust_hsl.md §7's ruling, reused by T77: the ring is `editInk`, never the
                // accent — the sheet's one accent belongs to 적용 (DESIGN.md §1).
                .border(
                    width = TILE_RING,
                    color = if (selected) colors.ink else Color.Transparent,
                    shape = shape,
                ),
        ) {
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(TILE_SIZE),
                )
            }
        }
        Text(
            text = label,
            style = Typography.label,
            color = if (selected) colors.ink else colors.inkSecondary,
        )
    }
}

@Composable
private fun VariantChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(CHIP_RADIUS)
    Text(
        text = label,
        style = Typography.label,
        color = if (selected) colors.ink else colors.inkSecondary,
        modifier = Modifier
            .background(color = colors.surfaceRaised, shape = shape)
            .border(
                width = TILE_RING,
                color = if (selected) colors.ink else Color.Transparent,
                shape = shape,
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

private val TILE_SIZE = 96.dp
private val TILE_RADIUS = 16.dp
private val TILE_RING = 2.dp
private val CHIP_RADIUS = 16.dp
