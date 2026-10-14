package com.diffuse.core.ai.topaz

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * specs/topaz_enhancement.md §2. The OpenAPI document's response schemas (Image API 1.2.0), only
 * the fields this app reads. Unknown fields are ignored; a missing required one fails decoding.
 */
@Serializable
internal data class AsyncResponseDto(
    @SerialName("process_id") val processId: String,
)

/** `StatusResponse`. `status` is one of Pending, Processing, Completed, Cancelled, Failed. */
@Serializable
internal data class StatusDto(
    val status: String,
    val progress: Int? = null,
    val credits: Int? = null,
)

/**
 * `DownloadResponse`. The quickstart's example reads `url`; the schema has [downloadUrl], which
 * is signed, expires within the hour and is never stored.
 */
@Serializable
internal data class DownloadDto(
    @SerialName("download_url") val downloadUrl: String,
)

/** `EstimationResponse`. */
@Serializable
internal data class EstimateDto(
    val credits: Int,
    val duration: Int = 0,
)

internal object TopazStatus {
    const val PENDING = "Pending"
    const val PROCESSING = "Processing"
    const val COMPLETED = "Completed"
    const val CANCELLED = "Cancelled"
    const val FAILED = "Failed"
}
