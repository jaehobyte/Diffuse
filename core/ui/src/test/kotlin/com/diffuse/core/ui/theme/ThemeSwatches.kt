package com.diffuse.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Test-only reference surface for the `theme_swatches` golden (T03). Renders every
 * DESIGN.md §2 color and §3 text token, with the mode-dependent palettes drawn inside
 * their own [AppTheme] so a regression in either mode shows up as a pixel diff.
 */
@Composable
internal fun ThemeSwatches() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.surfaceSoft)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SwatchGroup(
            title = "Brand + Semantic",
            swatches = listOf(
                "accent" to Tokens.accent,
                "accentPressed" to Tokens.accentPressed,
                "onAccent" to Tokens.onAccent,
                "success" to Tokens.success,
                "warning" to Tokens.warning,
                "error" to Tokens.error,
            ),
        )

        AppTheme(ThemeMode.Browse) {
            SwatchGroup(
                title = "Browse",
                swatches = listOf(
                    "canvasLight" to Tokens.canvasLight,
                    "surfaceSoft" to Tokens.surfaceSoft,
                    "surfaceCard" to Tokens.surfaceCard,
                    "surfaceSecondary" to Tokens.surfaceSecondary,
                    "hairline" to Tokens.hairline,
                    "ink" to Tokens.ink,
                    "inkSecondary" to Tokens.inkSecondary,
                ),
            )
        }

        AppTheme(ThemeMode.Edit) {
            SwatchGroup(
                title = "Edit",
                swatches = listOf(
                    "editBackground" to Tokens.editBackground,
                    "editSurface" to Tokens.editSurface,
                    "editSurfaceRaised" to Tokens.editSurfaceRaised,
                    "editHairline" to Tokens.editHairline,
                    "editInk" to Tokens.editInk,
                    "editInkSecondary" to Tokens.editInkSecondary,
                    "canvasCheckerA" to Tokens.canvasCheckerA,
                    "canvasCheckerB" to Tokens.canvasCheckerB,
                ),
            )
        }

        TypeScale()
    }
}

private const val SWATCHES_PER_ROW = 4

@Composable
private fun SwatchGroup(title: String, swatches: List<Pair<String, Color>>) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = title, style = Typography.headingMd, color = colors.ink)
        swatches.chunked(SWATCHES_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (name, color) -> Swatch(name = name, color = color) }
            }
        }
    }
}

@Composable
private fun Swatch(name: String, color: Color) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.width(84.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(color)
                .border(width = 1.dp, color = colors.hairline),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = name, style = Typography.label, color = colors.inkSecondary)
    }
}

@Composable
private fun TypeScale() {
    val samples: List<Pair<String, TextStyle>> = listOf(
        "headingXl" to Typography.headingXl,
        "headingLg" to Typography.headingLg,
        "headingMd" to Typography.headingMd,
        "bodyMd" to Typography.bodyMd,
        "bodyStrong" to Typography.bodyStrong,
        "bodySm" to Typography.bodySm,
        "label" to Typography.label,
        "mono" to Typography.mono,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.canvasLight)
            .padding(8.dp),
    ) {
        samples.forEach { (name, style) ->
            Text(text = "$name 배경 제거 123", style = style, color = Tokens.ink)
        }
    }
}
