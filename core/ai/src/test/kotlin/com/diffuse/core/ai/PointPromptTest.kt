package com.diffuse.core.ai

import android.graphics.PointF
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** specs/ai_provider.md §3: the prompt validates itself so no provider has to. */
@RunWith(RobolectricTestRunner::class)
class PointPromptTest {

    @Test
    fun `points and labels must be the same length`() {
        assertThrows(IllegalArgumentException::class.java) {
            PointPrompt(listOf(PointF(0.1f, 0.1f)), listOf(true, false))
        }
    }

    @Test
    fun `an empty prompt is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PointPrompt(emptyList(), emptyList())
        }
    }

    @Test
    fun `coordinates outside 0 to 1 are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PointPrompt(listOf(PointF(1.2f, 0.5f)), listOf(true))
        }
    }
}
