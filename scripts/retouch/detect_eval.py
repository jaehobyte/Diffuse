"""SR1-B: what the automatic detector finds, and what restoring its mask does.

specs/skin_retouch_validation.md §1.1 step 2. Deliberately a second CLI beside `evaluate.py`
rather than a flag on it: SR1-A's manually annotated masks stay the ground truth and are never
fed to the detector as input, so that a missed blemish and a badly repainted one cannot end up
inside the same number (§1.1).

Two things are measured, and reported apart (`work/tasks.md` requirement 11):

* **detection** — defect-level precision/recall against the manual annotation, plus how much
  normal skin a box-shaped candidate mask covers, and the cost of one ROI;
* **restoration** — the existing `RestoreRun`, run twice over the same ROI, allowance, engine and
  patch settings: once on the manual mask and once on the automatic one, as separate rows.

    # detection only; no restoration weights needed
    python3 detect_eval.py --manifest ~/retouch-cases/manual.json \
        --detector ~/.cache/vibe-retouch/acne_640_fp32.manifest.json --out ~/retouch-out/sr1b

    # detection, then restoration of both masks with the same engine
    python3 detect_eval.py --manifest ~/retouch-cases/manual.json \
        --detector ~/.cache/vibe-retouch/acne_640_fp32.manifest.json \
        --engine migan-onnx:$HOME/.cache/vibe-retouch/migan_pipeline_v2.onnx \
        --out ~/retouch-out/sr1b --repeat 5

The case manifest is `evaluate.py`'s, unchanged. `defect_mask` is the annotation and is used only
as ground truth here; `allowed_mask` is the allowance the candidate mask is intersected with, and
a case without one is skipped rather than treated as "all of the face may change"
(`work/tasks.md` requirement 7). A case image's **alpha is loaded and kept**: it reaches the
detector's preprocessing and the candidate mask separately, so this path enforces the same
`allowedMask ∩ alpha>0` contract the Android one does. No photograph, mask or result is written
into this repository.
"""

from __future__ import annotations

import argparse
import json
import resource
import statistics
import time
from dataclasses import asdict
from pathlib import Path

import cv2
import numpy as np

from detector import DEFAULT_CONF, DEFAULT_IOU, DEFAULT_MAX_DETECTIONS, Detection, candidate_mask
from evaluate import load_gray, save_rgb
from restore import RestoreRun

#: `work/tasks.md` requirement 11: defect-level matching, one prediction to one annotation.
MATCH_IOU = 0.5


def load_rgba(path: Path) -> tuple[np.ndarray, np.ndarray | None]:
    """One case image as RGB **plus** its alpha, rather than `evaluate.load_rgb`'s RGB alone.

    `work/tasks.md` requirement 7 makes the candidate mask a subset of `allowedMask ∩ alpha>0`,
    and requirement 5 composites transparent pixels over a fixed background before the model sees
    them. `cv2.IMREAD_COLOR` drops the alpha channel outright, so a case with a transparent
    region would be detected on whatever happened to be in its colour channels and masked as if
    it were skin — the two guarantees would hold in `detector.py` and not on this path.

    `evaluate.load_rgb` is left exactly as it is: SR1-A and `RestoreRun` take RGB, and the alpha
    is carried alongside here rather than pushed through their contract.

    Returns `(rgb, alpha)`, `alpha` being `None` for an image that has no alpha channel at all —
    which is not the same as an all-opaque one only in that it needs no intersection.
    """
    image = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
    if image is None:
        raise FileNotFoundError(path)
    if image.ndim == 2:
        return cv2.cvtColor(image, cv2.COLOR_GRAY2RGB), None
    if image.shape[2] == 3:
        return cv2.cvtColor(image, cv2.COLOR_BGR2RGB), None
    if image.shape[2] == 4:
        rgba = cv2.cvtColor(image, cv2.COLOR_BGRA2RGBA)
        return np.ascontiguousarray(rgba[:, :, :3]), np.ascontiguousarray(rgba[:, :, 3])
    raise ValueError(f"{path}: expected 1, 3 or 4 channels, got {image.shape}")


def ground_truth_boxes(defect: np.ndarray) -> list[tuple[float, float, float, float]]:
    """The annotation's connected components as boxes.

    The annotation is a mask, the detector emits boxes, and §2 of the validation spec requires the
    matching definition to be recorded rather than implied: one annotated blob is one defect, and
    its axis-aligned bounding box is what a prediction is matched against.
    """
    count, _, stats, _ = cv2.connectedComponentsWithStats((defect > 0).astype(np.uint8), 8)
    return [
        (
            float(stats[label, cv2.CC_STAT_LEFT]),
            float(stats[label, cv2.CC_STAT_TOP]),
            float(stats[label, cv2.CC_STAT_LEFT] + stats[label, cv2.CC_STAT_WIDTH]),
            float(stats[label, cv2.CC_STAT_TOP] + stats[label, cv2.CC_STAT_HEIGHT]),
        )
        for label in range(1, count)
    ]


