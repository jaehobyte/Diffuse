"""Shaving shadow: reduce the grey-blue stubble cast inside the philtrum/chin/jaw allowance.

Reference: per connected component of the allowance, the median Lab of its brightest skin-like
pixels (low-frequency L at or above REFERENCE_PERCENTILE and b* at or above the component median —
warm, bright skin rather than shadow).

Detection: low-frequency region that is darker than the reference AND bluer/greyer (lower b*,
lower chroma). Grown beard is excluded conservatively: a neighbourhood where a large fraction of
pixels are very dark with strong fine-scale texture is dense hair, and it and a margin around it
never change. When in doubt the density threshold errs toward preserving.

Correction: low-frequency L and a/b toward the reference with caps; high-frequency texture (the
stubble dots themselves, pores) is kept. One feather inside the support.
"""

from __future__ import annotations

import threading
import time

import cv2
import numpy as np

from app.engines.base import EngineOutput
from app.engines.common import (
    blend_rgb,
    clean_mask,
    dilate,
    feather_alpha,
    feather_px_for,
    frequency_split,
    gaussian,
    lab_to_rgb,
    rgb_to_lab,
    synthetic_skin,
)

PARAMS_VERSION = 1

REFERENCE_PERCENTILE = 70.0
MIN_COMPONENT_PX = 64
LOW_SIGMA_FRACTION = 0.012
LOW_SIGMA_MIN = 2.5
SHADOW_DELTA_L = 3.0
SHADOW_DELTA_B = 3.0
#: Shadow chroma must be at most this fraction of the reference chroma (greyer).
SHADOW_CHROMA_RATIO = 0.92
#: Beard: very dark vs the reference, strong fine texture, and dense in the neighbourhood.
BEARD_DELTA_L = 22.0
BEARD_TEXTURE_L = 6.0
BEARD_DENSITY = 0.15
BEARD_WINDOW_FRACTION = 0.03
BEARD_WINDOW_MIN = 7
BEARD_MARGIN_PX = 3
OPEN_PX = 1
MIN_AREA_FRACTION = 0.0005
MIN_AREA_PX = 24
L_STRENGTH = 0.6
L_CAP = 8.0
AB_STRENGTH = 0.7
AB_CAP = 7.0
FEATHER_FRACTION = 0.01
FEATHER_MIN_PX = 2.0


def beard_map(L: np.ndarray, ref_L: np.ndarray, has_ref: np.ndarray, long_edge: int) -> np.ndarray:
    """Dense, dark, highly textured hair (bool), dilated by a safety margin."""
    fine = gaussian(L, 1.0)
    texture = np.sqrt(np.maximum(gaussian((L - fine) ** 2, 1.5), 0.0))
    local_L = gaussian(L, 1.5)
    hair = has_ref & (local_L < ref_L - BEARD_DELTA_L * 0.5) & (
        (L < ref_L - BEARD_DELTA_L) | (texture > BEARD_TEXTURE_L)
    )
    window = max(BEARD_WINDOW_MIN, int(BEARD_WINDOW_FRACTION * long_edge) | 1)
    density = cv2.blur(hair.astype(np.float32), (window, window))
    return dilate(has_ref & (density > BEARD_DENSITY), BEARD_MARGIN_PX)


class ShavingShadowEngine:
    kind = "shaving_shadow"
    version = f"shaving_shadow/tone@{PARAMS_VERSION}"

    def load(self) -> None:
        pass

    def warm_up(self) -> None:
        roi = synthetic_skin()
        self.run(roi, np.ones(roi.shape[:2], dtype=bool), threading.Event())

    def run(self, image: np.ndarray, allowed: np.ndarray, stop: threading.Event) -> EngineOutput | None:
        started = time.perf_counter()
        region = allowed & (image[:, :, 3] > 0)
        if not region.any():
            return None
        lab = rgb_to_lab(image)
        long_edge = max(image.shape[:2])
        sigma = max(LOW_SIGMA_MIN, LOW_SIGMA_FRACTION * long_edge)
        lows, highs = zip(*(frequency_split(lab[:, :, c], sigma) for c in range(3)))
        L_low, a_low, b_low = lows

        ref_L = np.zeros(region.shape, dtype=np.float32)
        ref_a = np.zeros_like(ref_L)
        ref_b = np.zeros_like(ref_L)
        has_ref = np.zeros(region.shape, dtype=bool)
        count, labels = cv2.connectedComponents(region.astype(np.uint8), connectivity=8)
        for label in range(1, count):
            component = labels == label
            if component.sum() < MIN_COMPONENT_PX:
                continue
            bright = (
                component
                & (L_low >= np.percentile(L_low[component], REFERENCE_PERCENTILE))
                & (b_low >= np.median(b_low[component]))
            )
            if not bright.any():
                continue
            ref_L[component] = np.median(L_low[bright])
            ref_a[component] = np.median(a_low[bright])
            ref_b[component] = np.median(b_low[bright])
            has_ref |= component

        beard = beard_map(lab[:, :, 0], ref_L, has_ref, long_edge)
        chroma_low = np.hypot(a_low, b_low)
        chroma_ref = np.hypot(ref_a, ref_b)
        shadow = (
            has_ref
            & (ref_L - L_low > SHADOW_DELTA_L)
            & (ref_b - b_low > SHADOW_DELTA_B)
            & (chroma_low < chroma_ref * SHADOW_CHROMA_RATIO)
        )
        min_area = max(MIN_AREA_PX, int(MIN_AREA_FRACTION * image.shape[0] * image.shape[1]))
        support = clean_mask(shadow & ~beard, OPEN_PX, min_area) & ~beard & region
        detect_ms = _ms(started)
        if not support.any():
            return None

        started = time.perf_counter()
        lift = np.clip((ref_L - L_low) * L_STRENGTH, 0.0, L_CAP)
        da = np.clip((ref_a - a_low) * AB_STRENGTH, -AB_CAP, AB_CAP)
        db = np.clip((ref_b - b_low) * AB_STRENGTH, -AB_CAP, AB_CAP)
        new_lab = np.stack(
            [np.clip(L_low + lift + highs[0], 0, 100), a_low + da + highs[1], b_low + db + highs[2]], axis=2
        )
        corrected = lab_to_rgb(new_lab)
        alpha = feather_alpha(support, feather_px_for(image.shape, FEATHER_FRACTION, FEATHER_MIN_PX))
        candidate = blend_rgb(image, corrected, alpha)
        return EngineOutput(candidate, support, {"detect": detect_ms, "correct": _ms(started)})


def _ms(started: float) -> float:
    return round((time.perf_counter() - started) * 1000.0, 1)
