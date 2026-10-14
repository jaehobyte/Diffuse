package com.diffuse.feature.export

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * specs/export.md §고해상도 결과 저장. A finished image file, copied byte for byte into the
 * gallery. The bitmap path (`Renderer.full` → re-encode) is bounded at the 4096px working size;
 * this path is how a Topaz result keeps the resolution it was paid for.
 *
 * The same MediaStore rules as [MediaStoreImageStore]: `IS_PENDING` until the bytes are in, and a
 * failed or cancelled copy deletes its row.
 */
class MediaStoreFileStore(
    private val resolver: ContentResolver,
    private val albumName: String,
    private val dispatchers: DispatcherProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Copies [file] and then reads the stored image's bounds back: the gallery entry has to be
     * [width]×[height], or it is removed and this fails.
     */
    suspend fun copy(file: File, mimeType: String, width: Int, height: Int): Result<Uri> =
        withContext(dispatchers.io) {
            val extension = extensionOf(mimeType)
                ?: return@withContext Result.Failure(AppError.Unsupported)
            if (!file.isFile) return@withContext Result.Failure(AppError.MissingSource)
            val name = SimpleDateFormat(FILENAME_PATTERN, Locale.US).format(Date(clock()))
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.$extension")
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$albumName")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext Result.Failure(AppError.Io(IOException("MediaStore refused the insert")))

            var complete = false
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output, BUFFER_BYTES) }
                } ?: throw IOException("no output stream for $uri")
                coroutineContext.ensureActive()
                if (!storedBoundsMatch(uri, width, height)) throw IOException("stored image is not ${width}x$height")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                complete = true
                Result.Success(uri)
            } catch (e: IOException) {
                Result.Failure(AppError.Io(e))
            } finally {
                if (!complete) resolver.delete(uri, null, null)
            }
        }

    private fun storedBoundsMatch(uri: Uri, width: Int, height: Int): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        return bounds.outWidth == width && bounds.outHeight == height
    }

    private fun extensionOf(mimeType: String): String? = when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        else -> null
    }

    private companion object {
        const val FILENAME_PATTERN = "'IMG_'yyyyMMdd_HHmmss"
        const val BUFFER_BYTES = 64 * 1024
    }
}
