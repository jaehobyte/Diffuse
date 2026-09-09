"""The synthetic cases behind the adapter numbers in `work/retouch_evaluation.md` §3.

specs/skin_retouch_validation.md §2 asks the evaluation record to carry commands that reproduce it.
The photo set §2 fixes does not exist here, so what §3 measures is the **adapter**, on masks drawn
by this script over a repository fixture. Deterministic: no randomness, no download, no photograph
added to the repository, and the cases are written outside it.

    python3 make_cases.py --fixture ../../fixtures/photo_512.png --out ~/retouch-cases/synthetic

This is not a quality sample and cannot become one: the fixture contains no face. It exists so the
mechanical claims — polarity, patch planning, wrapper overreach, the protection guard, and cost per
patch — can be re-run and disagreed with.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

#: The 128x128 crop the polarity comparison runs on, and the defect drawn in the middle of it.
POLARITY_ORIGIN = (128, 128)
POLARITY_SIZE = 128
POLARITY_RADIUS = 10

#: One square defect per case, sized so `patches.plan_patches` lands on the patch named in the id:
#: a side of `d` gives `3d`, or the 64 px floor when that is smaller.
PATCH_CASES = {
    "patch64": (100, 100, 6),
    "patch129": (200, 100, 43),
    "patch258": (200, 140, 86),
    "patch384": (192, 128, 128),
}

#: Four defects: two 15 px apart (merged, closer than `MERGE_GAP_PX`), one far away, one that the
#: allowed mask protects entirely. Plus a protected rectangle across half of the far one.
TWO_PATCH_DEFECTS = ((60, 60, 80, 80), (95, 60, 115, 80), (300, 200, 330, 230), (420, 80, 440, 100))
TWO_PATCH_PROTECTED = ((410, 70, 455, 115), (315, 190, 360, 240))


def box_mask(shape: tuple[int, int], boxes) -> np.ndarray:
    mask = np.zeros(shape, dtype=np.uint8)
    for x0, y0, x1, y1 in boxes:
        mask[y0:y1, x0:x1] = 255
    return mask


def write(path: Path, image: np.ndarray) -> None:
    if not cv2.imwrite(str(path), image):
        raise OSError(f"could not write {path}")


def make_cases(fixture: Path, out: Path) -> list[dict]:
    photo = cv2.imread(str(fixture), cv2.IMREAD_COLOR)
    if photo is None:
        raise FileNotFoundError(fixture)
    height, width = photo.shape[:2]
    out.mkdir(parents=True, exist_ok=True)
    cases: list[dict] = []

    # --- polarity, on a small crop so the counts are readable ----------------------------------
    x0, y0 = POLARITY_ORIGIN
    crop = photo[y0:y0 + POLARITY_SIZE, x0:x0 + POLARITY_SIZE]
    defect = np.zeros(crop.shape[:2], dtype=np.uint8)
    centre = POLARITY_SIZE // 2
    cv2.circle(defect, (centre, centre), POLARITY_RADIUS, 255, thickness=-1)
    write(out / "polarity.png", crop)
    write(out / "polarity_defect.png", defect)
    # The same crop with the mask turned over: what the model would be asked to restore if the
    # app's polarity were passed through unconverted (`restore.to_model_mask` is the conversion).
    write(out / "polarity_inverted_defect.png", np.where(defect > 0, np.uint8(0), np.uint8(255)))
    cases.append({"id": "polarity", "image": "polarity.png", "defect_mask": "polarity_defect.png"})
    cases.append({
        "id": "polarity_inverted",
        "image": "polarity.png",
        "defect_mask": "polarity_inverted_defect.png",
    })

    # --- one patch per size, for the cost table ------------------------------------------------
    write(out / "photo.png", photo)
    for case_id, (left, top, side) in PATCH_CASES.items():
        write(
            out / f"{case_id}_defect.png",
            box_mask((height, width), [(left, top, left + side, top + side)]),
        )
        cases.append({
            "id": case_id,
            "image": "photo.png",
            "defect_mask": f"{case_id}_defect.png",
        })

    # --- patch planning and the protection guard, in one image --------------------------------
    write(out / "twopatch_defect.png", box_mask((height, width), TWO_PATCH_DEFECTS))
    protected = box_mask((height, width), TWO_PATCH_PROTECTED)
    write(out / "twopatch_allowed.png", np.where(protected > 0, np.uint8(0), np.uint8(255)))
    cases.append({
        "id": "twopatch",
        "image": "photo.png",
        "defect_mask": "twopatch_defect.png",
        "allowed_mask": "twopatch_allowed.png",
    })
    return cases


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--fixture", required=True, type=Path, help="a repository fixture image")
    parser.add_argument("--out", required=True, type=Path, help="case directory, outside the repo")
    args = parser.parse_args()

    cases = make_cases(args.fixture, args.out)
    manifest = args.out / "manifest.json"
    manifest.write_text(json.dumps({"cases": cases}, indent=2) + "\n")
    print(f"wrote {len(cases)} cases and {manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
