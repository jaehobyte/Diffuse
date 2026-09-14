"""SR1-B: the acne detector checkpoint, what it actually is, and its ONNX export.

specs/skin_retouch_validation.md §1.1 step 2 and §2. Three sub-commands, all **opt-in**: each one
needs the checkpoint, which is not in this repository, is never committed, and is never downloaded
by any test.

    python3 acne_model.py inspect --checkpoint ~/.cache/vibe-retouch/acne.pt
    python3 acne_model.py export  --checkpoint ~/.cache/vibe-retouch/acne.pt \
        --out ~/.cache/vibe-retouch/acne_640_fp32.onnx
    python3 acne_model.py parity  --manifest ~/.cache/vibe-retouch/acne_640_fp32.manifest.json \
        --checkpoint ~/.cache/vibe-retouch/acne.pt --image ../../fixtures/photo_512.png

`inspect` reads the contract off the checkpoint instead of off the model card: the card's
`model.detect_acne(...)` example is not an Ultralytics API and the HuggingFace "transformers"
tag is automatic, so neither is evidence of anything (`work/tasks.md`, Background).

`export` writes the ONNX **and** the manifest beside it. The manifest is the contract the Python
reference detector and the Android adapter both read; a model whose SHA-256 does not match its
manifest is an error, not a warning.

`parity` is the check `work/tasks.md` requirement 3 asks for: one ROI, one preprocessed tensor,
PyTorch and ONNX Runtime, compared as raw tensors and again as decoded detections.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import sys
from pathlib import Path

import numpy as np

from detector import (
    DEFAULT_CONF,
    DEFAULT_IOU,
    DEFAULT_MAX_DETECTIONS,
    LETTERBOX_PAD_VALUE,
    PREPROCESS_SEMANTICS,
    SUPPORTED_MANIFEST_VERSION,
    DetectorContract,
    decode_detections,
    preprocess,
)

#: The revision this port was made against. `export` refuses a checkpoint whose SHA-256 differs,
#: because every recorded number below and in work/retouch_evaluation.md is about this file.
SOURCE = {
    "repo": "Tinny-Robot/acne",
    "revision": "d1f64f86f6a89f3988c70aec67eb07492507feba",
    "file": "acne.pt",
    "sha256": "2cef23fe3587b0154cd3598cae54f8c0d8076acebb545286e8904c11a9ee0a6c",
    "bytes": 52001952,
}

#: work/tasks.md requirement 2. The **starting** configuration for the evaluation, not a claim
#: about how the model was trained: static 640x640 is what the checkpoint's own `train_args`
#: recorded as `imgsz`, and everything else here is chosen to keep the graph simple enough to run
#: on ONNX Runtime's Android CPU provider.
EXPORT_OPTIONS = {
    "format": "onnx",
    "imgsz": 640,
    "opset": 17,
    "dynamic": False,
    "half": False,
    "simplify": False,
    "nms": False,
    "batch": 1,
}

#: The manifest layout `export` writes. Defined in `detector.py` beside the reader that refuses
#: any other value, so the writer cannot move without the reader noticing.
MANIFEST_VERSION = SUPPORTED_MANIFEST_VERSION


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def manifest_path_for(model: Path) -> Path:
    return model.with_suffix(".manifest.json")


# --------------------------------------------------------------------------------------------
# inspect
# --------------------------------------------------------------------------------------------


def read_checkpoint(checkpoint: Path) -> dict:
    """What the `.pt` says about itself.

    The file was written by Ultralytics 8.0.85, whose module layout (`ultralytics.yolo.utils`) no
    longer exists. `torch_safe_load` is Ultralytics' own compatibility path for exactly that, and
    using it is why this port does not need the 2023 release installed — nor a different set of
    weights, which `work/tasks.md` requirement 1 forbids substituting.
    """
    from ultralytics.nn.tasks import torch_safe_load

    ckpt, _ = torch_safe_load(str(checkpoint))
    model = ckpt["model"]
    train_args = ckpt.get("train_args") if isinstance(ckpt.get("train_args"), dict) else {}
    names = {int(k): str(v) for k, v in dict(getattr(model, "names", {})).items()}
    return {
        "path": str(checkpoint),
        "sha256": sha256_of(checkpoint),
        "bytes": checkpoint.stat().st_size,
        "ultralytics_version": ckpt.get("version"),
        "saved": ckpt.get("date"),
        "torch_class": type(model).__name__,
        "architecture": getattr(model, "yaml", {}).get("yaml_file"),
        "scale": getattr(model, "yaml", {}).get("scale"),
        "task": train_args.get("task"),
        "nc": int(getattr(model, "nc", len(names))),
        "names": names,
        "train_imgsz": train_args.get("imgsz"),
        "train_data": train_args.get("data"),
        "stride": [float(s) for s in getattr(model, "stride", [])],
    }


def cmd_inspect(args: argparse.Namespace) -> int:
    print(json.dumps(read_checkpoint(args.checkpoint), indent=2))
    return 0


# --------------------------------------------------------------------------------------------
# export
# --------------------------------------------------------------------------------------------


def cmd_export(args: argparse.Namespace) -> int:
    import onnx
    import onnxruntime
    import torch
    import ultralytics
    from ultralytics import YOLO

    checkpoint: Path = args.checkpoint
    digest = sha256_of(checkpoint)
    if digest != SOURCE["sha256"] and not args.allow_other_checkpoint:
        print(
            f"checkpoint sha256 {digest} is not the ported revision "
            f"({SOURCE['sha256']}). Pass --allow-other-checkpoint to export it anyway; the "
            f"manifest will then say so.",
            file=sys.stderr,
        )
        return 2

    info = read_checkpoint(checkpoint)
    if info["nc"] != len(info["names"]):
        raise ValueError(f"checkpoint nc={info['nc']} but names={info['names']}")

    # `YOLO(local_path)` and not a hub name: nothing is downloaded here (requirement 1).
    model = YOLO(str(checkpoint))
    produced = Path(
        model.export(
            format=EXPORT_OPTIONS["format"],
            imgsz=EXPORT_OPTIONS["imgsz"],
            opset=EXPORT_OPTIONS["opset"],
            dynamic=EXPORT_OPTIONS["dynamic"],
            half=EXPORT_OPTIONS["half"],
            simplify=EXPORT_OPTIONS["simplify"],
            nms=EXPORT_OPTIONS["nms"],
            batch=EXPORT_OPTIONS["batch"],
        )
    )
    out: Path = args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    if produced.resolve() != out.resolve():
        out.write_bytes(produced.read_bytes())

    # requirement 2: both checks, on the file that will actually be shipped to the device.
    graph = onnx.load(str(out))
    onnx.checker.check_model(graph)
    graph_metadata = {p.key: p.value for p in graph.metadata_props}
    session = onnxruntime.InferenceSession(str(out), providers=["CPUExecutionProvider"])
    graph_inputs = [_tensor_info(t) for t in session.get_inputs()]
    graph_outputs = [_tensor_info(t) for t in session.get_outputs()]
    contract = DetectorContract.from_graph(graph_inputs, graph_outputs, num_classes=info["nc"])

    manifest = {
        "manifest_version": MANIFEST_VERSION,
        "source": dict(SOURCE, matches_recorded_revision=digest == SOURCE["sha256"]),
        "checkpoint": {
            "sha256": info["sha256"],
            "bytes": info["bytes"],
            "ultralytics_version": info["ultralytics_version"],
            "saved": info["saved"],
            "task": info["task"],
            "architecture": info["architecture"],
            "scale": info["scale"],
            "nc": info["nc"],
            "names": {str(k): v for k, v in info["names"].items()},
            "train_imgsz": info["train_imgsz"],
            "stride": info["stride"],
        },
        "export": dict(
            EXPORT_OPTIONS,
            exporter="ultralytics.YOLO.export",
            ultralytics=ultralytics.__version__,
            torch=torch.__version__,
            onnx=onnx.__version__,
            onnxruntime=onnxruntime.__version__,
            python=platform.python_version(),
        ),
        "model": {
            "file": out.name,
            "sha256": sha256_of(out),
            "bytes": out.stat().st_size,
            # Ultralytics stamps an export timestamp into the graph, so two exports of one
            # checkpoint are identical in content and different in SHA-256. The digest therefore
            # identifies **one exported file** — which is what the adapter checks — and not the
            # export configuration, which `export` above is.
            "byte_reproducible": False,
            # Recorded rather than summarised: the graph carries the exporter's own licence
            # string, and it is not the licence the model card states for the weights.
            "graph_metadata": graph_metadata,
        },
        "io": {"inputs": graph_inputs, "outputs": graph_outputs},
        # Written from the same dict `detector.py` checks a manifest against, so "what the
        # manifest says" and "what the code does" are one definition rather than two.
        "preprocess": dict(PREPROCESS_SEMANTICS),
        "decode": contract.as_manifest(),
        "postprocess_defaults": {
            "confidence": DEFAULT_CONF,
            "nms_iou": DEFAULT_IOU,
            "max_detections": DEFAULT_MAX_DETECTIONS,
        },
    }
    manifest_path = manifest_path_for(out)
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"wrote {out} ({manifest['model']['bytes']} B)")
    print(f"wrote {manifest_path}")
    print(json.dumps({"io": manifest["io"], "decode": manifest["decode"]}, indent=2))
    return 0


def _tensor_info(tensor) -> dict:
    return {
        "name": tensor.name,
        "shape": [d if isinstance(d, int) else str(d) for d in tensor.shape],
        "dtype": tensor.type,
    }


# --------------------------------------------------------------------------------------------
# parity
# --------------------------------------------------------------------------------------------

#: requirement 3, fixed separately on purpose. The raw tolerances are a float32 question about two
#: runtimes evaluating the same graph; the box tolerance below is a different question — whether
#: the same detections come out of the far end of NMS.
#:
#: The raw output's two halves are on different scales and get different tolerances rather than
#: one loose number covering both: rows 0-3 are box coordinates in model-input pixels (0..640,
#: where one float32 ULP is already ~6e-5 and a few fused-multiply-add reassociations accumulate
#: to ~1e-3), rows 4+ are class scores in 0..1. Measured on the fixtures: 9.8e-4 px and 1.2e-6.
RAW_BOX_TOLERANCE_PX = 1e-2
RAW_SCORE_TOLERANCE = 1e-5
BOX_ABS_TOLERANCE = 1.0
SCORE_ABS_TOLERANCE = 1e-3


def cmd_parity(args: argparse.Namespace) -> int:
    import cv2
    import onnxruntime
    import torch

    from acne_onnx import OnnxAcneDetector

    manifest = json.loads(args.manifest.read_text())
    detector = OnnxAcneDetector(args.manifest)

    bgr = cv2.imread(str(args.image), cv2.IMREAD_COLOR)
    if bgr is None:
        raise FileNotFoundError(args.image)
    roi = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    tensor, transform = preprocess(roi, detector.contract.input_size)

    onnx_raw = detector.session.run(None, {detector.contract.input_name: tensor})[0]

    from ultralytics.nn.tasks import torch_safe_load

    ckpt, _ = torch_safe_load(str(args.checkpoint))
    torch_model = ckpt["model"].float().eval()
    for parameter in torch_model.parameters():
        parameter.requires_grad_(False)
    with torch.no_grad():
        torch_out = torch_model(torch.from_numpy(tensor))
    torch_raw = (torch_out[0] if isinstance(torch_out, (list, tuple)) else torch_out).numpy()

    if torch_raw.shape != onnx_raw.shape:
        print(f"FAIL shape: torch {torch_raw.shape} vs onnx {onnx_raw.shape}", file=sys.stderr)
        return 1
    difference = np.abs(torch_raw.astype(np.float64) - onnx_raw.astype(np.float64))
    raw_box_max = float(difference[0, :4].max())
    raw_score_max = float(difference[0, 4:].max())

    torch_dets = decode_detections(
        torch_raw, detector.contract, transform, confidence=args.confidence
    )
    onnx_dets = decode_detections(
        onnx_raw, detector.contract, transform, confidence=args.confidence
    )

    report = {
        "image": str(args.image),
        "roi_size": [roi.shape[1], roi.shape[0]],
        "raw_shape": list(onnx_raw.shape),
        "raw_box_max_abs_diff_model_px": raw_box_max,
        "raw_box_tolerance_model_px": RAW_BOX_TOLERANCE_PX,
        "raw_score_max_abs_diff": raw_score_max,
        "raw_score_tolerance": RAW_SCORE_TOLERANCE,
        "confidence": args.confidence,
        "torch_detections": len(torch_dets),
        "onnx_detections": len(onnx_dets),
        "model_sha256": manifest["model"]["sha256"],
    }
    failures = []
    if raw_box_max > RAW_BOX_TOLERANCE_PX:
        failures.append(f"raw box diff {raw_box_max:g} > {RAW_BOX_TOLERANCE_PX:g} model px")
    if raw_score_max > RAW_SCORE_TOLERANCE:
        failures.append(f"raw score diff {raw_score_max:g} > {RAW_SCORE_TOLERANCE:g}")
    if len(torch_dets) != len(onnx_dets):
        failures.append(f"{len(torch_dets)} torch detections vs {len(onnx_dets)} onnx")
    else:
        box_diff = 0.0
        score_diff = 0.0
        for a, b in zip(torch_dets, onnx_dets):
            if a.class_id != b.class_id:
                failures.append(f"class {a.class_id} vs {b.class_id}")
            box_diff = max(box_diff, max(abs(x - y) for x, y in zip(a.box, b.box)))
            score_diff = max(score_diff, abs(a.score - b.score))
        # requirement 3 states the box tolerance in model-input pixels; the decoded boxes are in
        # ROI pixels, so it is converted rather than quietly widened.
        roi_tolerance = BOX_ABS_TOLERANCE / transform.scale
        report["box_max_abs_diff_roi_px"] = box_diff
        report["box_tolerance_roi_px"] = roi_tolerance
        report["score_max_abs_diff"] = score_diff
        if box_diff > roi_tolerance:
            failures.append(f"box diff {box_diff:.4f} > {roi_tolerance:.4f} ROI px")
        if score_diff > SCORE_ABS_TOLERANCE:
            failures.append(f"score diff {score_diff:g} > {SCORE_ABS_TOLERANCE:g}")

    report["result"] = "PASS" if not failures else "FAIL"
    report["failures"] = failures
    report["runtime"] = {
        "onnxruntime": onnxruntime.__version__,
        "torch": torch.__version__,
        "python": platform.python_version(),
    }
    print(json.dumps(report, indent=2))
    if args.save_raw:
        args.save_raw.parent.mkdir(parents=True, exist_ok=True)
        np.savez_compressed(
            args.save_raw, input=tensor, torch_output=torch_raw, onnx_output=onnx_raw
        )
        print(f"wrote {args.save_raw}", file=sys.stderr)
    return 0 if not failures else 1


# --------------------------------------------------------------------------------------------
# fixture
# --------------------------------------------------------------------------------------------

#: `work/tasks.md` requirement 5: the preprocessing contract is pinned by fixture, not by two
#: descriptions of it. This writes the file that `core/ai`'s `DetectorPreprocessParityTest` reads,
#: and `scripts/retouch/test_detector.py` reads the same file — so a change to either
#: implementation has to change this fixture, deliberately, and both suites see it.
FIXTURE_MODEL_SIZE = 32
FIXTURE_CASES = (
    (32, 32, "opaque"),
    (64, 32, "opaque"),
    (31, 17, "opaque"),
    (17, 31, "opaque"),
    (12, 12, "left_half_transparent"),
    # Partial alpha is its own case: an implementation that quantises the composite back to 8 bits
    # agrees with one that does not on every fully opaque and fully transparent pixel, and only
    # differs here (`work/tasks.md` requirement 5).
    (12, 12, "left_half_semi_transparent"),
)


def fixture_roi(width: int, height: int, alpha: str) -> np.ndarray:
    """A deterministic ROI both languages can build from the same three lines of arithmetic."""
    x = np.arange(width, dtype=np.int64)[None, :]
    y = np.arange(height, dtype=np.int64)[:, None]
    roi = np.zeros((height, width, 4), dtype=np.uint8)
    roi[:, :, 0] = (x * 7 + y * 13) % 256
    roi[:, :, 1] = (x * 3 + y * 29) % 256
    roi[:, :, 2] = (x * 17 + y * 5) % 256
    roi[:, :, 3] = 255
    if alpha == "left_half_transparent":
        roi[:, : width // 2, 3] = 0
    elif alpha == "left_half_semi_transparent":
        # Flat white at half alpha, not the pattern: Android stores ARGB_8888 **premultiplied**,
        # and an arbitrary colour does not survive that round trip exactly. 255 does
        # (255*128/255 = 128, 128*255/128 = 255), so what this case compares is the compositing
        # arithmetic rather than the platform's storage rounding.
        roi[:, : width // 2, :3] = 255
        roi[:, : width // 2, 3] = 128
    elif alpha != "opaque":
        raise ValueError(f"unknown alpha rule {alpha}")
    return roi


def cmd_fixture(args: argparse.Namespace) -> int:
    size = FIXTURE_MODEL_SIZE
    cases = []
    for width, height, alpha in FIXTURE_CASES:
        tensor, transform = preprocess(fixture_roi(width, height, alpha), size)
        flat = tensor[0]
        # Corners of the padding, corners and centre of the placed image, and the seam between
        # them: the positions where a letterbox that is off by one shows up first.
        positions = [
            (0, 0, 0),
            (2, size - 1, size - 1),
            (0, transform.pad_top, transform.pad_left),
            (1, transform.pad_top, transform.pad_left),
            (2, transform.pad_top, transform.pad_left),
            (0, transform.pad_top + transform.scaled_height - 1, transform.pad_left + transform.scaled_width - 1),
            (1, transform.pad_top + transform.scaled_height // 2, transform.pad_left + transform.scaled_width // 2),
            (0, size // 2, size // 2),
            (2, size // 2, size // 3),
        ]
        cases.append(
            {
                "roi": [width, height],
                "alpha": alpha,
                "letterbox": {
                    "scale": float(transform.scale),
                    "scaled": [transform.scaled_width, transform.scaled_height],
                    "pad_left": transform.pad_left,
                    "pad_top": transform.pad_top,
                    "pad_right": transform.pad_right,
                    "pad_bottom": transform.pad_bottom,
                },
                "samples": [
                    {"c": c, "y": y, "x": x, "v": float(flat[c, y, x])} for c, y, x in positions
                ],
                # Accumulated in float64 in both languages, so the summation order does not
                # matter to the precision this is compared at.
                "sum": float(np.asarray(flat, dtype=np.float64).sum()),
            }
        )

    fixture = {
        "fixture_version": 1,
        "generated_by": "scripts/retouch/acne_model.py fixture",
        "note": (
            "The preprocessing contract, shared by scripts/retouch/detector.py and "
            "core/ai's Kotlin adapter. ROI pixel (x,y) is ARGB with "
            "r=(7x+13y)%256, g=(3x+29y)%256, b=(17x+5y)%256, and a=255 unless the case says "
            "left_half_transparent (a=0 for x < width/2) or left_half_semi_transparent "
            "(rgb=255,255,255 and a=128 there — flat white because Android stores ARGB_8888 "
            "premultiplied and 255 survives that round trip exactly). The composite over the pad "
            "value is done in float and is never rounded back to 8 bits."
        ),
        "model_size": size,
        "pad_value": list(LETTERBOX_PAD_VALUE),
        "tolerance": {"sample_abs": 1e-5, "sum_rel": 1e-6},
        "cases": cases,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(fixture, indent=2) + "\n")
    print(f"wrote {args.out}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_inspect = sub.add_parser("inspect", help="print the checkpoint's own contract")
    p_inspect.add_argument("--checkpoint", required=True, type=Path)
    p_inspect.set_defaults(func=cmd_inspect)

    p_export = sub.add_parser("export", help="write the ONNX and its manifest")
    p_export.add_argument("--checkpoint", required=True, type=Path)
    p_export.add_argument("--out", required=True, type=Path)
    p_export.add_argument("--allow-other-checkpoint", action="store_true")
    p_export.set_defaults(func=cmd_export)

    p_parity = sub.add_parser("parity", help="PyTorch vs ONNX Runtime on one ROI")
    p_parity.add_argument("--manifest", required=True, type=Path)
    p_parity.add_argument("--checkpoint", required=True, type=Path)
    p_parity.add_argument("--image", required=True, type=Path, help="one face ROI, any size")
    p_parity.add_argument(
        "--confidence",
        type=float,
        default=DEFAULT_CONF,
        help="lower it to make a fixture without blemishes still exercise the box path",
    )
    p_parity.add_argument("--save-raw", type=Path, default=None, help="npz of both raw outputs")
    p_parity.set_defaults(func=cmd_parity)

    p_fixture = sub.add_parser(
        "fixture", help="write the preprocessing fixture both languages' tests read (no weights)"
    )
    p_fixture.add_argument(
        "--out",
        type=Path,
        default=Path(__file__).resolve().parents[2]
        / "core/ai/src/test/resources/retouch/preprocess_parity.json",
    )
    p_fixture.set_defaults(func=cmd_fixture)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
