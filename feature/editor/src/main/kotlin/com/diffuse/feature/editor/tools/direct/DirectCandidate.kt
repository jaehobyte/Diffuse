package com.diffuse.feature.editor.tools.direct

import android.graphics.Bitmap
import com.diffuse.core.ai.topaz.TopazFailure
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.Operation
import com.diffuse.feature.editor.tools.enhance.TopazPhase
import com.diffuse.feature.editor.tools.enhance.TopazResult
import kotlin.math.abs

/**
 * specs/vibe_edit.md §18, §19. Where the chosen workflow's result is. A plan that is ready has not
 * been judged good: one 선택한 보정 실행 builds its result as a candidate, off the document and its
 * history — the local steps, then the Topaz step on their full render — and only a reviewed result
 * is committed (local) or kept as a file (Topaz, D098).
 */
enum class CandidatePhase {
    /** The local steps or the Topaz step are running; the overlay shows which and a way out. */
    Running,

    /**
     * An existing selection whose meaning this session does not know is on the overlay, waiting for
     * the user's yes or no. A selection the plan's own phrase found never waits here (§19).
     */
    Confirming,

    /** The local candidate is built; the local checks and the 전 frame are being prepared. */
    Checking,

    /**
     * Shown on the canvas with 전/후 compare. Without Topaz 적용 commits it once; with Topaz the
     * canvas shows the Topaz result once it exists for this candidate, and 고해상도 저장 or
     * 새 편집본으로 열기 keeps it.
     */
    Review,

    /**
     * §19: the choice changed and the new one needs a remote call (a selection or a generated
     * patch never made for this input). Nothing is called until the user taps 선택한 보정 다시 실행;
     * the canvas shows the input meanwhile and nothing can be kept.
     */
    Stale,
}

/** §18: what the local checks found, each a line in the review. */
enum class CandidateFinding {
    /** Pixels outside the selection changed under 배경 유지 / 대상만: 적용 stays off. */
    ProtectedChanged,

    /** The plan crops: what falls outside the new frame is gone, which is not a colour change. */
    FrameCropped,

    /** 색 유지 with a tonal step: brightness and contrast still move how colours look. */
    TonalUnderColorRule,

    /** A generated patch: only a person comparing the two frames can judge it. */
    Generated,
}

/** §18, §19: the candidate under review, or on its way there. */
data class CandidateState(
    val phase: CandidatePhase,
    /** Running: the step in flight, 0-based, and how many there are — the Topaz step included. */
    val step: Int = 0,
    val steps: Int = 0,
    /** Confirming: the selection on the overlay. */
    val check: TargetCheck? = null,
    /** Review: the local candidate. Never pushed until 적용, and never when Topaz is chosen. */
    val document: EditDocument? = null,
    /** Review: the workflow's input frame — the 전 of the compare. */
    val before: Bitmap? = null,
    val comparing: Boolean = false,
    /** A numeric adjustment differs from the input, so 강도 줄이기 has something to halve. */
    val reducible: Boolean = false,
    val findings: Set<CandidateFinding> = emptySet(),
    /** §19: the local items this candidate was built (or is being built) for. */
    val chosen: Set<String> = emptySet(),
    /** §19: 강도 줄이기 was used; a new choice rebuilds at the proposal's own strength. */
    val reduced: Boolean = false,
    /**
     * §19: Running, the Topaz step's stage in the provider's words; Review, a save or a new copy
     * reading the result. Null otherwise.
     */
    val topazPhase: TopazPhase? = null,
    /** The API's own percentage while it reports one; never invented. */
    val topazProgress: Int? = null,
    /** §19: the chosen Topaz item's result for exactly [document]; null until it ran for it. */
    val topaz: TopazResult? = null,
    /** §19: why the Topaz step did not finish for [document]. The local candidate is kept. */
    val topazFailure: TopazFailure? = null,
    /** 고해상도 저장 succeeded for [topaz]. */
    val saved: Boolean = false,
    /** §19: the phrases this run found selections for without asking, said in the review. */
    val resolved: List<String> = emptyList(),
) {
    val canAdopt: Boolean
        get() = phase == CandidatePhase.Review && document != null && CandidateFinding.ProtectedChanged !in findings
}

/**
 * §18 강도 줄이기: every numeric setting that differs from the input moves halfway back,
 * `b + 0.5 × (t − b)`, where `b` is the input's value for the same `(kind, mask)` — 0 for a
 * selection the candidate made, the existing value for one it reused — and `t` the candidate's.
 * A style is a set of such settings, so it is halved per value it wrote. Masks, crops, erases,
 * fills and cut-outs are left exactly as they are. A value within [SNAP] of its input is the input.
 *
 * A setting the candidate removed (an explicit 0) comes back halfway **in its old position**.
 */
