package com.diffuse.core.ai

import android.graphics.Bitmap
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind

/**
 * specs/vibe_edit.md §14. Directions a 지시 sentence can be steered by, which conflict with each
 * other inside a group: 따뜻하게 and 차갑게 are opposites, 밝게 and 어두운 부분을 밝게 say nearly the
 * same thing. A list shows one direction per group.
 */
enum class SuggestionGroup { Light, Highlights, Temperature, Saturation }

/**
 * What a suggestion's sentence is expected to plan into. Checked by tests against what the plan
 * catalog offers; it is **not** an execution path — a picked sentence still goes through
 * `EditPlanProvider` like any typed one (specs/vibe_edit.md §14).
 */
sealed interface SuggestionCapability {
    data class Adjust(val kind: AdjustKind, val increase: Boolean) : SuggestionCapability

    data class Style(val style: StyleId) : SuggestionCapability
}

/**
 * specs/vibe_edit.md §14's reviewed catalog. [wire] is what the model answers with; [analysis] is
 * the English description it chooses from. The Korean label and sentence are `feature:editor`
 * resources, so nothing the model writes reaches the screen.
 */
enum class PromptSuggestionId(
    val wire: String,
    val group: SuggestionGroup,
    val capability: SuggestionCapability,
    val analysis: String,
) {
    Brighten(
        "brighten",
        SuggestionGroup.Light,
        SuggestionCapability.Adjust(AdjustKind.Exposure, increase = true),
        "Brighten the whole photo a little.",
    ),
    LiftShadows(
        "lift_shadows",
        SuggestionGroup.Light,
        SuggestionCapability.Adjust(AdjustKind.Shadows, increase = true),
        "Lift the dark areas a little so their detail shows.",
    ),
    SoftenHighlights(
        "soften_highlights",
        SuggestionGroup.Highlights,
        SuggestionCapability.Adjust(AdjustKind.Highlights, increase = false),
        "Tone down areas that are too bright.",
    ),
    Warm(
        "warm",
        SuggestionGroup.Temperature,
        SuggestionCapability.Adjust(AdjustKind.Temperature, increase = true),
        "Make the colours a little warmer.",
    ),
    Cool(
        "cool",
        SuggestionGroup.Temperature,
        SuggestionCapability.Adjust(AdjustKind.Temperature, increase = false),
        "Make the colours a little cooler.",
    ),
    NaturalColor(
        "natural_color",
        SuggestionGroup.Saturation,
        SuggestionCapability.Adjust(AdjustKind.Saturation, increase = false),
        "Reduce saturation that looks excessive.",
    ),
    VividColor(
        "vivid_color",
        SuggestionGroup.Saturation,
        SuggestionCapability.Adjust(AdjustKind.Saturation, increase = true),
        "Make dull colours a little more vivid.",
    ),
    FilmWarm(
        "film_warm",
        SuggestionGroup.Temperature,
        SuggestionCapability.Style(StyleId.FilmWarm),
        "Apply a light warm film look.",
    ),
    ;

    companion object {
        /** §14: a list never shows more than this. */
        const val MAX_SHOWN = 3

        fun ofWire(wire: String): PromptSuggestionId? = entries.firstOrNull { it.wire == wire }

        /**
         * §14: the model's order, with duplicates and every later member of an already-shown
         * group dropped, then at most [MAX_SHOWN]. An empty list is a valid answer.
         */
        fun distinctDirections(ids: List<PromptSuggestionId>): List<PromptSuggestionId> =
            ids.distinctBy { it.group }.take(MAX_SHOWN)
    }
}

/**
 * specs/vibe_edit.md §14. Reads the photo the canvas is showing and picks catalog ids for it. It
 * is read-only: it never returns a plan, never edits and is only asked on an explicit tap.
 */
interface PromptSuggestionProvider {

    /** One call. Success with an empty list means "nothing fits", which is not a failure. */
    suspend fun suggest(image: Bitmap): Result<List<PromptSuggestionId>>
}
