package com.diffuse.feature.browse

import android.net.Uri
import androidx.annotation.StringRes
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.imaging.load.ImageLoader

/** specs/architecture.md §9: features map `AppError` to Korean strings in strings.xml. */
@StringRes
fun AppError.messageRes(): Int = when (this) {
    AppError.TooLarge -> R.string.error_too_large
    AppError.Unsupported -> R.string.error_unsupported
    AppError.MissingSource -> R.string.error_missing_source
    is AppError.Io -> R.string.error_io
}

/**
 * specs/browse.md §Import. Decode, create the project, then hand back what the caller
 * should do — kept out of the composable so the two outcomes are testable without a picker.
 */
class BrowseImport(
    private val loader: ImageLoader,
    private val repository: ProjectRepository,
) {

    sealed interface Outcome {
        data class OpenEditor(val projectId: String) : Outcome
        data class Failed(val error: AppError) : Outcome
    }

    suspend fun import(uri: Uri): Outcome =
        when (val loaded = loader.load(uri)) {
            is Result.Failure -> Outcome.Failed(loaded.error)
            is Result.Success -> when (val created = repository.create(loaded.value)) {
                is Result.Failure -> Outcome.Failed(created.error)
                is Result.Success -> Outcome.OpenEditor(created.value)
            }
        }
}
