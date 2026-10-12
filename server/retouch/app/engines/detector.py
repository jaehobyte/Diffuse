"""Acne detector pre/post-processing, ported from scripts/retouch/detector.py (pure NumPy).

Only what the server needs: the manifest semantics check, letterbox preprocessing, decode + NMS.
The evaluation harness remains the reference; the constants below must stay equal to it.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

import numpy as np

LETTERBOX_PAD_VALUE = (114, 114, 114)
IO_DTYPE = "tensor(float)"
SUPPORTED_MANIFEST_VERSION = 1
ACNE_CLASS_IDS = (0,)

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

DECODE_SEMANTICS = {
    "layout": "[1, 4 + num_classes, anchors]",
    "box_format": "cxcywh",
    "box_units": "model input pixels",
    "objectness": False,
    "class_activation": "already applied in the graph; do not sigmoid again",
    "embedded_nms": False,
}


class ManifestContractError(ValueError):
    """The manifest describes a model this code would mis-feed or mis-decode."""


@dataclass(frozen=True)
class DetectorContract:
    input_name: str
    input_size: int
    output_name: str
    rows: int
    anchors: int
    num_classes: int

    @staticmethod
    def from_graph(inputs: Sequence[dict], outputs: Sequence[dict], *, num_classes: int) -> "DetectorContract":
        if len(inputs) != 1 or len(outputs) != 1:
            raise ManifestContractError("expected one input and one output")
        shape_in = inputs[0]["shape"]
        shape_out = outputs[0]["shape"]
        if len(shape_in) != 4 or shape_in[0] != 1 or shape_in[1] != 3 or shape_in[2] != shape_in[3]:
            raise ManifestContractError(f"expected a [1,3,S,S] input, got {shape_in}")
        if len(shape_out) != 3 or shape_out[0] != 1:
            raise ManifestContractError(f"expected a [1,rows,anchors] output, got {shape_out}")
        for tensor in (*inputs, *outputs):
            if tensor.get("dtype") != IO_DTYPE:
                raise ManifestContractError(f"tensor dtype {tensor.get('dtype')!r} is not {IO_DTYPE}")
        rows, anchors = int(shape_out[1]), int(shape_out[2])
        if rows != 4 + num_classes:
            raise ManifestContractError(f"output rows {rows} != 4 + nc ({num_classes})")
        return DetectorContract(
            input_name=inputs[0]["name"],
            input_size=int(shape_in[2]),
            output_name=outputs[0]["name"],
            rows=rows,
            anchors=anchors,
            num_classes=num_classes,
        )

    @staticmethod
    def from_manifest(manifest: dict) -> "DetectorContract":
        if manifest.get("manifest_version") != SUPPORTED_MANIFEST_VERSION:
            raise ManifestContractError("unsupported manifest version")
        for section, expected in (("preprocess", PREPROCESS_SEMANTICS), ("decode", DECODE_SEMANTICS)):
            values = manifest.get(section)
            if not isinstance(values, dict):
                raise ManifestContractError(f"manifest has no {section} section")
            for key, value in expected.items():
                if values.get(key) != value:
                    raise ManifestContractError(f"manifest {section}.{key} differs from this implementation")
        decode = manifest["decode"]
        num_classes = int(decode["num_classes"])
        class_ids = decode.get("acne_class_ids")
        if not isinstance(class_ids, list) or not any(
            isinstance(c, int) and 0 <= c < num_classes for c in class_ids
        ):
            raise ManifestContractError("manifest class allowlist is unusable")
        io = manifest.get("io")
        if not isinstance(io, dict):
            raise ManifestContractError("manifest has no io section")
        return DetectorContract.from_graph(io["inputs"], io["outputs"], num_classes=num_classes)


@dataclass(frozen=True)
class LetterboxTransform:
    roi_width: int
    roi_height: int
    model_size: int
    scaled_width: int
    scaled_height: int
    scale: float
    pad_left: int
    pad_top: int


def letterbox_transform(roi_width: int, roi_height: int, size: int) -> LetterboxTransform:
    if roi_width <= 0 or roi_height <= 0:
        raise ValueError("ROI must be non-empty")
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


def _axis(dst: int, src: int) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    centres = (np.arange(dst, dtype=np.float32) + 0.5) * (src / dst) - 0.5
    centres = np.clip(centres, 0.0, src - 1.0)
    low = np.floor(centres).astype(np.int64)
    high = np.minimum(low + 1, src - 1)
    return low, high, (centres - low).astype(np.float32)


def resize_bilinear_rgba_flattened(rgba: np.ndarray, width: int, height: int) -> np.ndarray:
    """Alpha-flatten over the pad grey and bilinear-resize (half-pixel centres), in float32.

    Equivalent to `flatten_alpha` followed by `resize_bilinear` in the reference: both are
    per-pixel linear operations applied at the same sample points, so the flatten is evaluated on
    the four gathered neighbours only instead of on a full-resolution float copy of the ROI.
    """
    src_h, src_w = rgba.shape[:2]
    y0, y1, wy = _axis(height, src_h)
    x0, x1, wx = _axis(width, src_w)
    background = np.array(LETTERBOX_PAD_VALUE, dtype=np.float32)

    def flat(ys: np.ndarray, xs: np.ndarray) -> np.ndarray:
        px = rgba[ys][:, xs].astype(np.float32)
        a = px[:, :, 3:4] / 255.0
        return px[:, :, :3] * a + background * (1.0 - a)

    top = flat(y0, x0) * (1 - wx)[None, :, None] + flat(y0, x1) * wx[None, :, None]
    bottom = flat(y1, x0) * (1 - wx)[None, :, None] + flat(y1, x1) * wx[None, :, None]
    return top * (1 - wy)[:, None, None] + bottom * wy[:, None, None]


def preprocess(rgba: np.ndarray, size: int) -> tuple[np.ndarray, LetterboxTransform]:
    """H×W×4 uint8 ROI → `[1,3,size,size]` float32 in [0,1], plus the inverse transform."""
    if rgba.ndim != 3 or rgba.shape[2] != 4 or rgba.dtype != np.uint8:
        raise ValueError("expected H×W×4 uint8")
    height, width = rgba.shape[:2]
    transform = letterbox_transform(width, height, size)
    canvas = np.full((size, size, 3), LETTERBOX_PAD_VALUE, dtype=np.float32)
    scaled = resize_bilinear_rgba_flattened(rgba, transform.scaled_width, transform.scaled_height)
    canvas[
        transform.pad_top : transform.pad_top + transform.scaled_height,
        transform.pad_left : transform.pad_left + transform.scaled_width,
    ] = scaled
    tensor = np.transpose(canvas / 255.0, (2, 0, 1))[None, ...].astype(np.float32)
    return np.ascontiguousarray(tensor), transform


@dataclass(frozen=True)
class Detection:
    box: tuple[float, float, float, float]  # ROI pixels, xyxy, clipped
    score: float
    class_id: int


def decode_detections(
    raw: np.ndarray,
    contract: DetectorContract,
    transform: LetterboxTransform,
    *,
    confidence: float,
    iou: float,
    max_detections: int,
    class_ids: Sequence[int] = ACNE_CLASS_IDS,
) -> list[Detection]:
    if raw.shape != (1, contract.rows, contract.anchors):
        raise ValueError(f"output shape {raw.shape} does not match the manifest")
    values = np.asarray(raw, dtype=np.float32)[0]
    finite = np.isfinite(values).all(axis=0)
    boxes = values[:4]
    kept: list[tuple[float, int, int]] = []
    for class_id in class_ids:
        if not 0 <= class_id < contract.num_classes:
            continue
        scores = values[4 + class_id]
        for anchor in np.nonzero((scores >= confidence) & finite)[0]:
            kept.append((float(scores[anchor]), int(class_id), int(anchor)))
    kept.sort(key=lambda row: (-row[0], row[1], row[2]))
    detections: list[Detection] = []
    for score, class_id, anchor in kept:
        cx, cy, bw, bh = (float(v) for v in boxes[:, anchor])
        box = _to_roi_box(cx, cy, bw, bh, transform)
        if box is not None:
            detections.append(Detection(box=box, score=score, class_id=class_id))
    return _nms(detections, iou, max_detections)


def _to_roi_box(cx: float, cy: float, bw: float, bh: float, t: LetterboxTransform):
    x0 = (cx - bw / 2.0 - t.pad_left) / t.scale
    y0 = (cy - bh / 2.0 - t.pad_top) / t.scale
    x1 = (cx + bw / 2.0 - t.pad_left) / t.scale
    y1 = (cy + bh / 2.0 - t.pad_top) / t.scale
    x0, x1 = sorted((x0, x1))
    y0, y1 = sorted((y0, y1))
    x0 = float(np.clip(x0, 0.0, t.roi_width))
    x1 = float(np.clip(x1, 0.0, t.roi_width))
    y0 = float(np.clip(y0, 0.0, t.roi_height))
    y1 = float(np.clip(y1, 0.0, t.roi_height))
    if x1 - x0 <= 0.0 or y1 - y0 <= 0.0:
        return None
    return (x0, y0, x1, y1)


def _nms(detections: Sequence[Detection], iou_threshold: float, max_detections: int) -> list[Detection]:
    kept: list[Detection] = []
    for candidate in detections:
        if len(kept) >= max_detections:
            break
        if any(o.class_id == candidate.class_id and _iou(o.box, candidate.box) > iou_threshold for o in kept):
            continue
        kept.append(candidate)
    return kept


def _iou(a, b) -> float:
    overlap = max(0.0, min(a[2], b[2]) - max(a[0], b[0])) * max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    if overlap <= 0.0:
        return 0.0
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - overlap
    return overlap / union if union > 0.0 else 0.0
