package com.diffuse.core.ai.sam3

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** specs/segmentation.md §6. */
@RunWith(RobolectricTestRunner::class)
class Sam3SettingsTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `an override survives a new instance`() {
        Sam3Settings(context).update("http://10.0.2.2:8080", "abc")

        val reloaded = Sam3Settings(context)

        assertEquals("http://10.0.2.2:8080", reloaded.current().baseUrl)
        assertEquals("abc", reloaded.current().token)
        assertTrue(reloaded.current().isConfigured)
    }

    @Test
    fun `a trailing slash and stray whitespace are normalized away`() {
        val settings = Sam3Settings(context)

        settings.update("  http://host:8080/  ", "  tok  ")

        assertEquals("http://host:8080", settings.current().baseUrl)
        assertEquals("tok", settings.current().token)
    }

    @Test
    fun `a blank base URL is not configured`() {
        val settings = Sam3Settings(context)

        settings.update("", "")

        assertFalse(settings.current().isConfigured)
    }

    @Test
    fun `the config flow emits on update`() {
        val settings = Sam3Settings(context)

        settings.update("http://host:8080", "tok")

        assertEquals(settings.current(), settings.config.value)
    }
}
