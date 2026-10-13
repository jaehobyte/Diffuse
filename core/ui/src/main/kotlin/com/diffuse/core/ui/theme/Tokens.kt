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
 * Raw design values from DESIGN.md §2. DESIGN.md names this file the home of its
 * tokens, so these literals are the definition, not a copy. TokensTest asserts
 * every one of them against the §2 tables.
 */
object Tokens {

    // --- Brand & light surfaces ---
    val accent = Color(0xFF387AFF)
    val accentPressed = Color(0xFF2864D9)

    /** A filled primary button: white text on [accent] would miss 4.5:1, so it is darker. */
    val primaryFill = Color(0xFF2864D9)
    val onAccent = Color(0xFFFFFFFF)
    val canvasLight = Color(0xFFFCFCFF)
    val surfaceSoft = Color(0xFFFCFCFF)
    val surfaceCard = Color(0xFFF5F4F6)
    val surfaceSecondary = Color(0xFFEBEFF5)
    val hairline = Color(0xFFE6E6E9)
    val ink = Color(0xFF000000)
    val inkSecondary = Color(0xFF4D4D52)
    val inkMuted = Color(0xFF8E8E92)
    val selectionFill = Color(0xFFD9E2F6)
    val selectionBorder = Color(0xFF8FB2F5)
    val promptTint = Color(0xFFD7E4FF)

    // --- Editor & glass ---
    val editBackground = Color(0xFF1C1C1E)
    val editSurface = Color(0xFFFCFCFF)
    val editSurfaceRaised = Color(0xFFF5F4F6)
    val editHairline = Color(0xFFE6E6E9)
    val editInk = Color(0xFF000000)
    val editInkSecondary = Color(0xFF4D4D52)

    /** Text and guides over the dark canvas or a progress scrim; never the controls' [editInk]. */
    val canvasOverlayInk = Color(0xFFFCFCFF)
    val canvasCheckerA = Color(0xFF2C2C2E)
    val canvasCheckerB = Color(0xFF242426)
    val sheetGlassTop = Color(0xF5F7F8FB)
    val sheetGlassBottom = Color(0xF0E6E9F1)
    val sheetEdge = Color(0xCCFFFFFF)
    val sheetHandle = Color(0xFFB4B6BC)
    val pillStatus = Color(0xFFA8A8AC)

    // --- Semantic ---
    val success = Color(0xFF1E7A46)
    val warning = Color(0xFFB8741A)

    /** DESIGN.md §2: HighlightRed, kept apart from the blue brand. */
    val error = Color(0xFFFF3B30)

    /**
     * DESIGN.md §2 asks small link and error text for 4.5:1 on its real background. [accent]
     * and [error] fall short on the glass sheet, so text uses these darker siblings — chosen to
     * hold 4.5:1 even where the sheet's bottom edge sits over black.
     */
    val linkText = Color(0xFF1F57C7)
    val errorText = Color(0xFFB3261E)
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
}

/**
 * DESIGN.md §3: the Android system sans-serif, as the reference app uses. The bundled
 * Pretendard file stays in the resources; no style names it any more.
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

/**
 * DESIGN.md §6: only the glass sheet casts a shadow, with a 1dp light edge; a photo's
 * iconCircle may lift 2dp. Everything else is flat.
 */
object Elevation {
    val sheetShadow = 10.dp
    val sheetEdgeWidth = 1.dp
    val iconCircle = 2.dp
}
