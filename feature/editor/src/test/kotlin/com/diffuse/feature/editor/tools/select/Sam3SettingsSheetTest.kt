package com.diffuse.feature.editor.tools.select

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.monet.MonetConfig
import com.diffuse.core.ai.sam3.Sam3Config
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** specs/generative_erase.md §8 and auto_enhance.md §4: one 서버 설정 sheet, five fields. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Sam3SettingsSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private data class Saved(
        val baseUrl: String,
        val token: String,
        val geminiApiKey: String,
        val monetBaseUrl: String,
        val monetToken: String,
    )

    private val saved = mutableListOf<Saved>()

    @Test
    fun `save carries the base URL, the token and the Gemini key`() {
        showSheet()

        compose.onNodeWithTag(GeminiKeyFieldTestTag).performTextReplacement("AIza-new")
        compose.onNodeWithText("저장").performClick()

        assertEquals(
            listOf(Saved("http://host:8080", "tok", "AIza-new", "http://monet:9090", "m-tok")),
            saved,
        )
    }

    /**
     * specs/auto_enhance.md §4. Without this field 자동 보정 is unreachable in a published APK:
     * no address ships, and `auto_needs_server` points at a sheet that had nowhere to type one.
     */
    @Test
    fun `save carries the 자동 보정 server too`() {
        showSheet()

        compose.onNodeWithTag(MonetBaseUrlFieldTestTag).performTextReplacement("http://monet:1")
        compose.onNodeWithTag(MonetTokenFieldTestTag).performTextReplacement("m-new")
        compose.onNodeWithText("저장").performClick()

        assertEquals(
            listOf(Saved("http://host:8080", "tok", "AIza-old", "http://monet:1", "m-new")),
            saved,
        )
    }

    /** 자동 보정 is usable without SAM 3, so its address alone must enable 저장. */
    @Test
    fun `the 자동 보정 address alone enables save`() {
        showSheet(sam3 = Sam3Config(baseUrl = "", token = ""))

        compose.onNodeWithTag(MonetBaseUrlFieldTestTag).performTextReplacement("http://monet:1")
        compose.onNodeWithText("저장").performClick()

        assertEquals(
            listOf(Saved("", "", "AIza-old", "http://monet:1", "m-tok")),
            saved,
        )
    }

    /** specs/auto_enhance.md §4: a typo is refused at 저장, and fixing it lets the save through. */
    @Test
    fun `a malformed 자동 보정 address blocks save until it is fixed`() {
        showSheet()

        compose.onNodeWithTag(MonetBaseUrlFieldTestTag).performTextReplacement("monet:9090")
        compose.onNodeWithText("http:// 또는 https://로 시작하는 주소를 입력해주세요").assertExists()
        compose.onNodeWithText("저장").performClick()
        assertTrue(saved.isEmpty())

        compose.onNodeWithTag(MonetBaseUrlFieldTestTag).performTextReplacement("https://monet.example")
        compose.onNodeWithText("저장").performClick()

        assertEquals("https://monet.example", saved.single().monetBaseUrl)
    }

    @Test
    fun `an unedited sheet saves back what it was given`() {
        showSheet()

        compose.onNodeWithText("저장").performClick()

        assertEquals(
            listOf(Saved("http://host:8080", "tok", "AIza-old", "http://monet:9090", "m-tok")),
            saved,
        )
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

    private fun showSheet(sam3: Sam3Config = Sam3Config(baseUrl = "http://host:8080", token = "tok")) {
        compose.setContent {
            AppTheme(mode = ThemeMode.Edit) {
                Sam3SettingsSheet(
                    config = sam3,
                    geminiApiKey = "AIza-old",
                    monetConfig = MonetConfig(baseUrl = "http://monet:9090", token = "m-tok"),
                    onSave = { baseUrl, token, key, monetUrl, monetToken ->
                        saved += Saved(baseUrl, token, key, monetUrl, monetToken)
                    },
                    onCancel = {},
                )
            }
        }
    }
}
