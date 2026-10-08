package com.diffuse.core.ai.retouch.server

import android.content.Context
import android.content.SharedPreferences
import com.diffuse.core.ai.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * specs/skin_retouch_pipeline.md §8, work/tasks.md requirement 18. The build default is blank
 * unless a private `-Pdiffuse.localCreds` build names one, so a published APK ships no address and
 * no token; a saved override wins over the default. `MonetSettings`' shape, for the same reasons.
 */
@Singleton
class RetouchServerSettings internal constructor(
    context: Context,
    private val defaults: RetouchServerConfig,
) : RetouchServerConfigSource {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context,
        RetouchServerConfig.normalized(BuildConfig.RETOUCH_BASE_URL, BuildConfig.RETOUCH_TOKEN),
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())
    val config: StateFlow<RetouchServerConfig> = _config

    private val _saves = MutableStateFlow(0)

    /** Every [update], including one that saves the same values: that is how a user retries. */
    val saves: StateFlow<Int> = _saves

    override fun current(): RetouchServerConfig = _config.value

    fun update(baseUrl: String, token: String) {
        val normalized = RetouchServerConfig.normalized(baseUrl, token)
        prefs.edit()
            .putOrDefault(KEY_BASE_URL, normalized.baseUrl, defaults.baseUrl)
            .putOrDefault(KEY_TOKEN, normalized.token, defaults.token)
            .apply()
        _config.value = normalized
        _saves.value += 1
    }

    /** A value equal to the build default is not pinned as an override. */
    private fun SharedPreferences.Editor.putOrDefault(
        key: String,
        value: String,
        default: String,
    ): SharedPreferences.Editor = if (value == default) remove(key) else putString(key, value)

    private fun read() = RetouchServerConfig.normalized(
        baseUrl = prefs.getString(KEY_BASE_URL, null) ?: defaults.baseUrl,
        token = prefs.getString(KEY_TOKEN, null) ?: defaults.token,
    )

    private companion object {
        const val PREFS_NAME = "retouch_server_settings"
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "token"
    }
}
