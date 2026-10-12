package com.diffuse.feature.editor.tools.retouch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.CorrectionOutcome
import com.diffuse.core.ai.KindSupport
import com.diffuse.core.ai.SkinRetouchKind
import com.diffuse.core.ai.UnsupportedReason
import com.diffuse.core.ui.components.EditSheetTestTag
import com.diffuse.feature.editor.EditorScreen
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.Tool
import com.diffuse.feature.editor.ToolLevelState
import com.diffuse.feature.editor.ToolMenuProfile
import com.diffuse.feature.editor.canvas.testImage
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * specs/skin_retouch.md §3–§6, skin_retouch_validation.md §4 (UI). The sheet reads its state and
 * nothing else: where the pixels go, the server's own status and settings path, the faces, a
 * slider only for an enabled kind with the reason otherwise, 미리보기, and 적용 gated on a result.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class SkinRetouchSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    @Test
    fun `tapping 피부 보정 opens its own sheet`() {
        showEditor(ready())
        compose.onNodeWithTag(EditSheetTestTag).assertDoesNotExist()

        compose.onNodeWithTag(string(Tool.SkinRetouch.labelRes)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(SkinRetouchSheetTestTag).assertExists()
        compose.onNode(
            hasText(string(R.string.skin_retouch_title)) and hasAnyAncestor(hasTestTag(SkinRetouchSheetTestTag)),
        ).assertExists()
    }

    @Test
    fun `the sheet says where the face goes before anything is sent`() {
        show(ready())

        compose.onNodeWithText(context().getString(R.string.skin_retouch_location, HOST)).assertExists()
        compose.onNodeWithTag(SkinRetouchServerTestTag).assertExists()
    }

    @Test
    fun `an unconfigured server offers the settings path and no preview`() {
        show(ready().copy(server = SkinServerStatus.NeedsSettings, strengths = strengths(50)))

        compose.onNodeWithText(string(R.string.skin_retouch_needs_server)).assertExists()
        compose.onNodeWithTag(SkinRetouchSettingsTestTag).performClick()
        compose.onNodeWithTag(SkinRetouchPreviewTestTag).performScrollTo().assertIsNotEnabled()
        assertEquals(listOf("settings"), calls)
    }

    @Test
    fun `an unreachable server can be re-checked with the same settings`() {
        show(ready().copy(server = SkinServerStatus.Unreachable))

        compose.onNodeWithTag(SkinRetouchRetryTestTag).performClick()

        assertEquals(listOf("recheck"), calls)
    }

    @Test
    fun `a disabled kind shows its reason and no slider`() {
        show(
            ready().copy(
                serverKinds = setOf(SkinRetouchKind.Blemish, SkinRetouchKind.DarkCircles),
                faceSupport = support() +
                    (SkinRetouchKind.DarkCircles to KindSupport.Unsupported(UnsupportedReason.MissingRegions)),
            ),
        )

        compose.onNodeWithTag(skinRetouchSliderTag(SkinRetouchKind.Blemish)).assertExists()
        compose.onNodeWithTag(skinRetouchSliderTag(SkinRetouchKind.Shine)).assertDoesNotExist()
        compose.onNodeWithTag(skinRetouchSliderTag(SkinRetouchKind.DarkCircles)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.skin_retouch_missing_regions)).assertExists()
    }

    @Test
    fun `미리보기 is enabled only when a kind above zero has no candidate, and 적용 only on a result`() {
        show(ready().copy(strengths = strengths(50)))
        compose.onNodeWithTag(SkinRetouchPreviewTestTag).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("적용").assertIsNotEnabled()
        assertEquals(listOf("preview"), calls)
    }

    @Test
    fun `a prepared result enables 적용`() {
        show(
            ready().copy(
                strengths = strengths(50),
                outcomes = mapOf(SkinRetouchKind.Blemish to CorrectionOutcome.Corrected),
                draftReady = true,
            ),
        )

        compose.onNodeWithText("적용").assertIsEnabled().performClick()
        compose.onNodeWithTag(SkinRetouchPreviewTestTag).performScrollTo().assertIsNotEnabled()
        assertEquals(listOf("apply"), calls)
    }

    @Test
    fun `several faces are offered as selectable thumbnails`() {
        show(ready().copy(faces = listOf(SkinFaceChoice("a", null), SkinFaceChoice("b", null)), selectedFaceId = "a"))

        compose.onNodeWithTag(skinRetouchFaceTag(0)).assertIsSelected()
        compose.onNodeWithTag(skinRetouchFaceTag(1)).performClick()

        assertEquals(listOf("face:b"), calls)
    }

    @Test
    fun `analysis failure offers a retry and no face says so`() {
        var state by mutableStateOf(ready().copy(analysis = SkinAnalysis.Failed))
        compose.setContent { SkinRetouchSheet(state, actions()) }

        compose.onNodeWithText(string(R.string.skin_retouch_analysis_failed)).assertExists()
        compose.onNodeWithText(string(R.string.skin_retouch_retry)).performClick()
        state = ready().copy(analysis = SkinAnalysis.NoFace)
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.skin_retouch_no_face)).assertExists()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `취소 closes it`() {
        show(ready())

        compose.onNodeWithText("취소").performClick()

        assertEquals(listOf("cancel"), calls)
    }

    private fun show(state: SkinRetouchState) {
        compose.setContent { SkinRetouchSheet(state, actions()) }
        compose.waitForIdle()
    }

    private fun actions() = SkinRetouchActions(
        onSelectFace = { calls += "face:$it" },
        onStrengthChange = { kind, value -> calls += "strength:$kind:$value" },
        onPreview = { calls += "preview" },
        onRetryAnalysis = { calls += "retry" },
        onRecheckServer = { calls += "recheck" },
        onOpenSettings = { calls += "settings" },
        onCancel = { calls += "cancel" },
        onApply = { calls += "apply" },
    )

    private fun ready() = SkinRetouchState(
        analysis = SkinAnalysis.Ready,
        faces = listOf(SkinFaceChoice("a", null)),
        selectedFaceId = "a",
        faceSupport = support(),
        server = SkinServerStatus.Ready,
        serverKinds = SkinRetouchKind.entries.toSet(),
        serverHost = HOST,
    )

    private fun support() =
        SkinRetouchKind.entries.associateWith<SkinRetouchKind, KindSupport> { KindSupport.Supported }

    private fun strengths(blemish: Int) = SkinRetouchState.ZERO_STRENGTHS + (SkinRetouchKind.Blemish to blemish)

    private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun string(id: Int): String = context().getString(id)

    /** The portrait profile, because that is the only menu 피부 보정 appears in. */
    private fun showEditor(state: SkinRetouchState) {
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
                sheet = { if (selected == Tool.SkinRetouch) SkinRetouchSheet(state, actions()) },
            )
        }
        compose.waitForIdle()
    }

    private companion object {
        const val HOST = "http://retouch.example:8094"
    }
}
