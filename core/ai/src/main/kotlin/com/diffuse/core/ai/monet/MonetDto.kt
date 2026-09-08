package com.diffuse.core.ai.monet

import kotlinx.serialization.Serializable

/**
 * specs/auto_enhance.md §4. The OpenAI-compatible chat shape `monetgpt/llm` serves, and nothing
 * more of it than one image and one instruction need.
 */
@Serializable
internal data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float,
)

@Serializable
internal data class ChatMessage(
    val role: String,
    val content: List<ChatContent>,
)

@Serializable
internal data class ChatContent(
    val type: String,
    val text: String? = null,
    @Suppress("ConstructorParameterNaming")
    val image_url: ChatImageUrl? = null,
)

/** The `data:` URL form, because the photo never has a URL a server could fetch. */
@Serializable
internal data class ChatImageUrl(val url: String)

@Serializable
internal data class ChatResponse(val choices: List<ChatChoice> = emptyList())

@Serializable
internal data class ChatChoice(val message: ChatReply = ChatReply())

@Serializable
internal data class ChatReply(val content: String = "")
