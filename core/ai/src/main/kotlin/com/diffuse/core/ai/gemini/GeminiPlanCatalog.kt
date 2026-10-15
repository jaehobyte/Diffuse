package com.diffuse.core.ai.gemini

import com.diffuse.core.ai.CropRatio
import com.diffuse.core.ai.StyleId
import com.diffuse.core.ai.topaz.TopazPurpose
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
internal const val FN_EXPAND_TO_RATIO = "expand_to_ratio"
internal const val FN_APPLY_STYLE = "apply_style"
internal const val FN_TOPAZ_ENHANCE = "topaz_enhance"

internal const val ARG_PHRASE = "phrase"
internal const val ARG_KIND = "kind"
internal const val ARG_VALUE = "value"
internal const val ARG_MASKED = "masked"
internal const val ARG_COLOR = "color"
internal const val ARG_RATIO = "ratio"
internal const val ARG_PROMPT = "prompt"
internal const val ARG_STYLE = "style"
internal const val ARG_INTENSITY = "intensity"
internal const val ARG_MULTIPLE = "multiple"
internal const val ARG_PURPOSE = "purpose"
internal const val ARG_SCALE = "scale"
internal const val ARG_FACE_RECOVERY = "face_recovery"
internal const val ARG_SHARPEN_STRENGTH = "sharpen_strength"

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

/** §6: 강도 runs 0…100 — a multiplier on the whole preset, never a blend with the photo. */
internal const val MAX_STYLE_INTENSITY = 100

/**
 * specs/vibe_edit.md §16: the nine purposes `TopazPurpose` implements, by a wire name the model
 * reads. The endpoint and the `model` string stay inside `TopazPurpose`; the planner never
 * writes them.
 */
internal val TopazPurpose.wireName: String
    get() = when (this) {
        TopazPurpose.Upscale -> "upscale"
        TopazPurpose.Sharpen -> "sharpen"
        TopazPurpose.Denoise -> "denoise"
        TopazPurpose.Restore -> "restore"
        TopazPurpose.Creative -> "creative"
        TopazPurpose.Lighting -> "lighting"
        TopazPurpose.ColorBalance -> "color_balance"
        TopazPurpose.Colorize -> "colorize"
        TopazPurpose.DustScratch -> "dust_scratch"
    }

internal fun topazPurposeOf(wire: String): TopazPurpose? =
    TopazPurpose.entries.firstOrNull { it.wireName == wire }

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
    const val DIRECTION_ITEMS = 4
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
internal const val ARG_DIRECTION = "direction"
internal const val ARG_STRENGTHS = "strengths"
internal const val ARG_PRESERVE = "preserve"
internal const val ARG_OBJECTIVE = "objective"
internal const val ARG_SUCCESS = "success"

