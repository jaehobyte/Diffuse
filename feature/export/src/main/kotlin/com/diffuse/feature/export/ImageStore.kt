package com.diffuse.feature.export

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** specs/export.md: MediaStore only, never `getExternalStoragePublicDirectory`. */
interface ImageStore {
    suspend fun write(bitmap: Bitmap, format: ExportFormat): Result<Uri>
}

private const val JPEG_QUALITY = 92
private const val PNG_QUALITY = 100
private const val FILENAME_PATTERN = "'IMG_'yyyyMMdd_HHmmss"

class MediaStoreImageStore(
    private val resolver: ContentResolver,
    private val albumName: String,
    private val dispatchers: DispatcherProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) : ImageStore {

    /** Off the main thread: a 4096px PNG encode takes seconds. */
    override suspend fun write(bitmap: Bitmap, format: ExportFormat): Result<Uri> =
        withContext(dispatchers.io) {
            val name = "${exportName(clock())}.${format.extension()}"
            val uri = resolver.insertPending(albumName, name, format.mimeType())
                ?: return@withContext Result.Failure(AppError.Io(IOException("MediaStore refused the insert")))

            var complete = false
            try {
                val written = resolver.openOutputStream(uri)?.use { stream ->
                    bitmap.compress(format.compressFormat(), format.quality(), stream)
                } ?: throw IOException("no output stream for $uri")
                if (!written) throw IOException("could not encode $format")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                complete = true
                Result.Success(uri)
            } catch (e: IOException) {
                Result.Failure(AppError.Io(e))
            } finally {
                if (!complete) resolver.delete(uri, null, null)
            }
        }
}

/** `IMG_yyyyMMdd_HHmmss`, the name both stores give a gallery entry. */
internal fun exportName(millis: Long): String =
    SimpleDateFormat(FILENAME_PATTERN, Locale.US).format(Date(millis))

/**
 * Inserts a row into `Pictures/<album>/` with `IS_PENDING` set, so a gallery never shows a
 * half-written file and a failed write can delete the row (specs/export.md).
 */
internal fun ContentResolver.insertPending(albumName: String, displayName: String, mimeType: String): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$albumName")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    return insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
}

internal fun ExportFormat.extension(): String = when (this) {
    ExportFormat.Jpeg -> "jpg"
    ExportFormat.Png -> "png"
}

internal fun ExportFormat.mimeType(): String = when (this) {
    ExportFormat.Jpeg -> "image/jpeg"
    ExportFormat.Png -> "image/png"
}

internal fun ExportFormat.compressFormat(): Bitmap.CompressFormat = when (this) {
    ExportFormat.Jpeg -> Bitmap.CompressFormat.JPEG
    ExportFormat.Png -> Bitmap.CompressFormat.PNG
}

internal fun ExportFormat.quality(): Int = when (this) {
    ExportFormat.Jpeg -> JPEG_QUALITY
    ExportFormat.Png -> PNG_QUALITY
}
