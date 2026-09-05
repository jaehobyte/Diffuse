package com.diffuse.core.imaging.load

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/** specs/imaging.md: the long edge never exceeds this. */
const val MAX_LONG_EDGE_PX = 4096

private const val ROTATE_90 = 90f
private const val ROTATE_180 = 180f
private const val ROTATE_270 = 270f

/**
 * Decodes a picked image into a bounded, correctly oriented bitmap (specs/imaging.md).
 *
 * `decode(ref, targetLongEdgePx)` from the spec is not here yet: it needs `ImageRef`
 * from T08 and specs/imaging.md assigns it to T10.
 */
class ImageLoader internal constructor(
    private val resolver: ContentResolver,
    private val dispatchers: DispatcherProvider,
    private val decodeByteArray: (ByteArray, BitmapFactory.Options) -> Bitmap?,
) {

    constructor(resolver: ContentResolver, dispatchers: DispatcherProvider) : this(
        resolver,
        dispatchers,
        { bytes, options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) },
    )

    suspend fun load(uri: Uri): Result<SourceImage> =
        when (val bytes = readBytes(uri)) {
            is Result.Failure -> bytes
            is Result.Success -> withContext(dispatchers.default) { decode(bytes.value) }
        }

    private suspend fun readBytes(uri: Uri): Result<ByteArray> = withContext(dispatchers.io) {
        try {
            val stream = resolver.openInputStream(uri)
                ?: return@withContext Result.Failure(AppError.MissingSource)
            Result.Success(stream.use { it.readBytes() })
        } catch (@Suppress("SwallowedException") e: FileNotFoundException) {
            // The typed error is the report; the stack trace adds nothing here.
            Result.Failure(AppError.MissingSource)
        } catch (@Suppress("SwallowedException") e: SecurityException) {
            // Read permission on a content:// URI can be revoked between pick and load.
            Result.Failure(AppError.MissingSource)
        } catch (e: IOException) {
            Result.Failure(AppError.Io(e))
        }
    }

    @Suppress("ReturnCount")
    private suspend fun decode(bytes: ByteArray): Result<SourceImage> = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decodeByteArray(bytes, bounds)
        val mimeType = bounds.outMimeType
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || mimeType == null) {
            return Result.Failure(AppError.Unsupported)
        }
        if (!isSupported(mimeType)) return Result.Failure(AppError.Unsupported)
        coroutineContext.ensureActive()

        val orientation = readOrientation(bytes)
        val sampled = decodeSampled(bytes, bounds)
            ?: return Result.Failure(AppError.Unsupported)
        coroutineContext.ensureActive()

        val scaled = scaleToBound(sampled, bounds)
        coroutineContext.ensureActive()

        val oriented = applyOrientation(scaled, orientation)
        Result.Success(
            SourceImage(
                bitmap = oriented,
                widthPx = oriented.width,
                heightPx = oriented.height,
                sourceWidthPx = bounds.outWidth,
                sourceHeightPx = bounds.outHeight,
                mimeType = mimeType,
                hasAlpha = oriented.hasAlpha(),
            ),
        )
    } catch (@Suppress("SwallowedException") e: OutOfMemoryError) {
        // specs/imaging.md: the only place OutOfMemoryError is caught. Reporting
        // TooLarge allocates nothing; the alternative is an unrecoverable crash.
        Result.Failure(AppError.TooLarge)
    }

    private fun decodeSampled(bytes: ByteArray, bounds: BitmapFactory.Options): Bitmap? {
        val (targetWidth, targetHeight) = targetSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return decodeByteArray(bytes, options)
    }

    private fun scaleToBound(sampled: Bitmap, bounds: BitmapFactory.Options): Bitmap {
        val (targetWidth, targetHeight) = targetSize(bounds.outWidth, bounds.outHeight)
        if (sampled.width == targetWidth && sampled.height == targetHeight) return sampled
        val scaled = Bitmap.createScaledBitmap(sampled, targetWidth, targetHeight, true)
        if (scaled !== sampled) sampled.recycle()
        return scaled
    }

    private fun readOrientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (@Suppress("SwallowedException") e: IOException) {
        // No readable EXIF block is normal for PNG and WebP.
        ExifInterface.ORIENTATION_NORMAL
    }

    private companion object {

        /** Never upscale: an image already inside the bound keeps its stored size. */
        fun targetSize(width: Int, height: Int): Pair<Int, Int> {
            val longEdge = max(width, height)
            if (longEdge <= MAX_LONG_EDGE_PX) return width to height
            val scale = MAX_LONG_EDGE_PX.toDouble() / longEdge
            return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
        }

        /** The largest power of two that still leaves both edges at or above the target. */
        fun sampleSizeFor(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): Int {
            var sample = 1
            while (srcWidth / (sample * 2) >= dstWidth && srcHeight / (sample * 2) >= dstHeight) {
                sample *= 2
            }
            return sample
        }

        fun isSupported(mimeType: String): Boolean = when (mimeType) {
            "image/jpeg", "image/png", "image/webp" -> true
            "image/heif", "image/heic", "image/avif" ->
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            else -> false
        }

        fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(ROTATE_90)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(ROTATE_180)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(ROTATE_270)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(ROTATE_90)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(ROTATE_270)
                    matrix.postScale(-1f, 1f)
                }
                else -> return bitmap
            }
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true,
            )
            if (rotated !== bitmap) bitmap.recycle()
            return rotated
        }
    }
}