def match_detections(
    detections: list[Detection], truth: list[tuple[float, float, float, float]]
) -> dict:
    """Greedy 1:1 matching at [MATCH_IOU], predictions in descending score order.

    Greedy and one-to-one, so two boxes over one blemish count as one hit and one false positive
    rather than two hits.
    """
    from detector import _iou

    unmatched = list(range(len(truth)))
    matched_iou: list[float] = []
    for detection in detections:
        best, best_iou = None, MATCH_IOU
        for index in unmatched:
            score = _iou(detection.box, truth[index])
            if score >= best_iou:
                best, best_iou = index, score
        if best is not None:
            unmatched.remove(best)
            matched_iou.append(best_iou)

    true_positives = len(matched_iou)
    false_positives = len(detections) - true_positives
    false_negatives = len(unmatched)
    return {
        "ground_truth_defects": len(truth),
        "predicted": len(detections),
        "true_positives": true_positives,
        "false_positives": false_positives,
        "false_negatives": false_negatives,
        "precision": true_positives / len(detections) if detections else None,
        "recall": true_positives / len(truth) if truth else None,
        "matched_iou_median": statistics.median(matched_iou) if matched_iou else None,
    }


def detect_case(
    detector,
    image: np.ndarray,
    allowed: np.ndarray,
    repeat: int,
    alpha: np.ndarray | None = None,
) -> tuple:
    """Run the detector [repeat] times over one ROI; return the last result and the timings.

    [alpha] is the case image's own alpha, and it is used **twice**, exactly as the Android path
    uses it: composited into the detector's input so transparent pixels take the fixed background
    (requirement 5), and intersected into the candidate mask so they can never be restored
    (requirement 7). The caller's [image] is not modified — `flatten_alpha` copies.
    """
    roi = image if alpha is None else np.dstack((image, alpha))
    totals: list[float] = []
    detections: list[Detection] = []
    stages: dict = {}
    for _ in range(max(1, repeat)):
        started = time.perf_counter()
        detections = detector.detect(roi)
        totals.append((time.perf_counter() - started) * 1000.0)
        stages = dict(detector.last_timings)
    mask = candidate_mask(detections, allowed, alpha=alpha)
    return detections, mask, totals, stages


def opaque_allowance(allowed: np.ndarray, alpha: np.ndarray | None) -> np.ndarray:
    """The allowance restricted to pixels the photograph actually has.

    Applied to the manual and the automatic row alike, so the only difference between the two
    remains where the defect mask came from (`work/tasks.md` requirement 10).
    """
    if alpha is None:
        return allowed
    return np.where((allowed > 0) & (alpha > 0), np.uint8(255), np.uint8(0))


def mask_metrics(
    mask: np.ndarray,
    defect: np.ndarray,
    allowed: np.ndarray,
    alpha: np.ndarray | None = None,
) -> dict:
    """How much of what the candidate mask claims is actually annotated as a defect.

    `work/tasks.md` requirement 7: a bounding box is not a segmentation, so the normal skin it
    sweeps in is recorded rather than assumed small. [protection_intrusion] and
    [candidate_on_transparent] must both be 0 — the mask is built as a subset of the allowance and
    of the opaque pixels, and these are the checks on that, not a repair of it.
    """
    candidate = mask > 0
    annotated = defect > 0
    return {
        "candidate_pixels": int(candidate.sum()),
        "annotated_defect_pixels": int(annotated.sum()),
        "candidate_on_annotated_defect": int((candidate & annotated).sum()),
        "candidate_on_normal_skin": int((candidate & ~annotated).sum()),
        "protection_intrusion": int((candidate & ~(allowed > 0)).sum()),
        "transparent_pixels": None if alpha is None else int((alpha == 0).sum()),
        "candidate_on_transparent": 0 if alpha is None else int((candidate & (alpha == 0)).sum()),
    }


