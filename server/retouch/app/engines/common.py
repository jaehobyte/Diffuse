"""Pieces shared by the engines: the single feather, colour space, masked blurs, cleanup."""

from __future__ import annotations

import cv2
import numpy as np


def feather_alpha(support: np.ndarray, feather_px: float) -> np.ndarray:
    """Soft alpha inside [support] only: ramps from ~0 at the support edge to 1 [feather_px] in.

    Outside the binary support the alpha is exactly 0, so a candidate blended with it equals the
    base there. This is the one feather §2/§5 allow; nothing downstream feathers again.
    """
    binary = support.astype(np.uint8)
    if not binary.any():
        return np.zeros(support.shape, dtype=np.float32)
    # Distance of each support pixel to the nearest non-support pixel (1 at the edge ring).
    # Padding with zeros makes the ROI border count as an edge too.
    padded = cv2.copyMakeBorder(binary, 1, 1, 1, 1, cv2.BORDER_CONSTANT, value=0)
    dist = cv2.distanceTransform(padded, cv2.DIST_L2, 3)[1:-1, 1:-1]
    alpha = np.clip(dist / (float(feather_px) + 1.0), 0.0, 1.0).astype(np.float32)
    alpha[~support] = 0.0
    return alpha


def blend_rgb(base: np.ndarray, corrected_rgb: np.ndarray, alpha: np.ndarray) -> np.ndarray:
    """RGBA base + alpha·(corrected − base) on RGB; base alpha is kept. Returns a new array."""
    out = base.copy()
    b = base[:, :, :3].astype(np.float32)
    c = corrected_rgb.astype(np.float32)
    mixed = b + alpha[:, :, None] * (c - b)
    rgb = np.clip(np.rint(mixed), 0, 255).astype(np.uint8)
    inside = alpha > 0
    out[:, :, :3][inside] = rgb[inside]
    return out


def feather_px_for(shape: tuple[int, ...], fraction: float, minimum: float) -> float:
    """Feather radius proportional to the ROI's long edge (§3: feather scales with the ROI)."""
    return max(minimum, fraction * max(shape[0], shape[1]))


def rgb_to_lab(rgba: np.ndarray) -> np.ndarray:
    """uint8 RGB(A) → float32 Lab (L 0..100, a/b roughly −127..127)."""
    rgb = rgba[:, :, :3].astype(np.float32) / 255.0
    return cv2.cvtColor(rgb, cv2.COLOR_RGB2Lab)


def lab_to_rgb(lab: np.ndarray) -> np.ndarray:
    """float32 Lab → uint8 RGB, clipped."""
    rgb = cv2.cvtColor(lab.astype(np.float32), cv2.COLOR_Lab2RGB)
    return np.clip(np.rint(rgb * 255.0), 0, 255).astype(np.uint8)


def gaussian(channel: np.ndarray, sigma: float) -> np.ndarray:
    return cv2.GaussianBlur(channel.astype(np.float32), (0, 0), sigmaX=float(sigma), sigmaY=float(sigma))


def masked_gaussian(channel: np.ndarray, weight: np.ndarray, sigma: float) -> tuple[np.ndarray, np.ndarray]:
    """Normalised convolution: the blur of [channel] using only pixels where [weight] > 0.

    Returns (estimate, coverage). Where coverage is ~0 the estimate is meaningless; callers only
    read it inside the weighted region, where coverage is bounded away from zero.
    """
    w = weight.astype(np.float32)
    num = channel.astype(np.float32) * w
    height, width = w.shape
    # A wide Gaussian is only low frequencies: evaluate it on a downscaled grid (sigma ~4 px there)
    # and upsample, instead of convolving a kernel hundreds of pixels wide at full resolution.
    factor = max(1.0, sigma / 4.0)
    if factor > 1.5:
        small = (max(1, int(round(width / factor))), max(1, int(round(height / factor))))
        num_s = gaussian(cv2.resize(num, small, interpolation=cv2.INTER_AREA), sigma / factor)
        den_s = gaussian(cv2.resize(w, small, interpolation=cv2.INTER_AREA), sigma / factor)
        num = cv2.resize(num_s, (width, height), interpolation=cv2.INTER_LINEAR)
        den = cv2.resize(den_s, (width, height), interpolation=cv2.INTER_LINEAR)
    else:
        num, den = gaussian(num, sigma), gaussian(w, sigma)
    return num / np.maximum(den, 1e-6), den


def clean_mask(mask: np.ndarray, open_px: int, min_area: int) -> np.ndarray:
    """Morphological opening and removal of connected components smaller than [min_area]."""
    binary = mask.astype(np.uint8)
    if open_px > 0:
        kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * open_px + 1, 2 * open_px + 1))
        binary = cv2.morphologyEx(binary, cv2.MORPH_OPEN, kernel)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(binary, connectivity=8)
    keep = np.zeros(count, dtype=bool)
    keep[1:] = stats[1:, cv2.CC_STAT_AREA] >= min_area
    return keep[labels]


def dilate(mask: np.ndarray, px: int) -> np.ndarray:
    if px <= 0:
        return mask.astype(bool)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * px + 1, 2 * px + 1))
    return cv2.dilate(mask.astype(np.uint8), kernel) > 0


def frequency_split(channel: np.ndarray, sigma: float) -> tuple[np.ndarray, np.ndarray]:
    """(low, high) with low + high == channel. Corrections move `low` and keep `high`."""
    low = gaussian(channel, sigma)
    return low, channel.astype(np.float32) - low


def synthetic_skin(height: int = 96, width: int = 96, seed: int = 0) -> np.ndarray:
    """Flat, lightly textured skin-coloured RGBA used by the tone engines' warm-up."""
    rng = np.random.default_rng(seed)
    base = np.array([205, 160, 135], dtype=np.float32)
    noise = rng.normal(0.0, 3.0, (height, width, 1)).astype(np.float32)
    rgb = np.clip(base + noise, 0, 255).astype(np.uint8)
    alpha = np.full((height, width, 1), 255, dtype=np.uint8)
    return np.concatenate([rgb, alpha], axis=2)
