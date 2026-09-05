package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.load.ImageLoader
import com.diffuse.core.imaging.load.MAX_LONG_EDGE_PX
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** specs/render.md sizes both caches in entries. */
const val PREVIEW_CACHE_ENTRIES = 3
const val BASE_CACHE_ENTRIES = 2

/**
 * specs/render.md. Returns [Result] rather than throwing: specs/architecture.md §9 rules
 * that core modules never throw across a module boundary except `CancellationException`,
 * and §"Read by every task" makes it the winner where a task spec disagrees.
 */
interface Renderer {

    /** Renders at canvas resolution while editing. */
    suspend fun preview(document: EditDocument, targetLongEdgePx: Int): Result<Bitmap>

    /** Renders at source resolution for export. */
    suspend fun full(
        document: EditDocument,
        onProgress: (Float) -> Unit = {},
    ): Result<Bitmap>
}

/**
 * CPU renderer (ADR 002). Cancellation is checked between operations, so a superseded
 * render stops without publishing a partial bitmap. Conflating requests is the caller's
 * job: it owns the coroutine scope, and only it knows which request is still wanted.
 */
class CpuRenderer(
    private val loader: ImageLoader,
    private val dispatchers: DispatcherProvider,
    private val ops: OpRegistry = Ops,
) : Renderer {

    private data class PreviewKey(val operations: List<Operation>, val targetLongEdgePx: Int)
    private data class BaseKey(val source: ImageRef, val targetLongEdgePx: Int)

    private val previewCache = LruCache<PreviewKey, Bitmap>(PREVIEW_CACHE_ENTRIES)
    private val baseCache = LruCache<BaseKey, Bitmap>(BASE_CACHE_ENTRIES)
    private val lock = Mutex()

    override suspend fun preview(
        document: EditDocument,
        targetLongEdgePx: Int,
    ): Result<Bitmap> = lock.withLock {
        val key = PreviewKey(document.operations, targetLongEdgePx)
        previewCache[key]?.let { return@withLock Result.Success(it) }

        when (val rendered = render(document, targetLongEdgePx) {}) {
            is Result.Failure -> rendered
            is Result.Success -> {
                previewCache.put(key, rendered.value)
                rendered
            }
        }
    }

    override suspend fun full(
        document: EditDocument,
        onProgress: (Float) -> Unit,
    ): Result<Bitmap> = lock.withLock {
        render(document, MAX_LONG_EDGE_PX, onProgress)
    }

    private suspend fun render(
        document: EditDocument,
        targetLongEdgePx: Int,
        onProgress: (Float) -> Unit,
    ): Result<Bitmap> {
        val base = when (val decoded = decodeBase(document.source, targetLongEdgePx)) {
            is Result.Failure -> return decoded
            is Result.Success -> decoded.value
        }
        return withContext(dispatchers.default) {
            Result.Success(applyOperations(document, base, onProgress))
        }
    }

    /**
     * specs/render.md pipeline order: adjustments in list order, then the crop last
     * regardless of where it sits, so adjustments stay visible inside the crop.
     */
    private suspend fun applyOperations(
        document: EditDocument,
        base: Bitmap,
        onProgress: (Float) -> Unit,
    ): Bitmap {
        val adjustments = document.operations.filterIsInstance<Operation.Adjust>()
        val crop = document.crop()
        val total = adjustments.size + if (crop == null) 0 else 1
        var output = base
        var completed = 0

        adjustments.forEach { adjust ->
            coroutineContext.ensureActive()
            output = ops.adjust(adjust.kind)(output, adjust.value)
            completed++
            onProgress(completed.toFloat() / total)
        }
        if (crop != null) {
            coroutineContext.ensureActive()
            output = ops.crop(output, crop)
            completed++
            onProgress(completed.toFloat() / total)
        }
        if (total == 0) onProgress(1f)
        return output
    }

    private suspend fun decodeBase(source: ImageRef, targetLongEdgePx: Int): Result<Bitmap> {
        val key = BaseKey(source, targetLongEdgePx)
        baseCache[key]?.let { return Result.Success(it) }
        return when (val decoded = loader.decode(source, targetLongEdgePx)) {
            is Result.Failure -> decoded
            is Result.Success -> {
                baseCache.put(key, decoded.value)
                decoded
            }
        }
    }
}
