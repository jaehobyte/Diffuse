package com.diffuse.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.isUnspecified
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Guards against drift from DESIGN.md. §2 names Tokens.kt as the home of its tokens,
 * so every row of the §2 and §3 tables is asserted here verbatim.
 */
class TokensTest {

    private data class ColorRow(val name: String, val actual: Color, val expected: Long)

    private data class TextRow(
        val name: String,
        val actual: TextStyle,
        val sizeSp: Float,
        val weight: Int,
        val lineHeightSp: Float,
    )

    private val colorRows = listOf(
        // Brand & selection
        ColorRow("accent", Tokens.accent, 0xFFF9DA4B),
        ColorRow("accentSoft", Tokens.accentSoft, 0xFFF9E16E),
        ColorRow("accentPressed", Tokens.accentPressed, 0xFFE3C53A),
        ColorRow("primaryFill", Tokens.primaryFill, 0xFF2F6BEB),
        ColorRow("onAccent", Tokens.onAccent, 0xFFFFFFFF),
        ColorRow("selectionFill", Tokens.selectionFill, 0xFFFAFAFA),
        ColorRow("selectionInk", Tokens.selectionInk, 0xFF000000),
        ColorRow("promptTint", Tokens.promptTint, 0xFF2A3346),
        // Surfaces & ink
        ColorRow("background", Tokens.background, 0xFF000000),
        ColorRow("surface", Tokens.surface, 0xFF000000),
        ColorRow("surfaceRaised", Tokens.surfaceRaised, 0xFF202023),
        ColorRow("surfaceSecondary", Tokens.surfaceSecondary, 0xFF2C2C30),
        ColorRow("surfaceMenu", Tokens.surfaceMenu, 0xFF272727),
        ColorRow("hairline", Tokens.hairline, 0xFF3A3A3D),
        ColorRow("ink", Tokens.ink, 0xFFFAFAFA),
        ColorRow("inkSecondary", Tokens.inkSecondary, 0xFFA6A6A7),
        ColorRow("inkMuted", Tokens.inkMuted, 0xFF6E6E70),
        // Editor
        ColorRow("editBackground", Tokens.editBackground, 0xFF000000),
        ColorRow("editSurface", Tokens.editSurface, 0xFF000000),
        ColorRow("editSurfaceRaised", Tokens.editSurfaceRaised, 0xFF202023),
        ColorRow("editHairline", Tokens.editHairline, 0xFF3A3A3D),
        ColorRow("editInk", Tokens.editInk, 0xFFFAFAFA),
        ColorRow("editInkSecondary", Tokens.editInkSecondary, 0xFFA6A6A7),
        ColorRow("canvasOverlayInk", Tokens.canvasOverlayInk, 0xFFFAFAFA),
        ColorRow("canvasCheckerA", Tokens.canvasCheckerA, 0xFF2C2C2E),
        ColorRow("canvasCheckerB", Tokens.canvasCheckerB, 0xFF242426),
        ColorRow("sliderTickMajor", Tokens.sliderTickMajor, 0xFF8D8D8E),
        ColorRow("sliderTickMinor", Tokens.sliderTickMinor, 0xFF4D4D4F),
        ColorRow("aiGradientStart", Tokens.aiGradientStart, 0xFF5C8AE6),
        ColorRow("aiGradientEnd", Tokens.aiGradientEnd, 0xFF9D7BD8),
        // Semantic
        ColorRow("success", Tokens.success, 0xFF3DBE6A),
        ColorRow("warning", Tokens.warning, 0xFFF2A33C),
        ColorRow("error", Tokens.error, 0xFFFF3B30),
        ColorRow("linkText", Tokens.linkText, 0xFF6FA3FF),
        ColorRow("errorText", Tokens.errorText, 0xFFFF6B5E),
    )

    private val textRows = listOf(
        TextRow("headingXl", Typography.headingXl, 27f, 700, 32f),
        TextRow("headingLg", Typography.headingLg, 17f, 700, 24f),
        TextRow("headingMd", Typography.headingMd, 13f, 600, 19f),
        TextRow("bodyMd", Typography.bodyMd, 14f, 400, 21f),
        TextRow("bodyStrong", Typography.bodyStrong, 14f, 600, 21f),
        TextRow("bodySm", Typography.bodySm, 13f, 400, 20f),
        TextRow("label", Typography.label, 12f, 400, 17f),
        TextRow("labelSmall", Typography.labelSmall, 11f, 600, 15f),
        TextRow("link", Typography.link, 13f, 600, 18f),
        TextRow("mono", Typography.mono, 13f, 500, 18f),
    )

    @TestFactory
    fun everyColorTokenMatchesDesignDoc(): List<DynamicTest> = colorRows.map { row ->
        DynamicTest.dynamicTest("${row.name} is ${"%08X".format(row.expected)}") {
            assertEquals(
                row.expected,
                row.actual.toArgb().toLong() and 0xFFFFFFFFL,
                "Tokens.${row.name} does not match DESIGN.md §2",
            )
        }
    }

    @Test
    fun designDocDeclaresThirtyFiveColorTokens() {
        assertEquals(35, colorRows.size, "DESIGN.md §2 lists 35 color tokens")
    }

