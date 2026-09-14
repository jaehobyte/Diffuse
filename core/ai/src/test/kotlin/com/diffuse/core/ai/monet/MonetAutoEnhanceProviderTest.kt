package com.diffuse.core.ai.monet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diffuse.core.ai.Availability
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * specs/auto_enhance.md §4, §6. The provider over a real client and real settings, against
 * localhost: what the 자동 tool sees after a failure, a recovery, a re-save and a settings race.
 */
@RunWith(RobolectricTestRunner::class)
class MonetAutoEnhanceProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer

    private val dispatchers = object : DispatcherProvider {
        override val default: CoroutineDispatcher get() = Dispatchers.IO
        override val io: CoroutineDispatcher get() = Dispatchers.IO
    }

    @Before
    fun setUp() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    // ---- retry on the same settings --------------------------------------

    /** The reported bug: a server that comes back is usable without restarting the app. */
    @Test
    fun `a failed probe recovers on refresh with the same address and token`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(HTTP_UNAVAILABLE))
        val settings = settings(defaults = MonetConfig(base(), TOKEN))
        val provider = provider(settings)
        provider.awaitAnswer()
        assertEquals(Availability.Unavailable(AppError.Unavailable), provider.availability.value)

        server.enqueue(MockResponse().setResponseCode(200))
        provider.refresh()

        provider.awaitAnswer()
        assertEquals(Availability.Ready, provider.availability.value)
        assertEquals(2, server.requestCount)
    }

    /** `StateFlow` swallows an equal value; a second save of the same address must still re-probe. */
    @Test
    fun `saving the same settings again re-probes`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(HTTP_UNAVAILABLE))
        val settings = settings(defaults = MonetConfig(base(), TOKEN))
        val provider = provider(settings)
        provider.awaitAnswer()

        server.enqueue(MockResponse().setResponseCode(200))
        settings.update(base(), TOKEN)

        withTimeout(WAIT_MS) { provider.availability.first { it == Availability.Ready } }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a refresh while the same probe is in flight joins it`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(200).setHeadersDelay(1, TimeUnit.SECONDS))
        val provider = provider(settings(defaults = MonetConfig(base(), TOKEN)))

        repeat(REPEATED_TAPS) { provider.refresh() }

        provider.awaitAnswer()
        assertEquals(Availability.Ready, provider.availability.value)
        assertEquals(1, server.requestCount)
    }

    // ---- the settings race -----------------------------------------------

    /** Settings A are slow to answer Ready; B, saved meanwhile, answer 401 first. B's answer stands. */
    @Test
    fun `a late answer for the previous settings never overwrites the current ones`() = runBlocking<Unit> {
        val slowA = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(200).setHeadersDelay(2, TimeUnit.SECONDS))
            start()
        }
        try {
            val settings = settings(defaults = MonetConfig(slowA.url("/").toString(), TOKEN))
            val provider = provider(settings)
            slowA.takeRequest()

            server.enqueue(MockResponse().setResponseCode(HTTP_UNAUTHORIZED))
            settings.update(base(), "wrong")
            provider.awaitAnswer()
            assertEquals(Availability.Unavailable(AppError.Unauthorized), provider.availability.value)

            // Well past A's delay: nothing may have changed the answer for B.
            Thread.sleep(A_DELAY_PLUS_MS)
            assertEquals(Availability.Unavailable(AppError.Unauthorized), provider.availability.value)
            assertFalse(provider.checking.value)
        } finally {
            slowA.shutdown()
        }
    }

    // ---- addresses: clean install, an old override, a typo ----------------

    @Test
    fun `a clean install with no address says so without probing`() = runBlocking<Unit> {
        val provider = provider(settings(defaults = MonetConfig("")))

        assertEquals(
            Availability.Unavailable(AppError.Invalid(MonetAutoEnhanceProvider.NO_SERVER)),
            provider.availability.value,
        )
        assertFalse(provider.checking.value)
        assertEquals(0, server.requestCount)
    }

    /** A malformed override from an older build is recoverable in the app, and never crashes. */
    @Test
    fun `a malformed saved address is Invalid and fixed by saving a good one`() = runBlocking<Unit> {
        prefs().edit().putString("base_url", "htp:/ typo").commit()
        val settings = settings(defaults = MonetConfig(""))
        val provider = provider(settings)
        provider.awaitAnswer()
        assertEquals(
            Availability.Unavailable(AppError.Invalid(MonetClient.INVALID_SERVER_ADDRESS)),
            provider.availability.value,
        )

        server.enqueue(MockResponse().setResponseCode(200))
        settings.update(base(), TOKEN)

        withTimeout(WAIT_MS) { provider.availability.first { it == Availability.Ready } }
    }

    /** An override from an older build still wins over this build's default — until it is edited. */
    @Test
    fun `an existing override wins over the build default`() {
        prefs().edit().putString("base_url", "http://old.example:8082/").commit()

        val settings = settings(defaults = MonetConfig("https://new.example"))

        assertEquals("http://old.example:8082", settings.current().baseUrl)
    }

    /**
     * Saving the value the build already carries stores no override, so the next build's default
     * is read — which is how one untouched save stopped pinning a stale address.
     */
    @Test
    fun `saving the build default stores no override`() {
        settings(defaults = MonetConfig("https://a.example", TOKEN)).update("https://a.example/", TOKEN)

        val nextBuild = settings(defaults = MonetConfig("https://b.example", TOKEN))

        assertEquals("https://b.example", nextBuild.current().baseUrl)
    }

    @Test
    fun `a pasted v1 base is normalised to the server root`() {
        val settings = settings(defaults = MonetConfig(""))

        settings.update(" https://a.example/v1/ ", " t ")

        assertEquals(MonetConfig("https://a.example", "t"), settings.current())
    }

    // ---- fixtures --------------------------------------------------------

    private fun base() = server.url("/").toString()

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun settings(defaults: MonetConfig) = MonetSettings(context, defaults)

    private fun provider(settings: MonetSettings) = MonetAutoEnhanceProvider(
        MonetClient(settings, dispatchers, OkHttpClient()),
        settings,
        dispatchers,
    )

    /**
     * `checking` is true from construction and set synchronously by `refresh`, so its next false
     * is the answer. A save re-probes asynchronously; those tests wait on the answer itself.
     */
    private suspend fun MonetAutoEnhanceProvider.awaitAnswer() {
        withTimeout(WAIT_MS) { checking.first { !it } }
    }

    private companion object {
        const val PREFS = "monet_settings"
        const val TOKEN = "t0ken"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_UNAVAILABLE = 503
        const val WAIT_MS = 10_000L
        const val REPEATED_TAPS = 5
        const val A_DELAY_PLUS_MS = 2_500L
    }
}
