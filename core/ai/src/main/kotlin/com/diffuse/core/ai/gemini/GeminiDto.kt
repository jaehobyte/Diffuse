package com.diffuse.core.ai.gemini

import kotlinx.serialization.Serializable

/**
 * specs/generative_erase.md §5. camelCase on both sides: the API accepts snake_case on input but
 * answers in camelCase, and one convention is cheaper than two.
 *
 * `Part` is one shape for both directions — a request part carries `inlineData` or `text`, and a
 * response part carries the same two. `explicitNulls = false` on the `Json` keeps the unused one
 * out of the body entirely.
 */
@Serializable
internal data class InlineData(val mimeType: String, val data: String)

@Serializable
internal data class Part(val inlineData: InlineData? = null, val text: String? = null)

@Serializable
internal data class Content(val role: String? = null, val parts: List<Part> = emptyList())

@Serializable
internal data class GenerationConfig(val responseModalities: List<String>)

@Serializable
internal data class GenerateContentRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig,
)

@Serializable
internal data class Candidate(val content: Content? = null, val finishReason: String? = null)

/** Set when the *prompt* was refused, as opposed to a candidate being cut short. */
@Serializable
internal data class PromptFeedback(val blockReason: String? = null)

@Serializable
internal data class GenerateContentResponse(
    val candidates: List<Candidate> = emptyList(),
    val promptFeedback: PromptFeedback? = null,
)

/** `{"error": {"code": …, "message": …, "status": …}}` (§6). */
@Serializable
internal data class GeminiError(
    val code: Int = 0,
    val message: String = "",
    val status: String = "",
)

@Serializable
internal data class GeminiErrorEnvelope(val error: GeminiError = GeminiError())
