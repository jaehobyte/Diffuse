package com.diffuse.core.imaging.model

import android.graphics.RectF
import com.diffuse.core.common.newId

/**
 * specs/edit_model.md: source plus an ordered operation list, so any state can be
 * re-rendered, undone, serialised and exported at full resolution.
 *
 * specs/architecture.md §6: read [operations] through the accessors below, never by
 * destructuring, so v2 can introduce layers without touching call sites.
 */
data class EditDocument(
    val id: String,
    val source: ImageRef,
    val operations: List<Operation> = emptyList(),
    /** specs/edit_model.md: the one [Operation.Mask] other tools apply to, or null. */
    val activeMaskId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {

    fun adjustValue(kind: AdjustKind, maskId: String? = null): Float =
        operations.filterIsInstance<Operation.Adjust>()
            .firstOrNull { it.kind == kind && it.maskId == maskId }
            ?.value
            ?: 0f

    fun crop(): Operation.Crop? = operations.filterIsInstance<Operation.Crop>().firstOrNull()

    fun mask(id: String): Operation.Mask? =
        operations.filterIsInstance<Operation.Mask>().firstOrNull { it.id == id }

    fun activeMask(): Operation.Mask? = activeMaskId?.let(::mask)

    fun cutOuts(): List<Operation.CutOut> = operations.filterIsInstance<Operation.CutOut>()

    fun generativeErases(): List<Operation.GenerativeErase> =
        operations.filterIsInstance<Operation.GenerativeErase>()

    fun generativeFills(): List<Operation.GenerativeFill> =
        operations.filterIsInstance<Operation.GenerativeFill>()

    /** specs/outpaint.md §3: at most one, and always `operations[0]`. */
    fun outpaint(): Operation.Outpaint? = operations.firstOrNull() as? Operation.Outpaint

    /**
     * specs/outpaint.md §3: 확대 comes before 선택. A `Mask`, `CutOut`, `GenerativeErase` or
     * `GenerativeFill` carries pixels or alpha sized to the un-extended canvas, and re-basing
     * those would mean resampling stored PNGs — a quality loss the user did not ask for. One
     * guard, in the model, so neither the tool nor a future planner can go round it.
     */
    val canOutpaint: Boolean
        get() = operations.none {
            it is Operation.Mask ||
                it is Operation.CutOut ||
                it is Operation.GenerativeErase ||
                it is Operation.GenerativeFill
        }

    /**
     * specs/edit_model.md: `source.hasAlpha || operations.any { it is CutOut }`. The document
     * holds no `SourceImage`, but `DefaultProjectRepository` writes the source as `.png`
     * exactly when it had an alpha channel, so the extension is that flag.
     */
    val hasAlpha: Boolean
        get() = source.path.endsWith(".png", ignoreCase = true) || cutOuts().isNotEmpty()

    /** Applies the active mask as a cut-out, in the same step that records the mask. */
    fun withCutOut(maskId: String, id: String = newId()): EditDocument =
        copy(operations = operations + Operation.CutOut(id, maskId))

    fun withGenerativeErase(
        maskId: String,
        resultRef: ImageRef,
        id: String = newId(),
    ): EditDocument = copy(operations = operations + Operation.GenerativeErase(id, maskId, resultRef))

    /** specs/generative_fill.md §5: the erase's shape plus the prompt that produced it. */
    fun withGenerativeFill(
        maskId: String,
        resultRef: ImageRef,
        prompt: String,
        id: String = newId(),
    ): EditDocument =
        copy(operations = operations + Operation.GenerativeFill(id, maskId, resultRef, prompt))

    /**
     * Adds a selection and makes it active, as one step. Older masks stay in the list so undo
     * can restore them; only [activeMaskId] moves.
     */
    fun withMask(maskRef: ImageRef, id: String = newId()): EditDocument =
        copy(operations = operations + Operation.Mask(id, maskRef), activeMaskId = id)

    /**
     * specs/edit_model.md: every mask reference must resolve. A document that fails this is not
     * loadable — silently dropping the reference would silently drop the user's selection.
     */
    fun referencesResolve(): Boolean =
        (activeMaskId == null || mask(activeMaskId) != null) &&
            cutOuts().all { mask(it.maskId) != null } &&
            generativeErases().all { mask(it.maskId) != null } &&
            generativeFills().all { mask(it.maskId) != null }

    /**
     * One live [Operation.Adjust] per `(kind, maskId)` pair: setting one that already exists
     * updates it in place, keeping its list position. A neutral value removes the entry rather
     * than storing a no-op. A masked Exposure and an unmasked Exposure may coexist
     * (specs/edit_model.md).
     */
    fun withAdjust(kind: AdjustKind, value: Float, maskId: String? = null): EditDocument {
        val coerced = kind.coerce(value)
        val index = operations.indexOfFirst {
            it is Operation.Adjust && it.kind == kind && it.maskId == maskId
        }
        val updated = when {
            kind.isNeutral(coerced) && index >= 0 -> operations - operations[index]
            kind.isNeutral(coerced) -> operations
            index >= 0 -> operations.toMutableList().also {
                it[index] = (it[index] as Operation.Adjust).copy(value = coerced)
            }
            else -> operations + Operation.Adjust(newId(), kind, coerced, maskId)
        }
        return copy(operations = updated)
    }

    /**
     * specs/outpaint.md §3. Inserts at index 0 and replaces an existing one, so a second 확대
     * re-bases from the bare source rather than extending the model's own invention. [margins]
     * are clamped, and an existing `Crop` is re-normalized into the new space rather than
     * dropped — four numbers with no pixels behind them cost nothing to move.
     *
     * Returns the document unchanged when [canOutpaint] is false; the tool greys itself on the
     * same flag, and this is what makes the rule the model's rather than the tool's.
     */
    fun withOutpaint(
        margins: Margins,
        resultRef: ImageRef,
        id: String = newId(),
    ): EditDocument {
        if (!canOutpaint) return this
        val clamped = margins.clamped()
        val existing = outpaint()
        val rest = operations.filterNot { it is Operation.Outpaint }.map { operation ->
            if (operation is Operation.Crop) {
                operation.copy(
                    rect = Margins.renormalize(
                        operation.rect,
                        from = existing?.margins ?: Margins.None,
                        to = clamped,
                    ),
                )
            } else {
                operation
            }
        }
        return copy(
            operations = listOf(Operation.Outpaint(existing?.id ?: id, clamped, resultRef)) + rest,
        )
    }

    /** At most one [Operation.Crop]; a new crop replaces the old one in place. */
    fun withCrop(rect: RectF, angleDeg: Float): EditDocument {
        val index = operations.indexOfFirst { it is Operation.Crop }
        val candidate = Operation.Crop(
            id = (operations.getOrNull(index) as? Operation.Crop)?.id ?: newId(),
            rect = RectF(rect),
            angleDeg = angleDeg,
        )
        val updated = when {
            candidate.isFullFrame && index >= 0 -> operations - operations[index]
            candidate.isFullFrame -> operations
            index >= 0 -> operations.toMutableList().also { it[index] = candidate }
            else -> operations + candidate
        }
        return copy(operations = updated)
    }
}
