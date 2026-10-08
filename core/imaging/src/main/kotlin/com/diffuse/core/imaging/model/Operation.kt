package com.diffuse.core.imaging.model

import android.graphics.RectF

/** specs/edit_model.md. A file in app storage. */
@JvmInline
value class ImageRef(val path: String)

/**
 * specs/edit_model.md. Zero-centred kinds live in [-1, 1]; Sharpen and Vignette in [0, 1].
 *
 * specs/adjust_hsl.md §3: a kind with an [hsl] target is one 혼합 slider. The target is a single
 * nullable field rather than a band and a channel, so a kind can never be half-declared and
 * `Ops` dispatches every one of them in one branch.
 */
enum class AdjustKind(
    val range: ClosedFloatingPointRange<Float>,
    val hsl: HslTarget? = null,
) {
    // Light
    Exposure(ZERO_CENTRED),
    Contrast(ZERO_CENTRED),
    Highlights(ZERO_CENTRED),
    Shadows(ZERO_CENTRED),

    // Color
    Temperature(ZERO_CENTRED),
    Tint(ZERO_CENTRED),
    Saturation(ZERO_CENTRED),
    Vibrance(ZERO_CENTRED),

    // Detail
    Sharpen(UNIT_RANGE),
    Vignette(UNIT_RANGE),

    // specs/adjust_hsl.md — 혼합, appended so no existing entry moves
    HslRedHue(ZERO_CENTRED, HslTarget(HslBand.Red, HslChannel.Hue)),
    HslRedSaturation(ZERO_CENTRED, HslTarget(HslBand.Red, HslChannel.Saturation)),
    HslRedLuminance(ZERO_CENTRED, HslTarget(HslBand.Red, HslChannel.Luminance)),
    HslOrangeHue(ZERO_CENTRED, HslTarget(HslBand.Orange, HslChannel.Hue)),
    HslOrangeSaturation(ZERO_CENTRED, HslTarget(HslBand.Orange, HslChannel.Saturation)),
    HslOrangeLuminance(ZERO_CENTRED, HslTarget(HslBand.Orange, HslChannel.Luminance)),
    HslYellowHue(ZERO_CENTRED, HslTarget(HslBand.Yellow, HslChannel.Hue)),
    HslYellowSaturation(ZERO_CENTRED, HslTarget(HslBand.Yellow, HslChannel.Saturation)),
    HslYellowLuminance(ZERO_CENTRED, HslTarget(HslBand.Yellow, HslChannel.Luminance)),
    HslGreenHue(ZERO_CENTRED, HslTarget(HslBand.Green, HslChannel.Hue)),
    HslGreenSaturation(ZERO_CENTRED, HslTarget(HslBand.Green, HslChannel.Saturation)),
    HslGreenLuminance(ZERO_CENTRED, HslTarget(HslBand.Green, HslChannel.Luminance)),
    HslAquaHue(ZERO_CENTRED, HslTarget(HslBand.Aqua, HslChannel.Hue)),
    HslAquaSaturation(ZERO_CENTRED, HslTarget(HslBand.Aqua, HslChannel.Saturation)),
    HslAquaLuminance(ZERO_CENTRED, HslTarget(HslBand.Aqua, HslChannel.Luminance)),
    HslBlueHue(ZERO_CENTRED, HslTarget(HslBand.Blue, HslChannel.Hue)),
    HslBlueSaturation(ZERO_CENTRED, HslTarget(HslBand.Blue, HslChannel.Saturation)),
    HslBlueLuminance(ZERO_CENTRED, HslTarget(HslBand.Blue, HslChannel.Luminance)),
    HslPurpleHue(ZERO_CENTRED, HslTarget(HslBand.Purple, HslChannel.Hue)),
    HslPurpleSaturation(ZERO_CENTRED, HslTarget(HslBand.Purple, HslChannel.Saturation)),
    HslPurpleLuminance(ZERO_CENTRED, HslTarget(HslBand.Purple, HslChannel.Luminance)),
    HslMagentaHue(ZERO_CENTRED, HslTarget(HslBand.Magenta, HslChannel.Hue)),
    HslMagentaSaturation(ZERO_CENTRED, HslTarget(HslBand.Magenta, HslChannel.Saturation)),
    HslMagentaLuminance(ZERO_CENTRED, HslTarget(HslBand.Magenta, HslChannel.Luminance)),

    // specs/style_match.md §3.1 and auto_enhance.md §3 — T71, appended for T54's reason: 24
    // entries arrived that way without touching the renderer's shape, the serializer or the
    // document model, and five more do the same.
    //
    // Blacks and Whites are the tone curve's endpoints, the siblings of Shadows and Highlights
    // that `AdjustKind` was missing; MonetGPT emits them in four of its six tone operations.
    Blacks(ZERO_CENTRED),
    Whites(ZERO_CENTRED),
    Fade(ZERO_CENTRED),
    SCurve(ZERO_CENTRED),
    Clarity(ZERO_CENTRED),
    ;

    /** 0 is neutral for every kind, so a zero value means "no operation". */
    fun isNeutral(value: Float): Boolean = value == 0f

    fun coerce(value: Float): Float = value.coerceIn(range)
}

