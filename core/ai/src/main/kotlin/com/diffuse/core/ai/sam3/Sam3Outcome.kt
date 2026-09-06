package com.diffuse.core.ai.sam3

import com.diffuse.core.common.AppError

/**
 * Transport result. `410 session_expired` is its own case rather than an [AppError] because
 * specs/segmentation.md §5 absorbs it one layer up — only the provider holds the bytes needed
 * to re-upload — and a caller must never be able to confuse it with a real failure.
 */
internal sealed interface Sam3Outcome<out T> {
    data class Success<T>(val value: T) : Sam3Outcome<T>
    data object SessionExpired : Sam3Outcome<Nothing>
    data class Failure(val error: AppError) : Sam3Outcome<Nothing>
}
