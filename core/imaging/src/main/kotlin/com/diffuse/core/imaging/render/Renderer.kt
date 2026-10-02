package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.load.ImageLoader
import com.diffuse.core.imaging.load.MAX_LONG_EDGE_PX
import com.diffuse.core.imaging.load.MaskIo
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.HeroMask
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.MAX_SHOTS
import com.diffuse.core.imaging.model.MultiShotMode
import com.diffuse.core.imaging.model.Operation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** specs/render.md sizes both caches in entries. */
const val PREVIEW_CACHE_ENTRIES = 3
const val BASE_CACHE_ENTRIES = 2

/** One active mask, plus room for the previous one while undo is in flight. */
const val MASK_CACHE_ENTRIES = 2

/**
 * specs/multishot.md §8: every shot of one composite, at one preview size. Each cached decode is
 * scaled to that size, so the cache holds at most four preview-sized bitmaps (≈ 4 × 3.5 MB at 1080).
 */
const val SHOT_CACHE_ENTRIES = MAX_SHOTS

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

    /**
     * specs/skin_retouch_pipeline.md §4 step 5: a bitmap the renderer resolves for [ref] instead
     * of reading its file, until removed — an `ARGB_8888` result or an `ALPHA_8` mask. Draft
     * previews only, so a slider move draws through the real renderer without writing a PNG;
     * never persisted.
     */
    fun putTransient(ref: ImageRef, bitmap: Bitmap)

    fun removeTransient(ref: ImageRef)
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
        /** A transient changes pixels behind an unchanged ref, so it must change the key too. */
        val transientRevision: Long,
    )
    private data class BaseKey(val source: ImageRef, val targetLongEdgePx: Int)
    private data class ShotKey(val subject: ImageRef, val targetLongEdgePx: Int)

    /** A shot's subject file would not decode; the render fails rather than drop the subject. */
    private class MissingShotException : Exception()

    private val previewCache = LruCache<PreviewKey, Bitmap>(PREVIEW_CACHE_ENTRIES)
    private val maskCache = LruCache<ImageRef, Bitmap>(MASK_CACHE_ENTRIES)
    private val resultCache = LruCache<ImageRef, Bitmap>(MASK_CACHE_ENTRIES)
    private val baseCache = LruCache<BaseKey, Bitmap>(BASE_CACHE_ENTRIES)
    private val shotCache = LruCache<ShotKey, Bitmap>(SHOT_CACHE_ENTRIES)
    private val lock = Mutex()

    /** Never copied into [maskCache] or [resultCache], so removing one leaves nothing stale. */
    private val transients = ConcurrentHashMap<ImageRef, Bitmap>()
    private val transientRevision = AtomicLong()

    override suspend fun preview(
        document: EditDocument,
        targetLongEdgePx: Int,
    ): Result<Bitmap> = lock.withLock {
        val key = PreviewKey(document.source, document.operations, targetLongEdgePx, transientRevision.get())
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
        return transients[ref] ?: maskCache[ref] ?: withContext(dispatchers.io) {
            MaskIo.read(File(ref.path))?.also { maskCache.put(ref, it) }
        }
    }

    override fun putTransient(ref: ImageRef, bitmap: Bitmap) {
        transients[ref] = bitmap
        transientRevision.incrementAndGet()
    }

    override fun removeTransient(ref: ImageRef) {
        if (transients.remove(ref) != null) transientRevision.incrementAndGet()
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
        return try {
            withContext(dispatchers.default) {
                Result.Success(applyOperations(document, expanded(document, base), targetLongEdgePx, onProgress))
            }
        } catch (@Suppress("SwallowedException") e: MissingShotException) {
            // specs/multishot.md §7: a composite without one of its subjects is not a success.
            Result.Failure(AppError.MissingSource)
        }
    }

    /**
     * specs/outpaint.md §4 step 2, ahead of the in-order walk: the canvas the rest of the
     * operations measure against. `Outpaint` is `operations[0]` by construction
     * (`EditDocument.withOutpaint`), and only that one is honoured — an `Outpaint` anywhere else
     * is the in-list design §3 rejects, and applying it there would move every op before it.
     */
    private suspend fun expanded(document: EditDocument, base: Bitmap): Bitmap {
        val outpaint = document.outpaint() ?: return base
        val result = decodeResult(outpaint.resultRef)
        return if (result == null) base else OutpaintOp.apply(base, result, outpaint.margins)
    }

    /**
     * specs/render.md pipeline order, and specs/generative_erase.md §10's "ops added after the
     * erase apply on top of it, in list order": the list is walked **once, in order**, so what
     * the user did last is what lands last.
     *
     * `Crop` is the single exception — it runs last wherever it sits, so adjustments stay visible
     * inside it (render.md). `Mask` changes no pixels; other ops reference it by id.
     *
     * Grouping by type instead, which this did until T49, silently dropped every masked
     * adjustment made after an erase: the adjustment was computed first and the erase result then
     * overwrote the same mask region.
     */
    private suspend fun applyOperations(
        document: EditDocument,
        base: Bitmap,
        targetLongEdgePx: Int,
        onProgress: (Float) -> Unit,
    ): Bitmap {
        val crop = document.crop()
        // `Outpaint` produced the canvas this walk starts from, so it is not one of the steps.
        val pixelOps = document.operations.filter {
            it !is Operation.Mask && it !is Operation.Crop && it !is Operation.Outpaint
        }
        val total = pixelOps.size + if (crop == null) 0 else 1
        var output = base
        var completed = 0

        pixelOps.forEach { operation ->
            coroutineContext.ensureActive()
            output = applyOperation(document, output, operation, targetLongEdgePx)
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

    private suspend fun applyOperation(
        document: EditDocument,
        input: Bitmap,
        operation: Operation,
        targetLongEdgePx: Int,
    ): Bitmap = when (operation) {
        is Operation.Adjust -> applyAdjust(document, input, operation)
        is Operation.GenerativeErase ->
            blendResult(document, input, operation.maskId, operation.resultRef)
        is Operation.GenerativeFill ->
            blendResult(document, input, operation.maskId, operation.resultRef)
        is Operation.CutOut -> applyCutOut(document, input, operation)
        is Operation.SkinRetouch -> applySkinRetouch(document, input, operation)
        is Operation.MultiShot -> applyMultiShot(input, operation, targetLongEdgePx)
        // A mask is a reference, the crop runs last in applyOperations, and the outpaint already
        // ran: it is what `input` is.
        is Operation.Mask, is Operation.Crop, is Operation.Outpaint -> input
    }

    /**
     * specs/generative_erase.md §6 and specs/generative_fill.md §5: the stored result replaces
     * pixels inside the mask only, so everything after it still composes. 지우기 and 채우기 differ
     * in which file they load and in nothing else, so they share this.
     */
    private suspend fun blendResult(
        document: EditDocument,
        input: Bitmap,
        maskId: String,
        resultRef: ImageRef,
    ): Bitmap {
        val mask = resolveMask(document, maskId)
        val result = decodeResult(resultRef)
        return if (mask == null || result == null) {
            input
        } else {
            MaskBlend.blend(input, scaleTo(result, input), mask)
        }
    }

    /**
     * specs/skin_retouch_pipeline.md §6: like [blendResult] in which files it needs and in leaving
     * the input alone when either is gone, but it replaces RGB only and keeps the input alpha.
     */
    private suspend fun applySkinRetouch(
        document: EditDocument,
        input: Bitmap,
        retouch: Operation.SkinRetouch,
    ): Bitmap {
        val support = resolveMask(document, retouch.maskId)
        val result = decodeResult(retouch.resultRef)
        return if (support == null || result == null) {
            input
        } else {
            SkinRetouchOp.apply(input, result, support)
        }
    }

    /**
     * specs/multishot.md §5: the shots are decoded and drawn one at a time, so an export never
     * holds more than one full-size subject beside the canvas. Only a preview-sized decode is cached.
     *
     * §6: the time layout then puts the input back inside the hero mask — `lerp(composite, input,
     * mask)` on premultiplied pixels, once — so the last moment's own pixels, colour and alpha, stay in front of every
     * afterimage. The only extra buffer is the mask; the input is the copy's source anyway.
     */
    private suspend fun applyMultiShot(
        input: Bitmap,
        multiShot: Operation.MultiShot,
        targetLongEdgePx: Int,
    ): Bitmap {
        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        multiShot.drawingOrder.forEach { shot ->
            coroutineContext.ensureActive()
            if (shot.placement.opacity <= 0f) return@forEach
            val subject = decodeShot(shot.subjectRef, targetLongEdgePx) ?: throw MissingShotException()
            MultiShotOp.draw(output, shot, subject)
        }
        val hero = multiShot.timeline?.hero?.takeIf { multiShot.mode == MultiShotMode.Timeline } ?: return output
        coroutineContext.ensureActive()
        val mask = decodeResult(hero.ref)
        // §7: a mask that is gone, or that was cut for another canvas, protects nothing it should.
        if (mask == null || !heroFits(mask, hero, output)) throw MissingShotException()
        return MultiShotOp.protect(output, input.toArgb(), mask)
    }

    private suspend fun decodeShot(ref: ImageRef, targetLongEdgePx: Int): Bitmap? {
        val cacheable = targetLongEdgePx < MAX_LONG_EDGE_PX
        val key = ShotKey(ref, targetLongEdgePx)
        return transients[ref] ?: shotCache[key].takeIf { cacheable } ?: withContext(dispatchers.io) {
            decodeSampled(ref, targetLongEdgePx)
                ?.let { if (cacheable) fitted(it, targetLongEdgePx) else it }
                ?.also { if (cacheable) shotCache.put(key, it) }
        }
    }


    /** The largest power-of-two subsample whose long edge still reaches [targetLongEdgePx]. */
    private fun decodeSampled(ref: ImageRef, targetLongEdgePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(ref.path, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return null
        var sample = 1
        while (longEdge / (sample * 2) >= targetLongEdgePx) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(ref.path, options)
    }

    /** A cut-out is about pixels, like the adjustments; only the crop is geometry. */
    private suspend fun applyCutOut(
        document: EditDocument,
        input: Bitmap,
        cutOut: Operation.CutOut,
    ): Bitmap = resolveMask(document, cutOut.maskId)?.let { CutOutOp.apply(input, it) } ?: input

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
        (transients[ref] ?: resultCache[ref])?.let { return it }
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

/** §8: a cached preview decode is no larger than the preview, so four of them stay small. */
private fun fitted(bitmap: Bitmap, targetLongEdgePx: Int): Bitmap {
    val longEdge = maxOf(bitmap.width, bitmap.height)
    if (longEdge <= targetLongEdgePx) return bitmap
    val scale = targetLongEdgePx.toFloat() / longEdge
    return Bitmap.createScaledBitmap(
        bitmap,
        maxOf(1, (bitmap.width * scale).toInt()),
        maxOf(1, (bitmap.height * scale).toInt()),
        true,
    )
}

private fun Bitmap.toArgb(): Bitmap =
    if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)

/** specs/multishot.md §7: the stored mask is the size it says, cut for a canvas of this shape. */
private fun heroFits(mask: Bitmap, hero: HeroMask, canvas: Bitmap): Boolean =
    mask.width == hero.widthPx && mask.height == hero.heightPx &&
        MultiShotOp.sameAspect(mask.width, mask.height, canvas.width, canvas.height)
