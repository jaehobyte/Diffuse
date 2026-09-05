package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.Operation

/** Records the pipeline order so it can be asserted without any pixel maths. */
internal class RecordingOps(
    private val onEachOp: () -> Unit = {},
) : OpRegistry {

    val applied = mutableListOf<String>()

    override fun adjust(kind: AdjustKind): (Bitmap, Float) -> Bitmap = { bitmap, value ->
        applied += "${kind.name}=$value"
        onEachOp()
        bitmap
    }

    override fun crop(bitmap: Bitmap, operation: Operation.Crop): Bitmap {
        applied += "crop@${operation.angleDeg}"
        onEachOp()
        return bitmap
    }
}
