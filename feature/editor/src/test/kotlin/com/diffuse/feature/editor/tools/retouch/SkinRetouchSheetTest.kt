package com.diffuse.feature.editor.tools.retouch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.SkinRetouchKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.ui.components.EditSheetTestTag
import com.diffuse.feature.editor.EditorScreen
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.Tool
import com.diffuse.feature.editor.ToolLevelState
import com.diffuse.feature.editor.ToolMenuProfile
import com.diffuse.feature.editor.canvas.testImage
import com.diffuse.feature.editor.tools.ToolSheetHost
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * specs/skin_retouch.md §1, §4. The tab really opens a sheet of its own, and the sheet says why
 * nothing in it works yet.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The portrait root is six items plus AI, and the strip is a `LazyRow`.
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class SkinRetouchSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val document = EditDocument("d", ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L)

    @Test
    fun `tapping 피부 보정 opens its own sheet`() {
        showEditor()
        compose.onNodeWithTag(EditSheetTestTag).assertDoesNotExist()

        compose.onNodeWithTag(toolTag(Tool.SkinRetouch)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(SkinRetouchSheetTestTag).assertExists()
        // Scoped to the sheet: the strip item beneath it carries the same label.
        compose.onNode(
            hasText(string(R.string.skin_retouch_title)) and
                hasAnyAncestor(hasTestTag(SkinRetouchSheetTestTag)),
        ).assertExists()
    }

    /** §1: the reason is the engine, and it is stated rather than left to an empty sheet. */
    @Test
    fun `the sheet says the feature is being prepared`() {
        openSheet()

        compose.onNodeWithTag(SkinRetouchStatusTestTag).assertExists()
        compose.onNodeWithText("피부 보정 기능을 준비 중이에요").assertExists()
    }

    @Test
    fun `all four corrections are listed and marked unsupported`() {
        openSheet()

        SkinRetouchKind.entries.forEach {
            compose.onNodeWithTag(skinRetouchRowTag(it)).assertExists()
            compose.onNodeWithText(labelOf(it)).assertExists()
        }
        // §1: every one of them, not just the ones with no engine — there is no engine at all.
        compose.onAllNodesWithText(string(R.string.skin_retouch_unsupported))
            .assertCountEquals(SkinRetouchKind.entries.size)
    }

    @Test
    fun `적용 is disabled and 취소 closes the sheet`() {
        openSheet()

        // core/ui's fixed action row: 적용 is the primary pill, 취소 the tertiary one.
        compose.onNodeWithText("적용").assertIsNotEnabled()

        compose.onNodeWithText("취소").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(SkinRetouchSheetTestTag).assertDoesNotExist()
    }

    @Test
    fun `tapping it again closes the sheet`() {
        openSheet()

        compose.onNodeWithTag(toolTag(Tool.SkinRetouch)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(EditSheetTestTag).assertDoesNotExist()
    }

    private fun openSheet() {
        showEditor()
        compose.onNodeWithTag(toolTag(Tool.SkinRetouch)).performClick()
        compose.waitForIdle()
    }

    private fun labelOf(kind: SkinRetouchKind): String = when (kind) {
        SkinRetouchKind.Blemish -> "잡티·여드름 제거"
        SkinRetouchKind.Shine -> "유분광 제거"
        SkinRetouchKind.DarkCircles -> "다크서클 완화"
        SkinRetouchKind.ShavingShadow -> "면도자국 완화"
    }

    private fun string(id: Int): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    private fun toolTag(tool: Tool): String = string(tool.labelRes)

    /** The portrait profile, because that is the only menu 피부 보정 appears in. */
    private fun showEditor() {
        compose.setContent {
            var selected by remember { mutableStateOf<Tool?>(null) }
            EditorScreen(
                preview = testImage(),
                selectedTool = selected,
                onToolClick = { selected = if (selected == it) null else it },
                canUndo = false,
                canRedo = false,
                canCompare = false,
                onBack = {},
                onUndo = {},
                onRedo = {},
                onCompareChange = {},
                onExport = {},
                toolLevel = ToolLevelState(profile = ToolMenuProfile.Portrait),
                sheet = {
                    ToolSheetHost(
                        selectedTool = selected,
                        document = document,
                        onValueChange = { _, _ -> },
                        onValueChangeFinished = {},
                        onCancel = { selected = null },
                        onApply = { selected = null },
                    )
                },
            )
        }
        compose.waitForIdle()
    }
}
