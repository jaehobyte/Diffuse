"""Turning a defect mask into the patches a restoration model is actually run on.

specs/skin_retouch_pipeline.md §1.1: the official MI-GAN wrapper crops around the masked bbox and
resizes to a fixed model size, so one bbox around two blemishes at opposite corners of a face
shrinks each of them towards nothing. Nearby defects share a patch; far apart ones get their own.

The numbers here are provisional. §1.1 requires the patch size, the context ratio and the merge
rule to be fixed by the SR1-A comparison and recorded in work/retouch_evaluation.md.
"""

from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np

#: Defects closer than this share one patch — closer than the context each would be given anyway.
MERGE_GAP_PX = 16

#: Context added on each side, as a fraction of the defect box. One box-width of skin around it.
CONTEXT_RATIO = 1.0

#: No patch smaller than this: a 6px pimple gives a model nothing to reconstruct texture from.
MIN_PATCH_PX = 64


@dataclass(frozen=True)
class Box:
    x0: int
    y0: int
    x1: int
    y1: int

    @property
    def width(self) -> int:
        return self.x1 - self.x0

    @property
    def height(self) -> int:
        return self.y1 - self.y0

    def contains_box(self, other: "Box") -> bool:
        return (
            self.x0 <= other.x0
            and self.y0 <= other.y0
            and self.x1 >= other.x1
            and self.y1 >= other.y1
        )

    def clipped(self, width: int, height: int) -> "Box":
        return Box(
            max(0, self.x0),
            max(0, self.y0),
            min(width, self.x1),
            min(height, self.y1),
        )

    def grown(self, by: int) -> "Box":
        return Box(self.x0 - by, self.y0 - by, self.x1 + by, self.y1 + by)

    def intersects(self, other: "Box") -> bool:
        return not (
            self.x1 <= other.x0
            or other.x1 <= self.x0
            or self.y1 <= other.y0
            or other.y1 <= self.y0
        )

    def union(self, other: "Box") -> "Box":
        return Box(
            min(self.x0, other.x0),
            min(self.y0, other.y0),
            max(self.x1, other.x1),
            max(self.y1, other.y1),
        )


@dataclass(frozen=True)
class Patch:
    """[box] is what the model sees, [defect_box] the part of it that asked for the call."""

    box: Box
    defect_box: Box

    @staticmethod
    def box_of(x0: int, y0: int, x1: int, y1: int) -> Box:
        return Box(x0, y0, x1, y1)


def plan_patches(
    defect: np.ndarray,
    *,
    merge_gap: int = MERGE_GAP_PX,
    context_ratio: float = CONTEXT_RATIO,
    min_patch: int = MIN_PATCH_PX,
) -> list[Patch]:
    """Patches covering every non-zero pixel of [defect], top-to-bottom then left-to-right.

    The order is total and depends only on the mask, so two runs over the same photo composite in
    the same order — specs/skin_retouch_pipeline.md §1.1 asks for exactly that.
    """
    height, width = defect.shape[:2]
    boxes = _merged_defect_boxes(defect, merge_gap)
    patches = [
        Patch(box=_context_box(box, context_ratio, min_patch).clipped(width, height), defect_box=box)
        for box in boxes
    ]
    return sorted(patches, key=lambda p: (p.defect_box.y0, p.defect_box.x0))


def _merged_defect_boxes(defect: np.ndarray, merge_gap: int) -> list[Box]:
    binary = (defect > 0).astype(np.uint8)
    count, _, stats, _ = cv2.connectedComponentsWithStats(binary, connectivity=8)
    boxes = [
        Box(
            int(stats[label, cv2.CC_STAT_LEFT]),
            int(stats[label, cv2.CC_STAT_TOP]),
            int(stats[label, cv2.CC_STAT_LEFT] + stats[label, cv2.CC_STAT_WIDTH]),
            int(stats[label, cv2.CC_STAT_TOP] + stats[label, cv2.CC_STAT_HEIGHT]),
        )
        # Label 0 is the background.
        for label in range(1, count)
    ]

    merged = True
    while merged:
        merged = False
        for i in range(len(boxes)):
            for j in range(i + 1, len(boxes)):
                if boxes[i].grown(merge_gap).intersects(boxes[j].grown(merge_gap)):
                    boxes[i] = boxes[i].union(boxes[j])
                    del boxes[j]
                    merged = True
                    break
            if merged:
                break
    return boxes


def _context_box(defect_box: Box, context_ratio: float, min_patch: int) -> Box:
    with_context = Box(
        defect_box.x0 - int(defect_box.width * context_ratio),
        defect_box.y0 - int(defect_box.height * context_ratio),
        defect_box.x1 + int(defect_box.width * context_ratio),
        defect_box.y1 + int(defect_box.height * context_ratio),
    )
    return _at_least(_at_least(with_context, min_patch, axis="x"), min_patch, axis="y")


def _at_least(box: Box, size: int, *, axis: str) -> Box:
    length = box.width if axis == "x" else box.height
    if length >= size:
        return box
    grow = size - length
    before, after = grow // 2, grow - grow // 2
    if axis == "x":
        return Box(box.x0 - before, box.y0, box.x1 + after, box.y1)
    return Box(box.x0, box.y0 - before, box.x1, box.y1 + after)
