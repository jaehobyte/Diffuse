package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.load.ImageLoader
import com.diffuse.core.imaging.load.MAX_LONG_EDGE_PX
import com.diffuse.core.imaging.load.MaskIo
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** specs/render.md sizes both caches in entries. */
const val PREVIEW_CACHE_ENTRIES = 3
const val BASE_CACHE_ENTRIES = 2

/** One active mask, plus room for the previous one while undo is in flight. */
const val MASK_CACHE_ENTRIES = 2

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

    /**
     * specs/edit_model.md: a `Mask` op changes no pixels, so consumers read it through here.
     * @return null when [maskId] names no `Mask` op, or its file is gone.
     */
    suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap?
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

    private data class PreviewKey(
        val source: ImageRef,
        val operations: List<Operation>,
        val targetLongEdgePx: Int,
    )
    private data class BaseKey(val source: ImageRef, val targetLongEdgePx: Int)

    private val previewCache = LruCache<PreviewKey, Bitmap>(PREVIEW_CACHE_ENTRIES)
    private val maskCache = LruCache<ImageRef, Bitmap>(MASK_CACHE_ENTRIES)
    private val resultCache = LruCache<ImageRef, Bitmap>(MASK_CACHE_ENTRIES)
    private val baseCache = LruCache<BaseKey, Bitmap>(BASE_CACHE_ENTRIES)
    private val lock = Mutex()

    override suspend fun preview(
        document: EditDocument,
        targetLongEdgePx: Int,
    ): Result<Bitmap> = lock.withLock {
        val key = PreviewKey(document.source, document.operations, targetLongEdgePx)
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

    override suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap? {
        val ref = document.mask(maskId)?.maskRef ?: return null
        return maskCache[ref] ?: withContext(dispatchers.io) {
            MaskIo.read(File(ref.path))?.also { maskCache.put(ref, it) }
        }
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
        val erases = document.generativeErases()
        val cutOuts = document.cutOuts()
        val crop = document.crop()
        val total =
            adjustments.size + erases.size + cutOuts.size + if (crop == null) 0 else 1
        var output = base
        var completed = 0

        adjustments.forEach { adjust ->
            coroutineContext.ensureActive()
            output = applyAdjust(document, output, adjust)
            completed++
            onProgress(completed.toFloat() / total)
        }
        // specs/generative_erase.md §6: the result replaces pixels inside the mask only, so
        // everything after it still composes.
        erases.forEach { erase ->
            coroutineContext.ensureActive()
            val mask = resolveMask(document, erase.maskId)
            val result = decodeResult(erase.resultRef)
            if (mask != null && result != null) {
                output = MaskBlend.blend(output, scaleTo(result, output), mask)
            }
            completed++
            onProgress(completed.toFloat() / total)
        }
        // Before the crop, which is geometry: a cut-out is about pixels, like the adjustments.
        cutOuts.forEach { cutOut ->
            coroutineContext.ensureActive()
            resolveMask(document, cutOut.maskId)?.let { output = CutOutOp.apply(output, it) }
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

    /**
     * specs/selection_tool.md §8.1: a masked adjustment is computed over the whole frame and
     * then blended back through the mask. Computing it whole keeps every op's maths unchanged —
     * an op never learns that masks exist.
     */
    private suspend fun applyAdjust(
        document: EditDocument,
        input: Bitmap,
        adjust: Operation.Adjust,
    ): Bitmap {
        val adjusted = ops.adjust(adjust.kind)(input, adjust.value)
        val mask = adjust.maskId?.let { resolveMask(document, it) }
        return if (mask == null) adjusted else MaskBlend.blend(input, adjusted, mask)
    }

    /**
     * The generative result was produced at whatever resolution the editor was previewing at;
     * export renders larger. Scaling here is what keeps §7's promise that export composites the
     * pixels the user approved rather than generating new ones.
     */
    private fun scaleTo(source: Bitmap, like: Bitmap): Bitmap =
        if (source.width == like.width && source.height == like.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, like.width, like.height, true)
        }

    private suspend fun decodeResult(ref: ImageRef): Bitmap? {
        resultCache[ref]?.let { return it }
        return withContext(dispatchers.io) {
            BitmapFactory.decodeFile(ref.path)
                ?.copy(Bitmap.Config.ARGB_8888, false)
                ?.also { resultCache.put(ref, it) }
        }
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
