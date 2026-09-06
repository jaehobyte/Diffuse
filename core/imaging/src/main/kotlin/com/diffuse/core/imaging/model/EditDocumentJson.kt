package com.diffuse.core.imaging.model

import android.graphics.RectF
import com.diffuse.core.common.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
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

    private fun decodeOperation(node: JsonObject, logger: Logger?): Operation? {
        val id = node["id"]?.jsonPrimitive?.content ?: return warn(logger, "operation without an id")
        return when (val type = node["type"]?.jsonPrimitive?.content) {
            TYPE_ADJUST -> decodeAdjust(node, id, logger)
            TYPE_MASK -> decodeMask(node, id, logger)
            TYPE_GENERATIVE_ERASE -> decodeGenerativeErase(node, id, logger)
            TYPE_GENERATIVE_FILL -> decodeGenerativeFill(node, id, logger)
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