private val ZERO_CENTRED = -1f..1f
private val UNIT_RANGE = 0f..1f

/** specs/skin_retouch_pipeline.md §5: the only strength composite there is, so far. */
const val SKIN_RETOUCH_COMPOSITE_VERSION = 1

/**
 * specs/skin_retouch_pipeline.md §6. The document's own copy of the four kinds: core:imaging does
 * not depend on core:ai, so the editor maps `SkinRetouchKind` onto this.
 */
enum class RetouchKind { Blemish, Shine, DarkCircles, ShavingShadow }

/**
 * specs/skin_retouch_pipeline.md §6: provenance of an [Operation.SkinRetouch], never re-rendered
 * from. The strengths are already baked into its result; [engines] carries, per active kind, the
 * detector and checkpoint versions the server reported.
 */
data class SkinRetouchSettings(
    val strengths: Map<RetouchKind, Float>,
    val engines: Map<RetouchKind, String>,
    val compositeVersion: Int = SKIN_RETOUCH_COMPOSITE_VERSION,
) {

    /** Four finite 0..1 strengths, at least one active, each active kind with its engine. */
    val isValid: Boolean
        get() = compositeVersion == SKIN_RETOUCH_COMPOSITE_VERSION &&
            RetouchKind.entries.all { kind ->
                val strength = strengths[kind]
                strength != null && strength.isFinite() && strength in UNIT_RANGE &&
                    (strength == 0f || !engines[kind].isNullOrBlank())
            } &&
            strengths.values.any { it > 0f }
}

/**
 * specs/multishot.md §4. Where one extracted subject sits on the canonical canvas: a uniform
 * [scale] relative to the initial contain fit and a [rotationDeg] about the photo's centre, then a
 * move of the centre by [offsetX]/[offsetY] as fractions of the canvas width/height. [opacity]
 * multiplies the subject's own alpha once.
 */
data class ShotPlacement(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val rotationDeg: Float = 0f,
    val opacity: Float = DEFAULT_SHOT_OPACITY,
) {

    /** §4: the ranges the sheet, the JSON check and the renderer all share. */
    val isValid: Boolean
        get() = offsetX.isFinite() && offsetY.isFinite() &&
            scale.isFinite() && scale in SHOT_SCALE_RANGE &&
            rotationDeg.isFinite() && rotationDeg in SHOT_ROTATION_RANGE &&
            opacity.isFinite() && opacity in UNIT_RANGE

    /** Clamped into the ranges; a non-finite offset goes back to the centre. */
    fun coerced(): ShotPlacement = ShotPlacement(
        offsetX = offsetX.takeIf { it.isFinite() } ?: 0f,
        offsetY = offsetY.takeIf { it.isFinite() } ?: 0f,
        scale = scale.takeIf { it.isFinite() }?.coerceIn(SHOT_SCALE_RANGE) ?: 1f,
        rotationDeg = rotationDeg.takeIf { it.isFinite() }?.coerceIn(SHOT_ROTATION_RANGE) ?: 0f,
        opacity = opacity.takeIf { it.isFinite() }?.coerceIn(UNIT_RANGE) ?: DEFAULT_SHOT_OPACITY,
    )
}

/**
 * specs/multishot.md §3. One added photograph's subject: an RGBA PNG at the photo's EXIF-upright
 * working size ([widthPx] × [heightPx]), transparent outside the subject.
 */
data class Shot(
    val id: String,
    val subjectRef: ImageRef,
    val widthPx: Int,
    val heightPx: Int,
    val placement: ShotPlacement = ShotPlacement(),
    /**
     * specs/multishot.md §4.2: the point of the subject the time layout puts on its path, as a
     * fraction of the photo — initially the bottom centre of the mask's bounds. Null in a document
     * written before the time layout existed.
     */
    val anchor: NormPoint? = null,
) {
    val isValid: Boolean
        get() = id.isNotBlank() && subjectRef.path.isNotBlank() && widthPx > 0 && heightPx > 0 &&
            placement.isValid && anchor?.isValid != false
}

/** A point as a fraction of an image's width and height, both in 0..1. */
data class NormPoint(val x: Float, val y: Float) {
    val isValid: Boolean
        get() = x.isFinite() && y.isFinite() && x in UNIT_RANGE && y in UNIT_RANGE
}

