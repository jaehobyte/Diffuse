"""Shine: detect specular shine on allowed skin and pull its low-frequency lightness down.

Detection: in Lab, a pixel is shine when its lightness is well above a local skin estimate (a
masked, wide blur computed with shine candidates excluded) and its chroma is not above the local
skin chroma (specular reflection desaturates). Components are opened and small ones dropped.

Correction: frequency separation on L. Only the low-frequency layer is moved toward the local skin
estimate (capped); the high-frequency layer (pores, texture) is added back unchanged. a/b are pulled
part-way toward the local skin chroma. Pixels outside the detected support are never touched.
"""

from __future__ import annotations

import threading
import time

import numpy as np

from app.engines.base import EngineOutput
from app.engines.common import (
    blend_rgb,
    clean_mask,
    dilate,
    feather_alpha,
    feather_px_for,
    frequency_split,
    lab_to_rgb,
    masked_gaussian,
    rgb_to_lab,
    synthetic_skin,
)

PARAMS_VERSION = 1

#: Local skin estimate blur, as a fraction of the ROI long edge.
SKIN_SIGMA_FRACTION = 0.08
SKIN_SIGMA_MIN = 8.0
#: Passes of the skin estimate, each excluding the previous pass's bright outliers.
SKIN_PASSES = 3
#: Lightness above the local skin estimate (L units, 0..100) that counts as shine.
SHINE_DELTA_L = 9.0
#: Shine chroma must not exceed this multiple of the local skin chroma.
SHINE_CHROMA_RATIO = 1.0
#: Minimum component area as a fraction of ROI pixels (with an absolute floor).
MIN_AREA_FRACTION = 0.0002
MIN_AREA_PX = 12
OPEN_PX = 1
#: Hysteresis: the support extends over pixels this far above the skin estimate that lie within
#: RIM_FRACTION of the ROI long edge of detected shine, so the correction ramps out over the
#: shine's soft rim instead of stopping at the hard threshold (bounded so it cannot spread).
RIM_DELTA_L = 3.0
RIM_FRACTION = 0.015
#: Grow the detected shine by this many px so its soft rim is included, before the feather.
GROW_PX = 2
#: Texture/base split, as a fraction of the ROI long edge.
DETAIL_SIGMA_FRACTION = 0.006
DETAIL_SIGMA_MIN = 1.5
#: How far the low-frequency L moves toward the skin estimate, and the cap in L units.
L_STRENGTH = 0.85
L_CAP = 30.0
CHROMA_PULL = 0.5
FEATHER_FRACTION = 0.006
FEATHER_MIN_PX = 2.0


class ShineEngine:
    kind = "shine"
    version = f"shine/tone@{PARAMS_VERSION}"

    def load(self) -> None:
        pass

    def warm_up(self) -> None:
        roi = synthetic_skin()
        self.run(roi, np.ones(roi.shape[:2], dtype=bool), threading.Event())

    def run(self, image: np.ndarray, allowed: np.ndarray, stop: threading.Event) -> EngineOutput | None:
        started = time.perf_counter()
        skin = allowed & (image[:, :, 3] > 0)
        if not skin.any():
            return None
        lab = rgb_to_lab(image)
        L, a, b = lab[:, :, 0], lab[:, :, 1], lab[:, :, 2]
        sigma = max(SKIN_SIGMA_MIN, SKIN_SIGMA_FRACTION * max(image.shape[:2]))
        chroma = np.hypot(a, b)

        # Each pass excludes the previous pass's bright outliers, so a large shiny patch does not
        # raise its own reference.
        reference = skin
        for _ in range(SKIN_PASSES):
            L_skin, _ = masked_gaussian(L, reference, sigma)
            reference = skin & (L <= L_skin + SHINE_DELTA_L * 0.5)
            if not reference.any():
                return None
        L_skin, _ = masked_gaussian(L, reference, sigma)
        a_skin, _ = masked_gaussian(a, reference, sigma)
        b_skin, _ = masked_gaussian(b, reference, sigma)
        chroma_skin = np.hypot(a_skin, b_skin)

        detail_sigma = max(DETAIL_SIGMA_MIN, DETAIL_SIGMA_FRACTION * max(image.shape[:2]))
        L_low, L_high = frequency_split(L, detail_sigma)
        shine = skin & (L_low - L_skin > SHINE_DELTA_L) & (chroma <= chroma_skin * SHINE_CHROMA_RATIO + 2.0)
        min_area = max(MIN_AREA_PX, int(MIN_AREA_FRACTION * image.shape[0] * image.shape[1]))
        shine = clean_mask(shine, OPEN_PX, min_area)
        rim_px = max(GROW_PX, int(round(RIM_FRACTION * max(image.shape[:2]))))
        rim = skin & (L_low - L_skin > RIM_DELTA_L) & dilate(shine, rim_px)
        support = dilate(shine | rim, GROW_PX) & skin
        detect_ms = _ms(started)
        if not support.any():
            return None

        started = time.perf_counter()
        excess = np.maximum(L_low - L_skin, 0.0)
        drop = np.minimum(excess * L_STRENGTH, L_CAP)
        new_L = (L_low - drop) + L_high
        pull = CHROMA_PULL * np.clip(drop / max(L_CAP, 1e-6) * 2.0, 0.0, 1.0)
        a_low, a_high = frequency_split(a, detail_sigma)
        b_low, b_high = frequency_split(b, detail_sigma)
        new_a = a_low + pull * (a_skin - a_low) + a_high
        new_b = b_low + pull * (b_skin - b_low) + b_high
        corrected = lab_to_rgb(np.stack([np.clip(new_L, 0, 100), new_a, new_b], axis=2))
        alpha = feather_alpha(support, feather_px_for(image.shape, FEATHER_FRACTION, FEATHER_MIN_PX))
        candidate = blend_rgb(image, corrected, alpha)
        return EngineOutput(candidate, support, {"detect": detect_ms, "correct": _ms(started)})


def _ms(started: float) -> float:
    return round((time.perf_counter() - started) * 1000.0, 1)
