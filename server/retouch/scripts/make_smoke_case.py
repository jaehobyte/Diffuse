#!/usr/bin/env python3
"""Make a mechanical smoke case: an RGBA PNG ROI, a binary allowed mask, and a manifest.

    make_smoke_case.py --image fixtures/photo_512.png --region region.json --out /tmp/case

`region.json` is either a rectangle or a polygon, in source-image pixels:

    {"roi": [x0, y0, x1, y1], "rect": [x0, y0, x1, y1]}
    {"roi": [x0, y0, x1, y1], "polygon": [[x, y], ...]}

`roi` (optional, default the whole image) is the crop sent to the server; `rect`/`polygon` is the
allowed region (255) within it. Optional `"kinds": {"shine": {...}, ...}` gives a per-kind region
of the same form (without `roi`), producing one mask per kind. The output never leaves --out and
is meant for face-free fixtures or photos you are allowed to send to the server.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

KINDS = ("blemish", "shine", "dark_circles", "shaving_shadow")


def draw_mask(shape: tuple[int, int], region: dict, offset: tuple[int, int]) -> np.ndarray:
    mask = np.zeros(shape, dtype=np.uint8)
    ox, oy = offset
    if "rect" in region:
        x0, y0, x1, y1 = (int(v) for v in region["rect"])
        mask[max(0, y0 - oy) : max(0, y1 - oy), max(0, x0 - ox) : max(0, x1 - ox)] = 255
    elif "polygon" in region:
        points = np.array([[int(x) - ox, int(y) - oy] for x, y in region["polygon"]], dtype=np.int32)
        cv2.fillPoly(mask, [points], 255)
    else:
        raise SystemExit("region needs 'rect' or 'polygon'")
    return mask


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--image", required=True, type=Path)
    parser.add_argument("--region", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--id", default="case1")
    args = parser.parse_args()

    source = cv2.imread(str(args.image), cv2.IMREAD_UNCHANGED)
    if source is None:
        raise SystemExit(f"cannot read {args.image}")
    if source.ndim == 2:
        rgba = cv2.cvtColor(source, cv2.COLOR_GRAY2RGBA)
    elif source.shape[2] == 3:
        rgba = cv2.cvtColor(source, cv2.COLOR_BGR2RGBA)
    else:
        rgba = cv2.cvtColor(source, cv2.COLOR_BGRA2RGBA)
    region = json.loads(args.region.read_text())
    height, width = rgba.shape[:2]
    x0, y0, x1, y1 = (int(v) for v in region.get("roi", [0, 0, width, height]))
    x0, y0, x1, y1 = max(0, x0), max(0, y0), min(width, x1), min(height, y1)
    roi = np.ascontiguousarray(rgba[y0:y1, x0:x1])
    if roi.size == 0:
        raise SystemExit("empty roi")

    args.out.mkdir(parents=True, exist_ok=True)
    image_name = f"{args.id}_roi.png"
    cv2.imwrite(str(args.out / image_name), cv2.cvtColor(roi, cv2.COLOR_RGBA2BGRA))
    case: dict = {"id": args.id, "image": image_name}
    shape = roi.shape[:2]
    if "rect" in region or "polygon" in region:
        name = f"{args.id}_allowed.png"
        cv2.imwrite(str(args.out / name), draw_mask(shape, region, (x0, y0)))
        case["allowed_mask"] = name
    per_kind = region.get("kinds", {})
    if per_kind:
        case["allowed_masks"] = {}
        for kind, kind_region in per_kind.items():
            if kind not in KINDS:
                raise SystemExit(f"unknown kind {kind}")
            name = f"{args.id}_{kind}_allowed.png"
            cv2.imwrite(str(args.out / name), draw_mask(shape, kind_region, (x0, y0)))
            case["allowed_masks"][kind] = name
    if "allowed_mask" not in case and "allowed_masks" not in case:
        raise SystemExit("region defines no allowed mask")
    if "expected" in region:
        case["expected"] = region["expected"]

    manifest_path = args.out / "manifest.json"
    manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {"cases": []}
    manifest["cases"] = [c for c in manifest["cases"] if c.get("id") != args.id] + [case]
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"wrote {args.out / image_name} ({roi.shape[1]}x{roi.shape[0]}) and {manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
