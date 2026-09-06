package com.diffuse.core.ai.gemini

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
internal data class Part(
    val inlineData: InlineData? = null,
    val text: String? = null,
    val functionCall: FunctionCall? = null,
)

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

// ---- function calling (specs/vibe_edit.md §4, §5) -------------------------

/** What the model chose to call. [args] is per-function, so it stays untyped until §5 reads it. */
@Serializable
internal data class FunctionCall(
    val name: String = "",
    val args: JsonObject = JsonObject(emptyMap()),
)

/** The OpenAPI subset Gemini accepts for a declaration's parameters. */
@Serializable
internal data class Schema(
    val type: String,
    val description: String? = null,
    @SerialName("enum") val enumValues: List<String>? = null,
    val properties: Map<String, Schema>? = null,
    val required: List<String>? = null,
)

@Serializable
internal data class FunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: Schema? = null,
)

@Serializable
internal data class Tool(val functionDeclarations: List<FunctionDeclaration>)

@Serializable
internal data class FunctionCallingConfig(val mode: String)

@Serializable
internal data class ToolConfig(val functionCallingConfig: FunctionCallingConfig)

/**
 * §5. Separate from [GenerateContentRequest] because a plan asks for a decision: it carries a
 * system instruction and a tool catalog, and no `generationConfig` at all.
 */
@Serializable
internal data class GeneratePlanRequest(
    val systemInstruction: Content,
    val contents: List<Content>,
    val tools: List<Tool>,
    val toolConfig: ToolConfig,
)