fun reducedCandidate(base: EditDocument, candidate: EditDocument): EditDocument {
    val halved = candidate.operations.map { op ->
        if (op !is Operation.Adjust) return@map op
        val b = base.adjustValue(op.kind, op.maskId)
        op.copy(value = halfway(b, op.value))
    }
    val restored = base.operations.withIndex()
        .filter { (_, op) ->
            op is Operation.Adjust && candidate.operations.none {
                it is Operation.Adjust && it.kind == op.kind && it.maskId == op.maskId
            }
        }
        .fold(halved) { list, (index, op) ->
            val adjust = op as Operation.Adjust
            list.toMutableList().apply { add(index.coerceAtMost(size), adjust.copy(value = halfway(adjust.value, 0f))) }
        }
    return candidate.copy(
        operations = restored.filterNot { it is Operation.Adjust && it.kind.isNeutral(it.value) },
    )
}

private fun halfway(b: Float, t: Float): Float {
    val v = b + HALF * (t - b)
    return if (abs(v - b) < SNAP) b else v
}

/** §18: whether any numeric setting in [candidate] differs from [base] — 강도 줄이기's condition. */
fun hasNumericChange(base: EditDocument, candidate: EditDocument): Boolean {
    fun settings(document: EditDocument) = document.operations.filterIsInstance<Operation.Adjust>()
        .associate { (it.kind to it.maskId) to it.value }
    val before = settings(base)
    val after = settings(candidate)
    return (before.keys + after.keys).any { abs((before[it] ?: 0f) - (after[it] ?: 0f)) > SAME_VALUE }
}

/**
 * §18: the files a candidate wrote that its input does not reference — the masks and generated
 * patches to delete when it is discarded. They have fresh ids, so nothing in history holds them.
 */
fun createdFiles(base: EditDocument, candidate: EditDocument): Set<String> = candidate.paths() - base.paths()

/** The mask and generated-patch files [this] references — what a cleanup must never delete. */
fun EditDocument.paths(): Set<String> = operations.mapNotNull { op ->
    when (op) {
        is Operation.Mask -> op.maskRef.path
        is Operation.GenerativeErase -> op.resultRef.path
        is Operation.GenerativeFill -> op.resultRef.path
        else -> null
    }
}.toSet()

/**
 * §18: the share of pixels **outside** [allowed] whose colour moved by more than [TOLERANCE] on
 * any channel between [before] and [after] — the code half of 배경 유지. It says nothing about
 * whether [allowed] is the right thing: a mask that leaked onto the background passes it, which
 * is why the review names what each selection was found for and the user compares 전/후. Sizes
 * must match; [allowed] is scaled.
 */
fun outsideChange(before: Bitmap, after: Bitmap, allowed: Bitmap?): Float {
    if (before.width != after.width || before.height != after.height) return 1f
    val w = before.width
    val h = before.height
    val mask = allowed?.let { if (it.width == w && it.height == h) it else Bitmap.createScaledBitmap(it, w, h, false) }
    val outside = mask?.let { pixels(it).map { alpha -> alpha ushr ALPHA_SHIFT == 0 } }
    return changedShare(pixels(before), pixels(after)) { outside?.get(it) ?: true }
}

private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height)
    .also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

/** The share of the pixels [counted] picks whose colour moved by more than [TOLERANCE]. */
private inline fun changedShare(a: IntArray, b: IntArray, counted: (Int) -> Boolean): Float {
    val indices = a.indices.filter(counted)
    if (indices.isEmpty()) return 0f
    return indices.count { channelDelta(a[it], b[it]) > TOLERANCE }.toFloat() / indices.size
}

private fun channelDelta(x: Int, y: Int): Int = maxOf(
    abs((x shr RED_SHIFT and BYTE) - (y shr RED_SHIFT and BYTE)),
    abs((x shr GREEN_SHIFT and BYTE) - (y shr GREEN_SHIFT and BYTE)),
    abs((x and BYTE) - (y and BYTE)),
)

private const val HALF = 0.5f

/** Half a slider step (the sheets show 1/100): closer than this reads as the same setting. */
private const val SNAP = 0.005f

/** Float noise, not a setting: 0.41 − 0.40 is a change, 1e-7 is not. */
private const val SAME_VALUE = 1e-4f

/** A JPEG-free render round trip moves no channel; 2/255 absorbs filtering at the mask edge. */
private const val TOLERANCE = 2

/** Above this share the outside is said to have changed (§18). */
const val OUTSIDE_CHANGE_LIMIT = 0.005f

private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val BYTE = 0xFF
