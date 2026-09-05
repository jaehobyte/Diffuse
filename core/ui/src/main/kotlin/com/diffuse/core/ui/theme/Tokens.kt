package com.diffuse.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diffuse.core.ui.R

/**
 * Raw design values from DESIGN.md §2. DESIGN.md names this file the source of
 * truth, so these literals are the definition, not a copy. TokensTest asserts
 * every one of them against the §2 table.
 */
object Tokens {

    // --- Brand ---
    val accent = Color(0xFFE60023)
    val accentPressed = Color(0xFFCC001F)
    val onAccent = Color(0xFFFFFFFF)

    // --- Browse mode (light) ---
    val canvasLight = Color(0xFFFFFFFF)
    val surfaceSoft = Color(0xFFFBFBF9)
    val surfaceCard = Color(0xFFF6F6F3)
    val surfaceSecondary = Color(0xFFE5E5E0)
    val hairline = Color(0xFFDADAD3)
    val ink = Color(0xFF000000)
    val inkSecondary = Color(0xFF5F5F5A)

    // --- Edit mode (dark) ---
    val editBackground = Color(0xFF1C1C19)
    val editSurface = Color(0xFF262622)
    val editSurfaceRaised = Color(0xFF33332E)
    val editHairline = Color(0xFF3F3F39)
    val editInk = Color(0xFFF6F6F3)
    val editInkSecondary = Color(0xFFA3A39B)
    val canvasCheckerA = Color(0xFF2E2E2A)
    val canvasCheckerB = Color(0xFF262622)

    // --- Semantic ---
    val success = Color(0xFF1E7A46)
    val warning = Color(0xFFB8741A)

    /** Same value as [accent] by design; DESIGN.md §2 keeps the token separate. */
    val error = Color(0xFFE60023)
}

/**
 * DESIGN.md §3. Pretendard ships as a single variable font, so each weight is a
 * variation of one resource rather than a separate file.
 */
@OptIn(ExperimentalTextApi::class)
private fun pretendard(weight: Int) = Font(
    resId = R.font.pretendard_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val PretendardFamily = FontFamily(
    pretendard(weight = 400),
    pretendard(weight = 500),
    pretendard(weight = 600),
    pretendard(weight = 700),
)

val JetBrainsMonoFamily = FontFamily(
    Font(resId = R.font.jetbrains_mono_medium, weight = FontWeight.W500),
)

/** DESIGN.md §3. Line heights are the table's multipliers resolved against the size. */
object Typography {

    val headingXl = TextStyle(
        fontFamily = PretendardFamily,
        fontWeight = FontWeight.W700,
        fontSize = 28.sp,
        lineHeight = 33.6.sp,
        letterSpacing = (-0.5).sp,
    )

    val headingLg = TextStyle(
        fontFamily = PretendardFamily,
        fontWeight = FontWeight.W600,
        fontSize = 22.sp,
        lineHeight = 27.5.sp,
        letterSpacing = 0.sp,
    )

    val headingMd = TextStyle(
        fontFamily = PretendardFamily,
        fontWeight = FontWeight.W600,
        fontSize = 18.sp,
        lineHeight = 23.4.sp,
        letterSpacing = 0.sp,
    )

    val bodyMd = TextStyle(
        fontFamily = PretendardFamily,
        fontWeight = FontWeight.W400,
        fontSize = 16.sp,
        lineHeight = 22.4.sp,
        letterSpacing = 0.sp,
    )

    val bodyStrong = TextStyle(
        fontFamily = PretendardFamily,
        fontWeight = FontWeight.W600,
        fontSize = 16.sp,
        lineHeight = 22.4.sp,
        letterSpacing = 0.sp,
    )

    val bodySm = TextStyle(
        fontFamily = PretendardFamily,
        fontWeight = FontWeight.W400,
        fontSize = 14.sp,
        lineHeight = 19.6.sp,
        letterSpacing = 0.sp,
    )

    val label = TextStyle(
        fontFamily = PretendardFamily,
        fontWeight = FontWeight.W500,
        fontSize = 12.sp,
        lineHeight = 15.6.sp,
        letterSpacing = 0.2.sp,
    )

    /** DESIGN.md §3 gives no line height for `mono`; it follows `label` at 1.3. */
    val mono = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.W500,
        fontSize = 13.sp,
        lineHeight = 16.9.sp,
        letterSpacing = 0.sp,
    )
}

/** DESIGN.md §6: exactly one shadow level; dark mode uses a hairline border instead. */
object Elevation {
    val shadowOffsetY = 2.dp
    val shadowBlur = 8.dp
    val shadowColor = Color(0x1F000000)
    val darkBorderWidth = 1.dp
}