    @TestFactory
    fun everyTextStyleMatchesDesignDoc(): List<DynamicTest> = textRows.map { row ->
        DynamicTest.dynamicTest("${row.name} is ${row.sizeSp}sp/${row.weight}") {
            assertEquals(row.sizeSp, row.actual.fontSize.value, "${row.name} fontSize")
            assertEquals(row.weight, row.actual.fontWeight?.weight, "${row.name} fontWeight")
            assertEquals(row.lineHeightSp, row.actual.lineHeight.value, 0.01f, "${row.name} lineHeight")
            // §3: letter spacing is the default, so no style sets one.
            assertTrue(row.actual.letterSpacing.isUnspecified, "${row.name} letterSpacing")
            // §3: the system sans-serif; only `mono` keeps a bundled face.
            if (row.name != "mono") assertEquals(FontFamily.Default, row.actual.fontFamily, "${row.name} font")
        }
    }

    @Test
    fun designDocDeclaresTenTextTokens() {
        assertEquals(10, textRows.size, "DESIGN.md §3 lists 10 text tokens")
    }

    @Test
    fun browseAndEditShareOneBlackPalette() {
        // DESIGN.md §1: the Photo Editor and Gallery are the same black; so are the two modes.
        assertEquals(Tokens.background, BrowseColors.background)
        assertEquals(Tokens.surface, BrowseColors.surface)
        assertEquals(Tokens.surfaceRaised, BrowseColors.surfaceRaised)
        assertEquals(Tokens.editBackground, EditColors.background)
        assertEquals(Tokens.editSurface, EditColors.surface)
        assertEquals(Tokens.editSurfaceRaised, EditColors.surfaceRaised)
        assertEquals(BrowseColors, EditColors)
        // DESIGN.md §4: the secondary button is surfaceSecondary in both modes.
        assertEquals(Tokens.surfaceSecondary, BrowseColors.surfaceSecondary)
        assertEquals(Tokens.surfaceSecondary, EditColors.surfaceSecondary)
    }

    @Test
    fun editControlsAreWhiteOnBlack() {
        // DESIGN.md §1: black everywhere, white ink; selection inverts to white with black ink.
        assertEquals(Tokens.editInk, EditColors.ink)
        assertEquals(Tokens.ink, EditColors.ink)
        assertTrue(EditColors.surface.luminance() < DARK_SURFACE)
        assertTrue(EditColors.background.luminance() < DARK_SURFACE)
        assertTrue(EditColors.ink.luminance() > LIGHT_SURFACE)
        assertTrue(Tokens.selectionFill.luminance() > LIGHT_SURFACE)
        assertTrue(Tokens.selectionInk.luminance() < DARK_SURFACE)
    }

    @Test
    fun bothPalettesShareTheBrandRoles() {
        // DESIGN.md §2: accent marks selection; primaryFill fills the primary button.
        assertEquals(Tokens.accent, BrowseColors.accent)
        assertEquals(Tokens.accent, EditColors.accent)
        assertEquals(Tokens.primaryFill, BrowseColors.primaryFill)
        assertEquals(Tokens.primaryFill, EditColors.primaryFill)
    }

    @Test
    fun smallTextKeepsFourAndAHalfToOneOnBlackAndRaised() {
        // DESIGN.md §2: link, error and secondary text at 4.5:1 on black and on surfaceRaised,
        // the two surfaces small text sits on.
        listOf(
            "linkText" to Tokens.linkText,
            "errorText" to Tokens.errorText,
            "inkSecondary" to Tokens.inkSecondary,
            "ink" to Tokens.ink,
        ).forEach { (name, color) ->
            assertTrue(contrast(color, Tokens.background) >= MIN_TEXT_CONTRAST, "$name on black")
            assertTrue(contrast(color, Tokens.surfaceRaised) >= MIN_TEXT_CONTRAST, "$name on surfaceRaised")
        }
        assertTrue(contrast(Tokens.onAccent, Tokens.primaryFill) >= MIN_TEXT_CONTRAST, "onAccent on primaryFill")
        assertTrue(contrast(Tokens.selectionInk, Tokens.selectionFill) >= MIN_TEXT_CONTRAST, "selectionInk")
        assertTrue(contrast(Tokens.selectionInk, Tokens.accentSoft) >= MIN_TEXT_CONTRAST, "value over wedge")
        assertTrue(contrast(Tokens.accent, Tokens.background) >= MIN_TEXT_CONTRAST, "accent text on black")
        // §4 State display: overlay text on the 40% black scrim over a mid-grey photo. Over a
        // white one the scrim alone is not enough, which is what OverlayTextShadow is for.
        val scrimOverGrey = Color.Black.copy(alpha = 0.4f).compositeOver(Color(0xFF808080))
        assertTrue(contrast(Tokens.canvasOverlayInk, scrimOverGrey) >= MIN_TEXT_CONTRAST, "overlay ink")
        assertTrue(OverlayTextShadow.color.alpha > 0f && OverlayTextShadow.blurRadius > 0f, "overlay shadow")
    }

    @Test
    fun onlyThePhotoIconCircleLifts() {
        // DESIGN.md §6: no shadows; a photo's iconCircle at most 2dp.
        assertEquals(2f, Elevation.iconCircle.value)
    }

    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    private companion object {
        const val MIN_TEXT_CONTRAST = 4.5f
        const val LIGHT_SURFACE = 0.9f
        const val DARK_SURFACE = 0.05f
    }
}
