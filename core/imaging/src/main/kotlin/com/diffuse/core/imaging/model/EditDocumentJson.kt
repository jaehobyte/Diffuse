package com.diffuse.core.imaging.model

import android.graphics.RectF
import com.diffuse.core.common.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** specs/edit_model.md: root field `v`, bumped only when the shape changes. */
const val EDIT_DOCUMENT_SCHEMA_VERSION = 1

private const val TAG = "EditDocumentJson"
private const val TYPE_ADJUST = "adjust"
private const val TYPE_CROP = "crop"
private const val TYPE_MASK = "mask"
private const val TYPE_CUTOUT = "cutout"
private const val TYPE_GENERATIVE_ERASE = "generativeErase"
private const val TYPE_GENERATIVE_FILL = "generativeFill"
private const val TYPE_OUTPAINT = "outpaint"
private const val TYPE_SKIN_RETOUCH = "skinRetouch"
private const val TYPE_MULTI_SHOT = "multiShot"
private val MARGIN_KEYS = listOf("left", "top", "right", "bottom")

/**
 * Operations are mapped by hand rather than through polymorphic serialisation, because
 * specs/edit_model.md requires an unknown operation type or `AdjustKind` to be *dropped*
 * with a warning while the rest of the document still loads. A sealed-hierarchy decoder
 * throws instead.
 */
object EditDocumentJson {

    private val json = Json { prettyPrint = false }

