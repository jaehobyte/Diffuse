package com.diffuse.core.ai.gemini

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** specs/generative_erase.md §8. */
@RunWith(RobolectricTestRunner::class)
class GeminiSettingsTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `the key defaults to empty, so nothing is configured out of the box`() {
        val settings = GeminiSettings(context)

        assertEquals("", settings.config.value.apiKey)
        assertFalse(settings.config.value.isConfigured)
    }

    @Test
    fun `a saved key survives a new instance`() {
        GeminiSettings(context).update("AIza-test-key")

        val reloaded = GeminiSettings(context)

        assertEquals("AIza-test-key", reloaded.config.value.apiKey)
        assertTrue(reloaded.config.value.isConfigured)
    }

    @Test
    fun `stray whitespace is trimmed away`() {
        val settings = GeminiSettings(context)

        settings.update("  AIza-test-key  ")

        assertEquals("AIza-test-key", settings.config.value.apiKey)
    }

    @Test
    fun `the config flow emits on update`() {
        val settings = GeminiSettings(context)
        val before = settings.config.value

        settings.update("AIza-second")

        assertEquals(GeminiConfig("AIza-second"), settings.config.value)
        assertFalse(before == settings.config.value)
    }

    @Test
    fun `the base URL is the constant, never read from preferences`() {
        val settings = GeminiSettings(context)

        settings.update("AIza-test-key")

        assertEquals(GeminiConfig.DEFAULT_BASE_URL, settings.config.value.baseUrl)
        assertEquals("https://generativelanguage.googleapis.com", settings.config.value.baseUrl)
    }
}
