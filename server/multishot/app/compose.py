"""The multishot arithmetic and pixels, reproduced from the app's contract (specs/multishot.md §4–§6).

- `subject_alpha` is `MultiShotSubject.compose`: the binary mask stretched nearest-neighbour to the
  photo, one radius-2 box feather in working pixels, times the photo's alpha, in the same integers.
- `hero_slot` / `slots` / `slot_target` / `opacity` / `placement` / `on_canvas` / `is_clipped` /
  `bounds` / `anchor_of` are `MultiShotLayout` (D088) with scale 1 and rotation 0.
- `draw` is `MultiShotOp.draw`: the §4 transform, bilinear on premultiplied pixels, source-over,
  `subjectAlpha × opacity` once, clipped by the canvas.
- `protect` is `MultiShotOp.protect`: `lerp(composite, input, heroAlpha)` on premultiplied pixels
  in the same integers.

The canvas is premultiplied 8-bit RGBA (as an Android bitmap is); the work is done in row bands so
no whole-canvas float buffer is ever held. Same inputs and masks → the same bytes.
"""

from __future__ import annotations

import math

import numpy as np

FEATHER_RADIUS_PX = 2
WINDOW = 2 * FEATHER_RADIUS_PX + 1
WINDOW_AREA = WINDOW * WINDOW
FIRST_OPACITY = 0.25
LAST_OPACITY = 0.7
BAND_ROWS = 128

Bounds = tuple[float, float, float, float]  # left, top, right, bottom as fractions


# -- masks and subjects -------------------------------------------------------------------------


def stretch_nearest(mask: np.ndarray, width: int, height: int) -> np.ndarray:
    """`MultiShotSubject`'s stretch: target pixel x reads mask x·mw / W (floor)."""
    mh, mw = mask.shape
    ys = (np.arange(height, dtype=np.int64) * mh) // height
    xs = (np.arange(width, dtype=np.int64) * mw) // width
    return mask[ys[:, None], xs[None, :]]


def _box_sum(values: np.ndarray, axis: int) -> np.ndarray:
    """Sum over a 5-sample window along [axis], edge samples repeated (Kotlin `coerceIn`)."""
    pad = [(0, 0), (0, 0)]
    pad[axis] = (FEATHER_RADIUS_PX, FEATHER_RADIUS_PX)
    padded = np.pad(values, pad, mode="edge")
    length = values.shape[axis]
    total = np.zeros(values.shape, dtype=np.uint16)
    for shift in range(WINDOW):
        total += np.take(padded, np.arange(shift, shift + length), axis=axis)
    return total


