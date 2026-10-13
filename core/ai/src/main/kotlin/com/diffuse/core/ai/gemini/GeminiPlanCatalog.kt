package com.diffuse.core.ai.gemini

import com.diffuse.core.ai.CropRatio
import com.diffuse.core.ai.StyleId
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
 * 자르기 is here as `crop_ratio` (§4.1) and only as a ratio. The objection that kept it out until
 * T58 stands unchanged — a model asked for a rectangle has to invent four numbers it cannot check
 * against what the user meant — which is exactly why `ratio` is a closed enum: the model answers
 * "which aspect does 인스타 포스팅 mean", a language question, and the rect is computed from the
 * preset the chips already use.
 */
internal const val FN_SELECT_REGION = "select_region"
internal const val FN_ADJUST = "adjust"
internal const val FN_ADJUST_COLOR_RANGE = "adjust_color_range"
internal const val FN_ERASE_SELECTION = "erase_selection"
internal const val FN_CUT_OUT_SELECTION = "cut_out_selection"
internal const val FN_FILL_SELECTION = "fill_selection"
internal const val FN_CROP_RATIO = "crop_ratio"
internal const val FN_APPLY_STYLE = "apply_style"

internal const val ARG_PHRASE = "phrase"
internal const val ARG_KIND = "kind"
internal const val ARG_VALUE = "value"
internal const val ARG_MASKED = "masked"
internal const val ARG_COLOR = "color"
internal const val ARG_RATIO = "ratio"
internal const val ARG_PROMPT = "prompt"
internal const val ARG_STYLE = "style"
internal const val ARG_INTENSITY = "intensity"

/**
 * §4's ten `AdjustKind` names in lower snake case. Every one of them is a single word, so this is
 * a plain lowercase.
 *
 * The 24 혼합 kinds are **not** on this path: specs/adjust_hsl.md §8 gives them their own function,
 * because two ways of saying the same thing is how a planner learns to say it badly, and 34 values
 * on the argument the model already gets wrong most often is the opposite of what T52 fixed.
 */
internal val AdjustKind.wireName: String get() = name.lowercase()

private val NOT_PLANNABLE = setOf(
    AdjustKind.Blacks,
    AdjustKind.Whites,
    AdjustKind.Fade,
    AdjustKind.SCurve,
    AdjustKind.Clarity,
)

/**
 * The kinds `adjust` offers: everything that is not one band of specs/adjust_hsl.md's 혼합, and
 * not one of T71's five.
 *
 * T71 added `Blacks`, `Whites`, `Fade`, `SCurve` and `Clarity` for specs/style_match.md and
 * specs/auto_enhance.md, which reach them through a preset and a server rather than through this
 * function. Widening `adjust`'s enum is a change to what the planner is asked for, so it belongs
 * to a task about the planner and to a device run that can say whether the model uses them well —
 * the same care T56 took when it filtered the 24 HSL kinds back out of this list.
 */
internal val plannableKinds: List<AdjustKind> =
    AdjustKind.entries.filter { it.hsl == null && it !in NOT_PLANNABLE }

internal fun adjustKindOf(wire: String): AdjustKind? =
    plannableKinds.firstOrNull { it.wireName == wire }

/**
 * §4.1's wire names. Spelled out rather than derived from the enum: "portrait_4_5" is not a
 * lowercasing of `Portrait4x5`, and a name the model sees should read like a ratio.
 */
internal val CropRatio.wireName: String
    get() = when (this) {
        CropRatio.Square -> "square"
        CropRatio.Portrait3x4 -> "portrait_3_4"
        CropRatio.Portrait4x5 -> "portrait_4_5"
        CropRatio.Story9x16 -> "story_9_16"
        CropRatio.Landscape4x3 -> "landscape_4_3"
        CropRatio.Landscape16x9 -> "landscape_16_9"
    }

internal fun cropRatioOf(wire: String): CropRatio? =
    CropRatio.entries.firstOrNull { it.wireName == wire }