    fun encode(document: EditDocument): String {
        val root = buildJsonObject {
            put("v", EDIT_DOCUMENT_SCHEMA_VERSION)
            put("id", document.id)
            put("source", document.source.path)
            put("createdAt", document.createdAt)
            put("updatedAt", document.updatedAt)
            document.activeMaskId?.let { put("activeMaskId", it) }
            put("operations", buildJsonArray { document.operations.forEach { add(it.encode()) } })
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    fun decode(text: String, logger: Logger? = null): EditDocument {
        val root = json.parseToJsonElement(text).jsonObject
        val operations = root["operations"]?.jsonArray.orEmpty()
            .mapNotNull { element -> decodeOperation(element.jsonObject, logger) }
        return EditDocument(
            id = root.getValue("id").jsonPrimitive.content,
            source = ImageRef(root.getValue("source").jsonPrimitive.content),
            operations = operations,
            activeMaskId = root["activeMaskId"]?.jsonPrimitive?.content,
            createdAt = root.getValue("createdAt").jsonPrimitive.long,
            updatedAt = root.getValue("updatedAt").jsonPrimitive.long,
        )
    }

    private fun Operation.encode(): JsonObject = when (this) {
        is Operation.Adjust -> buildJsonObject {
            put("type", TYPE_ADJUST)
            put("id", id)
            put("kind", kind.name)
            put("value", value)
            maskId?.let { put("maskId", it) }
        }
        is Operation.Mask -> buildJsonObject {
            put("type", TYPE_MASK)
            put("id", id)
            put("maskRef", maskRef.path)
        }
        is Operation.CutOut -> buildJsonObject {
            put("type", TYPE_CUTOUT)
            put("id", id)
            put("maskId", maskId)
        }
        is Operation.GenerativeErase -> buildJsonObject {
            put("type", TYPE_GENERATIVE_ERASE)
            put("id", id)
            put("maskId", maskId)
            put("resultRef", resultRef.path)
        }
        is Operation.GenerativeFill -> buildJsonObject {
            put("type", TYPE_GENERATIVE_FILL)
            put("id", id)
            put("maskId", maskId)
            put("resultRef", resultRef.path)
            put("prompt", prompt)
        }
        is Operation.Outpaint -> buildJsonObject {
            put("type", TYPE_OUTPAINT)
            put("id", id)
            put("resultRef", resultRef.path)
            put("left", margins.left)
            put("top", margins.top)
            put("right", margins.right)
            put("bottom", margins.bottom)
        }
        is Operation.SkinRetouch -> encodeSkinRetouch()
        is Operation.MultiShot -> encodeMultiShot()
        is Operation.Crop -> buildJsonObject {
            put("type", TYPE_CROP)
            put("id", id)
            put("angleDeg", angleDeg)
            put("left", rect.left)
            put("top", rect.top)
            put("right", rect.right)
            put("bottom", rect.bottom)
        }
    }

    // One branch per operation type; splitting it would only hide the list.
    @Suppress("CyclomaticComplexMethod")
    private fun decodeOperation(node: JsonObject, logger: Logger?): Operation? {
        val type = node["type"]?.jsonPrimitive?.content
        // specs/multishot.md §7: even without its id a multi-shot is kept, as an invalid one.
        val id = node["id"]?.jsonPrimitive?.content
            ?: return if (type == TYPE_MULTI_SHOT) node.multiShot("") else warn(logger, "operation without an id")
        return when (type) {
            TYPE_ADJUST -> decodeAdjust(node, id, logger)
            TYPE_MASK -> decodeMask(node, id, logger)
            TYPE_GENERATIVE_ERASE -> decodeGenerativeErase(node, id, logger)
            TYPE_GENERATIVE_FILL -> decodeGenerativeFill(node, id, logger)
            TYPE_OUTPAINT -> decodeOutpaint(node, id, logger)
            TYPE_SKIN_RETOUCH -> node.skinRetouch(id)
                ?: warn(logger, "skinRetouch '$id' without a maskId or resultRef")
            TYPE_MULTI_SHOT -> node.multiShot(id)
            TYPE_CUTOUT -> node["maskId"]?.jsonPrimitive?.content
                ?.let { Operation.CutOut(id, it) }
                ?: warn(logger, "cutout '$id' without a maskId")
            TYPE_CROP -> Operation.Crop(
                id = id,
                rect = RectF(
                    node.getValue("left").jsonPrimitive.float,
                    node.getValue("top").jsonPrimitive.float,
                    node.getValue("right").jsonPrimitive.float,
                    node.getValue("bottom").jsonPrimitive.float,
                ),
                angleDeg = node.getValue("angleDeg").jsonPrimitive.float,
            )
            else -> warn(logger, "unknown operation type '$type'")
        }
    }

    /**
     * A `mask` node without a `maskRef` is dropped rather than fatal, the same way an unknown
     * type is: one unreadable operation must not cost the user the whole document. A document
     * whose `activeMaskId` pointed at it then fails `referencesResolve`, which is where the
     * user is actually told (specs/edit_model.md).
     */
    private fun decodeMask(node: JsonObject, id: String, logger: Logger?): Operation? {
        val ref = node["maskRef"]?.jsonPrimitive?.content
            ?: return warn(logger, "mask '$id' without a maskRef")
        return Operation.Mask(id, ImageRef(ref))
    }

    private fun decodeGenerativeErase(node: JsonObject, id: String, logger: Logger?): Operation? {
        val maskId = node["maskId"]?.jsonPrimitive?.content
        val ref = node["resultRef"]?.jsonPrimitive?.content
        if (maskId == null || ref == null) {
            return warn(logger, "generativeErase '$id' without a maskId or resultRef")
        }
        return Operation.GenerativeErase(id, maskId, ImageRef(ref))
    }

    /** A missing prompt is empty rather than fatal: the pixels are what the render needs. */
    private fun decodeGenerativeFill(node: JsonObject, id: String, logger: Logger?): Operation? {
        val maskId = node["maskId"]?.jsonPrimitive?.content
        val ref = node["resultRef"]?.jsonPrimitive?.content
        if (maskId == null || ref == null) {
            return warn(logger, "generativeFill '$id' without a maskId or resultRef")
        }
        val prompt = node["prompt"]?.jsonPrimitive?.content.orEmpty()
        return Operation.GenerativeFill(id, maskId, ImageRef(ref), prompt)
    }

    /**
     * specs/outpaint.md §3. The margins are the geometry every later op measures against, so a
     * node missing one of them is dropped rather than defaulted — a silently narrower canvas
     * would move the photograph.
     */
    private fun decodeOutpaint(node: JsonObject, id: String, logger: Logger?): Operation? {
        val ref = node["resultRef"]?.jsonPrimitive?.content
        val margins = node.margins()
        if (ref == null || margins == null) {
            return warn(logger, "outpaint '$id' without a resultRef or margins")
        }
        return Operation.Outpaint(id, margins, ImageRef(ref))
    }

    private fun decodeAdjust(node: JsonObject, id: String, logger: Logger?): Operation? {
        val rawKind = node["kind"]?.jsonPrimitive?.content
        val kind = AdjustKind.entries.firstOrNull { it.name == rawKind }
            ?: return warn(logger, "unknown AdjustKind '$rawKind'")
        return Operation.Adjust(
            id = id,
            kind = kind,
            value = node.getValue("value").jsonPrimitive.float,
            maskId = node["maskId"]?.jsonPrimitive?.content,
        )
    }

    private fun warn(logger: Logger?, message: String): Operation? {
        logger?.warn(TAG, "dropped: $message")
        return null
    }
}

private fun Operation.SkinRetouch.encodeSkinRetouch(): JsonObject = buildJsonObject {
    put("type", TYPE_SKIN_RETOUCH)
    put("id", id)
    put("maskId", maskId)
    put("resultRef", resultRef.path)
    put("strengths", buildJsonObject { settings.strengths.forEach { (kind, value) -> put(kind.key, value) } })
    put("engines", buildJsonObject { settings.engines.forEach { (kind, value) -> put(kind.key, value) } })
    put("compositeVersion", settings.compositeVersion)
}

/**
 * specs/skin_retouch_pipeline.md §6. Without its ids or pixels the op is dropped like any other.
 * Its settings are kept exactly as parsed instead — a strength that is missing, not a number
 * or out of range fails `SkinRetouchSettings.isValid`, and with it `referencesResolve`, so
 * the document refuses to load rather than silently rendering different strengths.
 */
private fun JsonObject.skinRetouch(id: String): Operation.SkinRetouch? {
    val maskId = this["maskId"]?.jsonPrimitive?.content
    val ref = this["resultRef"]?.jsonPrimitive?.content
    if (maskId == null || ref == null) return null
    val settings = SkinRetouchSettings(
        strengths = byKind("strengths") { it.floatOrNull },
        engines = byKind("engines") { if (it.isString) it.content else null },
        // Missing is not the current version: nothing written by this app omits it.
        compositeVersion = (this["compositeVersion"] as? JsonPrimitive)?.intOrNull ?: 0,
    )
    return Operation.SkinRetouch(id, maskId, ImageRef(ref), settings)
}

private fun Operation.MultiShot.encodeMultiShot(): JsonObject = buildJsonObject {
    put("type", TYPE_MULTI_SHOT)
    put("id", id)
    put(
        "shots",
        buildJsonArray {
            shots.forEach { shot ->
                add(
                    buildJsonObject {
                        put("id", shot.id)
                        put("subjectRef", shot.subjectRef.path)
                        put("width", shot.widthPx)
                        put("height", shot.heightPx)
                        put("offsetX", shot.placement.offsetX)
                        put("offsetY", shot.placement.offsetY)
                        put("scale", shot.placement.scale)
                        put("rotationDeg", shot.placement.rotationDeg)
                        put("opacity", shot.placement.opacity)
                        shot.anchor?.let {
                            put("anchorX", it.x)
                            put("anchorY", it.y)
                        }
                    },
                )
            }
        },
    )
    // specs/multishot.md §7: written only when there is something beyond the free layout, so a
    // composite that never used the time layout keeps the shape it was saved in.
    if (mode != MultiShotMode.Free) put("mode", mode.key)
    timeline?.let { put("timeline", it.encode()) }
}

private fun Timeline.encode(): JsonObject = buildJsonObject {
    put("order", buildJsonArray { order.forEach { add(JsonPrimitive(it)) } })
    put("directionDeg", layout.directionDeg)
    put("distance", layout.distance)
    put("strength", layout.strength)
    // §7: written only for the even arrangement, so a path timeline keeps the shape it had.
    if (layout.arrangement == TimelineArrangement.Even) {
        put("arrangement", "even")
        put("spacing", layout.spacing)
    }
    // §7: written only when false, so a confirmed timeline keeps the shape it had.
    if (!orderConfirmed) put("orderConfirmed", false)
    hero?.let { hero ->
        put(
            "hero",
            buildJsonObject {
                put("ref", hero.ref.path)
                put("width", hero.widthPx)
                put("height", hero.heightPx)
                put("anchorX", hero.anchor.x)
                put("anchorY", hero.anchor.y)
            },
        )
    }
}

private val MultiShotMode.key: String
    get() = name.replaceFirstChar { it.lowercaseChar() }

/**
 * specs/multishot.md §7. Unlike every other op this one is never dropped: a known multi-shot that
 * is missing a field is kept exactly as parsed — a blank ref, a zero size or a NaN — so it fails
 * `Operation.MultiShot.isValid`, and with it `referencesResolve`, and the document refuses to load
 * rather than rendering a partial composite.
 */
@Suppress("ReturnCount") // Each refusal is a guard clause that keeps the op, invalid.
private fun JsonObject.multiShot(id: String): Operation.MultiShot {
    val shots = (this["shots"] as? JsonArray).orEmpty().map { element ->
        val node = element as? JsonObject ?: JsonObject(emptyMap())
        Shot(
            id = node.text("id"),
            subjectRef = ImageRef(node.text("subjectRef")),
            widthPx = (node["width"] as? JsonPrimitive)?.intOrNull ?: 0,
            heightPx = (node["height"] as? JsonPrimitive)?.intOrNull ?: 0,
            placement = ShotPlacement(
                offsetX = node.number("offsetX"),
                offsetY = node.number("offsetY"),
                scale = node.number("scale"),
                rotationDeg = node.number("rotationDeg"),
                opacity = node.number("opacity"),
            ),
            anchor = node.point(),
        )
    }
    // §7: no field is the free layout of a composite saved before the time layout existed. A mode
    // this build does not know is not one to guess at: the op is kept, invalid, by its blank id.
    val rawMode = this["mode"]
    val mode = if (rawMode == null) {
        MultiShotMode.Free
    } else {
        MultiShotMode.entries.firstOrNull { it.key == (rawMode as? JsonPrimitive)?.content }
            ?: return Operation.MultiShot("", shots)
    }
    val timeline = (this["timeline"] as? JsonObject)?.timeline()
    if (this["timeline"] != null && timeline == null) return Operation.MultiShot("", shots)
    return Operation.MultiShot(id, shots, mode, timeline)
}

/** Kept as parsed, like the shots: a missing number is NaN and fails `TimelineLayout.isValid`. */
// A missing order, a hero that is not an object and a flag that is not a boolean are refusals.
@Suppress("ReturnCount")
private fun JsonObject.timeline(): Timeline? {
    val order = (this["order"] as? JsonArray)?.map { (it as? JsonPrimitive)?.content.orEmpty() } ?: return null
    val heroNode = this["hero"]
    val hero = (heroNode as? JsonObject)?.let { node ->
        HeroMask(
            ref = ImageRef(node.text("ref")),
            widthPx = (node["width"] as? JsonPrimitive)?.intOrNull ?: 0,
            heightPx = (node["height"] as? JsonPrimitive)?.intOrNull ?: 0,
            anchor = node.point() ?: NormPoint(Float.NaN, Float.NaN),
        )
    }
    if (heroNode != null && hero == null) return null
    val confirmed = this["orderConfirmed"]?.let { (it as? JsonPrimitive)?.booleanOrNull ?: return null } ?: true
    val arrangement = arrangement() ?: return null
    return Timeline(
        order = order,
        layout = TimelineLayout(
            directionDeg = number("directionDeg"),
            distance = number("distance"),
            strength = number("strength"),
            arrangement = arrangement,
            // Required for the even arrangement (a missing one is NaN and refused), unused by the path.
            spacing = if (arrangement == TimelineArrangement.Even || this["spacing"] != null) number("spacing") else 1f,
        ),
        hero = hero,
        orderConfirmed = confirmed,
    )
}

/** §7: no field is the path every timeline before D088 used; anything but "even" is refused (null). */
private fun JsonObject.arrangement(): TimelineArrangement? = when (val raw = this["arrangement"]) {
    null -> TimelineArrangement.Path
    else -> TimelineArrangement.Even.takeIf { (raw as? JsonPrimitive)?.takeIf { it.isString }?.content == "even" }
}

/** Both or neither: half an anchor is NaN on the missing side, so it fails `NormPoint.isValid`. */
private fun JsonObject.point(): NormPoint? =
    if (this["anchorX"] == null && this["anchorY"] == null) null else NormPoint(number("anchorX"), number("anchorY"))

private fun JsonObject.text(field: String): String =
    (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

private fun JsonObject.number(field: String): Float =
    (this[field] as? JsonPrimitive)?.takeUnless { it.isString }?.floatOrNull ?: Float.NaN

/** specs/skin_retouch_pipeline.md §6: JSON keys are the kind names in lowerCamel. */
private val RetouchKind.key: String
    get() = name.replaceFirstChar { it.lowercaseChar() }

/** The kinds whose entry under [field] parses; anything else is left out, not defaulted. */
private fun <T : Any> JsonObject.byKind(
    field: String,
    parse: (JsonPrimitive) -> T?,
): Map<RetouchKind, T> {
    val entries = this[field] as? JsonObject ?: return emptyMap()
    return RetouchKind.entries.mapNotNull { kind ->
        (entries[kind.key] as? JsonPrimitive)?.let(parse)?.let { kind to it }
    }.toMap()
}

/** All four or none: a margin that failed to parse would silently narrow the canvas. */
private fun JsonObject.margins(): Margins? {
    val sides = MARGIN_KEYS.map { this[it]?.jsonPrimitive?.floatOrNull ?: return null }
    return Margins(sides.first(), sides[1], sides[2], sides.last())
}
