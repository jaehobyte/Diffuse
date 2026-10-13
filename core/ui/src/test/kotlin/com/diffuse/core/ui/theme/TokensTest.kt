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
        // Brand & light surfaces
        ColorRow("accent", Tokens.accent, 0xFF387AFF),
        ColorRow("accentPressed", Tokens.accentPressed, 0xFF2864D9),
        ColorRow("primaryFill", Tokens.primaryFill, 0xFF2864D9),
        ColorRow("onAccent", Tokens.onAccent, 0xFFFFFFFF),
        ColorRow("canvasLight", Tokens.canvasLight, 0xFFFCFCFF),
        ColorRow("surfaceSoft", Tokens.surfaceSoft, 0xFFFCFCFF),
        ColorRow("surfaceCard", Tokens.surfaceCard, 0xFFF5F4F6),
        ColorRow("surfaceSecondary", Tokens.surfaceSecondary, 0xFFEBEFF5),
        ColorRow("hairline", Tokens.hairline, 0xFFE6E6E9),
        ColorRow("ink", Tokens.ink, 0xFF000000),
        ColorRow("inkSecondary", Tokens.inkSecondary, 0xFF4D4D52),
        ColorRow("inkMuted", Tokens.inkMuted, 0xFF8E8E92),
        ColorRow("selectionFill", Tokens.selectionFill, 0xFFD9E2F6),
        ColorRow("selectionBorder", Tokens.selectionBorder, 0xFF8FB2F5),
        ColorRow("promptTint", Tokens.promptTint, 0xFFD7E4FF),
        // Editor & glass
        ColorRow("editBackground", Tokens.editBackground, 0xFF1C1C1E),
        ColorRow("editSurface", Tokens.editSurface, 0xFFFCFCFF),
        ColorRow("editSurfaceRaised", Tokens.editSurfaceRaised, 0xFFF5F4F6),
        ColorRow("editHairline", Tokens.editHairline, 0xFFE6E6E9),
        ColorRow("editInk", Tokens.editInk, 0xFF000000),
        ColorRow("editInkSecondary", Tokens.editInkSecondary, 0xFF4D4D52),
        ColorRow("canvasOverlayInk", Tokens.canvasOverlayInk, 0xFFFCFCFF),
        ColorRow("canvasCheckerA", Tokens.canvasCheckerA, 0xFF2C2C2E),
        ColorRow("canvasCheckerB", Tokens.canvasCheckerB, 0xFF242426),
        ColorRow("sheetGlassTop", Tokens.sheetGlassTop, 0xF5F7F8FB),
        ColorRow("sheetGlassBottom", Tokens.sheetGlassBottom, 0xF0E6E9F1),
        ColorRow("sheetEdge", Tokens.sheetEdge, 0xCCFFFFFF),
        ColorRow("sheetHandle", Tokens.sheetHandle, 0xFFB4B6BC),
        ColorRow("pillStatus", Tokens.pillStatus, 0xFFA8A8AC),
        // Semantic
        ColorRow("success", Tokens.success, 0xFF1E7A46),
        ColorRow("warning", Tokens.warning, 0xFFB8741A),
        ColorRow("error", Tokens.error, 0xFFFF3B30),
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
    fun designDocDeclaresThirtyTwoColorTokens() {
        assertEquals(32, colorRows.size, "DESIGN.md §2 lists 32 color tokens")
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
    fun browseAndEditPalettesUseTheirOwnSurfaces() {
        assertEquals(Tokens.surfaceSoft, BrowseColors.background)
        assertEquals(Tokens.canvasLight, BrowseColors.surface)
        assertEquals(Tokens.surfaceCard, BrowseColors.surfaceRaised)
        assertEquals(Tokens.editBackground, EditColors.background)
        assertEquals(Tokens.editSurface, EditColors.surface)
        assertEquals(Tokens.editSurfaceRaised, EditColors.surfaceRaised)
        // DESIGN.md §4: the secondary button is surfaceSecondary in both modes.
        assertEquals(Tokens.surfaceSecondary, BrowseColors.surfaceSecondary)
        assertEquals(Tokens.surfaceSecondary, EditColors.surfaceSecondary)
    }

    @Test
    fun editControlsAreLightWithBlackText() {
        // DESIGN.md §1, §9: Edit is not a dark theme — only the canvas surround is dark.
        assertEquals(Tokens.editInk, EditColors.ink)
        assertEquals(Tokens.ink, EditColors.ink)
        assertTrue(EditColors.surface.luminance() > LIGHT_SURFACE)
        assertTrue(EditColors.background.luminance() < DARK_SURFACE)
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
    fun smallTextKeepsFourAndAHalfToOneOnTheGlassSheet() {
        // DESIGN.md §2: link and error text at 4.5:1 on its real background — the sheet's bottom
        // edge is the darkest it gets, where the 94% glass sits over black.
        val glassOverBlack = Tokens.sheetGlassBottom.compositeOver(Color.Black)
        listOf(
            "linkText" to Tokens.linkText,
            "errorText" to Tokens.errorText,
            "inkSecondary" to Tokens.inkSecondary,
        ).forEach { (name, color) ->
            assertTrue(contrast(color, glassOverBlack) >= MIN_TEXT_CONTRAST, "$name on glass")
            assertTrue(contrast(color, Tokens.canvasLight) >= MIN_TEXT_CONTRAST, "$name on canvasLight")
        }
        assertTrue(contrast(Tokens.onAccent, Tokens.primaryFill) >= MIN_TEXT_CONTRAST, "onAccent on primaryFill")
        // §4 State display: overlay text on the 40% black scrim over a mid-grey photo. Over a
        // white one the scrim alone is not enough, which is what OverlayTextShadow is for.
        val scrimOverGrey = Color.Black.copy(alpha = 0.4f).compositeOver(Color(0xFF808080))
        assertTrue(contrast(Tokens.canvasOverlayInk, scrimOverGrey) >= MIN_TEXT_CONTRAST, "overlay ink")
        assertTrue(OverlayTextShadow.color.alpha > 0f && OverlayTextShadow.blurRadius > 0f, "overlay shadow")
    }

    @Test
    fun onlyTheGlassSheetCastsAShadow() {
        // DESIGN.md §6: the sheet's 10dp shadow and 1dp edge; iconCircle at most 2dp.
        assertEquals(10f, Elevation.sheetShadow.value)
        assertEquals(1f, Elevation.sheetEdgeWidth.value)
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
