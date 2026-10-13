package com.diffuse.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Typography as MaterialTypography

/**
 * DESIGN.md §1: Browse and the editor's controls are both cool white. What differs is the
 * editor's dark work area around the photo, which is [AppColors.background] in [Edit] — the
 * mode is not a dark theme.
 */
enum class ThemeMode { Browse, Edit }

/**
 * The mode-dependent slice of DESIGN.md §2. Brand, selection and semantic colors are the
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
    val primaryFill: Color = Tokens.primaryFill,
    val onAccent: Color = Tokens.onAccent,
    val inkMuted: Color = Tokens.inkMuted,
    val selectionFill: Color = Tokens.selectionFill,
    val selectionBorder: Color = Tokens.selectionBorder,
    val link: Color = Tokens.linkText,
    val success: Color = Tokens.success,
    val warning: Color = Tokens.warning,
    val error: Color = Tokens.error,
    val errorText: Color = Tokens.errorText,
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
    // DESIGN.md §1: only the canvas surround is dark; the controls on it are light.
    background = Tokens.editBackground,
    surface = Tokens.editSurface,
    surfaceRaised = Tokens.editSurfaceRaised,
    surfaceSecondary = Tokens.surfaceSecondary,
    hairline = Tokens.editHairline,
    ink = Tokens.editInk,
    inkSecondary = Tokens.editInkSecondary,
)

val LocalAppColors = staticCompositionLocalOf { BrowseColors }

/**
 * DESIGN.md §3 through Material's own slots, so a stock TextField, dialog or snackbar
 * reads in the same type as everything else rather than in Material's defaults.
 */
private val MaterialType = MaterialTypography(
    headlineMedium = Typography.headingXl,
    headlineSmall = Typography.headingLg,
    titleLarge = Typography.headingLg,
    titleMedium = Typography.headingLg,
    titleSmall = Typography.headingMd,
    bodyLarge = Typography.bodyMd,
    bodyMedium = Typography.bodyMd,
    bodySmall = Typography.bodySm,
    labelLarge = Typography.bodyStrong,
    labelMedium = Typography.label,
    labelSmall = Typography.labelSmall,
)

/**
 * DESIGN.md §9: Material 3 is used for primitives only, and its scheme is light in both
 * modes so a stock control never falls back to a dark or default palette. Visible styling
 * comes from [LocalAppColors] and [Typography].
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
    val scheme = lightColorScheme(
        // Material paints button text, focus rings and switch tracks with `primary`, so it is
        // the fill that keeps small blue text at 4.5:1 rather than the brighter accent.
        primary = colors.primaryFill,
        onPrimary = colors.onAccent,
        primaryContainer = colors.selectionFill,
        onPrimaryContainer = colors.ink,
        secondary = colors.inkSecondary,
        onSecondary = colors.onAccent,
        secondaryContainer = colors.surfaceSecondary,
        onSecondaryContainer = colors.ink,
        background = colors.background,
        onBackground = colors.ink,
        surface = colors.surface,
        onSurface = colors.ink,
        surfaceVariant = colors.surfaceRaised,
        onSurfaceVariant = colors.inkSecondary,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = colors.surface,
        surfaceContainerLow = colors.surface,
        surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.surface,
        surfaceContainerHighest = colors.surfaceRaised,
        outline = colors.inkMuted,
        outlineVariant = colors.hairline,
        error = colors.errorText,
        onError = colors.onAccent,
        // DESIGN.md §4 State display: the snackbar is dark with light text, never editInk.
        inverseSurface = Tokens.editBackground,
        inverseOnSurface = Tokens.canvasOverlayInk,
        inversePrimary = colors.selectionBorder,
        scrim = Color.Black,
    )

    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = MaterialType, content = content)
    }
}
