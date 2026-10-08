package com.diffuse.core.ai.retouch.server

import com.diffuse.core.ai.SkinRetouchKind
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * specs/skin_retouch_pipeline.md §8. Where the skin retouch server is and the token it wants.
 *
 * Its own address and its own token, never MonetGPT's or SAM 3's (§8: "SAM 3 credential을 자동
 * 복사하지 않는다"): the three services rotate secrets separately and the settings sheet shows them
 * as separate fields.
 */
data class RetouchServerConfig(val baseUrl: String, val token: String = "") {

    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    /** `http`/`https` only; checked before a request is built, because OkHttp throws otherwise. */
    val hasValidBaseUrl: Boolean get() = isValidRetouchBaseUrl(baseUrl)

    companion object {
        /** Trimmed and without a trailing slash; the client appends `/health` and `/v1/retouch`. */
        fun normalized(baseUrl: String, token: String) =
            RetouchServerConfig(baseUrl.trim().trimEnd('/'), token.trim())
    }
}

/** Blank is valid — it is the unconfigured state — so the sheet can tell a typo from nothing. */
fun isValidRetouchBaseUrl(baseUrl: String): Boolean =
    baseUrl.isBlank() || baseUrl.trim().toHttpUrlOrNull() != null

/** Read on every call, so a save takes effect without a new client. */
fun interface RetouchServerConfigSource {
    fun current(): RetouchServerConfig
}

/** §8.1 wire names. One table, used by the request, the response check and `/health`. */
internal val SkinRetouchKind.wireName: String
    get() = when (this) {
        SkinRetouchKind.Blemish -> "blemish"
        SkinRetouchKind.Shine -> "shine"
        SkinRetouchKind.DarkCircles -> "dark_circles"
        SkinRetouchKind.ShavingShadow -> "shaving_shadow"
    }

internal fun skinRetouchKindOf(wireName: String): SkinRetouchKind? =
    SkinRetouchKind.entries.firstOrNull { it.wireName == wireName }
