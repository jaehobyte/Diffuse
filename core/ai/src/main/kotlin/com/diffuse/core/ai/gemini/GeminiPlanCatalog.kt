package com.diffuse.core.ai.gemini

import com.diffuse.core.imaging.model.AdjustKind

/**
 * specs/vibe_edit.md §4. Everything the editor can do that a sentence can plausibly ask for,
 * declared for the model.
 *
 * Names, descriptions and enum values are **English code constants**: they are wire payload sent
 * to a model, not strings a person reads, so DESIGN.md §9's "Korean, in strings.xml" does not
 * apply — the same rule generative_erase.md §5 applies to its instruction.
 *
 * 자르기 is deliberately absent: a crop is four normalised numbers a model would have to invent,
 * and a wrong crop throws away framing the user chose (§4).
 */
internal const val FN_SELECT_REGION = "select_region"
internal const val FN_ADJUST = "adjust"
internal const val FN_ERASE_SELECTION = "erase_selection"
internal const val FN_CUT_OUT_SELECTION = "cut_out_selection"

internal const val ARG_PHRASE = "phrase"
internal const val ARG_KIND = "kind"
internal const val ARG_VALUE = "value"
internal const val ARG_MASKED = "masked"

/** §4's ten `AdjustKind` names in lower snake case. Every kind is one word, so this is lowercase. */
internal val AdjustKind.wireName: String get() = name.lowercase()

internal fun adjustKindOf(wire: String): AdjustKind? =
    AdjustKind.entries.firstOrNull { it.wireName == wire }

/** §4, verbatim. */
internal const val PLAN_SYSTEM_INSTRUCTION =
    "You are the planner for an Android photo editor. You are given a photo and the user's " +
        "request, which is usually Korean. Call the editing functions that fulfil the request, " +
        "in the order they must run. Rules:\n" +
        "- Use the fewest steps that achieve the request.\n" +
        "- To change only part of the photo, call select_region first and then call adjust with " +
        "masked=true. A selection stays active until the next select_region call.\n" +
        "- select_region takes a short noun phrase naming the thing, never a sentence and never " +
        "a verb.\n" +
        "- Values are relative strengths, not absolute settings: a slight change is 0.2, a clear " +
        "change is 0.4, a strong change is 0.7. Use the ends of the range only when the user " +
        "asked for an extreme.\n" +
        "- If the request cannot be met with these functions, call nothing."

internal val PLAN_FUNCTIONS: List<FunctionDeclaration> = listOf(
    FunctionDeclaration(
        name = FN_SELECT_REGION,
        description = "Select the part of the photo that shows a named thing. Later steps that " +
            "say masked, erase or cut out apply to this selection. Replaces any previous " +
            "selection.",
        parameters = Schema(
            type = TYPE_OBJECT,
            properties = mapOf(
                ARG_PHRASE to Schema(
                    type = TYPE_STRING,
                    description = "A short noun phrase naming the thing to select, such as " +
                        "\"tree\" or \"the sky\". Never a sentence and never a verb.",
                ),
            ),
            required = listOf(ARG_PHRASE),
        ),
    ),
    FunctionDeclaration(
        name = FN_ADJUST,
        description = "Change one image adjustment by a relative amount.",
        parameters = Schema(
            type = TYPE_OBJECT,
            properties = mapOf(
                ARG_KIND to Schema(
                    type = TYPE_STRING,
                    description = "Which adjustment to change.",
                    enumValues = AdjustKind.entries.map { it.wireName },
                ),
                ARG_VALUE to Schema(
                    type = TYPE_NUMBER,
                    description = "Relative strength, -1 to 1, where a positive value increases " +
                        "and a negative value decreases. sharpen and vignette accept 0 to 1 only.",
                ),
                ARG_MASKED to Schema(
                    type = TYPE_BOOLEAN,
                    description = "True to change only the current selection, false to change " +
                        "the whole photo. Defaults to true.",
                ),
            ),
            required = listOf(ARG_KIND, ARG_VALUE),
        ),
    ),
    FunctionDeclaration(
        name = FN_ERASE_SELECTION,
        description = "Remove whatever the current selection covers and fill the hole with " +
            "content that continues the surrounding scene.",
    ),
    FunctionDeclaration(
        name = FN_CUT_OUT_SELECTION,
        description = "Delete everything outside the current selection, leaving it on a " +
            "transparent background.",
    ),
)

private const val TYPE_OBJECT = "OBJECT"
private const val TYPE_STRING = "STRING"
private const val TYPE_NUMBER = "NUMBER"
private const val TYPE_BOOLEAN = "BOOLEAN"
