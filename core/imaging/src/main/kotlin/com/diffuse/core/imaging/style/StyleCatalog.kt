package com.diffuse.core.imaging.style

import android.content.res.AssetManager
import com.diffuse.core.imaging.model.AdjustKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * specs/style_match.md §3. `styles.json` is the source of the numbers and not of the strings: the
 * JSON's own `name`/`desc` are ignored and the `id` joins a preset to `style_name_<id>`.
 *
 * Parameters are converted once, here, through [StyleParams]. Nothing downstream sees a Lightroom
 * scale.
 */
object StyleCatalog {

    const val ASSET = "styles.json"

    fun load(assets: AssetManager): List<StylePreset> {
        val root = assets.open(ASSET).use { Json.parseToJsonElement(it.reader().readText()) }
        return root.jsonObject.getValue("styles").jsonArray.map { presetOf(it.jsonObject) }
    }

    private fun presetOf(json: JsonObject): StylePreset {
        val id = json.getValue("id").jsonPrimitive.content
        val axes = json.getValue("axes").jsonObject
        return StylePreset(
            id = id,
            warmth = axes.getValue("warmth").jsonPrimitive.float,
            hardness = axes.getValue("hardness").jsonPrimitive.float,
            params = paramsOf(json),
            variants = json.getValue("variants").jsonArray.mapIndexed { index, variant ->
                StyleVariant("$id-${index + 1}", paramsOf(variant.jsonObject))
            },
        )
    }

    /** JSON order is kept: it is the order the preset's author wrote the adjustments in. */
    private fun paramsOf(json: JsonObject): Map<AdjustKind, Float> =
        json.getValue("params").jsonObject.entries
            .flatMap { (key, value) -> StyleParams.convert(key, value) }
            .toMap()
}