/** specs/multishot.md §2: how the shots are laid out. A document without the field is [Free]. */
enum class MultiShotMode { Free, Timeline }

/**
 * specs/multishot.md §6: the last moment's protection — the current photo's own subject. [ref] is
 * an RGBA PNG whose alpha is the feathered mask, at [widthPx] × [heightPx], the canonical canvas
 * the composite draws on at the size it was selected; [anchor] is the path's end, on that canvas.
 */
data class HeroMask(
    val ref: ImageRef,
    val widthPx: Int,
    val heightPx: Int,
    val anchor: NormPoint,
) {
    val isValid: Boolean
        get() = ref.path.isNotBlank() && widthPx > 0 && heightPx > 0 && anchor.isValid
}

/**
 * specs/multishot.md §4.2–§4.3: how the time layout spreads the earlier moments.
 * [Path] (D086/D087): along a straight path ending at the hero. [Even] (D088): in equal slots of
 * `spacing × W / N` around the hero's own slot, N counting the hero.
 */
enum class TimelineArrangement { Path, Even }

/**
 * specs/multishot.md §4.2: the one-off layout's settings. [directionDeg] is the way the motion runs
 * towards the last moment, on screen (x right, y down); [distance] is the path's length as a
 * fraction of the canvas's short side; [strength] scales the default afterimage profile; [spacing]
 * scales the even arrangement's `W / N` (§4.3). A new proposal is [TimelineArrangement.Even]; a
 * stored timeline without the field is read as [TimelineArrangement.Path] (§7).
 */
data class TimelineLayout(
    val directionDeg: Float = DEFAULT_DIRECTION_DEG,
    val distance: Float = DEFAULT_DISTANCE,
    val strength: Float = 1f,
    val arrangement: TimelineArrangement = TimelineArrangement.Even,
    val spacing: Float = 1f,
) {
    val isValid: Boolean
        get() = directionDeg.isFinite() && directionDeg in SHOT_ROTATION_RANGE &&
            distance.isFinite() && distance in TIMELINE_DISTANCE_RANGE &&
            strength.isFinite() && strength in UNIT_RANGE &&
            spacing.isFinite() && spacing in UNIT_RANGE
}

/**
 * specs/multishot.md §3: the time order, earliest first, by shot id — never the drawing order of
 * the free layout — with the layout settings and the last moment's mask. [orderConfirmed] is false
 * when photos changed in the free layout since the order was confirmed: the hero and settings are
 * kept, and the time layout asks for the order again before it is used.
 */
data class Timeline(
    val order: List<String>,
    val layout: TimelineLayout = TimelineLayout(),
    val hero: HeroMask? = null,
    val orderConfirmed: Boolean = true,
)

/** specs/multishot.md §2: added moments; with the current photo, at most six in all. */
const val MAX_SHOTS = 5
const val DEFAULT_SHOT_OPACITY = 0.6f
val SHOT_SCALE_RANGE = 0.1f..4f
val SHOT_ROTATION_RANGE = -180f..180f

/**
 * §4.2: a new proposal runs from left (oldest) to right (the hero). Only new layouts use it: every
 * stored timeline carries its own `directionDeg`, and a node without one is refused, so changing
 * this (from D086's 135°) changes no saved document.
 */
const val DEFAULT_DIRECTION_DEG = 0f

/** §4.2: half the canvas's short side. */
const val DEFAULT_DISTANCE = 0.5f
val TIMELINE_DISTANCE_RANGE = 0f..2f

/**
 * specs/edit_model.md. Sealed so new ops arrive without touching the existing ones.
 */
sealed interface Operation {

    val id: String

    data class Adjust(
        override val id: String,
        val kind: AdjustKind,
        val value: Float,
        /**
         * specs/selection_tool.md §8.1: when set, the renderer blends this adjustment through
         * the named [Mask] instead of applying it to the whole frame.
         */
        val maskId: String? = null,
    ) : Operation

    /**
     * A selection. Changes no pixels on its own; other ops reference it by [id].
     *
     * It stores the resulting alpha and **not** the prompts that produced it: a v2 selection is
     * built by merging point runs and text phrases (specs/selection_tool.md §4), so no single
     * prompt reproduces it.
     */
    data class Mask(
        override val id: String,
        /** `ALPHA_8` PNG at working resolution, in the project folder. */
        val maskRef: ImageRef,
    ) : Operation

    /**
     * specs/selection_tool.md §8.2: clears the alpha outside [maskId], leaving a cut-out.
     * Several may stack; each one restricts the alpha further.
     */
    data class CutOut(override val id: String, val maskId: String) : Operation

