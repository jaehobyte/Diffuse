"""One request's work, run in a worker thread: validate → extract → lay out → composite → encode.

Every image is validated before SAM 3 is called. Photos are read from the spool one at a time; only
the small masks and the canvas live across steps. The last input is the background and the hero.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np

from app import compose
from app.config import CONTRACT_VERSION
from app.errors import ApiError
from app.images import encode_png, extraction_png, load, normalize
from app.options import Options
from app.sam import Extractor, JobContext, bad_upstream


@dataclass
class Job:
    request_id: str
    spool: Path
    images: list[Path]
    options: Options
    ctx: JobContext
    max_source_pixels: int


@dataclass
class Outcome:
    png: bytes
    metadata: dict


def _round(value: float) -> float:
    return round(float(value), 6)


def choose(candidates: list[np.ndarray], size: tuple[int, int], index: int) -> np.ndarray:
    """Exactly one non-empty mask is the subject; none or several is an explicit failure."""
    width, height = size
    if any(mask.shape != (height, width) for mask in candidates):
        raise bad_upstream()
    found = [mask for mask in candidates if mask.any()]
    if not found:
        raise ApiError(422, "subject_not_found", index)
    if len(found) > 1:
        raise ApiError(422, "ambiguous_subject", index)
    return found[0]


def run(job: Job, extractor: Extractor) -> Outcome:
    ctx = job.ctx
    total = len(job.images)
    normalized = [job.spool / f"normalized-{i}.npy" for i in range(total)]

    # 1. Every input is decoded and normalised before anything is sent to SAM 3.
    sizes: list[tuple[int, int]] = []
    for index, source in enumerate(job.images):
        ctx.check()
        sizes.append(normalize(source, index, normalized[index], job.max_source_pixels))
        source.unlink(missing_ok=True)

    # 2. One SAM 3 session per image, in input order.
    masks: list[np.ndarray] = []
    for index in range(total):
        ctx.check()
        png, width, height = extraction_png(load(normalized[index]))
        candidates = extractor.extract(png, (width, height), job.options.subject_points[index], ctx)
        masks.append(choose(candidates, (width, height), index))
    ctx.check()

    # 3. Layout (D088) and source-over in input order, onto the last photo.
    hero = total - 1
    canvas_size = sizes[hero]
    hero_box = compose.bounds(masks[hero])
    assert hero_box is not None  # choose() never returns an empty mask
    hero_anchor = compose.anchor_of(hero_box)
    added = total - 1
    hero_input = load(normalized[hero])
    canvas = compose.premultiply(hero_input)
    del hero_input
    shots: list[dict] = []
    warnings: list[dict] = []
    for index, slot in enumerate(compose.slots(total)):
        ctx.check()
        box = compose.bounds(masks[index])
        assert box is not None
        target = compose.slot_target(hero_anchor, job.options.spacing, slot, total)
        offset = compose.placement(compose.anchor_of(box), target, sizes[index], canvas_size)
        placed = compose.on_canvas(compose.anchor_of(box), offset, sizes[index], canvas_size)
        alpha = compose.opacity(index, added)
        compose.draw(canvas, compose.subject(load(normalized[index]), masks[index]), offset, alpha)
        if compose.is_clipped(box, offset, sizes[index], canvas_size):
            warnings.append({"code": "subject_clipped", "input_index": index})
        shots.append(
            {
                "input_index": index,
                "slot_index": slot,
                "anchor": [_round(placed[0]), _round(placed[1])],
                "opacity": _round(alpha),
                "is_hero": False,
            }
        )
    ctx.check()

    # 4. The hero's own pixels come back inside its feathered mask; it is never moved or drawn twice.
    hero_input = load(normalized[hero])
    weight = compose.subject_alpha(hero_input[..., 3], masks[hero])
    result = compose.protect(compose.unpremultiply(canvas), hero_input, weight)
    del canvas, hero_input, weight
    shots.append(
        {
            "input_index": hero,
            "slot_index": compose.hero_slot(total),
            "anchor": [_round(hero_anchor[0]), _round(hero_anchor[1])],
            "opacity": 1.0,
            "is_hero": True,
        }
    )
    ctx.check()
    png = encode_png(result)
    metadata = {
        "contract_version": CONTRACT_VERSION,
        "request_id": job.request_id,
        "input_count": total,
        "hero_index": hero,
        "width": canvas_size[0],
        "height": canvas_size[1],
        "spacing": job.options.spacing,
        "shots": shots,
        "warnings": warnings,
    }
    return Outcome(png, metadata)
