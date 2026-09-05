package com.diffuse.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.core.ui.theme.Tokens
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class AdjustSliderTest {

    @get:Rule
    val compose = createComposeRule()

    private val changes = mutableListOf<Float>()
    private var finishedCount = 0

    private fun showSlider(
        value: Float = 0f,
        range: ClosedFloatingPointRange<Float> = -1f..1f,
        zeroCentered: Boolean = true,
    ) {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Tokens.editSurface)
                        .padding(16.dp),
                ) {
                    AdjustSlider(
                        value = value,
                        range = range,
                        zeroCentered = zeroCentered,
                        onChange = { changes += it },
                        onChangeFinished = { finishedCount++ },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the value is pinned to the right and signed when zero-centred`() {
        showSlider(value = 0.5f)

        compose.onNodeWithText("+0.50").assertExists()
    }

    @Test
    fun `a one-sided range shows no sign`() {
        showSlider(value = 0.25f, range = 0f..1f, zeroCentered = false)

        compose.onNodeWithText("0.25").assertExists()
    }

    @Test
    fun `dragging reports values and finishes once`() {
        showSlider(value = -1f)

        compose.onNodeWithTag(AdjustSliderTestTag).performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertTrue("expected drag updates, got $changes", changes.isNotEmpty())
        assertTrue("values should rise, got $changes", changes.last() > -1f)
        assertEquals(1, finishedCount)
    }

    @Test
    fun `double tap resets to the neutral value and commits`() {
        showSlider(value = 0.8f)

        compose.onNodeWithTag(AdjustSliderTestTag).performTouchInput { doubleClick() }
        compose.waitForIdle()

        assertEquals(listOf(0f), changes)
        assertEquals(1, finishedCount)
    }

    @Test
    fun `double tap on a one-sided range resets into that range`() {
        showSlider(value = 0.8f, range = 0f..1f, zeroCentered = false)

        compose.onNodeWithTag(AdjustSliderTestTag).performTouchInput { doubleClick() }
        compose.waitForIdle()

        assertEquals(listOf(0f), changes)
    }
}