/**
 * specs/style_match.md §6. The catalog's own ids are the wire values — not a lowercasing of the
 * enum — because the id is what joins a plan step to a preset at the feature boundary.
 */
internal fun styleIdOf(wire: String): StyleId? = StyleId.entries.firstOrNull { it.id == wire }

/** §6: 강도 runs 0…100, and a request naming a look means all of it unless it says otherwise. */
internal const val FULL_STYLE_INTENSITY = 100

internal val HslBand.wireName: String get() = name.lowercase()

internal val HslChannel.wireName: String get() = name.lowercase()

internal fun hslBandOf(wire: String): HslBand? =
    HslBand.entries.firstOrNull { it.wireName == wire }

internal fun hslKindOf(band: HslBand, channel: HslChannel): AdjustKind =
    AdjustKind.entries.first { it.hsl == HslTarget(band, channel) }

/**
 * specs/vibe_edit.md §14. The relative weight a plan gives each kind of reason, shared by
 * 보정 추천 and a sent 지시 because both are one call under one instruction. They are **ranks**,
 * not probabilities and not multipliers on any value, and no sum of them outweighs a hard
 * constraint — the instruction says so, and `ProposalCheck` enforces what of it is mechanical.
 */
internal object PlanPriority {
    const val EXPLICIT_GOAL = 5
    const val EXPLICIT_OBJECT = 3
    const val GENERAL_IMPROVEMENT = 1
}

/** §14's caps on what the model may write; a response over any of them is refused whole. */
internal object ProposalLimits {
    const val TEXT_CHARS = 240
    const val OBSERVATIONS = 8
    const val GOALS = 8
    const val WORKFLOW = 12
    const val LIMITATIONS = 6
    const val COLORS = 8
    const val INTENT_ITEMS = 8
}

/** The one function the planner is forced to call: the whole assessment in one envelope. */
internal const val FN_PROPOSE_EDIT = "propose_edit"

internal const val ARG_OUTCOME = "outcome"
internal const val ARG_REASON = "reason"
internal const val ARG_OBSERVATIONS = "observations"
internal const val ARG_INTENT = "intent"
internal const val ARG_GOALS = "goals"
internal const val ARG_COLOR_ROLES = "color_roles"
internal const val ARG_WORKFLOW = "workflow"
internal const val ARG_LIMITATIONS = "limitations"
internal const val ARG_ID = "id"
internal const val ARG_SUBJECT = "subject"
internal const val ARG_NOTE = "note"
internal const val ARG_CONFIDENCE = "confidence"
internal const val ARG_UNKNOWN = "unknown"
internal const val ARG_TARGETS = "targets"
internal const val ARG_WANTS = "wants"
internal const val ARG_CONSTRAINTS = "constraints"
internal const val ARG_TEXT = "text"
internal const val ARG_EXPLICIT = "explicit"
internal const val ARG_SOURCE = "source"
internal const val ARG_SCOPE = "scope"
internal const val ARG_TARGET = "target"
internal const val ARG_OBSERVATION_IDS = "observation_ids"
internal const val ARG_ROLE = "role"
internal const val ARG_TOOL = "tool"
internal const val ARG_GOAL_ID = "goal_id"
internal const val ARG_ARGS = "args"
internal const val ARG_EFFECT = "effect"
internal const val ARG_CAUTION = "caution"

/** The eight executable tools a workflow step may name — the planner's whole vocabulary. */
internal val PLAN_TOOLS: List<String> = listOf(
    FN_SELECT_REGION,
    FN_ADJUST,
    FN_ADJUST_COLOR_RANGE,
    FN_ERASE_SELECTION,
    FN_CUT_OUT_SELECTION,
    FN_FILL_SELECTION,
    FN_CROP_RATIO,
    FN_APPLY_STYLE,
)

