"""Blemish: acne detector → defect mask → context patches → MI-GAN → guard → one feather.

Detection and restoration are separate models (specs/skin_retouch_pipeline.md §1.1). The detector
is the exported `Tinny-Robot/acne` YOLOv8m (`acne_640_fp32.onnx`, checked against its manifest);
restoration is the official MI-GAN 512 Places2 ONNX pipeline (`migan_pipeline_v2.onnx`, checked
against a pinned digest). A missing file or a wrong digest fails `load` — there is no fallback.
"""

from __future__ import annotations

import hashlib
import json
import logging
import threading
import time
from pathlib import Path
from typing import Callable

import cv2
import numpy as np

from app.engines.base import EngineError, EngineOutput
from app.engines.common import blend_rgb, feather_alpha, feather_px_for
from app.engines.detector import (
    ACNE_CLASS_IDS,
    Detection,
    DetectorContract,
    decode_detections,
    preprocess,
)
from app.engines.patches import plan_patches

log = logging.getLogger("retouch.engine.blemish")

PARAMS_VERSION = 1

DETECTOR_MANIFEST = "acne_640_fp32.manifest.json"
MIGAN_FILE = "migan_pipeline_v2.onnx"
MIGAN_SHA256 = "6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b"

CONFIDENCE = 0.25
NMS_IOU = 0.45
MAX_DETECTIONS = 100
#: Each box becomes the ellipse inscribed in it, grown by this fraction of the box's short side.
DILATE_FRACTION = 0.15
#: Feather radius: fraction of the ROI long edge, with a floor, applied once inside the support.
FEATHER_FRACTION = 0.003
FEATHER_MIN_PX = 1.5


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def to_model_mask(defect: np.ndarray) -> np.ndarray:
    """App polarity (non-zero = change) → MI-GAN polarity (255 = keep, 0 = restore)."""
    return np.where(defect > 0, np.uint8(0), np.uint8(255))


def defect_mask(detections: list[Detection], allowed: np.ndarray, alpha: np.ndarray) -> np.ndarray:
    """Filled ellipse inscribed in each box, slightly dilated, ∩ allowed ∩ alpha > 0 (bool)."""
    height, width = allowed.shape
    canvas = np.zeros((height, width), dtype=np.uint8)
    for det in detections:
        x0, y0, x1, y1 = det.box
        grow = DILATE_FRACTION * min(x1 - x0, y1 - y0)
        centre = (int(round((x0 + x1) / 2.0 - 0.5)), int(round((y0 + y1) / 2.0 - 0.5)))
        axes = (max(1, int(round((x1 - x0) / 2.0 + grow))), max(1, int(round((y1 - y0) / 2.0 + grow))))
        cv2.ellipse(canvas, centre, axes, 0.0, 0.0, 360.0, 255, thickness=-1)
    return (canvas > 0) & allowed & (alpha > 0)


Restorer = Callable[[np.ndarray, np.ndarray], np.ndarray]


def restore_patches(
    base: np.ndarray, support: np.ndarray, restorer: Restorer, stop: threading.Event | None = None
) -> tuple[np.ndarray, int]:
    """Run [restorer] over context patches of [support] on the same base; guard outside support.

    [restorer] takes an RGB uint8 patch (a copy) and an MI-GAN-polarity mask and must return an RGB
    patch of the same size. Patches are composited in plan order (top-to-bottom, left-to-right),
    each writing only its own support pixels, so overlaps resolve deterministically. Returns the
    restored RGB (H×W×3, equal to base outside support) and the number of patches run.
    """
    base_rgb = base[:, :, :3]
    out = base_rgb.copy()
    support_u8 = np.where(support, np.uint8(255), np.uint8(0))
    patches = plan_patches(support_u8)
    for patch in patches:
        if stop is not None and stop.is_set():
            raise EngineError("stopped")
        b = patch.box
        patch_rgb = base_rgb[b.y0 : b.y1, b.x0 : b.x1].copy()
        patch_mask = to_model_mask(support_u8[b.y0 : b.y1, b.x0 : b.x1])
        restored = restorer(patch_rgb, patch_mask)
        if restored.shape != patch_rgb.shape or restored.dtype != np.uint8:
            raise EngineError("restorer returned a patch of the wrong size or dtype")
        inside = support[b.y0 : b.y1, b.x0 : b.x1]
        out[b.y0 : b.y1, b.x0 : b.x1][inside] = restored[inside]
    # Protection guard: the wrapper's own blending bleeds outside the mask.
    out[~support] = base_rgb[~support]
    return out, len(patches)


def providers_for(execution_provider: str, gpu_mem_limit_mb: int) -> list:
    if execution_provider == "cpu":
        return ["CPUExecutionProvider"]
    return [
        (
            "CUDAExecutionProvider",
            {
                "gpu_mem_limit": int(gpu_mem_limit_mb) * 1024 * 1024,
                "arena_extend_strategy": "kSameAsRequested",
                # MI-GAN is run at many patch sizes; exhaustive search would re-benchmark each.
                "cudnn_conv_algo_search": "HEURISTIC",
            },
        ),
        "CPUExecutionProvider",
    ]


