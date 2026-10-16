package com.diffuse.core.ai

import android.graphics.Bitmap
import com.diffuse.core.ai.topaz.TopazOptions
import com.diffuse.core.ai.topaz.TopazPurpose
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import kotlinx.coroutines.flow.StateFlow

/**
 * specs/vibe_edit.md §15. Which selection a local step acts on, named **explicitly** — never "the
 * one that happens to be active". [Existing] is a selection the document already carries, reached
 * through the snapshot's opaque [Existing.ref]; the [Existing.maskId] it resolves to never goes on
 * the wire. [New] is the [PlanStep.Select] earlier in the same workflow that declared [New.label].
 */
sealed interface PlanTarget {

    data class Existing(val ref: String, val maskId: String) : PlanTarget

    data class New(val label: String) : PlanTarget
}

/**
 * specs/vibe_edit.md §7. One step of a plan; each maps onto a tool that already exists
 * (specs/vibe_edit.md §4), so a plan adds no `Operation` and no renderer path.
 */
sealed interface PlanStep {

    /**
     * §15: [label] is the new target this selection creates; a later step names it to act on it.
     * [multiple] is a request for several things ("풍선들"): only then may several instances be
     * unioned — one person never silently becomes everyone the segmenter found.
     */
    data class Select(val phrase: String, val label: String, val multiple: Boolean = false) : PlanStep

    /**
     * [kind] is the enum rather than the wire's function name: returning a `String` would split
     * one validation across two modules (specs/ai_provider.md §2). [value] is the **final**
     * setting; [target] null is the whole photo.
     */
    data class Adjust(val kind: AdjustKind, val value: Float, val target: PlanTarget? = null) : PlanStep {
        val masked: Boolean get() = target != null
    }

    /** §15: erase, cut-out and fill consume the selection [target] names, which a select made. */
    data class Erase(val target: String) : PlanStep

    /** Deletes everything **outside** [target]'s selection. */
    data class CutOut(val target: String) : PlanStep

    /**
     * specs/generative_fill.md §8. The one step that carries a noun the **model** wrote: it is
     * English, for the reason every phrase argument is (specs/vibe_edit.md §4).
     */
    data class Fill(val prompt: String, val target: String) : PlanStep

    /**
     * specs/vibe_edit.md §4.1. The model picks a **ratio**, never a rectangle: the rect is
     * computed from the preset the 자르기 chips already use, and the 자르기 tool opens straight
     * afterwards so the user chooses the framing.
     */
    data class Crop(val ratio: CropRatio) : PlanStep

    /**
     * specs/outpaint.md in a plan (2026-10-15): the same closed [ratio] as [Crop], reached by
     * **adding** generated area outside the frame instead of removing what is inside it. The
     * model chooses between the two from the photo; the margins are computed from the source's
     * aspect, never received. Generative and paid. It always runs first, because an `Outpaint`
     * is always the document's first operation, and never beside a crop or a cut-out.
     */
    data class Expand(val ratio: CropRatio) : PlanStep

    /**
     * specs/style_match.md §6. The model names a **look**, never numbers: [style] is one of the
     * catalog's ids and [intensity] is how much of it, 0…100 — a multiplier on the preset,
     * not a blend with the photo. The id resolves to a preset at the `feature:editor` boundary.
     */
    data class Style(val style: StyleId, val intensity: Int) : PlanStep

    /**
     * specs/vibe_edit.md §16, specs/topaz_enhancement.md §1. A Topaz purpose and its options. It
     * is never run by the plan runner: it opens the existing Topaz sheet, prefilled, and the user
     * runs it there — a result is a gallery file or a new edit copy (D098), never an operation.
     */
    data class Topaz(val purpose: TopazPurpose, val options: TopazOptions) : PlanStep
}

