package com.diffuse.core.ai

import android.graphics.Bitmap
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import kotlinx.coroutines.flow.StateFlow

/**
 * specs/vibe_edit.md §7. One step of a plan; each maps onto a tool that already exists
 * (specs/vibe_edit.md §4), so a plan adds no `Operation` and no renderer path.
 */
sealed interface PlanStep {

    data class Select(val phrase: String) : PlanStep

    /**
     * [kind] is the enum rather than the wire's function name: returning a `String` would split
     * one validation across two modules (specs/ai_provider.md §2).
     */
    data class Adjust(val kind: AdjustKind, val value: Float, val masked: Boolean) : PlanStep

    data object Erase : PlanStep

    data object CutOut : PlanStep

    /**
     * specs/generative_fill.md §8. The one step that carries a noun the **model** wrote: it is
     * English, for the reason every phrase argument is (specs/vibe_edit.md §4).
     */
    data class Fill(val prompt: String) : PlanStep

    /**
     * specs/vibe_edit.md §4.1. The model picks a **ratio**, never a rectangle: the rect is
     * computed from the preset the 자르기 chips already use, and the 자르기 tool opens straight
     * afterwards so the user chooses the framing.
     */
    data class Crop(val ratio: CropRatio) : PlanStep

    /**
     * specs/style_match.md §6. The model names a **look**, never numbers: [style] is one of the
     * catalog's twelve ids and [intensity] is how much of it, 0…100. The id resolves to a preset
     * at the `feature:editor` boundary — `core:ai` does not carry the catalog.
     */
    data class Style(val style: StyleId, val intensity: Int) : PlanStep
}

/**
 * specs/style_match.md §6's closed enum: `styles.json`'s twelve ids, mirrored here because the
 * function declaration has to name them and the catalog is an asset `core:ai` cannot read.
 *
 * [StyleId.id] is the join, and it is the catalog's own string — dashes and all — so a preset and
 * a plan step cannot drift apart over spelling. `StyleIdCatalogTest` in `feature:editor`, which
 * can see both, is what says they still agree.
 */
enum class StyleId(val id: String) {
    CleanBright("clean-bright"),
    NaturalEnhance("natural-enhance"),
    CrispLandscape("crisp-landscape"),
    FilmWarm("film-warm"),
    FadedMatte("faded-matte"),
    CinematicTeal("cinematic-teal"),
    MoodyDark("moody-dark"),
    VibrantPop("vibrant-pop"),
    PastelSoft("pastel-soft"),
    GoldenGlow("golden-glow"),
    UrbanHip("urban-hip"),
    BwClassic("bw-classic"),
}

/**
 * §4.1's closed set — the four preset chips specs/crop.md ships, minus 자유. A model choosing
 * "free" would be choosing nothing, so it is not on the wire.
 *
 * It lives here rather than in `feature:editor` for the reason `PlanStep.Adjust` carries
 * `AdjustKind` (specs/ai_provider.md §2): a plan model that cannot say what it means pushes one
 * validation into two modules. It maps to the tool's `AspectPreset` at the feature boundary;
 * `core:ai` never reaches for crop geometry.
 */
/**
 * specs/vibe_edit.md §4.1's closed set. T68 added `Portrait3x4` and `Landscape4x3`: a bare
 * "인스타그램" is a feed post, and a feed post is 3:4 or 4:3 — the device run had the model
 * answering `Square` for it, which is a shape almost nobody posts any more.
 */
enum class CropRatio { Square, Portrait3x4, Portrait4x5, Story9x16, Landscape4x3, Landscape16x9 }

/** [steps] in execution order. */
data class EditPlan(val steps: List<PlanStep>)

/**
 * specs/vibe_edit.md §14. [Recommend] is 보정 추천 받기 — the text may be empty and the plan is a
 * proposal for the photo; [Direct] is a sent 지시, whose text may not be empty. Both are one call
 * to the same planner under the same evaluation rules.
 */
enum class PlanMode { Recommend, Direct }

/**
 * One adjustment the document already carries, as the **final setting** `withAdjust` would
 * replace. [masked] says it lives on a selection rather than the whole photo.
 */
data class CurrentAdjust(val kind: AdjustKind, val value: Float, val masked: Boolean)

/**
 * specs/vibe_edit.md §14. A pure summary of the document the photo was rendered from, built in
 * `feature:editor` so `core:ai` never reads an `EditDocument`. It is what lets the model plan
 * "더 밝게" on a photo whose exposure is already raised.
 */
data class PlanContext(
    val adjustments: List<CurrentAdjust> = emptyList(),
    /** The document has an applied selection. */
    val hasSelection: Boolean = false,
    val cropped: Boolean = false,
)

/** What the planner is asked: the user's text verbatim, why it is asked, and the document state. */
data class PlanRequest(
    val text: String,
    val mode: PlanMode,
    val context: PlanContext = PlanContext(),
)

/**
 * specs/vibe_edit.md §7. Evaluates the photo and a request and proposes a workflow made of the
 * editor's own tools. It never returns pixels; executing the steps is `feature:editor`'s job (§9).
 */
interface EditPlanProvider {

    val availability: StateFlow<Availability>

    /**
     * One call, no session, no state. A response that fails the proposal checks is
     * `Invalid("unplannable:…")` — the whole proposal is refused, never partly kept.
     */
    suspend fun plan(image: Bitmap, request: PlanRequest): Result<PlanProposal>
}
