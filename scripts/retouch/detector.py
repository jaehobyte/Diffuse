"""The reference detector: preprocessing, decoding, and boxes to a candidate mask.

specs/skin_retouch_pipeline.md §1.1 and §2, `work/tasks.md` requirements 5-7. Everything here is
pure NumPy and needs no weights, no ONNX Runtime and no network — it is the half of the port that
the Kotlin adapter reimplements, and `test_detector.py` pins both halves to the same behaviour.

Two rules shape the whole file:

* **Detection is not restoration.** This module finds boxes and turns them into a *candidate*
  defect mask. Nothing here repaints a pixel; `restore.py` is still the only thing that does.
* **The graph's real output decides.** `DetectorContract` is read off the exported model rather
  than assumed, because a decoder that assumes `[1, 4+nc, N]` and a single class silently produces
  plausible nonsense on a model shaped any other way (`work/tasks.md` requirement 6).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

import numpy as np

#: Ultralytics' letterbox grey, and — deliberately the same value — the background transparent
#: pixels are composited over. One constant so Python and Kotlin cannot drift apart.
LETTERBOX_PAD_VALUE = (114, 114, 114)

#: `work/tasks.md` requirement 6: explicit settings, and these are the **evaluation starting**
#: values, not tuned ones. Changing them is a recorded decision, not an edit.
DEFAULT_CONF = 0.25
DEFAULT_IOU = 0.45
DEFAULT_MAX_DETECTIONS = 100

#: The class ids that mean "acne" in this checkpoint (`names = {0: 'acne'}`, read off the `.pt`).
#: An allowlist and not "everything the model emits": an unverified class is not a blemish.
ACNE_CLASS_IDS = (0,)

#: The manifest layout this implementation reads. A manifest written by a later tool may mean
#: something different by the same key, so it is refused rather than read optimistically.
SUPPORTED_MANIFEST_VERSION = 1

#: The only tensor element type this decoder handles. The export is FP32 (`half=False`), and a
#: quantised or FP16 graph is a different numerical contract, not a detail.
IO_DTYPE = "tensor(float)"

#: **What this file actually does** to a ROI before the graph sees it, written down so that a
#: manifest can be checked against it rather than merely read. `work/tasks.md` requirements 2 and
#: 5: a manifest saying BGR, or nearest-neighbour, or a different pad value describes an export
#: this code would silently mis-feed, so it is refused before inference.
#:
#: `acne_model.py export` writes this dict into the manifest, and `from_manifest` requires it
#: back. One definition, so writer and reader cannot drift.
PREPROCESS_SEMANTICS = {
    "color": "RGB",
    "dtype": "float32",
    "scale": 1.0 / 255.0,
    "layout": "NCHW",
    "resize": "letterbox",
    "scale_up": True,
    "interpolation": "bilinear-half-pixel-centers",
    "pad_value": list(LETTERBOX_PAD_VALUE),
    "pad_placement": "centred; an odd remainder goes to the bottom and the right",
    "transparent_background": list(LETTERBOX_PAD_VALUE),
}

#: **What this file actually does** to the graph's output. Same argument as [PREPROCESS_SEMANTICS]:
#: `cxcywh` read as `xyxy` produces boxes that are the wrong shape and the wrong place while
#: looking perfectly well-formed, and an `embedded_nms` export would be suppressed twice
#: (`work/tasks.md` requirement 6).
DECODE_SEMANTICS = {
    "layout": "[1, 4 + num_classes, anchors]",
    "box_format": "cxcywh",
    "box_units": "model input pixels",
    "objectness": False,
    "class_activation": "already applied in the graph; do not sigmoid again",
    "embedded_nms": False,
}


class ManifestContractError(ValueError):
    """The manifest describes execution semantics this implementation does not perform.

    Distinct from a shape mismatch: the graph can be exactly the right shape, and the same
    SHA-256, and still be an export whose boxes mean something else. `work/tasks.md` requirements
    2, 5 and 6 make that an error before inference rather than a wrong number after it.
    """


def _require_semantics(section: dict, expected: dict, where: str) -> None:
    """Every key of [expected] present in [section] with exactly that value."""
    if not isinstance(section, dict):
        raise ManifestContractError(f"manifest {where} is {type(section).__name__}, expected an object")
    for key, value in expected.items():
        if key not in section:
            raise ManifestContractError(f"manifest {where} does not state {key!r}")
        if section[key] != value:
            raise ManifestContractError(
                f"manifest {where}.{key} is {section[key]!r}; this implementation performs {value!r}"
            )


# --------------------------------------------------------------------------------------------
# The model contract
# --------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class DetectorContract:
    """What the exported graph actually takes and returns.

    [rows] is the size of the channel axis of the output: `4 + num_classes` for a YOLOv8 detection
    head with no objectness. If a graph disagrees with its own class count this raises, because
    the alternative is decoding class scores out of coordinate slots.
    """

    input_name: str
    input_size: int
    output_name: str
    rows: int
    anchors: int
    num_classes: int

    @staticmethod
    def from_graph(
        inputs: Sequence[dict], outputs: Sequence[dict], *, num_classes: int
    ) -> "DetectorContract":
        if len(inputs) != 1 or len(outputs) != 1:
            raise ValueError(f"expected one input and one output, got {inputs} / {outputs}")
        shape_in = inputs[0]["shape"]
        shape_out = outputs[0]["shape"]
        if len(shape_in) != 4 or shape_in[0] != 1 or shape_in[1] != 3:
            raise ValueError(f"expected a [1,3,S,S] image input, got {shape_in}")
        if shape_in[2] != shape_in[3]:
            raise ValueError(f"expected a square input, got {shape_in}")
        if len(shape_out) != 3 or shape_out[0] != 1:
            raise ValueError(f"expected a [1,rows,anchors] output, got {shape_out}")
        # The element type is part of the contract, not a detail: this preprocessor writes
        # float32 and this decoder reads float32, so an FP16 or quantised export is a different
        # graph that happens to have the same shape (`work/tasks.md` requirement 2).
        for tensor in (*inputs, *outputs):
            if tensor.get("dtype") != IO_DTYPE:
                raise ValueError(
                    f"{tensor.get('name')} is {tensor.get('dtype')!r}, this build reads {IO_DTYPE!r}"
                )
        rows, anchors = int(shape_out[1]), int(shape_out[2])
        if rows != 4 + num_classes:
            raise ValueError(
                f"output has {rows} rows, which is not 4 + nc for nc={num_classes}. The decoder "
                "in this file only knows the box+class layout; a different head needs a decoder "
                "written for it, not this one pointed at it."
            )
        return DetectorContract(
            input_name=inputs[0]["name"],
            input_size=int(shape_in[2]),
            output_name=outputs[0]["name"],
            rows=rows,
            anchors=anchors,
            num_classes=num_classes,
        )

    def as_manifest(self) -> dict:
        return {
            **DECODE_SEMANTICS,
            "rows": self.rows,
            "anchors": self.anchors,
            "num_classes": self.num_classes,
            "acne_class_ids": list(ACNE_CLASS_IDS),
        }

    @staticmethod
    def from_manifest(manifest: dict) -> "DetectorContract":
        """The manifest's own view of the graph — **after** checking what it says the run means.

        Shape agreement is not contract agreement. One ONNX file, one SHA-256, and a manifest that
        says `BGR` or `xyxy` or `embedded_nms: true` describes an export this code would feed and
        decode incorrectly while every digest still matched, so those fields are compared against
        what this file actually performs and a difference is refused here rather than discovered
        as a wrong box later (`work/tasks.md` requirements 2, 5 and 6).
        """
        version = manifest.get("manifest_version")
        if version != SUPPORTED_MANIFEST_VERSION:
            raise ManifestContractError(
                f"manifest version {version!r}, this build reads {SUPPORTED_MANIFEST_VERSION}"
            )
        _require_semantics(manifest.get("preprocess"), PREPROCESS_SEMANTICS, "preprocess")
        decode = manifest.get("decode")
        _require_semantics(decode, DECODE_SEMANTICS, "decode")

        if "num_classes" not in decode:
            raise ManifestContractError("manifest decode does not state 'num_classes'")
        if not isinstance(manifest.get("io"), dict):
            raise ManifestContractError("manifest has no io section")
        num_classes = int(decode["num_classes"])
        class_ids = decode.get("acne_class_ids")
        if not isinstance(class_ids, list) or not class_ids:
            raise ManifestContractError(f"manifest decode.acne_class_ids is {class_ids!r}")
        # An allowlist naming no class this model has would leave the decoder nothing to look at,
        # which is a configuration error and not "no blemishes in this photograph".
        if not any(isinstance(c, int) and 0 <= c < num_classes for c in class_ids):
            raise ManifestContractError(
                f"manifest class allowlist {class_ids} has nothing in 0..{num_classes - 1}"
            )
        return DetectorContract.from_graph(
            manifest["io"]["inputs"],
            manifest["io"]["outputs"],
            num_classes=num_classes,
        )


# --------------------------------------------------------------------------------------------
# Preprocessing
# --------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class LetterboxTransform:
    """Everything needed to put a model-space box back on the ROI it came from.

    [scale] is one number for both axes — that is what makes it aspect-preserving — and
    [pad_left] / [pad_top] are the *actual* padding, not half of the total, so an odd remainder
    inverts correctly instead of landing half a pixel off.
    """

    roi_width: int
    roi_height: int
    #: The square edge the ROI was fitted into.
    model_size: int
    scaled_width: int
    scaled_height: int
    scale: float
    pad_left: int
    pad_top: int

    @property
    def pad_right(self) -> int:
        return self.model_size - self.scaled_width - self.pad_left

    @property
    def pad_bottom(self) -> int:
        return self.model_size - self.scaled_height - self.pad_top


def letterbox_transform(roi_width: int, roi_height: int, size: int) -> LetterboxTransform:
    """Fit `roi_width x roi_height` into `size x size`, centred, aspect preserved.

    Upscaling is allowed: a face ROI is usually smaller than the model input, and refusing to
    scale up would letterbox a 200px face into a 640px canvas of grey and hand the model a
    picture that is mostly padding.

    An odd remainder goes to the bottom and the right. Stated here, tested in `test_detector.py`,
    and repeated in the manifest, because "which side gets the extra pixel" is exactly the kind of
    detail that differs silently between two implementations.
    """
    if roi_width <= 0 or roi_height <= 0:
        raise ValueError(f"ROI must be non-empty, got {roi_width}x{roi_height}")
    scale = min(size / roi_width, size / roi_height)
    scaled_width = min(size, max(1, int(np.floor(roi_width * scale + 0.5))))
    scaled_height = min(size, max(1, int(np.floor(roi_height * scale + 0.5))))
    return LetterboxTransform(
        roi_width=roi_width,
        roi_height=roi_height,
        model_size=size,
        scaled_width=scaled_width,
        scaled_height=scaled_height,
        scale=scale,
        pad_left=(size - scaled_width) // 2,
        pad_top=(size - scaled_height) // 2,
    )


def resize_bilinear(image: np.ndarray, width: int, height: int) -> np.ndarray:
    """Bilinear resize with half-pixel centres and clamped edges, in float32.

    Written out rather than delegated to `cv2.resize` so that the Kotlin adapter can be the same
    arithmetic rather than approximately the same: OpenCV's INTER_LINEAR uses fixed-point weights,
    Android's canvas filter uses its own, and `work/tasks.md` requirement 5 wants one definition.
    """
    src_h, src_w = image.shape[:2]
    source = image.astype(np.float32, copy=False)

    def axis(dst: int, src: int) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        centres = (np.arange(dst, dtype=np.float32) + 0.5) * (src / dst) - 0.5
        centres = np.clip(centres, 0.0, src - 1.0)
        low = np.floor(centres).astype(np.int64)
        high = np.minimum(low + 1, src - 1)
        return low, high, (centres - low).astype(np.float32)

    y0, y1, wy = axis(height, src_h)
    x0, x1, wx = axis(width, src_w)
    top = source[y0][:, x0] * (1 - wx)[None, :, None] + source[y0][:, x1] * wx[None, :, None]
    bottom = source[y1][:, x0] * (1 - wx)[None, :, None] + source[y1][:, x1] * wx[None, :, None]
    return top * (1 - wy)[:, None, None] + bottom * wy[:, None, None]


def flatten_alpha(rgba: np.ndarray) -> np.ndarray:
    """RGBA over the fixed background, straight (non-premultiplied) alpha, in **float32**.

    specs/skin_retouch_pipeline.md §2 keeps the caller's alpha untouched; this is a copy made for
    the model. Feeding a transparent ROI in raw would let whatever happens to sit in the unused
    colour channels decide what the detector sees.

    The result is **not** rounded back to 8 bits. Quantising here and then resampling in float
    loses up to half a level on every partially transparent pixel, and — because Android composites
    in float — it is exactly the kind of difference that makes the two implementations disagree on
    a semi-transparent ROI while agreeing on every opaque one (`work/tasks.md` requirement 5).
    """
    if rgba.ndim != 3 or rgba.shape[2] != 4:
        raise ValueError(f"expected HxWx4 RGBA, got {rgba.shape}")
    alpha = rgba[:, :, 3:4].astype(np.float32) / 255.0
    background = np.array(LETTERBOX_PAD_VALUE, dtype=np.float32)
    return rgba[:, :, :3].astype(np.float32) * alpha + background * (1.0 - alpha)


def preprocess(roi: np.ndarray, size: int) -> tuple[np.ndarray, LetterboxTransform]:
    """One RGB (or RGBA) ROI to the model's `[1,3,size,size]` float32 NCHW input, in `[0,1]`.

    RGB in, RGB out: `work/tasks.md` requirement 5 names feeding OpenCV's BGR or an ARGB word
    straight through as the mistake this is written to make impossible. The caller is responsible
    for EXIF, face detection and the crop; this only fits what it is given into the square.
    """
    if roi.ndim != 3 or roi.shape[2] not in (3, 4):
        raise ValueError(f"expected HxWx3 RGB or HxWx4 RGBA, got {roi.shape}")
    if roi.dtype != np.uint8:
        raise ValueError(f"expected uint8, got {roi.dtype}")
    # float32 from here on for the RGBA path: the composite is never quantised back to 8 bits.
    rgb = flatten_alpha(roi) if roi.shape[2] == 4 else roi

    height, width = rgb.shape[:2]
    transform = letterbox_transform(width, height, size)
    canvas = np.full((size, size, 3), LETTERBOX_PAD_VALUE, dtype=np.float32)
    scaled = resize_bilinear(rgb, transform.scaled_width, transform.scaled_height)
    canvas[
        transform.pad_top : transform.pad_top + transform.scaled_height,
        transform.pad_left : transform.pad_left + transform.scaled_width,
    ] = scaled

    tensor = np.transpose(canvas / 255.0, (2, 0, 1))[None, ...].astype(np.float32)
    return np.ascontiguousarray(tensor), transform


# --------------------------------------------------------------------------------------------
# Decoding
# --------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Detection:
    """One box in **ROI pixel** coordinates, `xyxy`, already clipped to the ROI."""

    box: tuple[float, float, float, float]
    score: float
    class_id: int

    @property
    def width(self) -> float:
        return self.box[2] - self.box[0]

    @property
    def height(self) -> float:
        return self.box[3] - self.box[1]


def decode_detections(
    raw: np.ndarray,
    contract: DetectorContract,
    transform: LetterboxTransform,
    *,
    confidence: float = DEFAULT_CONF,
    iou: float = DEFAULT_IOU,
    max_detections: int = DEFAULT_MAX_DETECTIONS,
    class_ids: Sequence[int] = ACNE_CLASS_IDS,
) -> list[Detection]:
    """Raw graph output to ROI-space detections. Deterministic, and total on bad input.

    No objectness is multiplied in (the head has none), no sigmoid is applied (the graph already
    did it) and NMS happens exactly once (the export has none embedded) — the three ways
    `work/tasks.md` requirement 6 says a YOLO decoder is usually wrong.
    """
    if raw.shape != (1, contract.rows, contract.anchors):
        raise ValueError(f"output is {raw.shape}, manifest says {(1, contract.rows, contract.anchors)}")
    values = np.asarray(raw, dtype=np.float32)[0]
    # NaN and Inf are dropped rather than propagated: they would win every comparison in NMS and
    # produce a box at infinity, which then clips to the whole ROI and masks the entire face.
    finite = np.isfinite(values).all(axis=0)

    boxes = values[:4]
    scores_by_class = values[4:]
    allowed = [c for c in class_ids if 0 <= c < contract.num_classes]
    if not allowed:
        raise ValueError(f"no usable class in {list(class_ids)} for nc={contract.num_classes}")

    kept: list[tuple[float, int, int]] = []
    for class_id in allowed:
        class_scores = scores_by_class[class_id]
        for anchor in np.nonzero((class_scores >= confidence) & finite)[0]:
            kept.append((float(class_scores[anchor]), int(class_id), int(anchor)))
    if not kept:
        return []

    # Descending score, and the anchor index breaks ties: two identical scores must not be able to
    # swap places between runs, or the max-detections cut becomes non-deterministic.
    kept.sort(key=lambda row: (-row[0], row[1], row[2]))

    detections: list[Detection] = []
    for score, class_id, anchor in kept:
        cx, cy, bw, bh = (float(v) for v in boxes[:, anchor])
        box = _to_roi_box(cx, cy, bw, bh, transform)
        if box is None:
            continue
        detections.append(Detection(box=box, score=score, class_id=class_id))

    return _non_max_suppression(detections, iou, max_detections)


def _to_roi_box(
    cx: float, cy: float, bw: float, bh: float, t: LetterboxTransform
) -> tuple[float, float, float, float] | None:
    """Model space to ROI space: undo the padding, undo the scale, clip, then check it survived.

    A box that lay entirely in the padding, or that clips down to nothing, is dropped. Keeping it
    would put a defect mask on a strip of grey that was never part of the photograph.
    """
    half_w, half_h = bw / 2.0, bh / 2.0
    x0 = (cx - half_w - t.pad_left) / t.scale
    y0 = (cy - half_h - t.pad_top) / t.scale
    x1 = (cx + half_w - t.pad_left) / t.scale
    y1 = (cy + half_h - t.pad_top) / t.scale
    x0, x1 = min(x0, x1), max(x0, x1)
    y0, y1 = min(y0, y1), max(y0, y1)
    x0 = float(np.clip(x0, 0.0, t.roi_width))
    y0 = float(np.clip(y0, 0.0, t.roi_height))
    x1 = float(np.clip(x1, 0.0, t.roi_width))
    y1 = float(np.clip(y1, 0.0, t.roi_height))
    if x1 - x0 <= 0.0 or y1 - y0 <= 0.0:
        return None
    return (x0, y0, x1, y1)


def _non_max_suppression(
    detections: Sequence[Detection], iou_threshold: float, max_detections: int
) -> list[Detection]:
    """Class-aware, greedy, on the already-sorted list. Boxes of different classes never suppress
    each other — one model may well see a blemish where another class also fires."""
    kept: list[Detection] = []
    for candidate in detections:
        if len(kept) >= max_detections:
            break
        if any(
            other.class_id == candidate.class_id and _iou(other.box, candidate.box) > iou_threshold
            for other in kept
        ):
            continue
        kept.append(candidate)
    return kept


def _iou(a: tuple[float, ...], b: tuple[float, ...]) -> float:
    x0 = max(a[0], b[0])
    y0 = max(a[1], b[1])
    x1 = min(a[2], b[2])
    y1 = min(a[3], b[3])
    overlap = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    if overlap <= 0.0:
        return 0.0
    area_a = (a[2] - a[0]) * (a[3] - a[1])
    area_b = (b[2] - b[0]) * (b[3] - b[1])
    union = area_a + area_b - overlap
    return overlap / union if union > 0.0 else 0.0


# --------------------------------------------------------------------------------------------
# Boxes to a candidate mask
# --------------------------------------------------------------------------------------------


def candidate_mask(
    detections: Sequence[Detection],
    allowed: np.ndarray,
    *,
    alpha: np.ndarray | None = None,
) -> np.ndarray:
    """Detections to a 0/255 candidate defect mask, `255` = may be changed.

    `work/tasks.md` requirement 7. The baseline is deliberately the plainest thing that can work:
    **rasterise the box interior, dilate by nothing, grow by nothing.** An ellipse or a shrink
    factor would be a quality claim, and there is no comparison behind one yet.

    The result is always a subset of [allowed], and of the opaque pixels when [alpha] is given. A
    missing allowance is not "the whole face": [allowed] is required, and passing an all-255 mask
    has to be a deliberate act by the caller.

    Rasterisation rule: a pixel belongs to the box when its **centre** is inside it, i.e. columns
    `ceil(x0 - 0.5) .. ceil(x1 - 0.5) - 1`. Half-open on the right, so two boxes that share an
    edge do not both claim the pixel on it.
    """
    if allowed.ndim != 2 or allowed.dtype != np.uint8:
        raise ValueError(f"allowed mask must be 2-D uint8, got {allowed.shape} {allowed.dtype}")
    height, width = allowed.shape
    mask = np.zeros((height, width), dtype=np.uint8)
    for detection in detections:
        x0, y0, x1, y1 = detection.box
        cx0 = max(0, _pixel_start(x0))
        cy0 = max(0, _pixel_start(y0))
        cx1 = min(width, _pixel_start(x1))
        cy1 = min(height, _pixel_start(y1))
        if cx1 > cx0 and cy1 > cy0:
            mask[cy0:cy1, cx0:cx1] = 255

    inside = mask > 0
    inside &= allowed > 0
    if alpha is not None:
        if alpha.shape != allowed.shape:
            raise ValueError(f"alpha is {alpha.shape}, allowed mask is {allowed.shape}")
        inside &= alpha > 0
    return np.where(inside, np.uint8(255), np.uint8(0))


def _pixel_start(edge: float) -> int:
    """First pixel column whose centre is at or past [edge]."""
    return int(np.ceil(edge - 0.5))
