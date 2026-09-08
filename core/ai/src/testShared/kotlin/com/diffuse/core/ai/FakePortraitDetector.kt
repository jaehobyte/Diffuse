package com.diffuse.core.ai

import android.graphics.Bitmap

/**
 * work/decisions.md T79. ML Kit needs Play services, which no JVM test has, so the editor's side
 * of the gate is tested against this and the threshold is tested on its own (`portraitResultOf`).
 */
class FakePortraitDetector(
    var result: PortraitResult = PortraitResult.NotPortrait,
) : PortraitDetector {

    var detectCount: Int = 0
        private set

    var lastImageWidth: Int = 0
        private set

    override suspend fun detect(image: Bitmap): PortraitResult {
        detectCount++
        lastImageWidth = image.width
        return result
    }
}