internal const val OUTCOME_READY = "ready"
internal const val OUTCOME_NO_CHANGE = "no_change"
internal const val OUTCOME_NEEDS_CLARIFICATION = "needs_clarification"
internal const val OUTCOME_UNSUPPORTED = "unsupported"

internal val OUTCOMES = listOf(OUTCOME_READY, OUTCOME_NO_CHANGE, OUTCOME_NEEDS_CLARIFICATION, OUTCOME_UNSUPPORTED)
internal val CONFIDENCES = listOf("high", "medium", "low")
internal val GOAL_SOURCES = listOf("user", "default")
internal val GOAL_SCOPES = listOf("global", "subject", "color_range")
internal val COLOR_ROLES = listOf("preserve", "emphasize", "background")
internal val CONSTRAINT_KINDS = listOf("subject_only", "keep_background", "keep_colors", "other")

/**
 * specs/vibe_edit.md §14: the tools as the model reads them. Each line keeps the rule T52, T58
 * and T62 learned on the device for that tool.
 */
private val TOOL_CATALOG: String =
    "Tools a workflow step may name (tool - args - what it does):\n" +
        "- $FN_SELECT_REGION - phrase - selects the thing a short ENGLISH noun phrase names " +
        "(\"face\", \"green bus\", \"flowers\"); never a sentence, a verb or a colour. The " +
        "segmentation model only understands English, so translate. A selection replaces the " +
        "previous one and stays active until the next $FN_SELECT_REGION.\n" +
        "- $FN_ADJUST - kind, value, masked - sets one adjustment. kind is one of " +
        "${plannableKinds.joinToString(", ") { it.wireName }}. value is the FINAL setting, -1 to 1 " +
        "(sharpen and vignette 0 to 1); masked=true acts only inside the current selection.\n" +
        "- $FN_ADJUST_COLOR_RANGE - color, hue, saturation, luminance, masked - sets how one colour " +
        "range looks; color is one of ${HslBand.entries.joinToString(", ") { it.wireName }}; give " +
        "at least one of hue/saturation/luminance as a final setting, -1 to 1. It needs no " +
        "selection, and it changes that colour everywhere it appears.\n" +
        "- $FN_ERASE_SELECTION - none - removes the selected thing and fills the hole with the " +
        "surrounding scene.\n" +
        "- $FN_CUT_OUT_SELECTION - none - deletes everything outside the selection.\n" +
        "- $FN_FILL_SELECTION - prompt - replaces the selected thing with what a short English " +
        "prompt names (\"a red umbrella\").\n" +
        "- $FN_CROP_RATIO - ratio - crops centred to one of " +
        "${CropRatio.entries.joinToString(", ") { it.wireName }}; the crop tool opens afterwards so " +
        "the user moves the frame. portrait_3_4 is an ordinary feed post (\"인스타\" with no shape), " +
        "landscape_4_3 the wide one, story_9_16 a story or reel, square only when the request says " +
        "square or 1:1. It is never a free rectangle.\n" +
        "- $FN_APPLY_STYLE - style, intensity - applies a catalogued look to the whole photo and " +
        "REPLACES the whole-photo values it carries; style is one of " +
        "${StyleId.entries.joinToString(", ") { it.id }}; intensity 0 to 100, 100 unless the " +
        "request asks for a hint of it.\n"

/**
 * specs/vibe_edit.md §14: the evaluation rules both modes run under. Observation is kept apart
 * from taste, a hard constraint outranks any weight, and every value is a final setting — the
 * old "relative strength" wording made "더 밝게" darken a photo that was already brightened,
 * because `withAdjust` replaces.
 */
