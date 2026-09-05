package com.diffuse.feature.editor

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.history.HistoryStack
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.feature.editor.canvas.testImage
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** T09 done-when: the top bar's undo/redo enablement reflects the history stack. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class EditorHistoryTest {

    @get:Rule
    val compose = createComposeRule()

    private val document = EditDocument(
        id = "doc",
        source = ImageRef("/p.jpg"),
        createdAt = 0L,
        updatedAt = 0L,
    )
    private val history = HistoryStack(document)

    private fun showShell() {
        compose.setContent {
            val canUndo by history.canUndo.collectAsState()
            val canRedo by history.canRedo.collectAsState()
            val current by history.current.collectAsState()
            EditorScreen(
                preview = testImage(),
                selectedTool = null,
                onToolClick = {},
                canUndo = canUndo,
                canRedo = canRedo,
                canCompare = false,
                onBack = {},
                onUndo = { history.undo() },
                onRedo = { history.redo() },
                canReset = current.operations.isNotEmpty(),
                onReset = { history.resetToOriginal() },
                onCompareChange = {},
                onExport = {},
            )
        }
        compose.waitForIdle()
    }

    private fun undoButton() = compose.onNodeWithTag(string(R.string.editor_undo))

    private fun redoButton() = compose.onNodeWithTag(string(R.string.editor_redo))

    private fun resetButton() = compose.onNodeWithTag(string(R.string.editor_reset))

    @Test
    fun `undo and redo track the stack as it changes`() {
        showShell()

        undoButton().assertIsNotEnabled()
        redoButton().assertIsNotEnabled()

        history.push(document.withAdjust(AdjustKind.Exposure, 0.5f))
        compose.waitForIdle()
        undoButton().assertIsEnabled()
        redoButton().assertIsNotEnabled()

        history.undo()
        compose.waitForIdle()
        undoButton().assertIsNotEnabled()
        redoButton().assertIsEnabled()
    }

    @Test
    fun `pressing undo in the top bar moves the stack`() {
        showShell()
        history.push(document.withAdjust(AdjustKind.Exposure, 0.5f))
        compose.waitForIdle()

        compose.onNodeWithTag(string(R.string.editor_undo)).performClick()
        compose.waitForIdle()

        undoButton().assertIsNotEnabled()
        redoButton().assertIsEnabled()
    }

    /** T22 done-when: reset then undo restores every operation. */
    @Test
    fun `reset drops every operation and undo restores them`() {
        showShell()
        resetButton().assertIsNotEnabled()

        val edited = document
            .withAdjust(AdjustKind.Exposure, 0.5f)
            .withAdjust(AdjustKind.Contrast, -0.25f)
        history.push(edited)
        compose.waitForIdle()
        resetButton().assertIsEnabled()

        resetButton().performClick()
        compose.waitForIdle()
        assertTrue(history.current.value.operations.isEmpty())
        resetButton().assertIsNotEnabled()

        undoButton().performClick()
        compose.waitForIdle()
        assertEquals(edited.operations, history.current.value.operations)
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(resId)
}
