package com.diffuse.feature.export

import android.graphics.Bitmap
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.render.Renderer
import kotlin.math.roundToInt

/**
 * specs/export.md §Pipeline: full render, then the preset centre-crop, then the downscale.
 * In that order — cropping after scaling would round the aspect twice.
 */
class ExportPipeline(private val renderer: Renderer) {

    suspend fun render(
        document: EditDocument,
        settings: ExportSettings,
        onProgress: (Float) -> Unit = {},
    ): Result<Bitmap> = when (val full = renderer.full(document, onProgress)) {
        is Result.Failure -> full
        is Result.Success -> Result.Success(
            downscale(centreCrop(full.value, settings.preset), settings.size),
        )
    }

    /** The preset applies to this export only; the document keeps its own crop. */
    internal fun centreCrop(bitmap: Bitmap, preset: ExportPreset): Bitmap {
        val aspect = preset.aspect
        val currentAspect = bitmap.width.toFloat() / bitmap.height
        if (aspect == null || kotlin.math.abs(currentAspect - aspect) < ASPECT_EPSILON) {
            return bitmap
        }

        val (width, height) = if (currentAspect > aspect) {
            (bitmap.height * aspect).roundToInt() to bitmap.height
        } else {
            bitmap.width to (bitmap.width / aspect).roundToInt()
        }
        val left = ((bitmap.width - width) / 2).coerceAtLeast(0)
        val top = ((bitmap.height - height) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(
            bitmap,
            left,
            top,
            width.coerceAtMost(bitmap.width),
            height.coerceAtMost(bitmap.height),
        )
    }

    /** Never upscales: "원본" and a target above the working resolution both mean "as is". */
    internal fun downscale(bitmap: Bitmap, size: ExportSize): Bitmap {
        val target = size.longEdgePx
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (target == null || longEdge <= target) return bitmap
        val scale = target.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private companion object {
        const val ASPECT_EPSILON = 0.001f
    }
}
