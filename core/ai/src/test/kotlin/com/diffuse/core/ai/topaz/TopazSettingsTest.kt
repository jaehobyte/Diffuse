package com.diffuse.core.ai.topaz

import androidx.test.core.app.ApplicationProvider
import com.diffuse.core.ai.gemini.GeminiSettings
import com.diffuse.core.ai.sam3.Sam3Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** specs/topaz_enhancement.md §4, §3. */
@RunWith(RobolectricTestRunner::class)
class TopazSettingsTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `no key ships, and the API host is the official HTTPS one`() {
        val config = TopazSettings(context).config.value

        assertFalse(config.isConfigured)
        assertEquals("https://api.topazlabs.com/image/v1", config.baseUrl)
    }

    @Test
    fun `a saved key survives, trimmed, and every change is a new generation`() {
        val settings = TopazSettings(context)
        settings.update("  tp-key  ")
        val first = settings.config.value

        assertEquals("tp-key", TopazSettings(context).config.value.apiKey)
        settings.update("tp-key")
        assertEquals(first.generation, settings.config.value.generation)
        settings.update("")
        assertFalse(settings.config.value.isConfigured)
        assertTrue(settings.config.value.generation > first.generation)
    }

    @Test
    fun `the Topaz key is stored apart from Gemini's and SAM 3's`() {
        GeminiSettings(context).update("gemini")
        Sam3Settings(context).update("http://sam3", "tok")

        TopazSettings(context).update("topaz")
        TopazSettings(context).update("")

        assertEquals("gemini", GeminiSettings(context).config.value.apiKey)
        assertEquals("http://sam3", Sam3Settings(context).config.value.baseUrl)
    }

    // ---- what a job may ask for -------------------------------------------

    @Test
    fun `scales outside the purpose, and options it does not document, are refused before sending`() {
        assertEquals(TopazFailure.OptionUnsupported, job(TopazPurpose.Upscale, TopazOptions(scale = 1)).problem())
        assertEquals(TopazFailure.OptionUnsupported, job(TopazPurpose.Denoise, TopazOptions(scale = 2)).problem())
        assertEquals(
            TopazFailure.OptionUnsupported,
            job(TopazPurpose.Denoise, TopazOptions(scale = 1, sharpenStrength = 0.4f)).problem(),
        )
        assertEquals(
            TopazFailure.OptionUnsupported,
            job(TopazPurpose.Lighting, TopazOptions(scale = 1, faceRecovery = true)).problem(),
        )
        assertNull(job(TopazPurpose.Sharpen, TopazOptions(scale = 1, sharpenStrength = 0.4f)).problem())
    }

    @Test
    fun `a 4x of a full working render exceeds Wonder's limit but not Standard 2's`() {
        val big = TopazJob(TopazPurpose.Restore, TopazOptions(scale = 4), 4096, 3072)

        assertEquals(TopazFailure.LimitExceeded, big.problem())
        assertFalse(big.fitsOutput(TopazPurpose.Restore, 4))
        assertTrue(big.fitsOutput(TopazPurpose.Restore, 2))
        assertTrue(big.fitsOutput(TopazPurpose.Upscale, 4))
        assertNull(TopazJob(TopazPurpose.Upscale, TopazOptions(scale = 4), 4096, 3072).problem())
    }

    @Test
    fun `a transparent input is refused for every purpose`() {
        TopazPurpose.entries.forEach { purpose ->
            val alpha = TopazJob(purpose, TopazOptions.defaultsFor(purpose), 100, 80, inputHasAlpha = true)
            assertEquals(purpose.name, TopazFailure.InputHasAlpha, alpha.problem())
        }
    }

    @Test
    fun `the proportional side keeps the aspect`() {
        val job = TopazJob(TopazPurpose.Upscale, TopazOptions(scale = 4), 4096, 2731)

        assertEquals(16384, job.outputWidth)
        assertEquals(10924, job.outputHeight)
    }

    private fun job(purpose: TopazPurpose, options: TopazOptions) = TopazJob(purpose, options, 400, 300)
}