/** The ten tools a workflow step may name — the planner's whole vocabulary. */
internal val PLAN_TOOLS: List<String> = listOf(
    FN_SELECT_REGION,
    FN_ADJUST,
    FN_ADJUST_COLOR_RANGE,
    FN_ERASE_SELECTION,
    FN_CUT_OUT_SELECTION,
    FN_FILL_SELECTION,
    FN_CROP_RATIO,
    FN_EXPAND_TO_RATIO,
    FN_APPLY_STYLE,
    FN_TOPAZ_ENHANCE,
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
internal val CONSTRAINT_KINDS = listOf(
    "subject_only",
    "keep_background",
    "keep_frame",
    "keep_hue",
    "keep_colors",
    "keep_exact_colors",
    "other",
)

/**
 * specs/vibe_edit.md §14–§16: the tools as the model reads them. Each line keeps the rule T52, T58
 * and T62 learned on the device for that tool, and says which selection a step acts on.
 */
private val TOOL_CATALOG: String =
    "Tools a workflow step may name (tool - args - what it does):\n" +
        "- $FN_SELECT_REGION - phrase, target, multiple - selects the thing a short ENGLISH noun " +
        "phrase names (\"face\", \"green bus\", \"flowers\"); never a sentence, a verb or a colour. " +
        "The segmentation model only understands English, so translate. target is a NEW label you " +
        "choose (n1, n2, ...) that later steps name; it always creates a new selection, which may " +
        "overlap an existing one and whose adjustments add to it. multiple=true only when the " +
        "request is about several things (\"the balloons\"); one person or one object is false, " +
        "and several matches for it stop the run. The user confirms every new selection on an " +
        "overlay before anything uses it.\n" +
        "- $FN_ADJUST - kind, value, target - sets one adjustment. kind is one of " +
        "${plannableKinds.joinToString(", ") { it.wireName }}. value is the FINAL setting, -1 to 1 " +
        "(sharpen and vignette 0 to 1). No target is the whole photo; target is either an " +
        "existing selection's ref from the snapshot (t1, ...) or a label a $FN_SELECT_REGION " +
        "earlier in this workflow declared. An existing target's setting is updated in place.\n" +
        "- $FN_ADJUST_COLOR_RANGE - color, hue, saturation, luminance - sets how one colour range " +
        "looks over the WHOLE photo; it takes no target. color is one of " +
        "${HslBand.entries.joinToString(", ") { it.wireName }}. Each given channel is a final " +
        "setting, -1 to 1; a channel left out keeps its current value and an explicit 0 resets it. " +
        "To change one of several same-coloured things, select it and use $FN_ADJUST instead.\n" +
        "- $FN_ERASE_SELECTION - target - removes the selected thing and fills the hole with the " +
        "surrounding scene. Generative and paid. Recommend it for a visible object that does not " +
        "fit the scene or distracts from the subject (litter, a bin, a sign, a stray passer-by), " +
        "even without an explicit removal request. The object need not be at the frame's edge; " +
        "explain its location and why removal improves this photo. It may redraw the nearby area.\n" +
        "- $FN_CUT_OUT_SELECTION - target - deletes everything OUTSIDE the selection, leaving it " +
        "transparent.\n" +
        "- $FN_FILL_SELECTION - prompt, target - replaces the selected thing with what a short " +
        "English prompt names (\"a red umbrella\"). Generative and paid.\n" +
        "  The target of these three is the label of the $FN_SELECT_REGION directly before them.\n" +
        "- $FN_CROP_RATIO - ratio - crops centred to one of " +
        "${CropRatio.entries.joinToString(", ") { it.wireName }}; the crop tool opens afterwards so " +
        "the user moves the frame. portrait_3_4 is an ordinary feed post (\"인스타\" with no shape), " +
        "landscape_4_3 the wide one, story_9_16 a story or reel, square only when the request says " +
        "square or 1:1. It is never a free rectangle, and it removes what falls outside the frame.\n" +
        "- $FN_EXPAND_TO_RATIO - ratio - reaches one of the same ratios the other way round: the " +
        "canvas grows and the new area outside the original frame is generated to continue the " +
        "scene (generative and paid; it redraws only the added area). Choose between " +
        "$FN_CROP_RATIO and $FN_EXPAND_TO_RATIO by looking at the photo: crop when the subject sits " +
        "well inside the frame and what the new frame removes is empty or disposable; expand when " +
        "a crop would cut into the subject or lose context the photo needs, and the scene continues " +
        "plainly at the edges that would grow (sky, wall, water, ground, floor, a plain backdrop). " +
        "A side can grow by at most half of itself, so a ratio further away than that is a crop. " +
        "It is not possible for a photo that already holds a selection, cut-out or generated patch.\n" +
        "- $FN_APPLY_STYLE - style, intensity - applies a catalogued look to the whole photo. style " +
        "is one of ${StyleId.entries.joinToString(", ") { it.id }}; the snapshot lists what each one " +
        "actually writes. intensity 0 to 100 multiplies every listed value (value x intensity/100); " +
        "it is not a blend with the original and 100 is not a natural default - choose it from how " +
        "far the listed values already move this photo. The style replaces those whole-photo " +
        "settings in place and adds the ones that do not exist yet; nothing else changes.\n" +
        "- $FN_TOPAZ_ENHANCE - purpose, scale, face_recovery, sharpen_strength - prepares one " +
        "remote Topaz run on the WHOLE photo, whatever is selected, after the local steps. It is " +
        "paid and it is not applied to this edit: the user first applies the local steps, then " +
        "reviews and runs it in the 화질 개선 sheet, and the result becomes a high-resolution file " +
        "or a new edit copy. purpose is one of " +
        "${TopazPurpose.entries.joinToString(", ") { it.wireName }}; the snapshot lists each one's " +
        "scales and options. face_recovery when faces are a main subject and would gain from it. " +
        "sharpen_strength (0 to 1) only for sharpen; leave it out for automatic.\n"

/**
 * specs/vibe_edit.md §16, §19: what each Topaz purpose does, each on its own line with when it is
 * the right choice, and how it differs from the local tool with a similar name. These are
 * descriptions for the model; naming them apart is not evidence that it chooses better.
 */
private val TOPAZ_RULES: String =
    "Topaz purposes (one per plan, chosen for what this photo needs most; say in the step's effect " +
        "why it beats the local tools here):\n" +
        "- upscale: more pixels in the same frame (2x or 4x resolution) for a small or low-resolution " +
        "photo that will be printed, cropped or viewed large. It is not $FN_CROP_RATIO, which " +
        "removes area, and not adding area outside the frame, which is $FN_EXPAND_TO_RATIO.\n" +
        "- sharpen: restores motion blur, missed focus or softness from a lens. The local " +
        "$FN_ADJUST sharpen only raises edge contrast and never brings back blurred detail.\n" +
        "- denoise: removes visible grain or colour noise, typical of night, indoor and high-ISO " +
        "photos; local tools cannot remove it. When the preview appears noisy, recommend " +
        "$FN_TOPAZ_ENHANCE(purpose=\"denoise\", scale=1), even without a denoise request. " +
        "A small preview cannot settle how much noise there is: qualify the observation rather " +
        "than omitting a useful recommendation. Night or indoor lighting alone is not evidence " +
        "of noise; distinguish random grain or colour speckles from real texture or intended grain.\n" +
        "- restore: generative restoration of an old, faded, low-quality or heavily compressed " +
        "photo; it may redraw shapes, text and faces.\n" +
        "- creative: generative extra detail and texture for a soft or small photo; it invents " +
        "detail that was never captured.\n" +
        "- lighting: re-balances the whole photo's light (flat, underexposed or uneven light) in " +
        "one pass; it is never a face alone, which is a selection and an adjust.\n" +
        "- color_balance: removes an overall colour cast (a green fluorescent or blue shade cast); " +
        "keep an intended sunset or stage light.\n" +
        "- colorize: gives a black-and-white photo plausible colours; they are invented, never the " +
        "real ones.\n" +
        "- dust_scratch: removes dust, scratches and spots of a scanned print or film; generative.\n" +
        "- One $FN_TOPAZ_ENHANCE at most, after the local steps; one purpose is one run, so never " +
        "describe it as doing several purposes at once. Prefer a local step when it reaches the " +
        "same result. It is never allowed under an explicit subject_only, keep_background or " +
        "colour constraint, after $FN_CUT_OUT_SELECTION, or for a photo with transparency. " +
        "Background removal and subject extraction are not available.\n"

/**
 * specs/vibe_edit.md §14, §15. The evaluation rules both modes run under: strengths and one
 * objective first, then only the tools that serve it; observation kept apart from taste; a hard
 * constraint outranks any weight; every value a final setting on an exactly described state.
 */
internal val PLAN_SYSTEM_INSTRUCTION: String =
    "You evaluate a photo for an Android photo editor and propose one coherent editing workflow " +
        "made of the editor's own tools. You are given the downscaled photo as it is now - already " +
        "rendered with every edit in the snapshot - the snapshot of its state, a mode and, " +
        "usually, the user's request in Korean. Answer only by calling $FN_PROPOSE_EDIT once.\n" +
        "Mode recommend: the user asked what to do; the request may be empty. Mode direct: the " +
        "user sent an instruction; plan it.\n" +
        "\n" +
        "Direction first. First identify the photograph's strengths, its main subject and the " +
        "relationships worth preserving. State one visual objective grounded in the request, and " +
        "an observable success criterion. If you infer that objective, mark it as a default " +
        "intention (source default), not an observation. Then propose the edit that makes the " +
        "best finished photograph for its subject, composition, mood and the user's purpose: " +
        "light, colour, detail, a style, a reframe, removing a distraction, a generative edit or a " +
        "Topaz run are all available when this photo benefits. Do not leave out a tool only because " +
        "the request did not name it, and do not add one only because it exists. Preserve what " +
        "already works; no_change is a valid result for a photo that is already good. Darkness, " +
        "muted colour, softness and empty space are not defects by themselves. For two people " +
        "playing with pale balloons, a default direction is " +
        "\"풍선과 두 사람의 동작 관계를 유지하며 가볍고 부드러운 분위기를 살린다\", with success " +
        "\"동작 관계와 파스텔 차이는 유지되고 필요한 부분만 더 읽힌다\".\n" +
        "\n" +
        "Evaluation:\n" +
        "- Before choosing the workflow, explicitly inspect both scene distractions and image " +
        "quality, alongside light and colour. If you find a scene-inappropriate object or " +
        "probable noise/blur, include the matching erase or Topaz step, not just an observation " +
        "or caution, unless an explicit user constraint or tool restriction prevents it. " +
        "These are recommendations the user can toggle off, not automatic executions.\n" +
        "- Silhouettes, night scenes, high-key and low-key, and minimal compositions are allowed. " +
        "Do not decide from an observation that brighter, cropped or sharper is right.\n" +
        "- Brightness: judge whether what matters - a face, a product, a flower - keeps readable " +
        "shape and texture, not the average. A whole-photo problem is a whole-photo adjust; a " +
        "problem only on a face is a face selection and an adjust on it. Keep the natural " +
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
        "amplified noise. For probable noise, recommend Topaz denoise; do not substitute local " +
        "sharpen, contrast or clarity, which can amplify it. For motion blur or missed focus, " +
        "recommend Topaz sharpen; local sharpen only boosts edge contrast. Choose the one Topaz " +
        "purpose that addresses the dominant problem, explain the visible evidence and preview " +
        "uncertainty, and never promise full recovery. Do not invent skin flaws you cannot see " +
        "in a small JPEG.\n" +
        "- Composition: observe cut-off subjects, distracting objects and the role of empty space. " +
        "Propose $FN_CROP_RATIO when a ratio clearly strengthens the subject or the user's platform, " +
        "never to enforce thirds or less space. For a ratio the user asks for, decide from the photo " +
        "whether $FN_CROP_RATIO or $FN_EXPAND_TO_RATIO serves it better and say why in the effect. " +
        "Actively look for objects that do not fit the " +
        "scene's mood or pull attention away from the subject, anywhere in the frame: litter " +
        "in a landscape, a distracting sign, or an incidental passer-by. When one is visible, " +
        "recommend $FN_SELECT_REGION followed by $FN_ERASE_SELECTION as a separate default " +
        "goal even if the user did not ask for removal. Name the object, its location and why " +
        "erasing it improves the scene; do not merely mention it in the evaluation. " +
        "Never remove the main subject or something that is part " +
        "of the photo's story only because it is an object; when you are not sure it is a " +
        "distraction, leave it.\n" +
        "- Quality, not a count: choose each step and amount for the best result, neither the " +
        "fewest steps nor the smallest values as a goal in themselves, and do not exaggerate " +
        "separation, colour or texture that is already enough. One goal set and one workflow; " +
        "when taste is unclear, keep the original mood as a default objective, and never mix " +
        "several styles in one plan.\n" +
        "- The user sees every step except $FN_SELECT_REGION as its own on/off item and may turn " +
        "any of them off before or after the preview. Keep independent effects as separate steps " +
        "(exposure and temperature are two adjust steps); a style is one effect. Each item's " +
        "effect says what it changes, on what.\n" +
        "\n" +
        "Keep these apart:\n" +
        "- direction: subject and relationships, strengths, what to preserve, the one objective, " +
        "the success criterion, and source user or default. Short; no reasoning transcript.\n" +
        "- observations: only what is visible in this small preview, each with your confidence " +
        "(high/medium/low is your estimate, not a measurement) and what the preview cannot " +
        "settle. Never state RAW recoverability, exact exposure or noise numbers, or a " +
        "histogram you were not given.\n" +
        "- intent: what the request names - targets, wants and constraints. explicit=true only for " +
        "what the user actually said (\"배경은 바꾸지 마\", \"이 사람은 남겨\"); a preservation you " +
        "only infer is explicit=false and belongs in direction.preserve. An inferred constraint is " +
        "your taste, not the user's rule: it never stops a suggestion, and the user can turn any " +
        "suggestion off.\n" +
        "- goals: the changes that serve the objective, each with source user or default, the " +
        "observations it rests on, and its scope: global, subject (one selected thing) or " +
        "color_range.\n" +
        "\"The face is darker than its surroundings\" is an observation; \"brighten the face\" is a " +
        "goal. An observation alone never decides taste.\n" +
        "\n" +
        "The snapshot. Use the supplied snapshot, target IDs, current final values, operation " +
        "order and actual preset effects. Reusing a target updates its existing settings; " +
        "selecting a new target creates another mask whose effect can accumulate. Never silently " +
        "reselect the same subject for a relative follow-up request: \"조금만 더\" after a face was " +
        "brightened reuses that face's target with a larger final value. If the snapshot does not " +
        "say which target the request means (its description is unknown, or several fit), answer " +
        "needs_clarification and ask the user to confirm the selection; never create a new one " +
        "instead. A target with an unknown description is never assumed to be a face or a " +
        "background. Every reference you write must be one the snapshot lists or a label your own " +
        "$FN_SELECT_REGION declared. Operations render in the listed order; a crop always last.\n" +
        "\n" +
        "Values. Every value is a FINAL setting. A value you do not give keeps the current one; " +
        "an explicit 0 resets it. To intensify, go beyond the current value in the same " +
        "direction: blue saturation already at 0.40 made \"조금 더 진하게\" is above 0.40, never " +
        "0.15. At a limit (1 or -1) there is nothing more to give: say so in limitations and do " +
        "not pretend to raise it. A new selection's adjustments start at 0 and act on top of the " +
        "whole-photo settings. Choose each amount from the photo and the tool, not from a fixed " +
        "step:\n" +
        "  - exposure: about +6 L* per 0.1 on a normally exposed photo, so 0.05 to 0.15 is " +
        "already clearly visible and 0.3 or more clips highlights; a dark subject or a dark face " +
        "selection may take 0.15 to 0.3.\n" +
        "  - shadows: almost nothing on a normally exposed photo; 0.2 to 0.4 lifts dark areas and " +
        "raises their noise.\n" +
        "  - contrast: 0.05 to 0.15; on a dark or night photo it crushes shadows quickly.\n" +
        "  - saturation: 0.05 to 0.2; vibrance moves about two thirds as much, 0.1 to 0.25; 0.4 " +
        "starts clipping strong colours.\n" +
        "  - colour range saturation: 0.2 to 0.4 acts only on that band.\n" +
        "  - sharpen: 0.1 to 0.3; it amplifies noise as much as detail.\n" +
        "  - style intensity: about 40 to 70 keeps a natural look; 100 is the whole preset, which " +
        "can clip highlights or drain colour, and only fits a request for the full look.\n" +
        "  - temperature, tint, highlights, vignette, hue and luminance have no measured range: " +
        "keep them small and say the amount is a guess.\n" +
        "  These are starting ranges measured on test images, not rules; the photo's exposure and " +
        "the size of a selection change them.\n" +
        "Exposure, saturation and sharpness numbers are not the same amount of visible change.\n" +
        "\n" +
        "Selection, preservation and frame. Select only supported tools whose scope and effects " +
        "respect the preservation constraints. Hard constraints come first, then goals inside " +
        "them:\n" +
        "- subject_only (\"얼굴만\") and keep_background (\"배경은 그대로\") keep the colour, " +
        "brightness and content outside the selection: no global adjust, colour range, style, " +
        "cut-out or Topaz. A crop the request also asks for (\"스토리 비율로\") is allowed - say in " +
        "limitations that what falls outside the new frame is removed. keep_frame (\"구도/배경 " +
        "영역 전부 유지\") forbids a crop; with a crop request that is needs_clarification.\n" +
        "- Colour: keep_hue keeps hue families (no temperature, tint, hue or style); keep_colors - " +
        "the default meaning of \"색은 바꾸지 마\" - keeps hue and saturation (also no " +
        "saturation, vibrance or colour range), and you add a limitation that exposure and " +
        "contrast still change how colours look; keep_exact_colors keeps RGB exactly, which any " +
        "brightness change breaks, so with a brightness request it is needs_clarification. A plan " +
        "without a colour step does not by that alone keep colours.\n" +
        "- Generative edits and Topaz cannot guarantee colour, face or background preservation; " +
        "never choose them under these constraints.\n" +
        "- Naming an object is not a goal: \"초록 버스\" alone makes the bus important but says " +
        "nothing about what to change; propose something conservative or ask for more.\n" +
        "- \"얼굴만 밝게\" is $FN_SELECT_REGION on the face then an adjust with that target. " +
        "\"꽃은 화사하게, 배경은 그대로\" stays inside the flowers. When several objects share a " +
        "colour, select the object; never $FN_ADJUST_COLOR_RANGE.\n" +
        "- \"봄처럼 화사하게\" is a user style goal; do not state colours or brightness as observed " +
        "facts, and keep skin, highlights and pastel differences.\n" +
        "- A contradictory request or a target you cannot identify (\"그 사람만\" among several " +
        "people, \"왼쪽 사람\" among similar ones) is needs_clarification with the reason; never " +
        "drop a condition silently or pick someone arbitrarily. An explicit request overrides " +
        "the keep-the-mood default.\n" +
        "- A request the tools cannot meet - a free rectangle crop such as \"위 여백만 줄여줘\", " +
        "skin retouching, background removal - is unsupported, or a limitation that says what to " +
        "do by hand; never invent coordinates or tools. More resolution is $FN_TOPAZ_ENHANCE " +
        "upscale, not area outside the frame; area outside the frame is $FN_EXPAND_TO_RATIO.\n" +
        "\n" +
        "Priorities: an explicit goal in the request weighs ${PlanPriority.EXPLICIT_GOAL}, an " +
        "object the request names weighs ${PlanPriority.EXPLICIT_OBJECT}, an unrequested general " +
        "improvement weighs ${PlanPriority.GENERAL_IMPROVEMENT}. These are ranks, not " +
        "probabilities or multipliers of any value, and no sum of them overrides a hard " +
        "constraint.\n" +
        "\n" +
        "Workflow rules:\n" +
        "- Every step names a goal that serves the direction. A subject goal's changes name a " +
        "target; a global goal uses whole-photo adjust, $FN_APPLY_STYLE, $FN_CROP_RATIO, " +
        "$FN_EXPAND_TO_RATIO or $FN_TOPAZ_ENHANCE; a color_range goal uses $FN_ADJUST_COLOR_RANGE. " +
        "A selection nothing " +
        "uses is not an edit.\n" +
        "- A default goal may use any tool its scope allows when the photo shows the reason. In " +
        "direct mode, first meet the request with user goals; a further improvement you add is a " +
        "separate default goal, so the user can tell it apart and turn it off. " +
        "$FN_CUT_OUT_SELECTION deletes everything outside, so propose it only when the request is " +
        "about a cut-out.\n" +
        "- Never set the same setting twice, never set a value a style in the same plan already " +
        "writes unless it is a deliberate further step in the same direction, never undo what " +
        "the style does, and never set a value before a style that then replaces it.\n" +
        "- $FN_CROP_RATIO at most once, as the last local step; $FN_EXPAND_TO_RATIO at most once, " +
        "as the first step, never together with $FN_CROP_RATIO or $FN_CUT_OUT_SELECTION and never " +
        "under subject_only or keep_background; one style at most; $FN_TOPAZ_ENHANCE at most " +
        "once, after everything.\n" +
        "- Expected effects are hypotheses until the rendered result is compared with the same " +
        "input snapshot. Do not claim that a valid plan proves a good result. The user previews " +
        "every local plan and compares it before anything is kept.\n" +
        "- outcome ready needs the direction and a non-empty workflow; no_change, " +
        "needs_clarification and unsupported have an empty workflow and a reason.\n" +
        "- Text fields are short plain Korean (at most ${ProposalLimits.TEXT_CHARS} characters, no " +
        "markup, links or reasoning transcripts): at most ${ProposalLimits.OBSERVATIONS} " +
        "observations, ${ProposalLimits.GOALS} goals, ${ProposalLimits.WORKFLOW} workflow steps " +
        "and ${ProposalLimits.LIMITATIONS} limitations. phrase and prompt stay English.\n" +
        "\n" +
        TOOL_CATALOG +
        "\n" +
        TOPAZ_RULES +
        "\n" +
        "Examples (abridged):\n" +
        "- recommend, no request, backlit portrait with a dark face: direction source default, " +
        "objective \"원래 분위기를 살리며 얼굴 형태만 조금 보이게\"; observation \"얼굴이 배경보다 " +
        "어둡다\" (medium) with unknown \"의도한 실루엣인지\"; workflow " +
        "select_region(phrase=\"face\", target=\"n1\"), adjust(kind=\"exposure\", value=0.2, " +
        "target=\"n1\").\n" +
        "- \"조금만 더\" right after that, the snapshot listing t1 (face, confirmed) with exposure " +
        "0.20 on t1: adjust(kind=\"exposure\", value=0.3, target=\"t1\"). No new selection.\n" +
        "- \"실루엣은 유지해줘\" on a silhouette: constraint other \"실루엣 유지\"; outcome no_change, " +
        "reason \"요청한 실루엣이 이미 잘 살아 있어요\".\n" +
        "- \"버스만 강조해줘\", green bus beside green plants: constraint subject_only; " +
        "select_region(phrase=\"green bus\", target=\"n1\"), adjust(kind=\"contrast\", value=0.1, " +
        "target=\"n1\"). Never adjust_color_range(color=\"green\").\n" +
        "- \"얼굴만 밝게, 배경은 그대로, 스토리용으로\": constraints subject_only and " +
        "keep_background; select_region(phrase=\"face\", target=\"n1\"), adjust(kind=\"exposure\", " +
        "value=0.2, target=\"n1\"), crop_ratio(ratio=\"story_9_16\"); limitation that the crop " +
        "removes the sides. Never cut_out_selection.\n" +
        "- \"파랑을 조금 더 진하게\" with blue saturation 0.40: adjust_color_range(color=\"blue\", " +
        "saturation=0.5).\n" +
        "- \"더 밝게\" with exposure already 0.40: adjust(kind=\"exposure\", value=0.5).\n" +
        "- \"필름 느낌으로 바꿔줘\": apply_style(style=\"film-warm\", intensity chosen from its listed " +
        "values and the photo), and nothing that repeats it.\n" +
        "- \"흐릿한 사진 선명하게 복원해줘\": topaz_enhance(purpose=\"sharpen\", scale=1, " +
        "face_recovery=false); a local adjust sharpen would not restore the blur.\n" +
        "- recommend, no request, a lake landscape with a rubbish bin at the right edge: " +
        "observation \"오른쪽 가장자리의 쓰레기통이 시선을 끈다\" (high); default goals: " +
        "select_region(phrase=\"trash can\", target=\"n1\"), erase_selection(target=\"n1\") with " +
        "effect \"오른쪽 쓰레기통을 지워 호수로 시선이 모인다\", and adjust(kind=\"contrast\", value=0.08) " +
        "as its own step.\n" +
        "- recommend, a small, grainy night street photo: adjust(kind=\"exposure\", value=0.1), then " +
        "topaz_enhance(purpose=\"denoise\", scale=1) last, effect \"노이즈를 줄인다 - 로컬 조정으로는 " +
        "제거되지 않는다\".\n" +
        "- \"배경은 그대로 두고 얼굴만 밝게\": explicit keep_background; no erase, style or Topaz.\n" +
        "- \"인스타 스토리에 올릴거야\" on a landscape with the subject centred and empty sides: " +
        "crop_ratio(ratio=\"story_9_16\").\n" +
        "- \"스토리 비율로\" on a tight portrait whose head nearly touches the top edge, plain wall " +
        "above and below: expand_to_ratio(ratio=\"story_9_16\") - a crop would cut the head; effect " +
        "\"위아래 벽을 이어 그려 스토리 비율을 만든다\".\n" +
        "- recommend, no request, a well-balanced photo: no_change with the strengths in the " +
        "direction and the reason.\n"

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
 * checked per tool by the client, with the rules the nine separate declarations used to carry.
 * There is no `masked`: a local step names its [ARG_TARGET] (specs/vibe_edit.md §15), and
 * `adjust_color_range` is whole-photo only.
 */
private val STEP_ARGS = obj(
    mapOf(
        ARG_PHRASE to text("select_region: short English noun phrase."),
        ARG_TARGET to text(
            "select_region: the new label it declares (n1, n2, ...). adjust: a snapshot target ref or a " +
                "declared label; leave out for the whole photo. erase, cut out, fill: the label just selected.",
        ),
        ARG_MULTIPLE to Schema(type = TYPE_BOOLEAN, description = "select_region: several things are meant."),
        ARG_KIND to choice("adjust: which adjustment.", plannableKinds.map { it.wireName }),
        ARG_VALUE to Schema(type = TYPE_NUMBER, description = "adjust: the final setting."),
        ARG_COLOR to choice("adjust_color_range: which colour range.", HslBand.entries.map { it.wireName }),
        HslChannel.Hue.wireName to Schema(type = TYPE_NUMBER),
        HslChannel.Saturation.wireName to Schema(type = TYPE_NUMBER),
        HslChannel.Luminance.wireName to Schema(type = TYPE_NUMBER),
        ARG_RATIO to choice("crop_ratio, expand_to_ratio.", CropRatio.entries.map { it.wireName }),
        ARG_PROMPT to text("fill_selection: short English description."),
        ARG_STYLE to choice("apply_style.", StyleId.entries.map { it.id }),
        ARG_INTENSITY to Schema(type = TYPE_NUMBER, description = "apply_style: 0 to 100, required."),
        ARG_PURPOSE to choice("topaz_enhance.", TopazPurpose.entries.map { it.wireName }),
        ARG_SCALE to Schema(
            type = TYPE_NUMBER,
            description = "topaz_enhance: an output multiplier the purpose offers.",
        ),
        ARG_FACE_RECOVERY to Schema(type = TYPE_BOOLEAN, description = "topaz_enhance: faces are a main subject."),
        ARG_SHARPEN_STRENGTH to Schema(type = TYPE_NUMBER, description = "topaz_enhance sharpen: 0 to 1."),
    ),
    emptyList(),
)

private val DIRECTION = obj(
    mapOf(
        ARG_SUBJECT to text("The main subject and the relationship worth keeping. Short Korean."),
        ARG_STRENGTHS to list("What the photo already does well. Short Korean.", Schema(type = TYPE_STRING)),
        ARG_PRESERVE to list("Relationships or mood to keep. Short Korean.", Schema(type = TYPE_STRING)),
        ARG_OBJECTIVE to text("The one visual objective. Short Korean."),
        ARG_SUCCESS to text("How a comparison with this input shows it worked. Short Korean."),
        ARG_SOURCE to choice("user if the request states the objective, default if you inferred it.", GOAL_SOURCES),
    ),
    listOf(ARG_SUBJECT, ARG_OBJECTIVE, ARG_SUCCESS, ARG_SOURCE),
)

internal val PROPOSE_FUNCTION = FunctionDeclaration(
    name = FN_PROPOSE_EDIT,
    description = "Report the evaluation of the photo and the request, and the workflow to run.",
    parameters = obj(
        mapOf(
            ARG_OUTCOME to choice("ready, or why nothing should run.", OUTCOMES),
            ARG_REASON to text("Why nothing should run; required unless ready. Short Korean."),
            ARG_DIRECTION to DIRECTION,
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
