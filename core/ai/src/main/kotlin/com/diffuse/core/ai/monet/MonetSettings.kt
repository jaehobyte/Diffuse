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
class MonetSettings internal constructor(
    context: Context,
    private val defaults: MonetConfig,
) : MonetConfigSource {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context,
        MonetConfig.normalized(BuildConfig.MONET_BASE_URL, BuildConfig.MONET_TOKEN),
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())

    /** The current address and token. Equal saves do not emit; see [saves]. */
    val config: StateFlow<MonetConfig> = _config

    private val _saves = MutableStateFlow(0)

    /**
     * specs/auto_enhance.md §6: counts every [update], including one that saves exactly what was
     * already there. Saving the same address again is how a user asks "try that server again",
     * and [config] alone — a `StateFlow` — would swallow it.
     */
    val saves: StateFlow<Int> = _saves

    override fun current(): MonetConfig = _config.value

    fun update(baseUrl: String, token: String) {
        val normalized = MonetConfig.normalized(baseUrl, token)
        prefs.edit()
            .putOrDefault(KEY_BASE_URL, normalized.baseUrl, defaults.baseUrl)
            .putOrDefault(KEY_TOKEN, normalized.token, defaults.token)
            .apply()
        _config.value = normalized
        _saves.value += 1
    }

    /**
     * A value equal to the build default is not stored as an override. The sheet saves every
     * field it shows, so otherwise one save of an untouched field would pin that build's default
     * forever and a later APK's corrected default would never be read.
     */
    private fun SharedPreferences.Editor.putOrDefault(
        key: String,
        value: String,
        default: String,
    ): SharedPreferences.Editor = if (value == default) remove(key) else putString(key, value)

    /** An older override is read back through the same normal form a save uses. */
    private fun read() = MonetConfig.normalized(
        baseUrl = prefs.getString(KEY_BASE_URL, null) ?: defaults.baseUrl,
        token = prefs.getString(KEY_TOKEN, null) ?: defaults.token,
    )

    private companion object {
        const val PREFS_NAME = "monet_settings"
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "token"
    }
}
