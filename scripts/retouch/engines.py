"""The engines SR1-A compares, behind one call.

specs/skin_retouch_pipeline.md §1: MI-GAN is the first candidate for blemish restoration and
OpenCV inpainting is the baseline run on the same masks. Neither is adopted; this file is how they
are measured, not a decision about which one ships.
"""

from __future__ import annotations

import hashlib
from pathlib import Path

import cv2
import numpy as np


class OpenCvInpaintEngine:
    """The baseline. OpenCV's polarity is the app's — non-zero is what gets repainted — so the
    MI-GAN mask this adapter passes around has to be turned back over here."""

    def __init__(self, method: str = "telea", radius: int = 3) -> None:
        self.method = method
        self.radius = radius
        self.name = f"opencv-{method}"
        self.version = f"opencv-{cv2.__version__}-{method}-r{radius}"

    def restore(self, patch_rgb: np.ndarray, patch_model_mask: np.ndarray) -> np.ndarray:
        flag = cv2.INPAINT_TELEA if self.method == "telea" else cv2.INPAINT_NS
        holes = np.where(patch_model_mask > 0, np.uint8(0), np.uint8(255))
        bgr = cv2.inpaint(cv2.cvtColor(patch_rgb, cv2.COLOR_RGB2BGR), holes, self.radius, flag)
        return cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)


class MiganOnnxEngine:
    """MI-GAN through its official exported ONNX pipeline.

    The pipeline graph takes a uint8 RGB image and a uint8 grayscale mask where **255 is known and
    0 is restored**, and does its own crop, resize and blending (§1.1). That is why nothing here
    normalises, resizes or feathers: doing any of it twice is the failure mode the spec names.

    The weights are not in this repository and are never committed. `--model` points at a local
    file; [version] carries its SHA-256 so a result can be traced to the exact checkpoint.
    """

    def __init__(self, model_path: str | Path) -> None:
        import onnxruntime  # Imported here so the baseline engine runs without it installed.

        self.path = Path(model_path)
        self.session = onnxruntime.InferenceSession(
            str(self.path), providers=["CPUExecutionProvider"]
        )
        inputs = {i.name: i for i in self.session.get_inputs()}
        if len(inputs) != 2:
            raise ValueError(f"expected an image and a mask input, got {list(inputs)}")
        self.image_input, self.mask_input = list(inputs)
        self.name = f"migan-onnx-{self.path.stem}"
        self.version = f"{self.path.name}@sha256:{sha256_of(self.path)[:16]}"

    def restore(self, patch_rgb: np.ndarray, patch_model_mask: np.ndarray) -> np.ndarray:
        image = np.expand_dims(np.transpose(patch_rgb, (2, 0, 1)), 0).astype(np.uint8)
        mask = np.expand_dims(np.expand_dims(patch_model_mask, 0), 0).astype(np.uint8)
        output = self.session.run(
            None, {self.image_input: image, self.mask_input: mask}
        )[0]
        result = np.squeeze(output, 0)
        if result.shape[0] in (1, 3):
            result = np.transpose(result, (1, 2, 0))
        return np.clip(result, 0, 255).astype(np.uint8)


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def engine_from_spec(spec: str):
    """`opencv-telea`, `opencv-ns`, or `migan-onnx:/path/to/migan_pipeline_v2.onnx`."""
    if spec.startswith("migan-onnx:"):
        return MiganOnnxEngine(spec.split(":", 1)[1])
    if spec in ("opencv-telea", "opencv-ns"):
        return OpenCvInpaintEngine(spec.removeprefix("opencv-"))
    raise ValueError(f"unknown engine: {spec}")
