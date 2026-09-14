package com.diffuse.core.ai.retouch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * specs/skin_retouch_validation.md §4, `work/tasks.md` requirements 2 and 8.
 *
 * A debug-variant unit test (`src/testDebug`), because the store it exercises only exists in the
 * debug variant. Nothing here loads ONNX Runtime: what is checked is everything that has to be
 * true *before* a native session is worth creating — the model is present, it is the file the
 * manifest describes, and the graph the manifest describes is one this decoder can read.
 */
class DetectorModelStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `an empty directory is simply not installed`() {
        val store = DetectorModelStore(folder.root)

        assertFalse(store.isInstalled())
        assertThrows(ModelNotInstalled::class.java) { store.read() }
    }

    /**
     * A manifest on its own is a **half**-installed pair, which is what a push that only partly
     * succeeded leaves behind. Reporting that as installed lets a caller go on to measure calls
     * that were never going to run.
     */
    @Test
    fun `a manifest whose model file is missing is not installed`() {
        writeManifest(manifestJson())

        assertFalse(DetectorModelStore(folder.root).isInstalled())
        assertThrows(ModelNotInstalled::class.java) { DetectorModelStore(folder.root).read() }
    }

    @Test
    fun `a manifest and the model it names are installed`() {
        val digest = writeModel("graph bytes")
        writeManifest(manifestJson(sha = digest))

        assertTrue(DetectorModelStore(folder.root).isInstalled())
    }

    /**
     * requirement 2: a model that is not the one its manifest describes is an **error**. Every
     * recorded box, threshold and timing belongs to one exported file, and a decoder configured
     * from someone else's manifest reads the wrong tensor layout out of a valid-looking graph.
     */
    @Test
    fun `a model whose digest does not match its manifest is corrupt`() {
        writeModel("not the exported graph")
        writeManifest(manifestJson(sha = "00".repeat(32)))

        val error = assertThrows(ModelCorrupt::class.java) { DetectorModelStore(folder.root).read() }

        assertTrue(error.message!!.contains("manifest says"))
    }

    @Test
    fun `a matching model and manifest load, and carry the digest into the version`() {
        val digest = writeModel("graph bytes")
        writeManifest(manifestJson(sha = digest))

        val model = DetectorModelStore(folder.root).read()

        assertEquals(digest, model.sha256)
        assertTrue(model.version.startsWith("acne-yolov8m-onnx@sha256:"))
        assertEquals(setOf(0), model.settingsClassIds)
    }

    @Test
    fun `the manifest's graph description becomes the decoder contract`() {
        val contract = installed().manifest.contract()

        assertEquals("images", contract.inputName)
        assertEquals(640, contract.inputSize)
        assertEquals(5, contract.rows)
        assertEquals(8400, contract.anchors)
        assertEquals(1, contract.numClasses)
    }

    /**
     * requirement 6: this decoder multiplies in no objectness and runs NMS itself. A manifest
     * claiming either would make the numbers wrong in a way nothing downstream could notice, so
     * it is refused rather than tolerated.
     */
    @Test
    fun `a manifest declaring objectness or embedded nms is refused`() {
        assertThrows(ModelCorrupt::class.java) {
            installed(manifestJson(objectness = true)).manifest.contract()
        }
        assertThrows(ModelCorrupt::class.java) {
            installed(manifestJson(embeddedNms = true)).manifest.contract()
        }
    }

    @Test
    fun `a manifest whose rows are not four plus nc is refused`() {
        assertThrows(ModelCorrupt::class.java) {
            installed(manifestJson(outputShape = "[1, 6, 8400]")).manifest.contract()
        }
    }

    @Test
    fun `a non square or non image input is refused`() {
        assertThrows(ModelCorrupt::class.java) {
            installed(manifestJson(inputShape = "[1, 3, 640, 480]")).manifest.contract()
        }
        assertThrows(ModelCorrupt::class.java) {
            installed(manifestJson(inputShape = "[1, 1, 640, 640]")).manifest.contract()
        }
    }

    @Test
    fun `an allowlist naming no class this model has is refused`() {
        assertThrows(ModelCorrupt::class.java) {
            installed(manifestJson(classIds = "[7]")).manifest.contract()
        }
    }

    @Test
    fun `a manifest from a later version of the tool is refused rather than guessed at`() {
        assertThrows(ModelCorrupt::class.java) {
            installed(manifestJson(version = 2)).manifest.contract()
        }
    }

    /**
     * `work/REVIEW.md` R3, `work/tasks.md` requirements 2, 5 and 6.
     *
     * The digest matches, the shapes match, and the manifest describes an export that means
     * something this adapter does not do. Every case below changes **one** semantics field and
     * nothing else: `BGR` would hand the model its channels swapped, `xyxy` read as `cxcywh`
     * would give well-formed boxes of the wrong size in the wrong place, `embedded_nms` would be
     * suppressed a second time, and an FP16 graph would be fed a float32 buffer. None of those
     * fails loudly on its own, which is why they are refused before a session is worth creating.
     */
    @Test
    fun `a manifest whose run means something else is refused`() {
        val different = mapOf(
            "\"color\": \"RGB\"" to "\"color\": \"BGR\"",
            "\"layout\": \"NCHW\"" to "\"layout\": \"NHWC\"",
            "\"interpolation\": \"bilinear-half-pixel-centers\"" to "\"interpolation\": \"nearest\"",
            "\"pad_value\": [114, 114, 114]" to "\"pad_value\": [0, 0, 0]",
            "\"transparent_background\": [114, 114, 114]" to "\"transparent_background\": [0, 0, 0]",
            "\"scale_up\": true" to "\"scale_up\": false",
            "\"scale\": 0.00392156862745098" to "\"scale\": 1.0",
            "\"box_format\": \"cxcywh\"" to "\"box_format\": \"xyxy\"",
            "\"box_units\": \"model input pixels\"" to "\"box_units\": \"normalised 0..1\"",
            "\"class_activation\": \"already applied in the graph; do not sigmoid again\"" to
                "\"class_activation\": \"apply sigmoid\"",
            "\"dtype\": \"tensor(float)\"" to "\"dtype\": \"tensor(float16)\"",
        )

        for ((original, replacement) in different) {
            val json = manifestJson()
            assertTrue("$original is not in the fixture manifest", json.contains(original))
            val error = assertThrows(
                "$replacement was accepted",
                ModelCorrupt::class.java,
            ) { installed(json.replace(original, replacement)).manifest.contract() }
            assertTrue(error.message!!.isNotEmpty())
        }
    }

    /**
     * A manifest that simply omits a semantics field is refused, not defaulted to the happy one.
     * Every field in turn, so a later addition to the block cannot become optional by accident.
     */
    @Test
    fun `a manifest missing a semantics field is refused rather than defaulted`() {
        for (field in PREPROCESS_FIELDS) {
            val without = (PREPROCESS_FIELDS - field)
                .joinToString(", ", """"preprocess": {""", "},")

            // `Exception` rather than `ModelCorrupt`: a missing key fails in the deserialiser,
            // and being refused there is just as good as being refused in `contract()`.
            assertThrows("$field was optional", Exception::class.java) {
                installed(manifestJson(preprocess = without)).manifest.contract()
            }
        }
    }

    /** Writes a model and a manifest that agrees with it, so only the field under test differs. */
    private fun installed(json: String = manifestJson()): InstalledModel {
        val digest = writeModel("graph bytes")
        writeManifest(json.replace(DIGEST_PLACEHOLDER, digest))
        return DetectorModelStore(folder.root).read()
    }

    private fun writeModel(content: String): String {
        val file = File(folder.root, MODEL_NAME)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun writeManifest(json: String) {
        val file = File(folder.root, "detector.manifest.json")
        file.parentFile?.mkdirs()
        file.writeText(json)
    }

    /**
     * A manifest shaped exactly like `acne_model.py export`'s, so a test can move one field and
     * everything else still agrees. The `preprocess` and `decode` semantics are the literal values
     * `scripts/retouch/detector.py` writes.
     */
    private fun manifestJson(
        sha: String = DIGEST_PLACEHOLDER,
        version: Int = 1,
        inputShape: String = "[1, 3, 640, 640]",
        outputShape: String = "[1, 5, 8400]",
        objectness: Boolean = false,
        embeddedNms: Boolean = false,
        classIds: String = "[0]",
        preprocess: String = PREPROCESS_BLOCK,
    ): String = """
        {
          "manifest_version": $version,
          "source": {"repo": "Tinny-Robot/acne"},
          "model": {"file": "$MODEL_NAME", "sha256": "$sha", "bytes": 11},
          "io": {
            "inputs": [{"name": "images", "shape": $inputShape, "dtype": "tensor(float)"}],
            "outputs": [{"name": "output0", "shape": $outputShape, "dtype": "tensor(float)"}]
          },
          $preprocess
          "decode": {
            "layout": "[1, 4 + num_classes, anchors]",
            "num_classes": 1,
            "acne_class_ids": $classIds,
            "box_format": "cxcywh",
            "box_units": "model input pixels",
            "objectness": $objectness,
            "class_activation": "already applied in the graph; do not sigmoid again",
            "embedded_nms": $embeddedNms
          }
        }
    """.trimIndent()

    private companion object {
        const val MODEL_NAME = "acne_640_fp32.onnx"
        const val DIGEST_PLACEHOLDER = "__DIGEST__"

        /**
         * `PREPROCESS_SEMANTICS` from `scripts/retouch/detector.py`, on one line so a test can
         * substitute a single field without disturbing the surrounding template's indentation.
         */
        val PREPROCESS_FIELDS = listOf(
            """"color": "RGB"""",
            """"dtype": "float32"""",
            """"scale": 0.00392156862745098""",
            """"layout": "NCHW"""",
            """"resize": "letterbox"""",
            """"scale_up": true""",
            """"interpolation": "bilinear-half-pixel-centers"""",
            """"pad_value": [114, 114, 114]""",
            """"pad_placement": "centred; an odd remainder goes to the bottom and the right"""",
            """"transparent_background": [114, 114, 114]""",
        )

        val PREPROCESS_BLOCK = PREPROCESS_FIELDS.joinToString(", ", """"preprocess": {""", "},")
    }
}
