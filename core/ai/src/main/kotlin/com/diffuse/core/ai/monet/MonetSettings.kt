package com.diffuse.core.ai.monet

import android.content.Context
import android.content.SharedPreferences
import com.diffuse.core.ai.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * specs/auto_enhance.md §4. `local.properties` supplies the build-time default and it is blank:
 * **no address is shipped**, the rule generative_erase.md §2 states for the Gemini key and this
 * spec inherits one server over.
 *
 * `SharedPreferences` rather than DataStore, matching `Sam3Settings` and `ExportSettingsStore` —
 * the version catalog has no DataStore entry and CLAUDE.md freezes it.
 */
@Singleton
class MonetSettings @Inject constructor(
    @ApplicationContext context: Context,
) : MonetConfigSource {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())

    /** Emits on every override so the provider can re-probe availability. */
    val config: StateFlow<MonetConfig> = _config

    override fun current(): MonetConfig = _config.value

    fun update(baseUrl: String, token: String) {
        val normalized = MonetConfig(baseUrl.trim().trimEnd('/'), token.trim())
        prefs.edit()
            .putString(KEY_BASE_URL, normalized.baseUrl)
            .putString(KEY_TOKEN, normalized.token)
            .apply()
        _config.value = normalized
    }

    private fun read() = MonetConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, null) ?: BuildConfig.MONET_BASE_URL.trimEnd('/'),
        token = prefs.getString(KEY_TOKEN, null) ?: BuildConfig.MONET_TOKEN,
    )

    private companion object {
        const val PREFS_NAME = "monet_settings"
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "token"
    }
}
