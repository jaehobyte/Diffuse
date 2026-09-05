package com.diffuse.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T11: holding compare shows the source, releasing returns to the preview.
 *
 * The canvas announces which image it is showing, so the two states are asserted through
 * that rather than by capturing pixels: Roborazzi only captures under its own record and
 * verify tasks, and Compose's `captureToImage` never reaches idle under Robolectric here.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class CompareGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private val preview = solid(0xFFE60023.toInt())
    private val sourceImage = solid(0xFF1E7A46.toInt())

    private fun solid(color: Int): ImageBitmap =
        Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(color) }
            .asImageBitmap()

    private fun showShell(canCompare: Boolean = true) {
        compose.setContent {
            EditorScreen(
                preview = preview,
                source = sourceImage,
                selectedTool = null,
                onToolClick = {},
                canUndo = false,
                canRedo = false,
                canCompare = canCompare,
                onBack = {},
                onUndo = {},
                onRedo = {},
                onCompareChange = {},
                onExport = {},
            )
        }
        compose.waitForIdle()
    }

    private fun assertShowing(resId: Int) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithContentDescription(context.getString(resId)).assertExists()
    }

    @Test
    fun `holding compare shows the source and releasing returns to the preview`() {
        showShell()
        assertShowing(R.string.editor_canvas_edited)

        compose.onNodeWithTag(CompareTestTag).performTouchInput { down(center) }
        compose.waitForIdle()
        assertShowing(R.string.editor_canvas_source)

        compose.onNodeWithTag(CompareTestTag).performTouchInput { up() }
        compose.waitForIdle()
        assertShowing(R.string.editor_canvas_edited)
    }

    @Test
    fun `compare does nothing when the document has no operations`() {
        showShell(canCompare = false)

        compose.onNodeWithTag(CompareTestTag).performTouchInput { down(center) }
        compose.waitForIdle()

        assertShowing(R.string.editor_canvas_edited)
        compose.onNodeWithTag(CompareTestTag).performTouchInput { up() }
    }

    @Test
    fun `compare availability follows the document's operations`() {
        val document = EditDocument("d", ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L)

        assertFalse(document.canCompare())
        assertTrue(document.withAdjust(AdjustKind.Exposure, 0.5f).canCompare())
        // Returning to neutral removes the operation, so there is nothing to compare again.
        assertFalse(
            document.withAdjust(AdjustKind.Exposure, 0.5f)
                .withAdjust(AdjustKind.Exposure, 0f)
                .canCompare(),
        )
    }

    @Test
    fun `the compare button is disabled without operations`() {
        showShell(canCompare = false)

        compose.onNodeWithTag(CompareTestTag).assertExists()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithTag(context.getString(R.string.editor_undo)).assertIsNotEnabled()
    }
}
