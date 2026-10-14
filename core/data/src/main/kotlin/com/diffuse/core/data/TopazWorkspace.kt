package com.diffuse.core.data

import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.common.newId
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * specs/topaz_enhancement.md §5. One run's files: the input JPEG that is uploaded and the result
 * that comes back. They belong to the editor session that opened them until it discards them.
 */
class TopazSessionFiles internal constructor(val dir: File) {

    val input: File get() = File(dir, "input.jpg")

    /** One file per run, so a result being saved is never overwritten by the next run. */
    fun output(run: Long): File = File(dir, "output_$run.jpg")
}

/**
 * specs/topaz_enhancement.md §5: where Topaz files live between the download and the user's
 * choice. Apart from every project folder, so nothing a document or its undo history references
 * is ever in here, and clearing it can never touch one.
 *
 * Process-wide: the first session opened in a process removes what earlier processes left —
 * nothing in this process can own those, because it has not opened any yet.
 */
class TopazWorkspace(
    private val root: File,
    private val dispatchers: DispatcherProvider,
) {

    private val swept = AtomicBoolean(false)

    suspend fun open(): Result<TopazSessionFiles> = withContext(dispatchers.io) {
        if (swept.compareAndSet(false, true)) root.listFiles()?.forEach { it.deleteRecursively() }
        val dir = File(root, newId())
        if (dir.mkdirs()) {
            Result.Success(TopazSessionFiles(dir))
        } else {
            Result.Failure(AppError.Io(IOException("could not create a Topaz work folder")))
        }
    }

    /** Deletes one file of a session, e.g. a result superseded by the next run. */
    suspend fun delete(file: File) {
        withContext(dispatchers.io) { file.delete() }
    }

    suspend fun discard(session: TopazSessionFiles) {
        withContext(dispatchers.io) { session.dir.deleteRecursively() }
    }
}
