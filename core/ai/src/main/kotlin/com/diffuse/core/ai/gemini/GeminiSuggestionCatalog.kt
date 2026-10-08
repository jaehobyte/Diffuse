package com.diffuse.core.ai.gemini

import com.diffuse.core.ai.PromptSuggestionId

/**
 * specs/vibe_edit.md §14. English wire payload for the read-only suggestion call, for the reason
 * `GeminiPlanCatalog` gives: it is sent to a model, never shown to a person.
 */
internal const val FN_SUGGEST_DIRECTIONS = "suggest_directions"
internal const val ARG_IDS = "ids"

internal val SUGGEST_FUNCTION = FunctionDeclaration(
    name = FN_SUGGEST_DIRECTIONS,
    description = "Report which catalog directions suit this photo, best first. An empty list " +
        "means none of them suits it.",
    parameters = Schema(
        type = "OBJECT",
        properties = mapOf(
            ARG_IDS to Schema(
                type = "ARRAY",
                description = "At most three catalog ids, best first, each from a different group.",
                items = Schema(
                    type = "STRING",
                    enumValues = PromptSuggestionId.entries.map { it.wire },
                ),
            ),
        ),
        required = listOf(ARG_IDS),
    ),
)

/** The catalog as the model sees it: id, group and what the sentence would do. */
internal val SUGGESTION_CATALOG: String =
    "Catalog (id - group - effect):\n" +
        PromptSuggestionId.entries.joinToString("\n") {
            "- ${it.wire} - ${it.group.name.lowercase()} - ${it.analysis}"
        }

/**
 * §14's rules: whole-photo tone and colour only, no opposites or near-duplicates, and a dark or
 * moody photo is not a failed one.
 */
internal const val SUGGESTION_SYSTEM_INSTRUCTION =
    "You help a user of an Android photo editor who finds it hard to say what they want. Look at " +
        "the photo as it is now and choose which directions from the catalog would plausibly " +
        "improve it or suit its mood. Rules:\n" +
        "- Answer only by calling suggest_directions with catalog ids, best first. Never invent " +
        "an id, a number or an editing command.\n" +
        "- Choose at most three, and at most one id from each group: never two opposites such as " +
        "warm and cool, and never two near-duplicates such as brighten and lift_shadows.\n" +
        "- A dark, moody or night photo may be dark on purpose. Do not treat it as a mistake; " +
        "prefer directions that keep its mood, and do not suggest brightening it unless " +
        "detail is clearly lost.\n" +
        "- If only one or two directions fit, return only those. If none fits, return an empty " +
        "list.\n"
