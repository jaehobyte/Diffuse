package com.diffuse.core.ai.gemini

import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.HslBand
import com.diffuse.core.imaging.model.HslChannel
import com.diffuse.core.imaging.model.HslTarget

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
internal const val FN_ADJUST_COLOR_RANGE = "adjust_color_range"
internal const val FN_ERASE_SELECTION = "erase_selection"
internal const val FN_CUT_OUT_SELECTION = "cut_out_selection"

internal const val ARG_PHRASE = "phrase"
internal const val ARG_KIND = "kind"
internal const val ARG_VALUE = "value"
internal const val ARG_MASKED = "masked"
internal const val ARG_COLOR = "color"

/**
 * §4's ten `AdjustKind` names in lower snake case. Every one of them is a single word, so this is
 * a plain lowercase.
 *
 * The 24 혼합 kinds are **not** on this path: specs/adjust_hsl.md §8 gives them their own function,
 * because two ways of saying the same thing is how a planner learns to say it badly, and 34 values
 * on the argument the model already gets wrong most often is the opposite of what T52 fixed.
 */
internal val AdjustKind.wireName: String get() = name.lowercase()

/** The kinds `adjust` offers: everything that is not one band of specs/adjust_hsl.md's 혼합. */
internal val plannableKinds: List<AdjustKind> = AdjustKind.entries.filter { it.hsl == null }

internal fun adjustKindOf(wire: String): AdjustKind? =
    plannableKinds.firstOrNull { it.wireName == wire }

internal val HslBand.wireName: String get() = name.lowercase()

internal val HslChannel.wireName: String get() = name.lowercase()

internal fun hslBandOf(wire: String): HslBand? =
    HslBand.entries.firstOrNull { it.wireName == wire }

internal fun hslKindOf(band: HslBand, channel: HslChannel): AdjustKind =
    AdjustKind.entries.first { it.hsl == HslTarget(band, channel) }

/**
 * specs/vibe_edit.md §4, with the three rules T52 added after the first device run:
 *
 * - the phrase is **English**, because SAM 3's text endpoint is English concept segmentation and
 *   a Korean noun returns nothing at all — which surfaced as "찾지 못했어요" on a photo that
 *   plainly contained the thing;
 * - every call goes in one turn. The model was stopping after `select_region` about half the
 *   time, leaving the user with a selection where they had asked for a removal;
 * - after a removal, a whole-photo adjustment must say `masked=false`, or it lands inside the
 *   hole that was just filled and changes nothing anybody can see.
 *
 * The examples are here for the same reason: the rules alone did not hold on the device.
 */
internal const val PLAN_SYSTEM_INSTRUCTION =
    "You are the planner for an Android photo editor. You are given a photo and the user's " +
        "request, which is usually Korean. Call the editing functions that fulfil the request, " +
        "in the order they must run. Rules:\n" +
        "- Emit every call the request needs, in this one turn. Never stop after selecting: if " +
        "the user asked for something to be removed or changed, selecting it is only the first " +
        "half of your answer.\n" +
        "- Use the fewest steps that achieve the request.\n" +
        "- To change only part of the photo, call select_region first and then call adjust with " +
        "masked=true. A selection stays active until the next select_region call.\n" +
        "- select_region takes a short English noun phrase naming the thing, never a sentence " +
        "and never a verb. It must always be English, whatever language the request is in: the " +
        "segmentation model understands English concepts only, so translate the user's word.\n" +
        "- To change how one colour looks - \"the reds are too strong\", \"make the sky bluer\" " +
        "- call adjust_color_range. It needs no selection: select_region names a thing in the " +
        "photo, never a colour.\n" +
        "- After erase_selection or cut_out_selection, an adjustment meant for the whole photo " +
        "must pass masked=false, because the selection now names a region that is gone.\n" +
        "- Values are relative strengths, not absolute settings: a slight change is 0.2, a clear " +
        "change is 0.4, a strong change is 0.7. Use the ends of the range only when the user " +
        "asked for an extreme.\n" +
        "- If the request cannot be met with these functions, call nothing.\n" +
        "Examples:\n" +
        "- \"버스를 지워줘\" -> select_region(phrase=\"bus\"), erase_selection()\n" +
        "- \"나무를 좀 더 푸르게 해줘\" -> select_region(phrase=\"tree\"), " +
        "adjust(kind=\"saturation\", value=0.4, masked=true)\n" +
        "- \"버스 지우고 사진 예쁘게 만들어줘\" -> select_region(phrase=\"bus\"), " +
        "erase_selection(), adjust(kind=\"contrast\", value=0.2, masked=false), " +
        "adjust(kind=\"saturation\", value=0.2, masked=false)\n" +
        "- \"배경 지워줘\" -> select_region(phrase=\"the main subject\"), cut_out_selection()\n" +
        "- \"하늘을 더 파랗게 해줘\" -> adjust_color_range(color=\"blue\", saturation=0.4)"

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
                    description = "A short English noun phrase naming the thing to select, such " +
                        "as \"tree\", \"bus\" or \"the sky\". Always English, even when the " +
                        "request is in another language. Never a sentence and never a verb.",
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
                    enumValues = plannableKinds.map { it.wireName },
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
        name = FN_ADJUST_COLOR_RANGE,
        description = "Change how one colour range of the photo looks, without selecting " +
            "anything. Use this when the request is about a colour rather than about a thing in " +
            "the photo.",
        parameters = Schema(
            type = TYPE_OBJECT,
            properties = mapOf(
                ARG_COLOR to Schema(
                    type = TYPE_STRING,
                    description = "Which colour range to change.",
                    enumValues = HslBand.entries.map { it.wireName },
                ),
                HslChannel.Hue.wireName to Schema(
                    type = TYPE_NUMBER,
                    description = "Shift this range towards a neighbouring colour, -1 to 1. " +
                        "Omit it to leave the hue alone.",
                ),
                HslChannel.Saturation.wireName to Schema(
                    type = TYPE_NUMBER,
                    description = "How vivid this colour range is, -1 to 1. Omit it to leave " +
                        "the saturation alone.",
                ),
                HslChannel.Luminance.wireName to Schema(
                    type = TYPE_NUMBER,
                    description = "How bright this colour range is, -1 to 1. Omit it to leave " +
                        "the brightness alone.",
                ),
                ARG_MASKED to Schema(
                    type = TYPE_BOOLEAN,
                    description = "True to change only the current selection. Defaults to " +
                        "false, because naming a colour is already a way of choosing what to " +
                        "change.",
                ),
            ),
            required = listOf(ARG_COLOR),
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
