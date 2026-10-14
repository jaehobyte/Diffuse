package com.diffuse.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Typography as MaterialTypography

/**
 * DESIGN.md §1: Browse and the editor share one black palette, as the Galaxy Photo Editor and
 * Gallery do. The mode is kept so a screen still states which one it is, and so a future
 * difference between the two has a place to live.
 */
enum class ThemeMode { Browse, Edit }

/**
 * The slice of DESIGN.md §2 a screen reads. Brand, selection and semantic colors are the same in
 * both modes, so they default to [Tokens] and are not per-palette.
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
    val selectionInk: Color = Tokens.selectionInk,
    val surfaceMenu: Color = Tokens.surfaceMenu,
    val link: Color = Tokens.linkText,
    val success: Color = Tokens.success,
    val warning: Color = Tokens.warning,
    val error: Color = Tokens.error,
    val errorText: Color = Tokens.errorText,
)

val BrowseColors = AppColors(
    background = Tokens.background,
    surface = Tokens.surface,
    surfaceRaised = Tokens.surfaceRaised,
    surfaceSecondary = Tokens.surfaceSecondary,
    hairline = Tokens.hairline,
    ink = Tokens.ink,
    inkSecondary = Tokens.inkSecondary,
)

val EditColors = AppColors(
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
 * DESIGN.md §9: Material 3 is used for primitives only, on a dark scheme built from this
 * palette so a stock dialog, switch or snackbar never falls back to Material's own colours.
 * Visible styling comes from [LocalAppColors] and [Typography].
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
    val scheme = darkColorScheme(
        // Material paints button text, focus rings and switch tracks with `primary`; the One UI
        // blue is what a dialog's primary button and a checked switch should be, not the yellow.
        primary = colors.primaryFill,
        onPrimary = colors.onAccent,
        primaryContainer = colors.selectionFill,
        onPrimaryContainer = colors.selectionInk,
        secondary = colors.inkSecondary,
        onSecondary = colors.selectionInk,
        secondaryContainer = colors.surfaceSecondary,
        onSecondaryContainer = colors.ink,
        background = colors.background,
        onBackground = colors.ink,
        surface = colors.surface,
        onSurface = colors.ink,
        surfaceVariant = colors.surfaceRaised,
        onSurfaceVariant = colors.inkSecondary,
        surfaceTint = Color.Transparent,
        // DESIGN.md §4: dialogs and menus are the Photo Editor's `surfaceMenu` card.
        surfaceContainerLowest = colors.surface,
        surfaceContainerLow = colors.surfaceRaised,
        surfaceContainer = colors.surfaceMenu,
        surfaceContainerHigh = colors.surfaceMenu,
        surfaceContainerHighest = colors.surfaceSecondary,
        outline = colors.inkMuted,
        outlineVariant = colors.hairline,
        error = colors.errorText,
        onError = colors.selectionInk,
        // DESIGN.md §4 State display: the snackbar is a surfaceMenu card with light text.
        inverseSurface = colors.surfaceMenu,
        inverseOnSurface = colors.ink,
        inversePrimary = colors.accent,
        scrim = Color.Black,
    )

    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = MaterialType, content = content)
    }
}
