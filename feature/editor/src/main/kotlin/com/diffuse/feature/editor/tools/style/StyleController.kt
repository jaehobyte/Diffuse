package com.diffuse.feature.editor.tools.style

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.diffuse.core.ai.MatchStyleProvider
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.render.Renderer
import com.diffuse.core.imaging.style.StyleMatch
import com.diffuse.core.imaging.style.StylePreset
import com.diffuse.core.imaging.style.atIntensity
import com.diffuse.feature.editor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** specs/style_match.md §4: 강도 runs 0…100 and opens at full. */
const val STYLE_INTENSITY_MAX = 100

/** §4: 원본 is first and always present — a style is a thing you can leave. */
const val STYLE_NONE_ID = "none"

/** §5: the 13th tile, 참조. Not a catalog preset, so it needs an id of its own. */
const val STYLE_REFERENCE_ID = "reference"

/**
 * §7: one shared render per tile at 96dp. The long edge is in pixels because the renderer works in
 * pixels; 256 is 96dp at the ~2.75 density the goldens are recorded at, and a tile is scaled to fit
 * whatever the device's 96dp actually is.
 */
const val STYLE_TILE_LONG_EDGE_PX = 256

/**
 * specs/style_match.md §4, §7. What the 스타일 sheet is showing: the catalog, the tiles that have
 * finished rendering, and the one the user picked.
 */
data class StyleState(
    val presets: List<StylePreset> = emptyList(),
    /**
     * §7: keyed by tile id, and grown as each render finishes. A tile with no entry yet is drawn
     * flat `surfaceCard` — DESIGN.md §4's rule for a loading image tile, with no skeleton shimmer.
     */
    val tiles: Map<String, ImageBitmap> = emptyMap(),
    /** The selected preset's id; null is 원본. */
    val selected: String? = null,
    /** §3: a variant is a second decision, so it only exists once a style is chosen. */
    val variant: String? = null,
    val intensity: Int = STYLE_INTENSITY_MAX,
    /**
     * §5: what 컬러 매칭 came back with — the model's own numbers, presented as a 13th tile and
     * **never applied silently**. Null until a reference has been read.
     */
    val reference: Map<AdjustKind, Float>? = null,
    val matching: Boolean = false,
    @StringRes val message: Int? = null,
) {

    val preset: StylePreset? get() = presets.firstOrNull { it.id == selected }

    private val referenceSelected: Boolean get() = selected == STYLE_REFERENCE_ID

    /** §4: 원본 commits nothing, so there is nothing to apply until a style is picked. */
    val canApply: Boolean get() = preset != null || (referenceSelected && reference != null)

    /**
     * §4: the 강도 slider scales **what is committed**. The preview and 적용 read this one fold,
     * which is why what the user judged and what lands in history cannot drift apart — the shape
     * `AutoState.appliedTo` established (T77).
     */
    fun scaled(): Map<AdjustKind, Float> = chosenParams().atIntensity(intensity)

    /** 참조's numbers, the chosen variant's, the chosen style's, or nothing — in that order. */
    private fun chosenParams(): Map<AdjustKind, Float> = when {
        referenceSelected -> reference.orEmpty()
        preset == null -> emptyMap()
        else -> preset?.variants?.firstOrNull { it.id == variant }?.params
            ?: preset?.params
            ?: emptyMap()
    }

    fun appliedTo(document: EditDocument): EditDocument =
        scaled().entries.fold(document) { acc, (kind, value) ->
            acc.withAdjust(kind, value, maskId = null)
        }
}

/**
 * specs/style_match.md §4. 스타일 calls nothing: it opens on the catalog, renders a tile of the
 * user's own photograph per preset, and commits ordinary `Adjust` ops.
 *
 * The catalog arrives as a `suspend` lambda rather than a parsed list, because reading an asset is
 * file IO and the sheet is opened from the main thread.
 */