/**
 * specs/style_match.md §6's closed enum: `styles.json`'s ids, mirrored here because the
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
    RetroSeventies("retro-seventies"),
    BlueHour("blue-hour"),
    CrossProcess("cross-process"),
    DreamyHaze("dreamy-haze"),
    VintageSlide("vintage-slide"),
    NordicMinimal("nordic-minimal"),
    TropicalVivid("tropical-vivid"),
    AutumnRust("autumn-rust"),
    NeonNight("neon-night"),
    SoftPortrait("soft-portrait"),
    EarthyMuted("earthy-muted"),
    HdrDetail("hdr-detail"),
    LomoToy("lomo-toy"),
    SepiaVintage("sepia-vintage"),
    DarkAcademia("dark-academia"),
    SpringFresh("spring-fresh"),
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
 * specs/vibe_edit.md §15. One adjustment the document already carries, as the **final setting**
 * `withAdjust` would replace. [ref] is opaque and only means something inside this snapshot;
 * [targetRef] is the [PlanTargetState.ref] it lives on, null for the whole photo. [order] is its
 * position in the operation list, which is the order the renderer applies it in.
 */
data class CurrentAdjust(
    val kind: AdjustKind,
    val value: Float,
    val targetRef: String? = null,
    val ref: String = "",
    val order: Int = 0,
) {
    val masked: Boolean get() = targetRef != null
}

/** Where a selection's description came from. [Unknown] is never guessed into "face". */
enum class TargetSource {
    /** A selection a plan made and the user confirmed in this editor session. */
    Confirmed,

    /**
     * specs/vibe_edit.md §19 (2026-10-14): a selection a plan's `Select` found for its phrase in this
     * editor session and used without asking — what it was looked for as, never "the user said so".
     */
    Resolved,
    Unknown,
}

/**
 * §15: one selection the document carries. [maskId] is local and never sent; [ref] is what the
 * model may name. A legacy or manual selection has no [description].
 */
data class PlanTargetState(
    val ref: String,
    val maskId: String,
    val active: Boolean,
    val description: String? = null,
    val source: TargetSource = TargetSource.Unknown,
    val order: Int = 0,
)

/** §15: an operation that is not a value — a mask, a generated patch, a crop — by kind only. */
data class OperationNote(val order: Int, val kind: String, val targetRef: String? = null)

/** §15: the crop the document carries, in the un-cropped source's normalised space. */
data class CropNote(val left: Float, val top: Float, val right: Float, val bottom: Float, val angleDeg: Float)

/**
 * specs/style_match.md §6, vibe_edit.md §15: what a style *actually* writes — the catalog's
 * converted values at intensity 100, neutral entries already dropped. Derived from the catalog,
 * never from the style's name.
 */
data class StyleEffect(val style: StyleId, val values: Map<AdjustKind, Float>)

/**
 * §16: the Topaz purposes a plan may hand off to. [configured] is whether a key exists — the key
 * itself never leaves the device. [inputWidth]/[inputHeight] are the working size the sheet would
 * render, when known; [hasAlpha] blocks every purpose.
 */
data class TopazCapability(
    val configured: Boolean,
    val inputWidth: Int? = null,
    val inputHeight: Int? = null,
    val hasAlpha: Boolean = false,
)

/**
 * specs/vibe_edit.md §14, §15. A pure summary of the document the photo was rendered from, built
 * in `feature:editor` so `core:ai` never reads an `EditDocument`. Every reference in it is opaque
 * and only valid for this snapshot.
 */
data class PlanContext(
    val adjustments: List<CurrentAdjust> = emptyList(),
    /** The document has an applied selection. */
    val hasSelection: Boolean = false,
    val cropped: Boolean = false,
    /**
     * outpaint.md §3: the document holds no stored selection, cut-out, generated patch, retouch
     * or composite, so an `Outpaint` may still go under everything — `EditDocument.canOutpaint`.
     */
    val expandable: Boolean = true,
    val targets: List<PlanTargetState> = emptyList(),
    /** Every operation in render order; adjustments are listed in [adjustments] too. */
    val operations: List<OperationNote> = emptyList(),
    val crop: CropNote? = null,
    /** The size of the preview the model is shown. */
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val styles: List<StyleEffect> = emptyList(),
    val topaz: TopazCapability? = null,
) {
    fun target(ref: String): PlanTargetState? = targets.firstOrNull { it.ref == ref }
}

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