internal val PLAN_SYSTEM_INSTRUCTION: String =
    "You evaluate a photo for an Android photo editor and propose one coherent editing workflow " +
        "made of the editor's own tools. You are given the downscaled photo as it is now, its " +
        "current settings, a mode and, usually, the user's request in Korean. Answer only by " +
        "calling $FN_PROPOSE_EDIT once.\n" +
        "Mode recommend: the user asked what to do; the request may be empty. Mode direct: the " +
        "user sent an instruction; plan it.\n" +
        "\n" +
        "Keep these apart:\n" +
        "- observations: only what is visible in this small preview, each with your confidence " +
        "(high/medium/low is your estimate, not a measurement) and what the preview cannot " +
        "settle. Never state RAW recoverability, exact exposure or noise numbers, or a " +
        "histogram you were not given.\n" +
        "- intent: what the request names - targets, wants and constraints - marking explicit " +
        "ones apart from ones you inferred.\n" +
        "- goals: what to change, each with source user (the request asks for it) or default " +
        "(you propose it unasked), the observations it rests on, and its scope: global, subject " +
        "(one selected thing) or color_range.\n" +
        "\"The face is darker than its surroundings\" is an observation; \"brighten the face\" is a " +
        "goal. An observation alone never decides taste.\n" +
        "\n" +
        "Evaluation:\n" +
        "- Darkness, empty space and low saturation are not defects by themselves. Silhouettes, " +
        "night scenes, high-key and low-key, and minimal compositions are allowed. Do not decide " +
        "from an observation that brighter, cropped or sharper is right. If nothing needs " +
        "improving, the outcome is no_change.\n" +
        "- Brightness: judge whether what matters - a face, a product, a flower - keeps readable " +
        "shape and texture, not the average. A whole-photo problem is a global adjust; a problem " +
        "only on a face is select_region on the face and a masked adjust. Keep the natural " +
        "lightness difference between a white dress or bright clouds and a black suit; do not " +
        "even them out. Lifting shadows can raise noise and flatten depth; say so as a caution. " +
        "Never aim for a centred histogram.\n" +
        "- Colour roles: tell colours to preserve, to emphasize and background colours apart. To " +
        "bring out pale balloon colours, a global saturation lift that mostly strengthens the sky " +
        "is wrong. A green bus already separated from its background may need no more " +
        "saturation. Keep skin tones, product colours, subtle petal differences and the " +
        "lightness relations between colours.\n" +
        "- White balance: propose temperature or tint only for a clear colour cast or a request. " +
        "Do not neutralize a sunset, stage light or warm interior by default, and do not claim a " +
        "global temperature change fixes a local mixed-light problem.\n" +
        "- Texture and sharpness: keep real detail in what matters without halos, rough skin or " +
        "amplified noise. Never promise that sharpening recovers blur or missed focus, and do not " +
        "invent skin flaws you cannot see in a small JPEG.\n" +
        "- Composition: you may observe cut-off subjects, distracting background and the role of " +
        "empty space, but never enforce thirds, level horizons or less space. Reframe only when " +
        "the request asks, and only with $FN_CROP_RATIO. Never propose erase or replace unasked.\n" +
        "- Minimal change: the fewest steps and conservative values that serve the goal. Do not " +
        "exaggerate separation, colour or texture that is already enough, and never add an " +
        "adjust that repeats what a style already does. Effects and cautions are predictions, " +
        "not guarantees.\n" +
        "- One goal set and one workflow. When taste is unclear, propose keeping the original " +
        "mood as a default goal; never mix several styles in one plan.\n" +
        "\n" +
        "Priorities: an explicit goal in the request weighs ${PlanPriority.EXPLICIT_GOAL}, an " +
        "object the request names weighs ${PlanPriority.EXPLICIT_OBJECT}, an unrequested general " +
        "improvement weighs ${PlanPriority.GENERAL_IMPROVEMENT}. These are ranks, not " +
        "probabilities or multipliers of any value, and no sum of them overrides a hard " +
        "constraint.\n" +
        "Hard constraints come first: \"얼굴만\" (subject_only), \"배경은 그대로\" (keep_background), " +
        "\"색은 바꾸지 마\" (keep_colors), \"실루엣 유지\" (other). Respect them and the tools' limits " +
        "first, then choose goals inside them. A subject_only or keep_background constraint " +
        "allows no global or color_range step; keep_colors allows no temperature, tint, " +
        "saturation, vibrance, colour range or style step.\n" +
        "- Naming an object is not a goal: \"초록 버스\" alone makes the bus important but says " +
        "nothing about what to change; propose something conservative or ask for more.\n" +
        "- \"얼굴만 밝게\" is select_region on the face then a masked adjust; never reuse another " +
        "existing selection. \"꽃은 화사하게, 배경은 그대로\" stays inside the flowers. When several " +
        "objects share a colour, never use $FN_ADJUST_COLOR_RANGE in place of selecting the object.\n" +
        "- \"봄처럼 화사하게\" is a user style goal; do not state colours or brightness as observed " +
        "facts, and keep skin, highlights and pastel differences.\n" +
        "- A contradictory request or a target you cannot identify (\"그 사람만\" among several " +
        "people) is needs_clarification with the reason; never drop a condition silently or pick " +
        "someone arbitrarily. An explicit request overrides the keep-the-mood default.\n" +
        "- A request the tools cannot meet - a free rectangle crop such as \"위 여백만 줄여줘\", " +
        "skin retouching, enlarging - is unsupported, or a limitation that says manual 자르기 is " +
        "needed; never invent coordinates or tools.\n" +
        "\n" +
        "Workflow rules:\n" +
        "- Every step names a goal. A subject goal starts with its own $FN_SELECT_REGION and its " +
        "changes are masked; a selection with nothing after it is not an edit. A global goal " +
        "uses unmasked adjust, $FN_APPLY_STYLE or $FN_CROP_RATIO; a color_range goal uses " +
        "$FN_ADJUST_COLOR_RANGE without a mask.\n" +
        "- A default goal may use only $FN_SELECT_REGION, $FN_ADJUST and $FN_ADJUST_COLOR_RANGE. " +
        "$FN_APPLY_STYLE needs a requested look (\"필름 느낌\"), $FN_CROP_RATIO a requested shape or " +
        "platform, and erase, cut out and fill a request for exactly that.\n" +
        "- $FN_APPLY_STYLE is whole-photo, so never use it under a subject_only or " +
        "keep_background constraint.\n" +
        "- $FN_CROP_RATIO at most once, as the last step.\n" +
        "- Values are FINAL settings that replace the current ones, which are listed with the " +
        "request. To brighten a photo whose exposure is already 0.40, set exposure above 0.40 - " +
        "never a smaller number. A masked adjust on a new selection starts from 0 and acts on top " +
        "of the whole-photo settings. A style replaces the whole-photo values it carries. A " +
        "slight change is about 0.15 from the current value, a clear one about 0.3.\n" +
        "- outcome ready needs a non-empty workflow; no_change, needs_clarification and " +
        "unsupported have an empty workflow and a reason.\n" +
        "- Text fields are short plain Korean (at most ${ProposalLimits.TEXT_CHARS} characters, no " +
        "markup, links or reasoning transcripts): at most ${ProposalLimits.OBSERVATIONS} " +
        "observations, ${ProposalLimits.GOALS} goals, ${ProposalLimits.WORKFLOW} workflow steps " +
        "and ${ProposalLimits.LIMITATIONS} limitations. phrase and prompt stay English.\n" +
        "\n" +
        TOOL_CATALOG +
        "\n" +
        "Examples (abridged):\n" +
        "- recommend, no request, backlit portrait with a dark face: observation \"얼굴이 배경보다 " +
        "어둡다\" (medium) with unknown \"의도한 실루엣인지\"; default goal \"원래 분위기를 살리며 얼굴 " +
        "형태만 조금 보이게\" scope subject; workflow select_region(phrase=\"face\"), " +
        "adjust(kind=\"exposure\", value=0.2, masked=true).\n" +
        "- \"실루엣은 유지해줘\" on a silhouette: constraint other \"실루엣 유지\"; outcome no_change, " +
        "reason \"요청한 실루엣이 이미 잘 살아 있어요\".\n" +
        "- \"버스만 강조해줘\", green bus beside green plants: constraint subject_only; user goal " +
        "scope subject target \"버스\"; select_region(phrase=\"green bus\"), adjust(kind=\"contrast\", " +
        "value=0.15, masked=true). Never adjust_color_range(color=\"green\").\n" +
        "- \"풍선의 연한 색을 살려줘\": colour roles preserve the balloon pastels, background the blue " +
        "sky; select_region(phrase=\"balloons\"), adjust(kind=\"vibrance\", value=0.2, masked=true). " +
        "No global saturation.\n" +
        "- \"꽃은 봄처럼 화사하게, 배경은 그대로\": constraint keep_background; select_region(phrase=" +
        "\"flowers\"), adjust(kind=\"exposure\", value=0.15, masked=true), adjust(kind=\"vibrance\", " +
        "value=0.2, masked=true). Never apply_style.\n" +
        "- \"더 밝게\" with exposure already 0.40: adjust(kind=\"exposure\", value=0.55, masked=false).\n" +
        "- \"위 여백 줄여줘\": unsupported or a limitation - only fixed ratios exist, the user adjusts " +
        "the frame in 자르기.\n" +
        "- \"인스타 스토리에 올릴거야\": crop_ratio(ratio=\"story_9_16\").\n" +
        "- \"필름 느낌으로 바꿔줘\": apply_style(style=\"film-warm\", intensity=100), and nothing that " +
        "repeats it.\n" +
        "- recommend, no request, a well-balanced photo: no_change with the reason.\n"

