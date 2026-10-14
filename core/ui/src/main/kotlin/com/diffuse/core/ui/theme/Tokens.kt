package com.diffuse.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diffuse.core.ui.R

/**
 * Raw design values from DESIGN.md §2, sampled from the Galaxy Photo Editor. DESIGN.md names
 * this file the home of its tokens, so these literals are the definition, not a copy.
 * TokensTest asserts every one of them against the §2 tables.
 */
object Tokens {

    // --- Brand & selection ---
    /** The Photo Editor's yellow: selected tab, current slider tick, the listening mic. */
    val accent = Color(0xFFF9DA4B)

    /** The wedge inside a selected adjust circle. */
    val accentSoft = Color(0xFFF9E16E)
    val accentPressed = Color(0xFFE3C53A)

    /** One UI blue for the few filled buttons, a step darker so white text holds 4.5:1. */
    val primaryFill = Color(0xFF2F6BEB)
    val onAccent = Color(0xFFFFFFFF)

    /** A selected chip or circle inverts: white fill, black glyph. */
    val selectionFill = Color(0xFFFAFAFA)
    val selectionInk = Color(0xFF000000)
    val promptTint = Color(0xFF2A3346)

    // --- Surfaces & ink (shared by Browse and Edit) ---
    val background = Color(0xFF000000)
    val surface = Color(0xFF000000)
    val surfaceRaised = Color(0xFF202023)
    val surfaceSecondary = Color(0xFF2C2C30)
    val surfaceMenu = Color(0xFF272727)
    val hairline = Color(0xFF3A3A3D)
    val ink = Color(0xFFFAFAFA)
    val inkSecondary = Color(0xFFA6A6A7)
    val inkMuted = Color(0xFF6E6E70)

    // --- Editor ---
    val editBackground = Color(0xFF000000)
    val editSurface = Color(0xFF000000)
    val editSurfaceRaised = Color(0xFF202023)
    val editHairline = Color(0xFF3A3A3D)
    val editInk = Color(0xFFFAFAFA)
    val editInkSecondary = Color(0xFFA6A6A7)

    /** Text and guides over the canvas or a progress scrim. */
    val canvasOverlayInk = Color(0xFFFAFAFA)
    val canvasCheckerA = Color(0xFF2C2C2E)
    val canvasCheckerB = Color(0xFF242426)

    /** The ruler slider: every fifth tick is major, the rest minor. */
    val sliderTickMajor = Color(0xFF8D8D8E)
    val sliderTickMinor = Color(0xFF4D4D4F)

    /** The AI entry circle, the one gradient DESIGN.md §6 allows. */
    val aiGradientStart = Color(0xFF5C8AE6)
    val aiGradientEnd = Color(0xFF9D7BD8)

    // --- Semantic ---
    val success = Color(0xFF3DBE6A)
    val warning = Color(0xFFF2A33C)
    val error = Color(0xFFFF3B30)

    /** DESIGN.md §2: link and error text hold 4.5:1 on black and on [surfaceRaised]. */
    val linkText = Color(0xFF6FA3FF)
    val errorText = Color(0xFFFF6B5E)
}

/**
 * DESIGN.md §4 State display: text over a progress scrim or the canvas. The 40% scrim alone
 * leaves [Tokens.canvasOverlayInk] under 3:1 where the photo beneath is white, so overlay
 * text carries a soft dark shadow that holds it apart from a bright photo too.
 */
val OverlayTextShadow = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(0f, 1f), blurRadius = 6f)

/** DESIGN.md §4–§6: the shared sizes and shapes the components and screens reuse. */
object Shapes {
    /** Buttons, chips and the prompt bar are full pills. */
    val pill = RoundedCornerShape(percent = 50)

    /** Photo tiles and option cards. */
    val card = RoundedCornerShape(16.dp)
}

object Dimens {
    /** DESIGN.md §4 Buttons: the default height of every pill button. */
    val buttonHeight = 48.dp

    /** DESIGN.md §5: the smallest touch target anywhere. */
    val touchTarget = 48.dp

    /** DESIGN.md §4 Adjust sheet: the circle that stands for one adjustment. */
    val adjustCircle = 56.dp
}

/**
 * DESIGN.md §3: the Android system sans-serif. The bundled Pretendard file stays in the
 * resources; no style names it any more.
 */
private val SystemSans = FontFamily.Default

val JetBrainsMonoFamily = FontFamily(
    Font(resId = R.font.jetbrains_mono_medium, weight = FontWeight.W500),
)

/** DESIGN.md §3. Letter spacing is left at the default. */
object Typography {

    val headingXl = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.W700,
        fontSize = 27.sp,
        lineHeight = 32.sp,
    )

    val headingLg = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.W700,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    )

    val headingMd = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.W600,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )

    val bodyMd = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.W400,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    )

    val bodyStrong = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.W600,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    )

    val bodySm = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.W400,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    )

    val label = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.W400,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )

    val labelSmall = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.W600,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )

    val link = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.W600,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    val mono = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.W500,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
}

/** DESIGN.md §6: nothing casts a shadow except a photo's iconCircle, at 2dp when it must. */
object Elevation {
    val iconCircle = 2.dp
}
