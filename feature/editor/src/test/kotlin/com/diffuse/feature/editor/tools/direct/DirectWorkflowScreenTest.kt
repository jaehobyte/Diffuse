package com.diffuse.feature.editor.tools.direct

import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.FakeTopazProvider
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.topaz.TopazOptions
import com.diffuse.core.ai.topaz.TopazPurpose
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import com.diffuse.core.ui.ScreenshotOptions
import com.diffuse.feature.editor.EditorRoute
import com.diffuse.feature.editor.EditorViewModel
import com.diffuse.feature.editor.shownPreview
import com.diffuse.feature.editor.tools.enhance.TopazCompareLabelTestTag
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * specs/vibe_edit.md §19 end to end through `EditorRoute`: the user's own taps — one 선택한 보정
 * 실행, a held 전후 비교 and 적용 — on the real screen, with three different fake
 * pixels (input, local candidate, Topaz result) so the frame the canvas is handed is told apart.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class DirectWorkflowScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var harness: DirectHarness

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        harness = DirectHarness()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        harness.topazRoot.deleteRecursively()
    }

    @Test
    fun `one 실행 tap shows the Topaz result on this canvas, and holding compare shows the input`() {
        val viewModel = planned(LOCAL_AND_TOPAZ)
        compose.setContent { EditorRoute(onExport = {}, viewModel = viewModel) }
        compose.waitForIdle()

        compose.onNodeWithText("선택한 보정 실행").performClick()
        compose.waitForIdle()

        assertEquals(1, harness.topaz.submitCount)
        assertNear(FakeTopazProvider.RESULT_COLOR, centre(shownPreview(viewModel.uiState.value)))
        compose.onNodeWithTag(TopazCompareLabelTestTag).assertTextEquals("후")
        compose.onRoot().captureRoboImage(
            filePath = ScreenshotOptions.goldenPath("direct_workflow_canvas"),
            roborazziOptions = ScreenshotOptions.options,
        )

        val compare = compose.onNodeWithTag(DirectCompareTestTag).performScrollTo()
        compare.performTouchInput { down(center) }
        compose.waitForIdle()
        assertNear(DirectFakeRenderer.PLAIN, centre(shownPreview(viewModel.uiState.value)))
        compose.onNodeWithTag(TopazCompareLabelTestTag).assertTextEquals("전")
        compare.performTouchInput { up() }
        compose.waitForIdle()
        assertNear(FakeTopazProvider.RESULT_COLOR, centre(shownPreview(viewModel.uiState.value)))

        // Comparing and recomposing call nothing; nothing was stored or committed.
        assertEquals(1, harness.topaz.submitCount)
        assertTrue(harness.repository.enhanced.isEmpty())
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)
        assertFalse(viewModel.uiState.value.canUndo)

        compose.onNodeWithText("적용").performClick()
        compose.waitForIdle()

        assertEquals(1, harness.repository.enhanced.size)
        assertEquals(ImageRef(harness.repository.enhancedPath(1)), viewModel.uiState.value.document!!.source)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)
        assertTrue(viewModel.uiState.value.canUndo)
        assertNull(viewModel.uiState.value.selectedTool)
    }

    @Test
    fun `with Topaz turned off on the screen, the canvas shows the local candidate and 적용 commits it`() {
        val viewModel = planned(LOCAL_AND_TOPAZ)
        compose.setContent { EditorRoute(onExport = {}, viewModel = viewModel) }
        compose.waitForIdle()

        compose.onNodeWithTag(directItemTag("s2")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("선택한 보정 실행").performClick()
        compose.waitForIdle()
        assertNear(DirectFakeRenderer.ADJUSTED, centre(shownPreview(viewModel.uiState.value)))
        compose.onNodeWithText("적용").performClick()
        compose.waitForIdle()

        assertEquals(0, harness.topaz.submitCount)
        assertNull(viewModel.uiState.value.selectedTool)
        assertEquals(EXPOSURE, viewModel.uiState.value.document!!.adjustValue(AdjustKind.Exposure), TOLERANCE)
    }

    private fun planned(plan: EditPlan): EditorViewModel {
        val viewModel = harness.opened()
        harness.planner.next(plan)
        viewModel.direct.submit("밝고 깨끗하게")
        return viewModel
    }

    private fun centre(frame: ImageBitmap?): Int {
        val bitmap = requireNotNull(frame).asAndroidBitmap()
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    }

    private fun assertNear(expected: Int, actual: Int) {
        val near = abs(Color.red(expected) - Color.red(actual)) <= JPEG_SLACK &&
            abs(Color.green(expected) - Color.green(actual)) <= JPEG_SLACK &&
            abs(Color.blue(expected) - Color.blue(actual)) <= JPEG_SLACK
        assertTrue("expected ${Integer.toHexString(expected)}, was ${Integer.toHexString(actual)}", near)
    }

    private companion object {
        const val EXPOSURE = 0.2f
        const val TOLERANCE = 1e-4f
        const val JPEG_SLACK = 8
        val LOCAL_AND_TOPAZ = EditPlan(
            listOf(
                PlanStep.Adjust(AdjustKind.Exposure, EXPOSURE),
                PlanStep.Topaz(TopazPurpose.Denoise, TopazOptions(scale = 1)),
            ),
        )
    }
}
