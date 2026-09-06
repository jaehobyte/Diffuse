package com.diffuse.core.ai.gemini

/**
 * specs/generative_erase.md §8. Where `gemini-2.5-flash-image` is and which key pays for it.
 *
 * [baseUrl] is not a setting. It is a constant with a constructor seam so tests can point at
 * `MockWebServer`; a text field for it would be a way to send the user's key to an arbitrary
 * host.
 */
data class GeminiConfig(
    val apiKey: String,
    val baseUrl: String = DEFAULT_BASE_URL,
) {

    /** No key is shipped (§2), so "unconfigured" is the state the app starts in. */
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
    }
}

/**
 * Read on every call rather than injected once, so a key typed into the settings sheet takes
 * effect without rebuilding the client. `GeminiSettings` is the real implementation.
 */
fun interface GeminiConfigSource {
    fun current(): GeminiConfig
}