def load_detector(args):
    """The real ONNX detector. A seam, so the weight-free test can drive [main] without weights."""
    from acne_onnx import OnnxAcneDetector

    return OnnxAcneDetector(
        args.detector,
        confidence=args.confidence,
        iou=args.nms_iou,
        max_detections=args.max_detections,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--manifest", required=True, type=Path, help="the SR1-A case manifest")
    parser.add_argument(
        "--detector", required=True, type=Path, help="the exported ONNX's .manifest.json"
    )
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument(
        "--engine",
        action="append",
        default=[],
        help="repeatable; omit for a detection-only run that needs no restoration weights",
    )
    parser.add_argument("--repeat", type=int, default=3)
    parser.add_argument("--confidence", type=float, default=DEFAULT_CONF)
    parser.add_argument("--nms-iou", type=float, default=DEFAULT_IOU)
    parser.add_argument("--max-detections", type=int, default=DEFAULT_MAX_DETECTIONS)
    parser.add_argument("--model-size", type=int, default=None, help="see evaluate.py")
    args = parser.parse_args(argv)

    detector = load_detector(args)
    cases = json.loads(args.manifest.read_text())["cases"]
    root = args.manifest.parent
    args.out.mkdir(parents=True, exist_ok=True)

    engines = []
    if args.engine:
        from engines import engine_from_spec

        engines = [engine_from_spec(spec) for spec in args.engine]

    rows: list[dict] = []
    skipped: list[str] = []
    for case in cases:
        image, alpha = load_rgba(root / case["image"])
        defect = load_gray(root / case["defect_mask"])
        if not case.get("allowed_mask"):
            skipped.append(case["id"])
            continue
        allowed = load_gray(root / case["allowed_mask"])
        for name, mask in (("defect_mask", defect), ("allowed_mask", allowed)):
            if mask.shape[:2] != image.shape[:2]:
                raise ValueError(
                    f"{case['id']}: {name} is {mask.shape[:2]}, image is {image.shape[:2]}"
                )
        # requirement 7: what may change is the allowance **and** the pixels the photograph has.
        # Both the manual and the automatic row use it, so the pair stays comparable.
        restorable = opaque_allowance(allowed, alpha)

        detections, auto, totals, stages = detect_case(
            detector, image, allowed, args.repeat, alpha=alpha
        )
        cv2.imwrite(str(args.out / f"{case['id']}_auto_mask.png"), auto)
        row = {
            "case": case["id"],
            "stage": "detect",
            "detector_version": detector.version,
            "confidence": args.confidence,
            "nms_iou": args.nms_iou,
            "max_detections": args.max_detections,
            "hit_max_detections": bool(stages.get("hit_max_detections")),
            "boxes": [
                {"box": [round(v, 3) for v in d.box], "score": round(d.score, 5), "class": d.class_id}
                for d in detections
            ],
            "matching": match_detections(detections, ground_truth_boxes(defect)),
            "mask": mask_metrics(auto, defect, allowed, alpha),
            "setting_overrides": getattr(detector, "setting_overrides", {}),
            "stage_ms": stages,
            "total_ms_p50": statistics.median(totals),
            "total_ms_p95": max(totals) if len(totals) < 20 else statistics.quantiles(totals, n=20)[18],
            "repeats": args.repeat,
            "model_load_ms": detector.load_ms,
        }
        rows.append(row)
        matching = row["matching"]
        print(
            f"{case['id']:>12}  detect  boxes={len(detections):<3} "
            f"tp={matching['true_positives']} fp={matching['false_positives']} "
            f"fn={matching['false_negatives']} "
            f"normal_skin={row['mask']['candidate_on_normal_skin']:<7} "
            f"p50={row['total_ms_p50']:.0f}ms"
        )

        # The same ROI, allowance, engine and patch settings for both masks: the only difference
        # between the two rows is where the defect mask came from.
        for engine in engines:
            for source, mask in (("manual", defect), ("auto", auto)):
                rows.append(
                    restore_row(engine, case, image, mask, restorable, source, args)
                )
                print(
                    f"{case['id']:>12}  restore {engine.name:<24} mask={source:<6} "
                    f"changed={rows[-1]['changed_pixels']:<8} "
                    f"violations={rows[-1]['protection_violations']}"
                )

    report = {
        "rows": rows,
        "skipped_cases_without_allowed_mask": skipped,
        "detector": {
            "manifest": str(args.detector),
            "version": detector.version,
            "model_sha256": detector.manifest["model"]["sha256"],
            "model_bytes": detector.manifest["model"]["bytes"],
            "load_ms": detector.load_ms,
            # requirement 11 wants threshold-sweep material told apart from a final figure: these
            # are the settings this run did **not** take from the manifest.
            "setting_overrides": getattr(detector, "setting_overrides", {}),
        },
        "matching": {"definition": "defect-level, greedy 1:1", "iou": MATCH_IOU},
        "peak_rss_kb": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
        "manifest": str(args.manifest),
    }
    (args.out / "metrics.json").write_text(json.dumps(report, indent=2))
    print(f"\nwrote {args.out / 'metrics.json'}  peak RSS {report['peak_rss_kb'] / 1024:.0f} MB")
    if skipped:
        print(f"skipped (no allowed_mask): {', '.join(skipped)}")
    print("Detection quality and restoration quality are separate verdicts (validation §1.1).")
    return 0


def restore_row(engine, case, image, mask, allowed, source: str, args) -> dict:
    run = RestoreRun(engine, model_size=args.model_size)
    totals = []
    for _ in range(max(1, args.repeat)):
        started = time.perf_counter()
        result = run.apply(image, mask, allowed)
        totals.append((time.perf_counter() - started) * 1000.0)
    save_rgb(args.out / f"{case['id']}_{engine.name}_{source}.png", result.image)
    return {
        "case": case["id"],
        "stage": "restore",
        "mask_source": source,
        "engine": engine.name,
        "engine_version": engine.version,
        "patches": len(result.patches),
        "patch_sizes": [[p.box.width, p.box.height] for p in result.patches],
        "changed_pixels": result.changed_pixels,
        "defect_pixels": int((mask > 0).sum()),
        "overreach_pixels_before_guard": result.overreach_pixels,
        "protection_violations": result.protection_violations,
        "stage_ms": asdict(result.timings),
        "total_ms_p50": statistics.median(totals),
        "total_ms_p95": max(totals) if len(totals) < 20 else statistics.quantiles(totals, n=20)[18],
        "repeats": args.repeat,
    }


if __name__ == "__main__":
    raise SystemExit(main())
