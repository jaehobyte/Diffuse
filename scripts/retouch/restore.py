"""The adapter around a restoration engine: polarity, patches, and the protection guard.

specs/skin_retouch_pipeline.md §1.1 and §2. This is the part of the MI-GAN path that has nothing
to do with MI-GAN — it is what any patch-based restorer has to be wrapped in before its output may
touch a photograph, and it is what specs/skin_retouch_validation.md §4 asks to be tested without
weights. It mirrors what the Kotlin adapter will have to do; it is not the Kotlin adapter.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Protocol

import cv2
import numpy as np

from patches import Patch, plan_patches


class Engine(Protocol):
    """A restorer. [patch_model_mask] is MI-GAN polarity: 255 keeps, 0 is restored."""

    name: str
    version: str

    def restore(self, patch_rgb: np.ndarray, patch_model_mask: np.ndarray) -> np.ndarray: ...


def to_model_mask(defect: np.ndarray) -> np.ndarray:
    """App polarity (255 = change this) to MI-GAN's (255 = keep this).

    specs/skin_retouch_pipeline.md §1.1. Getting this backwards restores everything **except** the
    blemish, which is why it is a named function with a test rather than a `255 -` in a loop.
    """
    return np.where(defect > 0, np.uint8(0), np.uint8(255))


@dataclass
class Timings:
    """specs/skin_retouch_validation.md §5: the stages are reported apart, then together."""

    preprocess_ms: list[float] = field(default_factory=list)
    inference_ms: list[float] = field(default_factory=list)
    composite_ms: list[float] = field(default_factory=list)

    @property
    def total_ms(self) -> float:
        return sum(self.preprocess_ms) + sum(self.inference_ms) + sum(self.composite_ms)


@dataclass
class RestoreResult:
    image: np.ndarray
    patches: list[Patch]
    changed_pixels: int
    #: Pixels the engine changed outside the support before the guard put them back. Wrapper
    #: post-processing bleeds; §1.1 requires it to be measured rather than trusted.
    overreach_pixels: int
    #: Pixels outside the support still differing from the base afterwards. Must be 0.
    protection_violations: int
    timings: Timings


class RestoreRun:
    """One engine, applied patch by patch to one image.

    [model_size] is for engines that take a fixed square input. The official MI-GAN ONNX *pipeline*
    does its own crop and resize, so it runs with `None` and this class hands it the patch as-is.
    """

    def __init__(self, engine: Engine, *, model_size: int | None = None) -> None:
        self.engine = engine
        self.model_size = model_size

    def apply(
        self,
        image: np.ndarray,
        defect: np.ndarray,
        allowed: np.ndarray | None = None,
    ) -> RestoreResult:
        """[image] is RGB uint8 and is never written to; [defect] and [allowed] are 0/255 masks.

        Sizes are checked rather than broadcast: a mask of the wrong shape is a mistake in the
        case that produced it, and NumPy would silently stretch a 1xW row across the whole image
        (specs/skin_retouch_pipeline.md §2 — same size, same coordinates, or fail).
        """
        check_image(image)
        check_mask(defect, image, "defect")
        if allowed is not None:
            check_mask(allowed, image, "allowed")
        support = (defect > 0) if allowed is None else ((defect > 0) & (allowed > 0))
        timings = Timings()
        if not support.any():
            # specs/skin_retouch_pipeline.md §1.1: an empty mask does not call the model at all.
            return RestoreResult(image.copy(), [], 0, 0, 0, timings)

        base = image
        out = image.copy()
        support_mask = np.where(support, np.uint8(255), np.uint8(0))
        patches = plan_patches(support_mask)

        for patch in patches:
            box = patch.box
            started = time.perf_counter()
            # A copy, not a view: `np.ascontiguousarray` hands an already contiguous slice
            # straight back, and an engine that uses its input as scratch space would then be
            # writing into the caller's photograph. §1.1 forbids sharing the original tensor.
            patch_rgb = base[box.y0:box.y1, box.x0:box.x1].copy()
            patch_mask = to_model_mask(support_mask[box.y0:box.y1, box.x0:box.x1])
            model_rgb, model_mask = self._to_model_size(patch_rgb, patch_mask)
            timings.preprocess_ms.append(_ms_since(started))

            started = time.perf_counter()
            restored = self.engine.restore(model_rgb, model_mask)
            check_engine_output(restored, model_rgb)
            timings.inference_ms.append(_ms_since(started))

            started = time.perf_counter()
            out[box.y0:box.y1, box.x0:box.x1] = self._from_model_size(restored, box)
            timings.composite_ms.append(_ms_since(started))

        overreach = int(np.any(out != base, axis=2)[~support].sum())
        # §1.1: the wrapper's own post-processing may have touched skin outside the support, and
        # protected pixels are not the engine's to decide. Both go back to the base.
        out[~support] = base[~support]
        changed = int(np.any(out != base, axis=2).sum())
        violations = int(np.any(out != base, axis=2)[~support].sum())

        return RestoreResult(out, patches, changed, overreach, violations, timings)

    def _to_model_size(
        self, patch_rgb: np.ndarray, patch_mask: np.ndarray
    ) -> tuple[np.ndarray, np.ndarray]:
        if self.model_size is None:
            return patch_rgb, patch_mask
        size = (self.model_size, self.model_size)
        return (
            cv2.resize(patch_rgb, size, interpolation=cv2.INTER_AREA),
            # Nearest, so the mask stays binary: an interpolated edge would half-restore a pixel.
            cv2.resize(patch_mask, size, interpolation=cv2.INTER_NEAREST),
        )

    def _from_model_size(self, restored: np.ndarray, box) -> np.ndarray:
        """Only the resize this adapter performed is undone.

        specs/skin_retouch_pipeline.md §2: the adapter's own patch resize is inverted and verified.
        An engine whose output is some other size is not resized to fit — [check_engine_output] has
        already rejected it — because stretching an unexpected output would move every restored
        pixel off the coordinates it was asked about.
        """
        if self.model_size is None:
            return restored
        return cv2.resize(restored, (box.width, box.height), interpolation=cv2.INTER_LINEAR)


def check_image(image: np.ndarray) -> None:
    """RGB uint8, `HxWx3`. The engines and the guard all index the third axis as colour."""
    if image.ndim != 3 or image.shape[2] != 3:
        raise ValueError(f"image must be HxWx3 RGB, got shape {image.shape}")
    if image.dtype != np.uint8:
        raise ValueError(f"image must be uint8, got {image.dtype}")


def check_mask(mask: np.ndarray, image: np.ndarray, name: str) -> None:
    """A mask is 2-D uint8 at exactly the image's size — never a broadcastable near-miss."""
    if mask.ndim != 2:
        raise ValueError(f"{name} mask must be 2-D, got shape {mask.shape}")
    if mask.shape != image.shape[:2]:
        raise ValueError(f"{name} mask is {mask.shape}, image is {image.shape[:2]}")
    if mask.dtype != np.uint8:
        raise ValueError(f"{name} mask must be uint8, got {mask.dtype}")


def check_engine_output(restored: np.ndarray, model_rgb: np.ndarray) -> None:
    """The engine answers at the size it was asked, in the dtype it was asked (§2).

    Anything else is an incompatible model rather than something to resize into place: a wrong
    output size means the restored pixels do not correspond to the coordinates that were sent.
    """
    if restored.shape != model_rgb.shape:
        raise ValueError(
            f"engine returned shape {restored.shape}, expected {model_rgb.shape}"
        )
    if restored.dtype != np.uint8:
        raise ValueError(f"engine returned {restored.dtype}, expected uint8")


def _ms_since(started: float) -> float:
    return (time.perf_counter() - started) * 1000.0
