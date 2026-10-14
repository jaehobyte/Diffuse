package com.diffuse.feature.editor.tools.select

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.sam3.Sam3Config
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * specs/segmentation.md §6, generative_erase.md §8 and topaz_enhancement.md §4: one 서버 설정
 * sheet, four fields. The 자동 보정 server it used to carry is gone with the tool (D097).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Sam3SettingsSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private data class Saved(
        val baseUrl: String,
        val token: String,
        val geminiApiKey: String,
        val topazApiKey: String = TOPAZ_OLD,
    )

    private val saved = mutableListOf<Saved>()
    private var cancelled = 0

    @Test
    fun `save carries the base URL, the token and the Gemini key`() {
        showSheet()

        compose.onNodeWithTag(GeminiKeyFieldTestTag).performTextReplacement("AIza-new")
        compose.onNodeWithText("저장").performClick()

        assertEquals(listOf(Saved("http://host:8080", "tok", "AIza-new")), saved)
    }

    /** D097: the sheet carries SAM 3 and Gemini only; no 자동 보정 field or hint is left behind. */
    @Test
    fun `the sheet has no 자동 보정 server fields`() {
        showSheet()

        compose.onNodeWithTag("MonetBaseUrl").assertDoesNotExist()
        compose.onNodeWithTag("MonetToken").assertDoesNotExist()
        compose.onNodeWithText("자동 보정", substring = true).assertDoesNotExist()
    }

    /** Gemini is usable without SAM 3, so a key alone must be savable. */
    @Test
    fun `the Gemini key alone saves without a SAM 3 address`() {
        showSheet(sam3 = Sam3Config(baseUrl = "", token = ""), geminiApiKey = "")

        compose.onNodeWithTag(GeminiKeyFieldTestTag).performTextReplacement("AIza-only")
        compose.onNodeWithText("저장").assertIsEnabled().performClick()

        assertEquals(listOf(Saved("", "", "AIza-only")), saved)
    }

    /** SAM 3 is usable without Gemini, so its address alone must be savable. */
    @Test
    fun `the SAM 3 server alone saves without a Gemini key`() {
        showSheet(sam3 = Sam3Config(baseUrl = "", token = ""), geminiApiKey = "")

        compose.onNodeWithTag(Sam3BaseUrlFieldTestTag).performTextReplacement("http://sam:8080")
        compose.onNodeWithTag(Sam3TokenFieldTestTag).performTextReplacement("s-tok")
        compose.onNodeWithText("저장").assertIsEnabled().performClick()

        assertEquals(listOf(Saved("http://sam:8080", "s-tok", "")), saved)
    }

    /** Clearing a value is a save too; nothing gates 저장 on a field being filled. */
    @Test
    fun `clearing every value still saves`() {
        showSheet()

        compose.onNodeWithTag(Sam3BaseUrlFieldTestTag).performTextReplacement("")
        compose.onNodeWithTag(Sam3TokenFieldTestTag).performTextReplacement("")
        compose.onNodeWithTag(GeminiKeyFieldTestTag).performTextReplacement("")
        compose.onNodeWithText("저장").assertIsEnabled().performClick()

        assertEquals(listOf(Saved("", "", "")), saved)
    }

    @Test
    fun `cancel saves none of the edits`() {
        showSheet()

        compose.onNodeWithTag(Sam3BaseUrlFieldTestTag).performTextReplacement("http://other:1")
        compose.onNodeWithTag(GeminiKeyFieldTestTag).performTextReplacement("AIza-new")
        compose.onNodeWithText("취소").performClick()

        assertEquals(1, cancelled)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `an unedited sheet saves back what it was given`() {
        showSheet()

        compose.onNodeWithText("저장").performClick()

        assertEquals(listOf(Saved("http://host:8080", "tok", "AIza-old")), saved)
    }

    /** The key is masked, so neither a shoulder-surfer nor a screenshot reads it. */
    @Test
    fun `the key field renders as dots, never as the key itself`() {
        showSheet()

        val node = compose.onNodeWithTag(GeminiKeyFieldTestTag).fetchSemanticsNode()

        assertTrue(SemanticsProperties.Password in node.config)
        assertEquals(
            "•".repeat("AIza-old".length),
            node.config[SemanticsProperties.EditableText].text,
        )
    }

    /**
     * DESIGN.md §4: the sheet at large type. The 45% cap stays, the fields scroll inside it, and
     * the pinned 저장 is still on screen — nothing is clipped away to make room.
     */
    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a, fontScale = 2.0f)
    fun `at twice the font size the last field scrolls into reach and 저장 stays on screen`() {
        showSheet()

        compose.onNodeWithTag(GeminiKeyFieldTestTag).performScrollTo().performTextReplacement("AIza-big")
        compose.onNodeWithText("저장").assertIsDisplayed().performClick()

        assertEquals(listOf(Saved("http://host:8080", "tok", "AIza-big")), saved)
        val screen = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics.heightPixels
        val sheet = compose.onNodeWithTag(Sam3SettingsSheetTestTag).fetchSemanticsNode().size.height
        assertTrue("sheet took $sheet of $screen", sheet <= screen * 0.45f + 1f)
    }

    /** specs/topaz_enhancement.md §4: the Topaz key saves, and clears, on its own. */
    @Test
    fun `the Topaz key is masked and saves or clears without touching the others`() {
        showSheet()

        val field = compose.onNodeWithTag(TopazKeyFieldTestTag).performScrollTo()
        field.performTextReplacement("tp-new")
        compose.onNodeWithText("저장").performClick()
        compose.onNodeWithTag(TopazKeyFieldTestTag).performTextReplacement("")
        compose.onNodeWithText("저장").performClick()

        assertEquals(
            listOf(
                Saved("http://host:8080", "tok", "AIza-old", "tp-new"),
                Saved("http://host:8080", "tok", "AIza-old", ""),
            ),
            saved,
        )
    }

    @Test
    fun `a typed Topaz key is shown as dots`() {
        showSheet(topazApiKey = "tp-secret")

        val shown = compose.onNodeWithTag(TopazKeyFieldTestTag).performScrollTo().fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text
        assertTrue("got '$shown'", !shown.contains("tp-secret"))
    }

    @Test
    fun `cancel saves no Topaz key`() {
        showSheet()

        compose.onNodeWithTag(TopazKeyFieldTestTag).performScrollTo().performTextReplacement("tp-typed")
        compose.onNodeWithText("취소").performClick()

        assertTrue(saved.isEmpty())
        assertEquals(1, cancelled)
    }

    private fun showSheet(
        sam3: Sam3Config = Sam3Config(baseUrl = "http://host:8080", token = "tok"),
        geminiApiKey: String = "AIza-old",
        topazApiKey: String = TOPAZ_OLD,
    ) {
        compose.setContent {
            AppTheme(mode = ThemeMode.Edit) {
                Sam3SettingsSheet(
                    config = sam3,
                    geminiApiKey = geminiApiKey,
                    topazApiKey = topazApiKey,
                    onSave = { baseUrl, token, key, topaz -> saved += Saved(baseUrl, token, key, topaz) },
                    onCancel = { cancelled++ },
                )
            }
        }
    }

    private companion object {
        const val TOPAZ_OLD = "tp-old"
    }
}
