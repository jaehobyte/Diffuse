package com.diffuse.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Guards against drift from DESIGN.md. §2 declares Tokens.kt the source of truth
 * for values, so every row of the §2 and §3 tables is asserted here verbatim.
 */
class TokensTest {

    private data class ColorRow(val name: String, val actual: Color, val expected: Long)

    private data class TextRow(
        val name: String,
        val actual: TextStyle,
        val sizeSp: Float,
        val weight: Int,
        val lineHeightSp: Float,
        val letterSpacingSp: Float,
    )

    private val colorRows = listOf(
        // Brand
        ColorRow("accent", Tokens.accent, 0xFFE60023),
        ColorRow("accentPressed", Tokens.accentPressed, 0xFFCC001F),
        ColorRow("onAccent", Tokens.onAccent, 0xFFFFFFFF),
        // Browse mode (light)
        ColorRow("canvasLight", Tokens.canvasLight, 0xFFFFFFFF),
        ColorRow("surfaceSoft", Tokens.surfaceSoft, 0xFFFBFBF9),
        ColorRow("surfaceCard", Tokens.surfaceCard, 0xFFF6F6F3),
        ColorRow("surfaceSecondary", Tokens.surfaceSecondary, 0xFFE5E5E0),
        ColorRow("hairline", Tokens.hairline, 0xFFDADAD3),
        ColorRow("ink", Tokens.ink, 0xFF000000),
        ColorRow("inkSecondary", Tokens.inkSecondary, 0xFF5F5F5A),
        // Edit mode (dark)
        ColorRow("editBackground", Tokens.editBackground, 0xFF1C1C19),
        ColorRow("editSurface", Tokens.editSurface, 0xFF262622),
        ColorRow("editSurfaceRaised", Tokens.editSurfaceRaised, 0xFF33332E),
        ColorRow("editHairline", Tokens.editHairline, 0xFF3F3F39),
        ColorRow("editInk", Tokens.editInk, 0xFFF6F6F3),
        ColorRow("editInkSecondary", Tokens.editInkSecondary, 0xFFA3A39B),
        ColorRow("canvasCheckerA", Tokens.canvasCheckerA, 0xFF2E2E2A),
        ColorRow("canvasCheckerB", Tokens.canvasCheckerB, 0xFF262622),
        // Semantic
        ColorRow("success", Tokens.success, 0xFF1E7A46),
        ColorRow("warning", Tokens.warning, 0xFFB8741A),
        ColorRow("error", Tokens.error, 0xFFE60023),
    )

    private val textRows = listOf(
        TextRow("headingXl", Typography.headingXl, 28f, 700, 33.6f, -0.5f),
        TextRow("headingLg", Typography.headingLg, 22f, 600, 27.5f, 0f),
        TextRow("headingMd", Typography.headingMd, 18f, 600, 23.4f, 0f),
        TextRow("bodyMd", Typography.bodyMd, 16f, 400, 22.4f, 0f),
        TextRow("bodyStrong", Typography.bodyStrong, 16f, 600, 22.4f, 0f),
        TextRow("bodySm", Typography.bodySm, 14f, 400, 19.6f, 0f),
        TextRow("label", Typography.label, 12f, 500, 15.6f, 0.2f),
        TextRow("mono", Typography.mono, 13f, 500, 16.9f, 0f),
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
    fun designDocDeclaresTwentyOneColorTokens() {
        assertEquals(21, colorRows.size, "DESIGN.md §2 lists 21 color tokens")
    }

    @TestFactory
    fun everyTextStyleMatchesDesignDoc(): List<DynamicTest> = textRows.map { row ->
        DynamicTest.dynamicTest("${row.name} is ${row.sizeSp}sp/${row.weight}") {
            assertEquals(row.sizeSp, row.actual.fontSize.value, "${row.name} fontSize")
            assertEquals(row.weight, row.actual.fontWeight?.weight, "${row.name} fontWeight")
            assertEquals(row.lineHeightSp, row.actual.lineHeight.value, 0.01f, "${row.name} lineHeight")
            assertEquals(
                row.letterSpacingSp,
                row.actual.letterSpacing.value,
                0.01f,
                "${row.name} letterSpacing",
            )
        }
    }

    @Test
    fun designDocDeclaresEightTextTokens() {
        assertEquals(8, textRows.size, "DESIGN.md §3 lists 8 text tokens")
    }

    @Test
    fun browseAndEditPalettesUseTheirOwnSurfaces() {
        assertEquals(Tokens.surfaceSoft, BrowseColors.background)
        assertEquals(Tokens.canvasLight, BrowseColors.surface)
        assertEquals(Tokens.surfaceCard, BrowseColors.surfaceRaised)
        assertEquals(Tokens.editBackground, EditColors.background)
        assertEquals(Tokens.editSurface, EditColors.surface)
        assertEquals(Tokens.editSurfaceRaised, EditColors.surfaceRaised)
        // DESIGN.md §4: secondary button is surfaceSecondary, editSurfaceRaised in dark.
        assertEquals(Tokens.surfaceSecondary, BrowseColors.surfaceSecondary)
        assertEquals(Tokens.editSurfaceRaised, EditColors.surfaceSecondary)
    }

    @Test
    fun bothPalettesShareTheBrandAccent() {
        // DESIGN.md §2: the accent is the same value in both modes.
        assertEquals(Tokens.accent, BrowseColors.accent)
        assertEquals(Tokens.accent, EditColors.accent)
    }

    @Test
    fun singleShadowLevelMatchesDesignDoc() {
        // DESIGN.md §6: one shadow level only, 0 2dp 8dp #000000 12%.
        assertEquals(2f, Elevation.shadowOffsetY.value)
        assertEquals(8f, Elevation.shadowBlur.value)
        assertEquals(0x1F000000L, Elevation.shadowColor.toArgb().toLong() and 0xFFFFFFFFL)
        assertEquals(1f, Elevation.darkBorderWidth.value)
    }
}
