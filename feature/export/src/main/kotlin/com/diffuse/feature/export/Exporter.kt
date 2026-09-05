package com.diffuse.feature.export

import android.net.Uri
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.EditDocument

/** specs/export.md: render, write, and report what the editor should show. */
class Exporter(
    private val pipeline: ExportPipeline,
    private val store: ImageStore,
) {

    sealed interface Outcome {
        data class Saved(val uri: Uri) : Outcome
        data class Failed(val error: AppError) : Outcome
    }

    suspend fun export(
        document: EditDocument,
        settings: ExportSettings,
        onProgress: (Float) -> Unit = {},
    ): Outcome = when (val rendered = pipeline.render(document, settings, onProgress)) {
        is Result.Failure -> Outcome.Failed(rendered.error)
        is Result.Success -> when (val written = store.write(rendered.value, settings.format)) {
            is Result.Failure -> Outcome.Failed(written.error)
            is Result.Success -> Outcome.Saved(written.value)
        }
    }
}
