package com.diffuse.core.ai.sam3

/** specs/segmentation.md §6. Where the service is and how to authenticate against it. */
data class Sam3Config(val baseUrl: String, val token: String) {

    /** An unconfigured base URL is the default state, not an error to hide. */
    val isConfigured: Boolean get() = baseUrl.isNotBlank()
}

/**
 * Read on every call rather than injected once, so changing the settings sheet takes effect
 * without rebuilding the client. T28's `Sam3Settings` is the real implementation.
 */
fun interface Sam3ConfigSource {
    fun current(): Sam3Config
}