private fun text(description: String) = Schema(type = TYPE_STRING, description = description)

private fun choice(description: String, values: List<String>) =
    Schema(type = TYPE_STRING, description = description, enumValues = values)

private fun list(description: String, item: Schema) =
    Schema(type = TYPE_ARRAY, description = description, items = item)

private fun obj(properties: Map<String, Schema>, required: List<String>) =
    Schema(type = TYPE_OBJECT, properties = properties, required = required)

private val INTENT_ITEM = obj(
    mapOf(ARG_TEXT to text("Short Korean."), ARG_EXPLICIT to Schema(type = TYPE_BOOLEAN)),
    listOf(ARG_TEXT, ARG_EXPLICIT),
)

/**
 * Every argument any tool takes, all optional at the schema level: which ones a tool needs is
 * checked per tool by the client, with the rules the eight separate declarations used to carry.
 */
private val STEP_ARGS = obj(
    mapOf(
        ARG_PHRASE to text("select_region: short English noun phrase."),
        ARG_KIND to choice("adjust: which adjustment.", plannableKinds.map { it.wireName }),
        ARG_VALUE to Schema(type = TYPE_NUMBER, description = "adjust: the final setting."),
        ARG_MASKED to Schema(type = TYPE_BOOLEAN, description = "adjust, adjust_color_range."),
        ARG_COLOR to choice("adjust_color_range: which colour range.", HslBand.entries.map { it.wireName }),
        HslChannel.Hue.wireName to Schema(type = TYPE_NUMBER),
        HslChannel.Saturation.wireName to Schema(type = TYPE_NUMBER),
        HslChannel.Luminance.wireName to Schema(type = TYPE_NUMBER),
        ARG_RATIO to choice("crop_ratio.", CropRatio.entries.map { it.wireName }),
        ARG_PROMPT to text("fill_selection: short English description."),
        ARG_STYLE to choice("apply_style.", StyleId.entries.map { it.id }),
        ARG_INTENSITY to Schema(type = TYPE_NUMBER, description = "apply_style: 0 to 100."),
    ),
    emptyList(),
)

