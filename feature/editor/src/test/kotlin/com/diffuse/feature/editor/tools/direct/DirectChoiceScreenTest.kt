package com.diffuse.feature.editor.tools.direct

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.load.ImageLoader
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.render.CpuRenderer
import com.diffuse.feature.editor.EditorRoute
import com.diffuse.feature.editor.TestDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * specs/vibe_edit.md §19 end to end: the user's own taps through `EditorRoute`, and the real CPU
 * renderer on the candidate, so "off" is seen to mean "not in the pixels" as well as "not in the
 * list".
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DirectChoiceScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `tapping an item off, 실행 and 적용 commits only what is on`() {
        val harness = DirectHarness()
        val viewModel = harness.opened()
        harness.planner.next(WARM_AND_BRIGHT)
        viewModel.direct.submit("따뜻하고 밝게")
        compose.setContent { EditorRoute(onExport = {}, viewModel = viewModel) }
        compose.waitForIdle()

        compose.onNodeWithTag(directItemTag("s2")).performScrollTo().assertIsOn().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(directItemTag("s2")).assertIsOff()
        compose.onNodeWithText("선택한 보정 1/2개").performScrollTo().assertExists()
        compose.onNodeWithText("선택한 보정 실행").performClick()
        compose.waitForIdle()
        assertEquals(CandidatePhase.Review, viewModel.uiState.value.direct.candidate?.phase)
        // Off and on again on the candidate: rebuilt from the same input, still applicable.
        compose.onNodeWithTag(directItemTag("s2")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(directItemTag("s2")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("적용").performClick()
        compose.waitForIdle()

        val document = viewModel.uiState.value.document!!
        assertNull(viewModel.uiState.value.selectedTool)
        assertEquals(EXPOSURE, document.adjustValue(AdjustKind.Exposure), TOLERANCE)
        assertEquals(0f, document.adjustValue(AdjustKind.Temperature), TOLERANCE)
    }

    /** On a neutral grey source: temperature moves red against blue, exposure moves both up. */
    @Test
    fun `the real renderer shows only the chosen effects`() {
        val renderer = CpuRenderer(
            ImageLoader(
                ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver,
                TestDispatchers,
            ),
            TestDispatchers,
        )
        val harness = DirectHarness(realRenderer = renderer)
        harness.repository.document = EditDocument(id = "p", source = grey(), createdAt = 0L, updatedAt = 0L)
        val viewModel = harness.opened()
        harness.planner.next(WARM_AND_BRIGHT)
        viewModel.direct.submit("따뜻하고 밝게")
        viewModel.applySheet()
        val input = centre(render(renderer, viewModel.uiState.value.document!!))

        viewModel.direct.toggle("s2")
        val bright = centre(render(renderer, candidate(viewModel)))
        viewModel.direct.toggle("s2")
        viewModel.direct.toggle("s1")
        val warm = centre(render(renderer, candidate(viewModel)))

        assertTrue("brighter", luma(bright) > luma(input) + MIN_STEP)
        assertTrue("not warmer", abs(warmth(bright) - warmth(input)) <= NEUTRAL)
        assertTrue("warmer", warmth(warm) > warmth(input) + MIN_STEP)
        assertTrue("not brighter", luma(warm) < luma(bright))
    }

    private fun candidate(viewModel: com.diffuse.feature.editor.EditorViewModel) =
        viewModel.uiState.value.direct.candidate!!.document!!

    private fun render(renderer: CpuRenderer, document: EditDocument): Bitmap =
        (runBlocking { renderer.preview(document, SIZE) } as Result.Success).value

    private fun centre(bitmap: Bitmap): Int = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)

    private fun luma(pixel: Int) = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3

    private fun warmth(pixel: Int) = Color.red(pixel) - Color.blue(pixel)

    private fun grey(): ImageRef {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.rgb(GREY, GREY, GREY)) }
        val file = File(temp.newFolder(), "grey.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return ImageRef(file.absolutePath)
    }

    private companion object {
        const val EXPOSURE = 0.3f
        const val TOLERANCE = 1e-4f
        const val SIZE = 64
        const val GREY = 110
        const val MIN_STEP = 4
        const val NEUTRAL = 2
        val WARM_AND_BRIGHT = EditPlan(
            listOf(PlanStep.Adjust(AdjustKind.Exposure, EXPOSURE), PlanStep.Adjust(AdjustKind.Temperature, 0.6f)),
        )
    }
}
