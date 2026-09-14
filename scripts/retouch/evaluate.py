"""SR1-A: run the candidate engines over one fixed set of photos and record what happened.

specs/skin_retouch_validation.md §1.1 and §2. This measures restoration on **manually annotated
defect masks**, which is a question about the model and not about the app: automatic detection is
SR1-B and is evaluated separately, because a missed blemish and a badly repainted one must not end
up inside the same number.

    python3 evaluate.py --manifest cases/manual.json --engine opencv-telea --out /tmp/sr1a
    python3 evaluate.py --manifest cases/manual.json \
        --engine migan-onnx:$HOME/.cache/vibe-retouch/migan_pipeline_v2.onnx --out /tmp/sr1a

The manifest is JSON and lives outside this repository when it points at photographs:

    {"cases": [{"id": "p01", "image": "p01.png", "defect_mask": "p01_defect.png",
                "allowed_mask": "p01_skin.png"}]}

Paths are relative to the manifest. Masks are 0/255 grayscale, 255 = "this is a defect" and, for
`allowed_mask`, 255 = "this pixel may change". No photograph, mask or result is written into the
repository by this script (§2), and nothing is uploaded anywhere.
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

from engines import engine_from_spec
from restore import RestoreRun


def load_gray(path: Path) -> np.ndarray:
    mask = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
    if mask is None:
        raise FileNotFoundError(path)
    return mask


def load_rgb(path: Path) -> np.ndarray:
    image = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if image is None:
        raise FileNotFoundError(path)
    return cv2.cvtColor(image, cv2.COLOR_BGR2RGB)


def save_rgb(path: Path, image: np.ndarray) -> None:
    cv2.imwrite(str(path), cv2.cvtColor(image, cv2.COLOR_RGB2BGR))


def run_case(engine, case: dict, root: Path, out_dir: Path, repeat: int, model_size: int | None) -> dict:
    image = load_rgb(root / case["image"])
    defect = load_gray(root / case["defect_mask"])
    allowed = load_gray(root / case["allowed_mask"]) if case.get("allowed_mask") else None
    # Named here as well as in the adapter, so a badly prepared case says which case it was.
    for name, mask in (("defect_mask", defect), ("allowed_mask", allowed)):
        if mask is not None and mask.shape[:2] != image.shape[:2]:
            raise ValueError(
                f"{case['id']}: {name} is {mask.shape[:2]}, image is {image.shape[:2]}"
            )

    run = RestoreRun(engine, model_size=model_size)
    totals = []
    for _ in range(max(1, repeat)):
        started = time.perf_counter()
        result = run.apply(image, defect, allowed)
        totals.append((time.perf_counter() - started) * 1000.0)

    save_rgb(out_dir / f"{case['id']}_{engine.name}.png", result.image)
    changed = np.any(result.image != image, axis=2)
    save_rgb(
        out_dir / f"{case['id']}_{engine.name}_changed.png",
        np.repeat(np.where(changed, np.uint8(255), np.uint8(0))[:, :, None], 3, axis=2),
    )

    return {
        "case": case["id"],
        "engine": engine.name,
        "engine_version": engine.version,
        "patches": len(result.patches),
        "patch_sizes": [[p.box.width, p.box.height] for p in result.patches],
        "changed_pixels": result.changed_pixels,
        "defect_pixels": int((defect > 0).sum()),
        "overreach_pixels_before_guard": result.overreach_pixels,
        "protection_violations": result.protection_violations,
        "stage_ms": asdict(result.timings),
        "total_ms_p50": statistics.median(totals),
        "total_ms_p95": max(totals) if len(totals) < 20 else statistics.quantiles(totals, n=20)[18],
        "repeats": repeat,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--engine", required=True, action="append", help="repeatable")
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--repeat", type=int, default=3, help="runs per case, for p50/p95")
    parser.add_argument(
        "--model-size",
        type=int,
        default=None,
        help="fixed square input for a raw model; omit for the official ONNX pipeline, "
        "which crops and resizes on its own",
    )
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text())
    root = args.manifest.parent
    args.out.mkdir(parents=True, exist_ok=True)

    rows = []
    for spec in args.engine:
        load_started = time.perf_counter()
        engine = engine_from_spec(spec)
        load_ms = (time.perf_counter() - load_started) * 1000.0
        for case in manifest["cases"]:
            row = run_case(engine, case, root, args.out, args.repeat, args.model_size)
            row["model_load_ms"] = load_ms
            rows.append(row)
            print(
                f"{row['case']:>12}  {row['engine']:<28} "
                f"patches={row['patches']:<3} changed={row['changed_pixels']:<8} "
                f"violations={row['protection_violations']} "
                f"p50={row['total_ms_p50']:.0f}ms"
            )

    report = {
        "rows": rows,
        # ru_maxrss is the whole process, so it covers the session and every buffer it allocated.
        "peak_rss_kb": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
        "manifest": str(args.manifest),
    }
    (args.out / "metrics.json").write_text(json.dumps(report, indent=2))
    print(f"\nwrote {args.out / 'metrics.json'}  peak RSS {report['peak_rss_kb'] / 1024:.0f} MB")
    print("Quality is judged by a human on these outputs (validation §3); this tool only measures.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
