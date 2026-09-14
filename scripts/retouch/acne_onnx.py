"""The exported acne detector behind one call, on desktop ONNX Runtime.

specs/skin_retouch_pipeline.md §1.1. The Python twin of the Android adapter: it loads the ONNX
next to its manifest, checks that the two describe the same file, and runs one ROI through
`detector.py`. Importing this module needs `onnxruntime`; `detector.py` and its tests do not.

The weights are never in this repository and this module never downloads them
(`work/tasks.md` Validation).
"""

from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Sequence

import numpy as np

from detector import (
    ACNE_CLASS_IDS,
    DEFAULT_CONF,
    DEFAULT_IOU,
    DEFAULT_MAX_DETECTIONS,
    Detection,
    DetectorContract,
    decode_detections,
    preprocess,
)


class ManifestMismatch(RuntimeError):
    """The ONNX beside the manifest is not the ONNX the manifest describes.

    `work/tasks.md` requirement 2 calls this an error and not a warning: every recorded box,
    threshold and timing belongs to one exported file, and a decoder configured from a manifest
    for some other export is decoding the wrong tensor layout.
    """


class OnnxAcneDetector:
    """One session, reused. [detect] takes an RGB (or RGBA) ROI and returns ROI-space boxes."""

    def __init__(
        self,
        manifest_path: str | Path,
        *,
        confidence: float = DEFAULT_CONF,
        iou: float = DEFAULT_IOU,
        max_detections: int = DEFAULT_MAX_DETECTIONS,
        class_ids: Sequence[int] = ACNE_CLASS_IDS,
        providers: Sequence[str] = ("CPUExecutionProvider",),
    ) -> None:
        import onnxruntime

        from acne_model import sha256_of

        self.manifest_path = Path(manifest_path)
        self.manifest = json.loads(self.manifest_path.read_text())
        model_path = self.manifest_path.parent / self.manifest["model"]["file"]
        if not model_path.exists():
            raise FileNotFoundError(model_path)
        digest = sha256_of(model_path)
        if digest != self.manifest["model"]["sha256"]:
            raise ManifestMismatch(
                f"{model_path.name} is sha256:{digest}, manifest says "
                f"sha256:{self.manifest['model']['sha256']}"
            )

        self.model_path = model_path
        started = time.perf_counter()
        self.session = onnxruntime.InferenceSession(str(model_path), providers=list(providers))
        self.load_ms = (time.perf_counter() - started) * 1000.0
        graph_inputs = [_info(t) for t in self.session.get_inputs()]
        graph_outputs = [_info(t) for t in self.session.get_outputs()]
        self.contract = DetectorContract.from_graph(
            graph_inputs, graph_outputs, num_classes=int(self.manifest["decode"]["num_classes"])
        )
        manifest_contract = DetectorContract.from_manifest(self.manifest)
        if manifest_contract != self.contract:
            raise ManifestMismatch(f"graph says {self.contract}, manifest says {manifest_contract}")

        self.confidence = confidence
        self.iou = iou
        self.max_detections = max_detections
        self.class_ids = tuple(class_ids)
        self.version = f"acne-yolov8m-onnx@sha256:{digest[:16]}"
        #: Stage timings of the most recent [detect], for the evaluation rows.
        self.last_timings: dict[str, float] = {}
        #: Which thresholds this run does **not** take from the manifest.
        #:
        #: The manifest's execution semantics are refused when they differ (above); its
        #: `postprocess_defaults` are a different kind of field — a threshold sweep is exactly
        #: what `work/tasks.md` requirement 11 asks for, so an override is allowed and recorded
        #: instead, and the evaluation report carries it beside the numbers it produced.
        self.setting_overrides = _overrides(
            self.manifest.get("postprocess_defaults", {}),
            confidence=confidence,
            nms_iou=iou,
            max_detections=max_detections,
        )
        allowed = list(self.manifest["decode"]["acne_class_ids"])
        if list(self.class_ids) != allowed:
            self.setting_overrides["class_ids"] = {"manifest": allowed, "used": list(self.class_ids)}

    def detect(self, roi: np.ndarray) -> list[Detection]:
        started = time.perf_counter()
        tensor, transform = preprocess(roi, self.contract.input_size)
        preprocess_ms = (time.perf_counter() - started) * 1000.0

        started = time.perf_counter()
        raw = self.session.run(None, {self.contract.input_name: tensor})[0]
        inference_ms = (time.perf_counter() - started) * 1000.0

        started = time.perf_counter()
        detections = decode_detections(
            raw,
            self.contract,
            transform,
            confidence=self.confidence,
            iou=self.iou,
            max_detections=self.max_detections,
            class_ids=self.class_ids,
        )
        decode_ms = (time.perf_counter() - started) * 1000.0

        self.last_timings = {
            "preprocess_ms": preprocess_ms,
            "inference_ms": inference_ms,
            "decode_ms": decode_ms,
            "total_ms": preprocess_ms + inference_ms + decode_ms,
        }
        #: `work/tasks.md` requirement 6: a run that hit the cap is a run whose recall figure is
        #: capped too, so the evaluation has to be able to see it.
        self.last_timings["hit_max_detections"] = float(len(detections) >= self.max_detections)
        return detections


def _overrides(defaults: dict, **used) -> dict:
    """The settings that differ from the manifest's recorded defaults, `{manifest, used}` each."""
    return {
        key: {"manifest": defaults[key], "used": value}
        for key, value in used.items()
        if key in defaults and defaults[key] != value
    }


def _info(tensor) -> dict:
    return {
        "name": tensor.name,
        "shape": [d if isinstance(d, int) else str(d) for d in tensor.shape],
        "dtype": tensor.type,
    }
