"""Dark circles: lighten and neutralise under-eye darkness inside the app's under-eye allowance.

Reference: per connected component of the allowance (one per eye), the median Lab of the brighter
part of the component (low-frequency L at or above REFERENCE_PERCENTILE).

Detection: low-frequency L darker than the reference by DARK_DELTA_L, or darker by a smaller
amount while bluer (lower b*) than the reference. Lashes / eyeliner — very dark, high local
contrast pixels — are excluded (with a small margin) and never change.

Correction: move only the low-frequency L and a/b toward the reference with caps; the
high-frequency layer is added back unchanged. One feather inside the support.
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
#: Low-frequency split, as a fraction of the ROI long edge.
LOW_SIGMA_FRACTION = 0.01
LOW_SIGMA_MIN = 2.0
DARK_DELTA_L = 5.0
#: A smaller darkness counts when the area is also this much bluer (b* lower) than the reference.
DARK_DELTA_L_WITH_CAST = 2.5
BLUE_DELTA_B = 3.0
#: Lash / eyeliner protection: this dark relative to the reference and this much local contrast.
LASH_DELTA_L = 25.0
LASH_CONTRAST_L = 8.0
LASH_MARGIN_PX = 2
OPEN_PX = 1
MIN_AREA_FRACTION = 0.0003
MIN_AREA_PX = 16
L_STRENGTH = 0.8
L_CAP = 12.0
AB_STRENGTH = 0.7
AB_CAP = 6.0
FEATHER_FRACTION = 0.008
FEATHER_MIN_PX = 2.0


class DarkCirclesEngine:
    kind = "dark_circles"
    version = f"dark_circles/tone@{PARAMS_VERSION}"

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

        # Lashes / eyeliner: very dark relative to the fine-scale neighbourhood and to the region.
        local = gaussian(lab[:, :, 0], max(1.0, sigma * 0.5))
        contrast = np.abs(lab[:, :, 0] - local)

        ref_L = np.zeros(region.shape, dtype=np.float32)
        ref_a = np.zeros_like(ref_L)
        ref_b = np.zeros_like(ref_L)
        has_ref = np.zeros(region.shape, dtype=bool)
        count, labels = cv2.connectedComponents(region.astype(np.uint8), connectivity=8)
        for label in range(1, count):
            component = labels == label
            if component.sum() < MIN_COMPONENT_PX:
                continue
            values = L_low[component]
            bright = component & (L_low >= np.percentile(values, REFERENCE_PERCENTILE))
            ref_L[component] = np.median(L_low[bright])
            ref_a[component] = np.median(a_low[bright])
            ref_b[component] = np.median(b_low[bright])
            has_ref |= component

        lash = has_ref & (lab[:, :, 0] < ref_L - LASH_DELTA_L) & (contrast > LASH_CONTRAST_L)
        protected = dilate(lash, LASH_MARGIN_PX)

        darkness = ref_L - L_low
        bluer = ref_b - b_low
        dark = has_ref & ((darkness > DARK_DELTA_L) | ((darkness > DARK_DELTA_L_WITH_CAST) & (bluer > BLUE_DELTA_B)))
        min_area = max(MIN_AREA_PX, int(MIN_AREA_FRACTION * image.shape[0] * image.shape[1]))
        support = clean_mask(dark & ~protected, OPEN_PX, min_area) & ~protected & region
        detect_ms = _ms(started)
        if not support.any():
            return None

        started = time.perf_counter()
        lift = np.clip(darkness * L_STRENGTH, 0.0, L_CAP)
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
