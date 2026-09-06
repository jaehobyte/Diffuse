package com.diffuse.feature.editor.tools.select

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.sam3.Sam3Config
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** specs/generative_erase.md §8: one 서버 설정 sheet, three fields. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Sam3SettingsSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val saved = mutableListOf<Triple<String, String, String>>()

    @Test
    fun `save carries the base URL, the token and the Gemini key`() {
        showSheet()

        compose.onNodeWithTag(GeminiKeyFieldTestTag).performTextReplacement("AIza-new")
        compose.onNodeWithText("저장").performClick()

        assertEquals(
            listOf(Triple("http://host:8080", "tok", "AIza-new")),
            saved,
        )
    }

    @Test
    fun `an unedited sheet saves back what it was given`() {
        showSheet()

        compose.onNodeWithText("저장").performClick()

        assertEquals(listOf(Triple("http://host:8080", "tok", "AIza-old")), saved)
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

    private fun showSheet() {
        compose.setContent {
            AppTheme(mode = ThemeMode.Edit) {
                Sam3SettingsSheet(
                    config = Sam3Config(baseUrl = "http://host:8080", token = "tok"),
                    geminiApiKey = "AIza-old",
                    onSave = { baseUrl, token, key -> saved += Triple(baseUrl, token, key) },
                    onCancel = {},
                )
            }
        }
    }
}
