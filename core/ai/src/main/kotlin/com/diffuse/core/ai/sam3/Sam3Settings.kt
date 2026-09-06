package com.diffuse.core.ai.sam3

import android.content.Context
import android.content.SharedPreferences
import com.diffuse.core.ai.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * specs/segmentation.md §6. `local.properties` supplies the build-time default; the settings
 * sheet overrides it at runtime.
 *
 * `SharedPreferences` rather than DataStore, matching `ExportSettingsStore` — the version
 * catalog has no DataStore entry and CLAUDE.md freezes it (see progress.md, "Open issues").
 */
@Singleton
class Sam3Settings @Inject constructor(
    @ApplicationContext context: Context,
) : Sam3ConfigSource {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())

    /** Emits on every override so callers can re-probe availability. */
    val config: StateFlow<Sam3Config> = _config

    override fun current(): Sam3Config = _config.value

    fun update(baseUrl: String, token: String) {
        val normalized = Sam3Config(baseUrl = baseUrl.trim().trimEnd('/'), token = token.trim())
        prefs.edit()
            .putString(KEY_BASE_URL, normalized.baseUrl)
            .putString(KEY_TOKEN, normalized.token)
            .apply()
        _config.value = normalized
    }

    private fun read() = Sam3Config(
        baseUrl = prefs.getString(KEY_BASE_URL, null) ?: BuildConfig.SAM3_BASE_URL.trimEnd('/'),
        token = prefs.getString(KEY_TOKEN, null) ?: BuildConfig.SAM3_TOKEN,
    )

    private companion object {
        const val PREFS_NAME = "sam3_settings"
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "token"
    }
}
