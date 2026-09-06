package com.diffuse.core.ai.sam3

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire shapes from ~/sam3-server/specs/api.md. Unknown keys are ignored, so `bbox` and `rle` are absent here. */
@Serializable
internal data class UploadDto(
    @SerialName("image_id") val imageId: String,
    val width: Int,
    val height: Int,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
internal data class MaskDto(
    val score: Float,
    /** Base64 8-bit grayscale PNG. Present because every request sends `format = "png"`. */
    val png: String? = null,
)

@Serializable
internal data class MasksDto(val masks: List<MaskDto> = emptyList())

@Serializable
internal data class ErrorDto(val error: String = "", val detail: String = "")

@Serializable
internal data class PointsRequestDto(
    val points: List<List<Float>>,
    val labels: List<Int>,
    val multimask: Boolean,
    val format: String,
)

@Serializable
internal data class TextRequestDto(
    val prompt: String,
    val threshold: Float,
    @SerialName("max_instances") val maxInstances: Int,
    val format: String,
)