class StyleController(
    private val catalog: suspend () -> List<StylePreset>,
    private val renderer: Renderer,
    private val matchStyle: MatchStyleProvider,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(StyleState())
    val state: StateFlow<StyleState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * §7: one decode, twelve tiles, in catalog order and appearing as they finish. Every tile is
     * the user's own photograph — a swatch of someone else's is a lie about what the style will do.
     */
    fun open(document: EditDocument?) {
        if (document == null) return
        job?.cancel()
        job = scope.launch {
            val presets = presets()
            renderTile(STYLE_NONE_ID, document)
            presets.forEach { preset ->
                // The same fold the canvas and 적용 use, at full strength: a tile shows what the
                // style is, and 강도 is then a slider on the one the user picked.
                val full = StyleState(presets = presets, selected = preset.id)
                renderTile(preset.id, full.appliedTo(document))
            }
        }
    }

    /**
     * The catalog, loaded once and kept. T74: a 지시 plan can name a style before the sheet has
     * ever been opened, so the load cannot belong to [open].
     */
    suspend fun presets(): List<StylePreset> {
        _state.value.presets.takeIf { it.isNotEmpty() }?.let { return it }
        val loaded = catalog()
        _state.value = _state.value.copy(presets = loaded)
        return loaded
    }

    private suspend fun renderTile(id: String, document: EditDocument) {
        val rendered = renderer.preview(document, STYLE_TILE_LONG_EDGE_PX)
        if (rendered is Result.Success) {
            _state.value = _state.value.copy(
                tiles = _state.value.tiles + (id to rendered.value.asImageBitmap()),
            )
        }
    }

    /** §4: selecting a tile applies live. Picking a different style drops the variant with it. */
    fun select(id: String?) {
        _state.value = _state.value.copy(
            selected = id,
            variant = null,
            intensity = STYLE_INTENSITY_MAX,
        )
    }

    fun selectVariant(id: String?) {
        _state.value = _state.value.copy(variant = id)
    }

    fun setIntensity(intensity: Int) {
        _state.value = _state.value.copy(
            intensity = intensity.coerceIn(0, STYLE_INTENSITY_MAX),
        )
    }

    /**
     * §4: 적용 commits every `Adjust` the preset carries as **one** history entry, so one undo
     * takes the whole style back.
     */
    fun apply(document: EditDocument?): EditDocument? {
        val state = _state.value
        if (document == null || !state.canApply) return null
        return state.appliedTo(document)
    }

    /**
     * specs/style_match.md §5. The **local** matcher first, and the model only when it fails —
     * which is the whole reason 컬러 매칭 works offline and usually costs nothing.
     *
     * [reference] is read, used, and dropped: §10 keeps it out of the document, out of the
     * project, and off the disk.
     */
    fun matchReference(reference: Bitmap, image: Bitmap?, document: EditDocument?) {
        job?.cancel()
        job = scope.launch {
            val presets = presets()
            _state.value = _state.value.copy(presets = presets, message = null)

            val nearest = StyleMatch.rank(reference, presets).firstOrNull()
            if (nearest != null && nearest.isNear) {
                // §5 step 1: offered as a selected tile, and no model is asked.
                select(nearest.preset.id)
                _state.value = _state.value.copy(message = R.string.style_reference_near)
                return@launch
            }
            if (image == null) {
                _state.value = _state.value.copy(message = R.string.style_failed)
                return@launch
            }

            _state.value = _state.value.copy(matching = true)
            when (val answer = matchStyle.match(image, reference)) {
                is Result.Success -> {
                    _state.value = _state.value.copy(
                        matching = false,
                        reference = answer.value,
                        selected = STYLE_REFERENCE_ID,
                        variant = null,
                        intensity = STYLE_INTENSITY_MAX,
                    )
                    document?.let { renderTile(STYLE_REFERENCE_ID, applied(answer.value, it)) }
                }
                is Result.Failure -> _state.value = _state.value.copy(
                    matching = false,
                    message = messageFor(answer.error),
                )
            }
        }
    }

    private fun applied(params: Map<AdjustKind, Float>, document: EditDocument): EditDocument =
        params.entries.fold(document) { acc, (kind, value) ->
            acc.withAdjust(kind, value, maskId = null)
        }

    /** §5: generative_erase.md §6 row for row — a missing key is the one the user can fix. */
    @StringRes
    private fun messageFor(error: AppError): Int =
        if (error is AppError.Invalid || error is AppError.Unauthorized) {
            R.string.style_needs_key
        } else {
            R.string.style_failed
        }

    /** The picker handed back something that would not decode. */
    fun showFailure() {
        _state.value = _state.value.copy(message = R.string.style_failed)
    }

    fun onMessageShown() {
        _state.value = _state.value.copy(message = null)
    }

    /** 취소, or a commit: the selection dies with the sheet. The catalog and its tiles do not. */
    fun close() {
        job?.cancel()
        _state.value = _state.value.copy(
            selected = null,
            variant = null,
            intensity = STYLE_INTENSITY_MAX,
            matching = false,
            // §10: the reference is never stored, and it does not outlive the sheet either.
            reference = null,
            tiles = _state.value.tiles - STYLE_REFERENCE_ID,
        )
    }
}
