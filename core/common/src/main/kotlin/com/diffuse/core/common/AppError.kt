package com.diffuse.core.common

/**
 * specs/architecture.md §9. Every failure a core module can report crossing a module
 * boundary. Features map these to Korean strings in `strings.xml`.
 */
sealed interface AppError {

    /** The image is too large to decode within the memory budget. */
    data object TooLarge : AppError

    /** The format is not one this app decodes, or the file is not an image at all. */
    data object Unsupported : AppError

    /** The referenced file or URI no longer resolves. */
    data object MissingSource : AppError

    /** Reading or writing failed. Carries the cause so the [Logger] can record it. */
    data class Io(val cause: Throwable) : AppError
}
