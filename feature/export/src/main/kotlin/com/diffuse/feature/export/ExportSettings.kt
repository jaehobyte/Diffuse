package com.diffuse.feature.export

import android.content.Context
import androidx.annotation.StringRes

private const val LONG_EDGE_2048 = 2048
private const val LONG_EDGE_1080 = 1080
private const val ASPECT_FOUR_FIVE = 0.8f
private const val ASPECT_NINE_SIXTEEN = 0.5625f

/** specs/export.md §Sheet. */
enum class ExportFormat(@StringRes val labelRes: Int) {
    Jpeg(R.string.export_format_jpeg),
    Png(R.string.export_format_png),
}

/** Long edge in pixels; `Original` is the working resolution (≤ 4096, specs/imaging.md). */
enum class ExportSize(@StringRes val labelRes: Int, val longEdgePx: Int?) {
    Original(R.string.export_size_original, null),
    Px2048(R.string.export_size_2048, LONG_EDGE_2048),
    Px1080(R.string.export_size_1080, LONG_EDGE_1080),
}

/**
 * A preset centre-crops on top of the document's own crop **for this export only**; the
 * document is never modified (specs/export.md).
 */
enum class ExportPreset(@StringRes val labelRes: Int, val aspect: Float?) {
    None(R.string.export_preset_none, null),
    FourFive(R.string.export_preset_four_five, ASPECT_FOUR_FIVE),
    NineSixteen(R.string.export_preset_nine_sixteen, ASPECT_NINE_SIXTEEN),
}

data class ExportSettings(
    val format: ExportFormat = ExportFormat.Jpeg,
    val size: ExportSize = ExportSize.Original,
    val preset: ExportPreset = ExportPreset.None,
)

/**
 * specs/export.md asks for DataStore. The frozen `libs.versions.toml` has no DataStore
 * entry and CLAUDE.md forbids adding one, so two enum names go through
 * `SharedPreferences` instead — same durability, no change to the catalog the whole loop
 * depends on. Recorded in progress.md.
 */
class ExportSettingsStore(context: Context) {

    private val preferences =
        context.getSharedPreferences("export_settings", Context.MODE_PRIVATE)

    fun load(): ExportSettings = ExportSettings(
        format = preferences.readEnum(KEY_FORMAT, ExportFormat.entries, ExportFormat.Jpeg),
        size = preferences.readEnum(KEY_SIZE, ExportSize.entries, ExportSize.Original),
        // The preset is per-export intent, not a preference, so it is never restored.
        preset = ExportPreset.None,
    )

    fun save(settings: ExportSettings) {
        preferences.edit()
            .putString(KEY_FORMAT, settings.format.name)
            .putString(KEY_SIZE, settings.size.name)
            .apply()
    }

    private fun <T : Enum<T>> android.content.SharedPreferences.readEnum(
        key: String,
        values: List<T>,
        fallback: T,
    ): T {
        val stored = getString(key, null) ?: return fallback
        return values.firstOrNull { it.name == stored } ?: fallback
    }

    private companion object {
        const val KEY_FORMAT = "format"
        const val KEY_SIZE = "size"
    }
}
