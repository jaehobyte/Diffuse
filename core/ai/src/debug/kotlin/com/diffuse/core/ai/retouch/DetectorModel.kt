package com.diffuse.core.ai.retouch

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * `work/tasks.md` requirement 8. Where a development build looks for the exported detector, and
 * what "installed" means.
 *
 * Debug-only, and deliberately inert: the app builds, its tests run and it starts normally with
 * nothing here (see `README.md` in `scripts/retouch/` for the two `adb push` commands that put a
 * model in place). Nothing downloads it, nothing copies it out of `assets`, and no production DI
 * binding reaches it — adopting a runtime is a later decision that needs the measurements this
 * class exists to make possible.
 */
internal class DetectorModelStore(private val directory: File) {

    constructor(context: Context) : this(File(context.filesDir, DIRECTORY))

    val manifestFile: File get() = File(directory, MANIFEST_NAME)

    /**
     * **Both** files present: the manifest, and the model it names. Absence is "not installed",
     * which is not the same as "broken".
     *
     * The model is checked as well as the manifest because a half-installed pair is the shape a
     * push that only half-succeeded leaves behind, and a caller that treats it as installed goes
     * on to measure calls that were never going to run. The digest is deliberately **not**
     * verified here — this is a cheap predicate, and [read] is where correctness is decided.
     */
    fun isInstalled(): Boolean {
        val manifest = manifestFile.takeIf { it.isFile } ?: return false
        val name = runCatching {
            JSON.decodeFromString(DetectorManifest.serializer(), manifest.readText()).model.file
        }.getOrNull() ?: return false
        return File(directory, name).isFile
    }

    fun read(): InstalledModel {
        val manifestText = manifestFile.takeIf { it.isFile }?.readText()
            ?: throw ModelNotInstalled(manifestFile.absolutePath)
        val manifest = JSON.decodeFromString(DetectorManifest.serializer(), manifestText)
        val model = File(directory, manifest.model.file)
        if (!model.isFile) throw ModelNotInstalled(model.absolutePath)
        // requirement 2: a mismatch is an error, not a warning. Every recorded box, threshold and
        // timing belongs to one exported file, and a manifest describing a different export
        // configures the decoder for a tensor layout this model does not have.
        val digest = model.sha256()
        if (!digest.equals(manifest.model.sha256, ignoreCase = true)) {
            throw ModelCorrupt("${manifest.model.file} is sha256:$digest, manifest says sha256:${manifest.model.sha256}")
        }
        return InstalledModel(model, manifest, digest)
    }

