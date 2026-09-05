package com.diffuse.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** DESIGN.md §1: browse chrome is light cream, edit chrome is warm dark. */
enum class ThemeMode { Browse, Edit }

/**
 * The mode-dependent slice of DESIGN.md §2. Brand and semantic colors are the
 * same in both modes, so they default to [Tokens] and are not per-palette.
 */
@Immutable
data class AppColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSecondary: Color,
    val hairline: Color,
    val ink: Color,
    val inkSecondary: Color,
    val accent: Color = Tokens.accent,
    val accentPressed: Color = Tokens.accentPressed,
    val onAccent: Color = Tokens.onAccent,
    val success: Color = Tokens.success,
    val warning: Color = Tokens.warning,
    val error: Color = Tokens.error,
)

val BrowseColors = AppColors(
    background = Tokens.surfaceSoft,
    surface = Tokens.canvasLight,
    surfaceRaised = Tokens.surfaceCard,
    surfaceSecondary = Tokens.surfaceSecondary,
    hairline = Tokens.hairline,
    ink = Tokens.ink,
    inkSecondary = Tokens.inkSecondary,
)

val EditColors = AppColors(
    background = Tokens.editBackground,
    surface = Tokens.editSurface,
    surfaceRaised = Tokens.editSurfaceRaised,
    // DESIGN.md §4: the secondary button fill is editSurfaceRaised in dark mode.
    surfaceSecondary = Tokens.editSurfaceRaised,
    hairline = Tokens.editHairline,
    ink = Tokens.editInk,
    inkSecondary = Tokens.editInkSecondary,
)

val LocalAppColors = staticCompositionLocalOf { BrowseColors }

/**
 * DESIGN.md §2: Material 3 is used for primitives only, so the M3 scheme is
 * derived from [Tokens] purely to keep stock components off their default
 * palette. Visible styling comes from [LocalAppColors] and [Typography].
 */
@Composable
fun AppTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val colors = when (mode) {
        ThemeMode.Browse -> BrowseColors
        ThemeMode.Edit -> EditColors
    }
    val scheme = when (mode) {
        ThemeMode.Browse -> lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.inkSecondary,
            outline = colors.hairline,
            error = colors.error,
        )
        ThemeMode.Edit -> darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.inkSecondary,
            outline = colors.hairline,
            error = colors.error,
        )
    }

    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
