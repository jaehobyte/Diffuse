package com.diffuse.core.ai.gemini

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * specs/generative_erase.md §8. The key is typed into the settings sheet at runtime and lives
 * only here: there is no `BuildConfig` field, no `local.properties` entry and nothing in Gradle
 * reads `.env`, so a published APK carries no credential (§2).
 *
 * Separate from `Sam3Settings` — different host, different credential, different lifetime. One
 * class holding both would let a SAM 3 change invalidate a Gemini key.
 *
 * `SharedPreferences` rather than DataStore, for the reason `Sam3Settings` gives: the version
 * catalog has no DataStore entry and CLAUDE.md freezes it.
 */
@Singleton
class GeminiSettings @Inject constructor(
    @ApplicationContext context: Context,
) : GeminiConfigSource {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(GeminiConfig(apiKey = read()))

    /** Emits on every override, so availability re-derives without a probe (§7). */
    val config: StateFlow<GeminiConfig> = _config

    override fun current(): GeminiConfig = _config.value

    fun update(apiKey: String) {
        val normalized = apiKey.trim()
        prefs.edit().putString(KEY_API_KEY, normalized).apply()
        _config.value = _config.value.copy(apiKey = normalized)
    }

    private fun read(): String = prefs.getString(KEY_API_KEY, null).orEmpty()

    private companion object {
        const val PREFS_NAME = "gemini_settings"
        const val KEY_API_KEY = "api_key"
    }
}
