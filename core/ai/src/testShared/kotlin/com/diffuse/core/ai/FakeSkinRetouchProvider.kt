package com.diffuse.core.ai

import android.graphics.Bitmap
import android.graphics.Color
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * specs/ai_provider.md §6, specs/skin_retouch_pipeline.md §2. What the editor's tests retouch
 * through, so no UI test needs a model.
 *
 * It corrects nothing real: inside the allowed region it blends each pixel halfway towards a tint
 * that is a function of the kind, which is deterministic — goldens stay stable — and different per
 * kind, so a test can tell a blemish candidate from a shine one. Everything the contract actually
 * promises is honoured: the input is untouched, the candidate is the base outside its support, the
 * support is binary at input size, and an empty allowance is [CorrectionOutcome.NoChange].
 *
 * It is a fake, never a quality baseline (skin_retouch_validation.md §1).
 */
class FakeSkinRetouchProvider(
    ready: Boolean = true,
    supportedKinds: Set<SkinRetouchKind> = SkinRetouchKind.entries.toSet(),
    override val executionLocation: ExecutionLocation = ExecutionLocation.Local,
) : SkinRetouchProvider {

    private val _availability = MutableStateFlow<Availability>(
        if (ready) Availability.Ready else Availability.Unavailable(AppError.Unavailable),
    )
    override val availability: StateFlow<Availability> = _availability

    override val checking: StateFlow<Boolean> = MutableStateFlow(false)

    private val _supportedKinds = MutableStateFlow(supportedKinds)
    override val supportedKinds: StateFlow<Set<SkinRetouchKind>> = _supportedKinds

    var refreshCount: Int = 0
        private set

    override fun refresh() {
        refreshCount++
    }

    fun setSupportedKinds(kinds: Set<SkinRetouchKind>) {
        _supportedKinds.value = kinds
    }

    /**
     * Each queued gate holds one [prepare], in call order, until it completes — for race tests. A
     * gate completed after its caller was cancelled lets the fake answer anyway, which is exactly
     * the provider that ignores cancellation (skin_retouch_validation.md §4 경합).
     */
    val gates: ArrayDeque<kotlinx.coroutines.CompletableDeferred<Unit>> = ArrayDeque()

    /** Kinds answered with [CorrectionOutcome.NoChange] whatever the pixels are. */
    var noChangeKinds: Set<SkinRetouchKind> = emptySet()

    fun setAvailability(availability: Availability) {
        _availability.value = availability
    }

    var prepareCount: Int = 0
        private set

    /** In call order, so a test can prove one request is one kind. */
    val preparedKinds: List<SkinRetouchKind> get() = _preparedKinds.toList()
    private val _preparedKinds = mutableListOf<SkinRetouchKind>()

    private var failure: AppError? = null

    fun failNext(error: AppError) {
        failure = error
    }

    fun setReady(ready: Boolean) {
        _availability.value =
            if (ready) Availability.Ready else Availability.Unavailable(AppError.Unavailable)
    }

    override suspend fun prepare(
        faceRoi: Bitmap,
        allowedMask: Bitmap,
        kind: SkinRetouchKind,
    ): Result<PreparedCorrection> {
        if (allowedMask.width != faceRoi.width || allowedMask.height != faceRoi.height) {
            return Result.Failure(AppError.Invalid("allowed mask is not the roi size"))
        }
        if (kind !in _supportedKinds.value) return Result.Failure(AppError.Unsupported)
        prepareCount++
        _preparedKinds += kind
        gates.removeFirstOrNull()?.let { gate ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
        }
        failure?.let {
            failure = null
            return Result.Failure(it)
        }

        val candidate = faceRoi.copy(Bitmap.Config.ARGB_8888, true)
        val support = MaskBitmaps.empty(faceRoi.width, faceRoi.height)
        var changed = false
        for (y in 0 until if (kind in noChangeKinds) 0 else faceRoi.height) {
            for (x in 0 until faceRoi.width) {
                if (MaskBitmaps.alphaAt(allowedMask, x, y) != MaskBitmaps.OPAQUE) continue
                val base = faceRoi.getPixel(x, y)
                val corrected = blendHalfway(base, tintFor(kind))
                if (corrected == base) continue
                candidate.setPixel(x, y, corrected)
                support.setPixel(x, y, MaskBitmaps.OPAQUE shl 24)
                changed = true
            }
        }

        return Result.Success(
            PreparedCorrection(
                candidate = candidate,
                changeSupport = support,
                outcome = if (changed) CorrectionOutcome.Corrected else CorrectionOutcome.NoChange,
                engineVersion = "$ENGINE:${kind.name.lowercase()}",
            ),
        )
    }

    /** Alpha is the base's: a correction never changes what is transparent (§2). */
    private fun blendHalfway(base: Int, tint: Int): Int = Color.argb(
        Color.alpha(base),
        (Color.red(base) + Color.red(tint)) / 2,
        (Color.green(base) + Color.green(tint)) / 2,
        (Color.blue(base) + Color.blue(tint)) / 2,
    )

    private fun tintFor(kind: SkinRetouchKind): Int = when (kind) {
        SkinRetouchKind.Blemish -> Color.rgb(216, 180, 156)
        SkinRetouchKind.Shine -> Color.rgb(158, 127, 110)
        SkinRetouchKind.DarkCircles -> Color.rgb(199, 155, 134)
        SkinRetouchKind.ShavingShadow -> Color.rgb(191, 160, 140)
    }

    private companion object {
        const val ENGINE = "fake-skin-retouch-1"
    }
}
