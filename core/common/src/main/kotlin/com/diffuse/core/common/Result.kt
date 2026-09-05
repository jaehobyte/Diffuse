package com.diffuse.core.common

/**
 * specs/architecture.md §9: core modules return this rather than throwing across a
 * module boundary. `CancellationException` remains the one exception that may propagate.
 *
 * Deliberately shadows `kotlin.Result` at the import site, which cannot carry a typed
 * error and whose `Failure` is not exhaustively matchable.
 */
sealed interface Result<out T> {

    data class Success<T>(val value: T) : Result<T>

    data class Failure(val error: AppError) : Result<Nothing>
}
