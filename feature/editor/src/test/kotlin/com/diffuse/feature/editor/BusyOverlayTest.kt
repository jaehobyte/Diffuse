package com.diffuse.feature.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ui.ScreenshotOptions
import com.diffuse.feature.editor.canvas.testImage
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** DESIGN.md §4/§7: the AI working overlay keeps its label and 취소 under the new motion. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class BusyOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    private var busy by mutableStateOf(true)
    private var label by mutableStateOf(R.string.erase_working)
    private var cancels = 0

    private fun show() {
        compose.setContent {
            EditorScreen(
                preview = testImage(),
                selectedTool = Tool.Erase,
                onToolClick = {},
                canUndo = false,
                canRedo = false,
                canCompare = false,
                onUndo = {},
                onRedo = {},
                onCompareChange = {},
                onExport = {},
                busy = busy,
                busyLabelRes = label,
                onCancelWork = { cancels++ },
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun labelAndCancelStayUnderTheMotion() {
        show()
        compose.onNodeWithText("지우는 중").assertIsDisplayed()
        compose.onNodeWithTag(SelectionCancelTestTag).performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun overlayFadesOutWhenWorkEnds() {
        show()
        compose.onNodeWithTag(SelectionOverlayProgressTestTag).assertIsDisplayed()
        busy = false
        compose.mainClock.advanceTimeBy(FADE_AND_SWEEP_MS)
        compose.waitForIdle()
        compose.onNodeWithTag(SelectionOverlayProgressTestTag).assertDoesNotExist()
    }

    @Test
    fun labelCrossfadesToTheNextStage() {
        show()
        label = R.string.topaz_phase_processing
        compose.mainClock.advanceTimeBy(FADE_AND_SWEEP_MS)
        compose.waitForIdle()
        compose.onNodeWithText("Topaz에서 처리 중").assertIsDisplayed()
    }

    /**
     * The light band mid-pass over the scrim, at a fixed point of its first cycle. The test
     * rule cancels infinite animations while it auto-advances, so the clock goes manual first.
     */
    @Test
    fun editorBusyOverlay() {
        compose.mainClock.autoAdvance = false
        show()
        compose.mainClock.advanceTimeBy(GOLDEN_AT_MS)
        compose.onRoot().captureRoboImage(
            filePath = ScreenshotOptions.goldenPath("editor_busy_overlay"),
            roborazziOptions = ScreenshotOptions.options,
        )
    }

    private companion object {
        const val FADE_AND_SWEEP_MS = 2_000L
        const val GOLDEN_AT_MS = 1_200L
    }
}