    private companion object {
        const val DIRECTORY = "blemish-detector"
        const val MANIFEST_NAME = "detector.manifest.json"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

internal class InstalledModel(
    val file: File,
    val manifest: DetectorManifest,
    val sha256: String,
) {
    /** What goes into the saved operation's per-kind version (specs/skin_retouch_pipeline.md §6). */
    val version: String get() = "acne-yolov8m-onnx@sha256:${sha256.take(16)}"

    val settingsClassIds: Set<Int> get() = manifest.decode.acneClassIds.toSet()
}

/** Not installed. The caller turns this into `Unavailable`; it is not a broken model. */
internal class ModelNotInstalled(path: String) : Exception("no detector model at $path")

/** Installed but unusable: wrong digest, unreadable manifest, or a graph the decoder cannot read. */
internal class ModelCorrupt(message: String) : Exception(message)

/**
 * The subset of `acne_model.py export`'s manifest this adapter reads. Unknown keys are ignored,
 * so the provenance and licence sections the evaluation record needs stay in the file without
 * having to be modelled here.
 */
@Serializable
internal data class DetectorManifest(
    @SerialName("manifest_version") val manifestVersion: Int,
    val model: ModelSection,
    val io: IoSection,
    val preprocess: PreprocessSection,
    val decode: DecodeSection,
) {
    @Serializable
    internal data class ModelSection(val file: String, val sha256: String, val bytes: Long)

    @Serializable
    internal data class IoSection(val inputs: List<TensorSection>, val outputs: List<TensorSection>)

    @Serializable
    internal data class TensorSection(val name: String, val shape: List<Int>, val dtype: String)

    /**
     * What the manifest says has to happen to a ROI before the graph sees it — every field of it,
     * not a summary.
     *
     * `DetectorPreprocess.kt` performs exactly one of these; a manifest naming BGR, `NHWC`,
     * nearest-neighbour or a different pad value describes an export this adapter would feed
     * incorrectly while every shape and digest still matched (`work/tasks.md` requirements 2, 5).
     */
    @Serializable
    internal data class PreprocessSection(
        val color: String,
        val dtype: String,
        val scale: Double,
        val layout: String,
        val resize: String,
        @SerialName("scale_up") val scaleUp: Boolean,
        val interpolation: String,
        @SerialName("pad_value") val padValue: List<Int>,
        @SerialName("pad_placement") val padPlacement: String,
        @SerialName("transparent_background") val transparentBackground: List<Int>,
    )

    /**
     * What the manifest says the output means. Same argument as [PreprocessSection]: `cxcywh`
     * read as `xyxy` gives well-formed boxes of the wrong size in the wrong place, and an
     * `embedded_nms` export would be suppressed a second time (requirement 6).
     */
    @Serializable
    internal data class DecodeSection(
        val layout: String,
        @SerialName("num_classes") val numClasses: Int,
        @SerialName("acne_class_ids") val acneClassIds: List<Int>,
        @SerialName("box_format") val boxFormat: String,
        @SerialName("box_units") val boxUnits: String,
        val objectness: Boolean,
        @SerialName("class_activation") val classActivation: String,
        @SerialName("embedded_nms") val embeddedNms: Boolean,
    )

    /**
     * The manifest's own view of the graph, checked against the session's before anything runs —
     * and, first, checked against **what this build actually does**.
     *
     * Shape agreement is not contract agreement. The same ONNX, the same SHA-256, and a manifest
     * that says `BGR` or `xyxy` or `objectness: true` describes an export whose numbers this code
     * would produce wrongly in a way nothing downstream could notice, so every semantics field is
     * compared rather than read (`work/tasks.md` requirements 2, 5 and 6). The values are the ones
     * `scripts/retouch/detector.py` writes into the manifest and checks on its own side.
     */
    fun contract(): DetectorContract {
        if (manifestVersion != SUPPORTED_VERSION) {
            throw ModelCorrupt("manifest version $manifestVersion, this build reads $SUPPORTED_VERSION")
        }
        requireSemantics("preprocess.color", preprocess.color, "RGB")
        requireSemantics("preprocess.dtype", preprocess.dtype, "float32")
        requireSemantics("preprocess.scale", preprocess.scale, 1.0 / MAX_CHANNEL_VALUE)
        requireSemantics("preprocess.layout", preprocess.layout, "NCHW")
        requireSemantics("preprocess.resize", preprocess.resize, "letterbox")
        requireSemantics("preprocess.scale_up", preprocess.scaleUp, true)
        requireSemantics("preprocess.interpolation", preprocess.interpolation, INTERPOLATION)
        requireSemantics("preprocess.pad_value", preprocess.padValue, PAD_RGB)
        requireSemantics("preprocess.pad_placement", preprocess.padPlacement, PAD_PLACEMENT)
        requireSemantics("preprocess.transparent_background", preprocess.transparentBackground, PAD_RGB)
        requireSemantics("decode.layout", decode.layout, DECODE_LAYOUT)
        requireSemantics("decode.box_format", decode.boxFormat, "cxcywh")
        requireSemantics("decode.box_units", decode.boxUnits, "model input pixels")
        requireSemantics("decode.objectness", decode.objectness, false)
        requireSemantics("decode.class_activation", decode.classActivation, CLASS_ACTIVATION)
        requireSemantics("decode.embedded_nms", decode.embeddedNms, false)
        // An allowlist that names no class this model has would leave the decoder with nothing to
        // look at, which is a configuration error rather than "no blemishes here".
        if (decode.acneClassIds.none { it in 0 until decode.numClasses }) {
            throw ModelCorrupt("class allowlist ${decode.acneClassIds} has nothing in 0..${decode.numClasses - 1}")
        }
        val input = io.inputs.singleOrNull() ?: throw ModelCorrupt("expected one input, got ${io.inputs.size}")
        val output = io.outputs.singleOrNull() ?: throw ModelCorrupt("expected one output, got ${io.outputs.size}")
        if (input.shape.size != 4 || input.shape[0] != 1 || input.shape[1] != 3) {
            throw ModelCorrupt("expected a [1,3,S,S] input, got ${input.shape}")
        }
        if (input.shape[2] != input.shape[3]) throw ModelCorrupt("expected a square input, got ${input.shape}")
        if (output.shape.size != 3 || output.shape[0] != 1) {
            throw ModelCorrupt("expected a [1,rows,anchors] output, got ${output.shape}")
        }
        // The element type is part of the contract too: this preprocessor writes float32 and this
        // decoder reads float32, so an FP16 or quantised export of the same shape is a different
        // graph rather than a detail (requirement 2).
        for (tensor in io.inputs + io.outputs) {
            requireSemantics("io.${tensor.name}.dtype", tensor.dtype, IO_DTYPE)
        }
        return runCatching {
            DetectorContract(
                inputName = input.name,
                inputSize = input.shape[2],
                outputName = output.name,
                rows = output.shape[1],
                anchors = output.shape[2],
                numClasses = decode.numClasses,
            )
        }.getOrElse { throw ModelCorrupt(it.message ?: "unusable graph shape") }
    }

    private fun requireSemantics(field: String, actual: Any?, expected: Any?) {
        if (actual != expected) {
            throw ModelCorrupt("manifest $field is $actual; this build performs $expected")
        }
    }
}

/** `@Serializable` generates the companion object, so these cannot live inside the class. */
private const val SUPPORTED_VERSION = 1

/**
 * The execution semantics this adapter performs, stated as the manifest states them. Kept in step
 * with `PREPROCESS_SEMANTICS` and `DECODE_SEMANTICS` in `scripts/retouch/detector.py`, which
 * writes them into the manifest in the first place.
 */
private const val MAX_CHANNEL_VALUE = 255.0
private const val INTERPOLATION = "bilinear-half-pixel-centers"
private const val PAD_PLACEMENT = "centred; an odd remainder goes to the bottom and the right"
private const val DECODE_LAYOUT = "[1, 4 + num_classes, anchors]"
private const val CLASS_ACTIVATION = "already applied in the graph; do not sigmoid again"
private const val IO_DTYPE = "tensor(float)"
private val PAD_RGB = listOf(LETTERBOX_PAD_VALUE, LETTERBOX_PAD_VALUE, LETTERBOX_PAD_VALUE)

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { stream ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