    /**
     * specs/generative_erase.md §6. The one op that carries its own pixels: the renderer takes
     * [resultRef] inside [maskId] and leaves everything outside it alone, so the document stays
     * composable and undo is still a single removal.
     */
    data class GenerativeErase(
        override val id: String,
        val maskId: String,
        /** PNG at working resolution, in the project folder. */
        val resultRef: ImageRef,
    ) : Operation

    /**
     * specs/generative_fill.md §5. 채우기's result, stored the way 지우기's is and composited
     * through the same blend; [prompt] is what produced it.
     *
     * The prompt is kept where `Mask` deliberately keeps none: a merged selection has no single
     * string that reproduces it (specs/edit_model.md), and this one does. It is display and
     * provenance data and is never re-sent on its own.
     */
    data class GenerativeFill(
        override val id: String,
        val maskId: String,
        /** PNG at working resolution, in the project folder. */
        val resultRef: ImageRef,
        val prompt: String,
    ) : Operation

    /**
     * specs/outpaint.md §2, §3. The **only** op that makes the canvas bigger, which is why it is
     * always first in the list: everything after it measures against one canvas, the expanded
     * one, and no op has to ask which era it was created in.
     *
     * [resultRef] is the whole expanded image the model returned, at working resolution. The
     * renderer draws the decoded source back over its interior (§4), so the original pixels
     * survive at whatever resolution they were decoded at and only the invented border is the
     * model's ~1024px answer.
     */
    data class Outpaint(
        override val id: String,
        val margins: Margins,
        /** `outpaint_<id>.png` at working resolution, in the project folder. */
        val resultRef: ImageRef,
    ) : Operation

    /**
     * specs/skin_retouch_pipeline.md §6. Stored the way 지우기 is, with one difference in the
     * render: inside [maskId] only the RGB is replaced and the input alpha is kept.
     *
     * [resultRef] is the canonical working-size render R with the strengths and feather already
     * baked in, so the renderer never multiplies a soft alpha again. [maskId] names the `Mask`
     * holding the binary union of the active kinds' supports.
     */
    data class SkinRetouch(
        override val id: String,
        val maskId: String,
        /** `retouch_<id>.png` at working resolution, in the project folder. */
        val resultRef: ImageRef,
        val settings: SkinRetouchSettings,
    ) : Operation

    /**
     * specs/multishot.md §3. At most one per document: the added photographs' subjects drawn
     * source-over onto whatever the ops before it produced — in [shots] order for
     * [MultiShotMode.Free], in [timeline] order for [MultiShotMode.Timeline], which also keeps the
     * last moment's own pixels in front ([Timeline.hero]). Its pixels are the shots' own PNGs, so
     * the source and every other op stay untouched.
     */
    data class MultiShot(
        override val id: String,
        val shots: List<Shot>,
        val mode: MultiShotMode = MultiShotMode.Free,
        /** Kept in the free layout too once confirmed, so switching back loses nothing. */
        val timeline: Timeline? = null,
    ) : Operation {

        /**
         * One to [MAX_SHOTS] valid shots with distinct ids; a timeline that orders exactly those
         * ids; and for the time layout a hero mask and an anchor on every shot. Anything else is
         * not a partial composite.
         */
        val isValid: Boolean
            get() = id.isNotBlank() && shots.size in 1..MAX_SHOTS && shots.all { it.isValid } &&
                shots.map { it.id }.toSet().size == shots.size &&
                timeline?.let { isValidTimeline(it) } != false &&
                (mode == MultiShotMode.Free || isCompleteTimeline())

        /** The order the shots are drawn in: later is on top. */
        val drawingOrder: List<Shot>
            get() {
                val order = timeline?.order.takeIf { mode == MultiShotMode.Timeline } ?: return shots
                return shots.sortedBy { shot -> order.indexOf(shot.id).let { if (it < 0) Int.MAX_VALUE else it } }
            }

        private fun isValidTimeline(timeline: Timeline): Boolean =
            timeline.order.size == shots.size && timeline.order.toSet() == shots.map { it.id }.toSet() &&
                timeline.layout.isValid && timeline.hero?.isValid != false

        private fun isCompleteTimeline(): Boolean =
            timeline?.hero != null && timeline.orderConfirmed && shots.all { it.anchor != null }
    }

    /** [rect] is normalised 0..1 against the un-cropped, un-rotated source. */
    data class Crop(
        override val id: String,
        val rect: RectF,
        val angleDeg: Float,
    ) : Operation {

        /** A full-frame, unrotated crop is a no-op and is never stored. */
        val isFullFrame: Boolean
            get() = angleDeg == 0f &&
                rect.left == 0f && rect.top == 0f && rect.right == 1f && rect.bottom == 1f
    }
}
