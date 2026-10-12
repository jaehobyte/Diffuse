"""Defect mask → context patches, ported from scripts/retouch/patches.py.

Nearby defects share a patch; far-apart ones get their own, so the MI-GAN wrapper's internal
resize does not shrink a small blemish towards nothing. Order is total and depends only on the mask.
"""

from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np

MERGE_GAP_PX = 16
CONTEXT_RATIO = 1.0
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

    def clipped(self, width: int, height: int) -> "Box":
        return Box(max(0, self.x0), max(0, self.y0), min(width, self.x1), min(height, self.y1))

    def grown(self, by: int) -> "Box":
        return Box(self.x0 - by, self.y0 - by, self.x1 + by, self.y1 + by)

    def intersects(self, other: "Box") -> bool:
        return not (self.x1 <= other.x0 or other.x1 <= self.x0 or self.y1 <= other.y0 or other.y1 <= self.y0)

    def union(self, other: "Box") -> "Box":
        return Box(min(self.x0, other.x0), min(self.y0, other.y0), max(self.x1, other.x1), max(self.y1, other.y1))


@dataclass(frozen=True)
class Patch:
    box: Box  # what the model sees, in ROI pixels
    defect_box: Box  # the merged defects that asked for it


def plan_patches(
    defect: np.ndarray,
    *,
    merge_gap: int = MERGE_GAP_PX,
    context_ratio: float = CONTEXT_RATIO,
    min_patch: int = MIN_PATCH_PX,
) -> list[Patch]:
    height, width = defect.shape[:2]
    patches = [
        Patch(box=_context_box(box, context_ratio, min_patch).clipped(width, height), defect_box=box)
        for box in _merged_defect_boxes(defect, merge_gap)
    ]
    return sorted(patches, key=lambda p: (p.defect_box.y0, p.defect_box.x0, p.defect_box.y1, p.defect_box.x1))


def _merged_defect_boxes(defect: np.ndarray, merge_gap: int) -> list[Box]:
    count, _, stats, _ = cv2.connectedComponentsWithStats((defect > 0).astype(np.uint8), connectivity=8)
    boxes = [
        Box(
            int(stats[i, cv2.CC_STAT_LEFT]),
            int(stats[i, cv2.CC_STAT_TOP]),
            int(stats[i, cv2.CC_STAT_LEFT] + stats[i, cv2.CC_STAT_WIDTH]),
            int(stats[i, cv2.CC_STAT_TOP] + stats[i, cv2.CC_STAT_HEIGHT]),
        )
        for i in range(1, count)
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
    box = Box(
        defect_box.x0 - int(defect_box.width * context_ratio),
        defect_box.y0 - int(defect_box.height * context_ratio),
        defect_box.x1 + int(defect_box.width * context_ratio),
        defect_box.y1 + int(defect_box.height * context_ratio),
    )
    if box.width < min_patch:
        grow = min_patch - box.width
        box = Box(box.x0 - grow // 2, box.y0, box.x1 + grow - grow // 2, box.y1)
    if box.height < min_patch:
        grow = min_patch - box.height
        box = Box(box.x0, box.y0 - grow // 2, box.x1, box.y1 + grow - grow // 2)
    return box
