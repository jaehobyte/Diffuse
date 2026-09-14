package com.diffuse.core.ai

import android.graphics.Bitmap

/**
 * work/decisions.md T79. What the editor asks about a photograph before it decides which tools to
 * put under the user's thumb. Three answers rather than a `Boolean`, because "we could not look"
 * is a real state — the on-device model may still be downloading — and it is not the same claim as
 * "we looked and there is no face".
 *
 * [Unknown] and [NotPortrait] are treated alike by the menu, deliberately: a hint that fails is a
 * hint that stays quiet, not an error the user has to dismiss.
 */
enum class PortraitResult { Portrait, NotPortrait, Unknown }

/**
 * work/decisions.md T79. A menu personalization signal, not an image-editing primitive: it reads
 * pixels and answers a question, and nothing it returns reaches the document.
 *
 * Deliberately not an [Availability]-carrying provider like the rest of `core:ai`. The others gate
 * a tool the user tapped, so they owe an explanation; this one is asked without being asked for,
 * so its only failure mode is [PortraitResult.Unknown].
 */
interface PortraitDetector {

    /**
     * [image] is whatever frame the caller already has — the preview, not a re-decode of the
     * original. Cancellable: the caller's coroutine context is what stops the work.
     */
    suspend fun detect(image: Bitmap): PortraitResult
}

/**
 * T79's threshold, as a pure function so it is a test rather than a device.
 *
 * A face justifies portrait-retouch UI when its box is at least [MIN_FACE_WIDTH_RATIO] of the
 * image width **or** at least [MIN_FACE_WIDTH_PX] wide. Either bar, not both: the ratio is what
 * survives preview scaling, and the pixel floor is what keeps the ratio honest once the preview
 * itself is around a thousand pixels wide — which is what `PREVIEW_LONG_EDGE_PX` makes it.
 *
 * A face in the crowd behind the subject clears neither, which is the point: blemish-first UI for
 * a landscape with three tourists in it would be worse than no personalization at all.
 */
internal fun portraitResultOf(faceWidthsPx: List<Int>, imageWidthPx: Int): PortraitResult =
    if (faceWidthsPx.any { it >= MIN_FACE_WIDTH_PX || it >= imageWidthPx * MIN_FACE_WIDTH_RATIO }) {
        PortraitResult.Portrait
    } else {
        PortraitResult.NotPortrait
    }

/** 10% of the image width. Scale-invariant, so the preview's size does not change the answer. */
internal const val MIN_FACE_WIDTH_RATIO = 0.10f

/** The absolute bar on the detector's input, per T79. */
internal const val MIN_FACE_WIDTH_PX = 100