internal val PROPOSE_FUNCTION = FunctionDeclaration(
    name = FN_PROPOSE_EDIT,
    description = "Report the evaluation of the photo and the request, and the workflow to run.",
    parameters = obj(
        mapOf(
            ARG_OUTCOME to choice("ready, or why nothing should run.", OUTCOMES),
            ARG_REASON to text("Why nothing should run; required unless ready. Short Korean."),
            ARG_OBSERVATIONS to list(
                "What is visible in the preview.",
                obj(
                    mapOf(
                        ARG_ID to text("o1, o2, …"),
                        ARG_SUBJECT to text("The thing or area. Short Korean."),
                        ARG_NOTE to text("What is seen there. Short Korean."),
                        ARG_CONFIDENCE to choice("Your estimate, not a measurement.", CONFIDENCES),
                        ARG_UNKNOWN to text("What the preview cannot settle. Short Korean."),
                    ),
                    listOf(ARG_ID, ARG_SUBJECT, ARG_NOTE, ARG_CONFIDENCE),
                ),
            ),
            ARG_INTENT to obj(
                mapOf(
                    ARG_TARGETS to list("Things the request is about.", INTENT_ITEM),
                    ARG_WANTS to list("Changes the request asks for.", INTENT_ITEM),
                    ARG_CONSTRAINTS to list(
                        "Hard constraints: what must not change.",
                        obj(
                            mapOf(
                                ARG_TEXT to text("Short Korean."),
                                ARG_KIND to choice("What the constraint protects.", CONSTRAINT_KINDS),
                                ARG_EXPLICIT to Schema(type = TYPE_BOOLEAN),
                            ),
                            listOf(ARG_TEXT, ARG_KIND, ARG_EXPLICIT),
                        ),
                    ),
                ),
                emptyList(),
            ),
            ARG_GOALS to list(
                "What to change and why.",
                obj(
                    mapOf(
                        ARG_ID to text("g1, g2, …"),
                        ARG_TEXT to text("The goal. Short Korean."),
                        ARG_SOURCE to choice("user if the request asks for it, default if not.", GOAL_SOURCES),
                        ARG_SCOPE to choice("Where its steps act.", GOAL_SCOPES),
                        ARG_TARGET to text("For subject scope: the thing, in Korean."),
                        ARG_OBSERVATION_IDS to list("Observations it rests on.", Schema(type = TYPE_STRING)),
                    ),
                    listOf(ARG_ID, ARG_TEXT, ARG_SOURCE, ARG_SCOPE),
                ),
            ),
            ARG_COLOR_ROLES to list(
                "Colours to preserve, emphasize or treat as background; empty without evidence.",
                obj(
                    mapOf(
                        ARG_ROLE to choice("The colour's role.", COLOR_ROLES),
                        ARG_COLOR to text("The colour. Short Korean."),
                        ARG_TARGET to text("Where it is. Short Korean."),
                    ),
                    listOf(ARG_ROLE, ARG_COLOR, ARG_TARGET),
                ),
            ),
            ARG_WORKFLOW to list(
                "The steps, in execution order. Empty unless ready.",
                obj(
                    mapOf(
                        ARG_TOOL to choice("The tool to run.", PLAN_TOOLS),
                        ARG_GOAL_ID to text("The goal this step serves."),
                        ARG_ARGS to STEP_ARGS,
                        ARG_EFFECT to text("Expected effect. Short Korean."),
                        ARG_CAUTION to text("What it may cost. Short Korean."),
                    ),
                    listOf(ARG_TOOL, ARG_GOAL_ID),
                ),
            ),
            ARG_LIMITATIONS to list(
                "Ambiguities, what the preview cannot show, unsupported parts, what to confirm.",
                Schema(type = TYPE_STRING),
            ),
        ),
        listOf(ARG_OUTCOME),
    ),
)

private const val TYPE_OBJECT = "OBJECT"
private const val TYPE_STRING = "STRING"
private const val TYPE_NUMBER = "NUMBER"
private const val TYPE_BOOLEAN = "BOOLEAN"
private const val TYPE_ARRAY = "ARRAY"
