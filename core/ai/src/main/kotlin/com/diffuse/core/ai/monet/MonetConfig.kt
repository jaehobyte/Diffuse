package com.diffuse.core.ai.monet

/**
 * specs/auto_enhance.md §4. Where the MonetGPT server is and how to authenticate against it —
 * the shape `Sam3Config` already has, because it is the same kind of thing: a self-hosted model
 * behind a URL the user pastes in.
 */
data class MonetConfig(val baseUrl: String, val token: String = "") {

    /** An unconfigured base URL is the default state, not an error to hide. */
    val isConfigured: Boolean get() = baseUrl.isNotBlank()
}

/** Read on every call, so changing the settings sheet takes effect without a new client. */
fun interface MonetConfigSource {
    fun current(): MonetConfig
}