def subject_alpha(photo_alpha: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """`photoAlpha × feather(mask)` at the photo's size, in `MultiShotSubject.compose`'s integers."""
    height, width = photo_alpha.shape
    covered = stretch_nearest(mask, width, height).astype(np.uint8)
    sums = _box_sum(_box_sum(covered, axis=1).astype(np.uint8), axis=0).astype(np.uint32)
    coverage = (sums * 255 + WINDOW_AREA // 2) // WINDOW_AREA
    return ((photo_alpha.astype(np.uint32) * coverage + 127) // 255).astype(np.uint8)


def subject(photo: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """The added photo's RGB with alpha `photoAlpha × feather(mask)`: never the whole photo or a crop."""
    out = photo.copy()
    out[..., 3] = subject_alpha(photo[..., 3], mask)
    return out


def bounds(mask: np.ndarray) -> Bounds | None:
    """Bounds of every set pixel, as fractions of the mask; None for an empty mask."""
    rows = np.flatnonzero(mask.any(axis=1))
    if rows.size == 0:
        return None
    cols = np.flatnonzero(mask.any(axis=0))
    height, width = mask.shape
    return (cols[0] / width, rows[0] / height, (cols[-1] + 1) / width, (rows[-1] + 1) / height)


def anchor_of(box: Bounds) -> tuple[float, float]:
    """The bottom centre of the bounds — the feet / ground, not the photo's centre."""
    left, _, right, bottom = box
    return ((left + right) / 2.0, bottom)


# -- layout (D088) -----------------------------------------------------------------------------


def hero_slot(total: int) -> int:
    return (total - 1) // 2


def slots(total: int) -> list[int]:
    """The slots the earlier inputs take, in input order: 0..N−1 without the hero's."""
    return [j for j in range(total) if j != hero_slot(total)]


def slot_target(hero: tuple[float, float], spacing: float, slot: int, total: int) -> tuple[float, float]:
    return (hero[0] + (slot - hero_slot(total)) * spacing / total, hero[1])


def opacity(index: int, count: int) -> float:
    """The time profile over the [count] added inputs, earliest first: 25 % → 70 % evenly."""
    if count <= 1:
        return 0.5
    return FIRST_OPACITY + (LAST_OPACITY - FIRST_OPACITY) * index / (count - 1)


def contain_scale(width: int, height: int, canvas_w: int, canvas_h: int) -> float:
    return min(canvas_w / width, canvas_h / height)


def placement(
    anchor: tuple[float, float], target: tuple[float, float], size: tuple[int, int], canvas: tuple[int, int]
) -> tuple[float, float]:
    """`placedAt`: the offset that puts the photo's [anchor] on [target] (scale 1, rotation 0)."""
    (w, h), (cw, ch) = size, canvas
    s = contain_scale(w, h, cw, ch)
    dx = (anchor[0] - 0.5) * w * s
    dy = (anchor[1] - 0.5) * h * s
    return (target[0] - 0.5 - dx / cw, target[1] - 0.5 - dy / ch)


def on_canvas(
    point: tuple[float, float], offset: tuple[float, float], size: tuple[int, int], canvas: tuple[int, int]
) -> tuple[float, float]:
    (w, h), (cw, ch) = size, canvas
    s = contain_scale(w, h, cw, ch)
    return (
        0.5 + offset[0] + (point[0] - 0.5) * w * s / cw,
        0.5 + offset[1] + (point[1] - 0.5) * h * s / ch,
    )


def is_clipped(box: Bounds, offset: tuple[float, float], size: tuple[int, int], canvas: tuple[int, int]) -> bool:
    """Some corner of the subject's bounds lands off the canvas."""
    left, top, right, bottom = box
    for corner in ((left, top), (right, top), (left, bottom), (right, bottom)):
        x, y = on_canvas(corner, offset, size, canvas)
        if x < 0.0 or x > 1.0 or y < 0.0 or y > 1.0:
            return True
    return False


# -- pixels ------------------------------------------------------------------------------------


def premultiply(rgba: np.ndarray) -> np.ndarray:
    """Straight → premultiplied 8-bit, rounded."""
    out = rgba.copy()
    alpha = rgba[..., 3:4].astype(np.uint32)
    out[..., :3] = ((rgba[..., :3].astype(np.uint32) * alpha + 127) // 255).astype(np.uint8)
    return out


def unpremultiply(rgba: np.ndarray) -> np.ndarray:
    """Premultiplied → straight 8-bit, rounded; a clear pixel is all zero."""
    out = np.zeros_like(rgba)
    for top in range(0, rgba.shape[0], BAND_ROWS):
        band = rgba[top : top + BAND_ROWS].astype(np.uint32)
        alpha = band[..., 3:4]
        safe = np.maximum(alpha, 1)
        colour = np.minimum((band[..., :3] * 255 + safe // 2) // safe, 255)
        colour = np.where(alpha == 0, 0, colour)
        out[top : top + BAND_ROWS, :, :3] = colour.astype(np.uint8)
        out[top : top + BAND_ROWS, :, 3] = band[..., 3].astype(np.uint8)
    return out


def _sample_axis(dst: int, origin: float, scale: float, src: int):
    """For each destination pixel along one axis: its two source taps and their weights.

    Pixel centres: destination centre d + ½ maps to source `(d + ½ − origin) / scale`, sampled
    bilinearly between source centres; a tap outside the source counts as clear. Only destination
    pixels with some source under them are returned.
    """
    centres = (np.arange(dst, dtype=np.float64) + 0.5 - origin) / scale - 0.5
    keep = np.flatnonzero((centres > -1.0) & (centres < src))
    position = centres[keep]
    first = np.floor(position).astype(np.int64)
    frac = position - first
    second = first + 1
    w_first = np.where((first >= 0) & (first < src), 1.0 - frac, 0.0)
    w_second = np.where((second >= 0) & (second < src), frac, 0.0)
    return keep, np.clip(first, 0, src - 1), np.clip(second, 0, src - 1), w_first, w_second


def draw(canvas: np.ndarray, subject_rgba: np.ndarray, offset: tuple[float, float], alpha: float) -> None:
    """Source-over of [subject_rgba] (straight) onto premultiplied [canvas] in place."""
    paint = min(255, max(0, math.floor(alpha * 255 + 0.5)))
    if paint == 0:
        return
    ch, cw = canvas.shape[:2]
    h, w = subject_rgba.shape[:2]
    s = contain_scale(w, h, cw, ch)
    origin_x = cw * (0.5 + offset[0]) - w / 2.0 * s
    origin_y = ch * (0.5 + offset[1]) - h / 2.0 * s
    xs, x0, x1, wx0, wx1 = _sample_axis(cw, origin_x, s, w)
    ys, y0, y1, wy0, wy1 = _sample_axis(ch, origin_y, s, h)
    if xs.size == 0 or ys.size == 0:
        return
    left, right = int(xs[0]), int(xs[-1]) + 1
    factor = np.float32(paint / 255.0)
    for start in range(0, ys.size, BAND_ROWS):
        rows = slice(start, start + BAND_ROWS)
        top, bottom = int(ys[rows][0]), int(ys[rows][-1]) + 1

        def premultiplied(index: np.ndarray) -> np.ndarray:
            src = subject_rgba[index].astype(np.float32)
            src[..., :3] *= src[..., 3:4] / 255.0
            return src

        upper, lower = premultiplied(y0[rows]), premultiplied(y1[rows])
        a = wx0.astype(np.float32)[None, :, None]
        b = wx1.astype(np.float32)[None, :, None]
        upper = upper[:, x0] * a + upper[:, x1] * b
        lower = lower[:, x0] * a + lower[:, x1] * b
        src = (upper * wy0.astype(np.float32)[rows, None, None] + lower * wy1.astype(np.float32)[rows, None, None]) * factor
        dst = canvas[top:bottom, left:right].astype(np.float32)
        out = src + dst * (1.0 - src[..., 3:4] / 255.0)
        canvas[top:bottom, left:right] = np.clip(np.floor(out + 0.5), 0, 255).astype(np.uint8)


def protect(composite: np.ndarray, hero_input: np.ndarray, weight: np.ndarray) -> np.ndarray:
    """`MultiShotOp.protect` on straight pixels: weight 0 keeps the composite, 255 the input, exactly."""
    out = np.empty_like(composite)
    for top in range(0, composite.shape[0], BAND_ROWS):
        rows = slice(top, top + BAND_ROWS)
        frm = composite[rows].astype(np.int64)
        to = hero_input[rows].astype(np.int64)
        w = weight[rows].astype(np.int64)[..., None]
        fa, ta = frm[..., 3:4], to[..., 3:4]
        alpha_sum = fa * (255 - w) + ta * w
        safe = np.maximum(alpha_sum, 1)
        colour = (frm[..., :3] * fa * (255 - w) + to[..., :3] * ta * w + alpha_sum // 2) // safe
        blended = np.concatenate([np.clip(colour, 0, 255), (alpha_sum + 127) // 255], axis=-1)
        blended = np.where(alpha_sum == 0, 0, blended)
        blended = np.where(w == 0, frm, np.where(w == 255, to, blended))
        out[rows] = blended.astype(np.uint8)
    return out
