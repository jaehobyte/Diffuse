package com.diffuse.feature.export

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
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
    private val clock: () -> Long = System::currentTimeMillis,
) : ImageStore {

    /**
     * `IS_PENDING` stays set until the bytes are written, so a gallery never shows a
     * half-written file and a cancelled export can delete the row (specs/export.md).
     */
    override suspend fun write(bitmap: Bitmap, format: ExportFormat): Result<Uri> {
        val name = SimpleDateFormat(FILENAME_PATTERN, Locale.US).format(Date(clock()))
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.${format.extension()}")
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType())
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$albumName",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return Result.Failure(AppError.Io(IOException("MediaStore refused the insert")))

        return try {
            resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(format.compressFormat(), format.quality(), stream)
            } ?: throw IOException("no output stream for $uri")
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            Result.Success(uri)
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            Result.Failure(AppError.Io(e))
        }
    }
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
