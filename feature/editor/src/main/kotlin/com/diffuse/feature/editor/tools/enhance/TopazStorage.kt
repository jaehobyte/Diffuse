package com.diffuse.feature.editor.tools.enhance

import com.diffuse.core.common.Result
import com.diffuse.core.data.TopazWorkspace
import java.io.File
import javax.inject.Inject

/**
 * specs/topaz_enhancement.md §5: copies a finished file into the gallery without re-encoding it.
 * The implementation is `feature:export`'s, bound in `app`, so this module never depends on it
 * (architecture.md §4.1).
 */
fun interface ResultFileSaver {
    suspend fun save(file: File, mimeType: String, width: Int, height: Int): Result<Unit>
}

/** Where a Topaz session's files live and where a finished one can go; one constructor argument. */
class TopazStorage @Inject constructor(
    val workspace: TopazWorkspace,
    val saver: ResultFileSaver,
)
