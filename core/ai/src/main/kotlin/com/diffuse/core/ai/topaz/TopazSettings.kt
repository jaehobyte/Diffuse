package com.diffuse.core.ai.topaz

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * specs/topaz_enhancement.md §4. Where the Topaz Image API is and which key pays for it.
 *
 * [baseUrl] is not a setting, for the reason `GeminiConfig` gives: a text field for it would be a
 * way to send the user's key to an arbitrary host. The constructor seam exists for MockWebServer.
 *
 * [generation] changes on every save, so a run started under one key can tell that the key it
 * was started with is no longer the one the user wants paid with.
 */
data class TopazConfig(
    val apiKey: String,
    val baseUrl: String = DEFAULT_BASE_URL,
    val generation: Long = 0,
) {

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.topazlabs.com/image/v1"
    }
}

/** Read on every call, so a key typed into the settings sheet takes effect without a rebuild. */
fun interface TopazConfigSource {
    fun current(): TopazConfig
}

/**
 * specs/topaz_enhancement.md §4: the user's own key (BYOK), typed into the 서버 설정 sheet. No
 * `BuildConfig` field, no Gradle input, nothing in the repository — a published APK carries none.
 *
 * Its own preferences file, separate from Gemini's and SAM 3's, so saving or clearing one never
 * touches another.
 */
@Singleton
class TopazSettings @Inject constructor(
    @ApplicationContext context: Context,
) : TopazConfigSource {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(TopazConfig(apiKey = read()))

    val config: StateFlow<TopazConfig> = _config

    override fun current(): TopazConfig = _config.value

    /** A blank [apiKey] clears it. Saving the same key again changes nothing, generation included. */
    fun update(apiKey: String) {
        val normalized = apiKey.trim()
        if (normalized == _config.value.apiKey) return
        prefs.edit().putString(KEY_API_KEY, normalized).apply()
        _config.value = _config.value.copy(
            apiKey = normalized,
            generation = _config.value.generation + 1,
        )
    }

    private fun read(): String = prefs.getString(KEY_API_KEY, null).orEmpty()

    private companion object {
        const val PREFS_NAME = "topaz_settings"
        const val KEY_API_KEY = "api_key"
    }
}
