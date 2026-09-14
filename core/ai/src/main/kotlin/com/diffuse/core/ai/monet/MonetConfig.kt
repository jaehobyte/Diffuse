package com.diffuse.core.ai.monet

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * specs/auto_enhance.md §4. Where the MonetGPT server is and how to authenticate against it —
 * the shape `Sam3Config` already has, because it is the same kind of thing: a self-hosted model
 * behind a URL the user pastes in.
 */
data class MonetConfig(val baseUrl: String, val token: String = "") {

    /** An unconfigured base URL is the default state, not an error to hide. */
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    /**
     * §4: an `http`/`https` address OkHttp can build a request from. Checked before any request,
     * because `Request.Builder.url` throws on anything else and a saved typo must not crash.
     */
    val hasValidBaseUrl: Boolean get() = isValidMonetBaseUrl(baseUrl)

    companion object {

        /**
         * One normal form for what the sheet saves and what an older override reads back as:
         * trimmed, no trailing slash, and no trailing `/v1` — the client appends
         * `/v1/chat/completions` itself, so a pasted OpenAI-style base would otherwise ask for
         * `/v1/v1/…`.
         */
        fun normalized(baseUrl: String, token: String): MonetConfig {
            val trimmed = baseUrl.trim().trimEnd('/')
            val base = if (trimmed.endsWith(OPENAI_PREFIX)) {
                trimmed.removeSuffix(OPENAI_PREFIX).trimEnd('/')
            } else {
                trimmed
            }
            return MonetConfig(base, token.trim())
        }

        private const val OPENAI_PREFIX = "/v1"
    }
}

/** Blank is valid — it is the unconfigured state — so the sheet can tell a typo from nothing. */
fun isValidMonetBaseUrl(baseUrl: String): Boolean =
    baseUrl.isBlank() || baseUrl.trim().toHttpUrlOrNull() != null

/** Read on every call, so changing the settings sheet takes effect without a new client. */
fun interface MonetConfigSource {
    fun current(): MonetConfig
}