def make_session(path: Path, execution_provider: str, gpu_mem_limit_mb: int):
    import onnxruntime as ort

    if execution_provider != "cpu" and hasattr(ort, "preload_dlls"):
        try:
            ort.preload_dlls()
        except Exception:  # noqa: BLE001 - the CUDA EP check below reports the outcome
            pass
    options = ort.SessionOptions()
    options.log_severity_level = 3
    session = ort.InferenceSession(
        str(path), sess_options=options, providers=providers_for(execution_provider, gpu_mem_limit_mb)
    )
    active = session.get_providers()[0]
    if execution_provider != "cpu" and active != "CUDAExecutionProvider":
        log.warning("CUDA execution provider unavailable for %s; running on %s", path.name, active)
    return session, active


class BlemishEngine:
    kind = "blemish"

    def __init__(self, model_dir: Path, execution_provider: str = "auto", gpu_mem_limit_mb: int = 2048) -> None:
        self.model_dir = Path(model_dir)
        self.execution_provider = execution_provider
        self.gpu_mem_limit_mb = gpu_mem_limit_mb
        self._version = ""
        self.detector = None
        self.migan = None
        self.contract: DetectorContract | None = None
        self.active_providers: dict[str, str] = {}

    @property
    def version(self) -> str:
        return self._version

    def load(self) -> None:
        manifest_path = self.model_dir / DETECTOR_MANIFEST
        manifest = json.loads(manifest_path.read_text())
        detector_path = self.model_dir / manifest["model"]["file"]
        detector_sha = sha256_of(detector_path)
        if detector_sha != manifest["model"]["sha256"]:
            raise EngineError(f"{detector_path.name} digest does not match its manifest")
        migan_path = self.model_dir / MIGAN_FILE
        migan_sha = sha256_of(migan_path)
        if migan_sha != MIGAN_SHA256:
            raise EngineError(f"{MIGAN_FILE} digest does not match the pinned digest")

        contract = DetectorContract.from_manifest(manifest)
        detector, det_ep = make_session(detector_path, self.execution_provider, self.gpu_mem_limit_mb)
        graph = DetectorContract.from_graph(
            [{"name": t.name, "shape": list(t.shape), "dtype": t.type} for t in detector.get_inputs()],
            [{"name": t.name, "shape": list(t.shape), "dtype": t.type} for t in detector.get_outputs()],
            num_classes=contract.num_classes,
        )
        if graph != contract:
            raise EngineError("detector graph does not match its manifest")
        migan, migan_ep = make_session(migan_path, self.execution_provider, self.gpu_mem_limit_mb)
        names = [i.name for i in migan.get_inputs()]
        if names != ["image", "mask"]:
            raise EngineError("MI-GAN pipeline inputs are not [image, mask]")

        self.detector, self.migan, self.contract = detector, migan, contract
        self.active_providers = {"detector": det_ep, "migan": migan_ep}
        self._version = f"blemish/acne-yolov8m-640@{detector_sha[:8]}+migan512@{migan_sha[:8]}/c{PARAMS_VERSION}"
        log.info("blemish engine loaded: detector=%s migan=%s", det_ep, migan_ep)

    def warm_up(self) -> None:
        roi = np.full((96, 96, 4), 180, dtype=np.uint8)
        roi[:, :, 3] = 255
        self.detect(roi)
        mask = np.full((64, 64), 255, dtype=np.uint8)
        mask[24:40, 24:40] = 0
        self.restore(roi[:64, :64, :3].copy(), mask)

    def detect(self, rgba: np.ndarray) -> list[Detection]:
        tensor, transform = preprocess(rgba, self.contract.input_size)
        raw = self.detector.run(None, {self.contract.input_name: tensor})[0]
        return decode_detections(
            raw,
            self.contract,
            transform,
            confidence=CONFIDENCE,
            iou=NMS_IOU,
            max_detections=MAX_DETECTIONS,
            class_ids=ACNE_CLASS_IDS,
        )

    def restore(self, patch_rgb: np.ndarray, model_mask: np.ndarray) -> np.ndarray:
        image = np.ascontiguousarray(np.transpose(patch_rgb, (2, 0, 1))[None])
        mask = np.ascontiguousarray(model_mask[None, None])
        result = self.migan.run(None, {"image": image, "mask": mask})[0]
        result = np.squeeze(result, 0)
        if result.ndim != 3 or result.shape[0] != 3:
            raise EngineError("MI-GAN returned an unexpected shape")
        return np.clip(np.transpose(result, (1, 2, 0)), 0, 255).astype(np.uint8)

    def run(self, image: np.ndarray, allowed: np.ndarray, stop: threading.Event) -> EngineOutput | None:
        timings: dict[str, float] = {}
        started = time.perf_counter()
        detections = self.detect(image)
        support = defect_mask(detections, allowed, image[:, :, 3])
        timings["detect"] = _ms(started)
        if not support.any():
            return None
        started = time.perf_counter()
        restored, count = restore_patches(image, support, self.restore, stop)
        timings["restore"] = _ms(started)
        started = time.perf_counter()
        alpha = feather_alpha(support, feather_px_for(image.shape, FEATHER_FRACTION, FEATHER_MIN_PX))
        candidate = blend_rgb(image, restored, alpha)
        timings["composite"] = _ms(started)
        return EngineOutput(candidate=candidate, support=support, timings=timings)


def _ms(started: float) -> float:
    return round((time.perf_counter() - started) * 1000.0, 1)
