package com.diffuse.core.common

/**
 * specs/architecture.md §9: logging goes through this, not scattered `Log.d` calls.
 * `core:common` carries no Android dependency, so the platform implementation is
 * supplied by `app`.
 */
interface Logger {
    fun debug(tag: String, message: String)
    fun warn(tag: String, message: String, cause: Throwable? = null)
    fun error(tag: String, message: String, cause: Throwable? = null)
}
