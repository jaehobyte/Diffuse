"""The SR1-B evaluation CLI, end to end, without weights.

`work/tasks.md` requirement 10 and requirement 7. What is pinned here is the part of the CLI that
`test_detector.py` cannot see: `detector.py` enforces `allowedMask ∩ alpha>0` on its own inputs,
but only this path decides what those inputs *are* — and a loader that drops the alpha channel
would satisfy every test in that file while quietly evaluating transparent pixels as skin.

No ONNX, no checkpoint and no network: the detector is a deterministic fake and the restoration
engine is OpenCV's baseline, so this runs in the same `pytest` invocation as everything else.

    python3 -m pytest scripts/retouch
"""

from __future__ import annotations

import json

import cv2
import numpy as np
import pytest

import detect_eval
from detector import Detection

WIDTH, HEIGHT = 40, 24
#: The top third of the case image is a transparent hole.
TRANSPARENT_ROWS = 8


class FakeDetector:
    """Returns one box over the whole ROI, and records the ROI it was handed.

    Covering everything is deliberate: it makes "the mask is limited to the allowance and to the
    opaque pixels" the only thing that can keep a pixel out of the result, so a dropped alpha
    channel shows up as a failure rather than as a slightly different number.
    """

    version = "fake-detector@test"
    load_ms = 0.0

    def __init__(self) -> None:
        self.manifest = {"model": {"sha256": "00" * 32, "bytes": 0}}
        self.last_timings: dict[str, float] = {}
        self.setting_overrides: dict = {}
        self.seen: list[np.ndarray] = []

    def detect(self, roi: np.ndarray) -> list[Detection]:
        self.seen.append(roi)
        self.last_timings = {"preprocess_ms": 0.0, "inference_ms": 0.0, "decode_ms": 0.0}
        return [Detection(box=(0.0, 0.0, float(WIDTH), float(HEIGHT)), score=0.9, class_id=0)]


@pytest.fixture
def case(tmp_path, monkeypatch):
    """One case whose image has an alpha hole, plus the manifest that names it."""
    rgba = np.zeros((HEIGHT, WIDTH, 4), dtype=np.uint8)
    rgba[:, :, :3] = 180
    rgba[:, :, 3] = 255
    # A transparent band with **non-zero** colour underneath it: if the alpha were dropped, the
    # band would look like ordinary skin rather than like an obviously empty region.
    rgba[:TRANSPARENT_ROWS, :, 3] = 0
    rgba[:TRANSPARENT_ROWS, :, :3] = 90
    cv2.imwrite(str(tmp_path / "face.png"), cv2.cvtColor(rgba, cv2.COLOR_RGBA2BGRA))

    defect = np.zeros((HEIGHT, WIDTH), dtype=np.uint8)
    defect[12:16, 4:8] = 255
    cv2.imwrite(str(tmp_path / "defect.png"), defect)

    allowed = np.full((HEIGHT, WIDTH), 255, dtype=np.uint8)
    cv2.imwrite(str(tmp_path / "allowed.png"), allowed)

    (tmp_path / "cases.json").write_text(
        json.dumps(
            {
                "cases": [
                    {
                        "id": "alpha_case",
                        "image": "face.png",
                        "defect_mask": "defect.png",
                        "allowed_mask": "allowed.png",
                    }
                ]
            }
        )
    )

    detector = FakeDetector()
    monkeypatch.setattr(detect_eval, "load_detector", lambda args: detector)
    return tmp_path, detector


def run(tmp_path, *extra: str) -> dict:
    out = tmp_path / "out"
    assert (
        detect_eval.main(
            [
                "--manifest",
                str(tmp_path / "cases.json"),
                "--detector",
                str(tmp_path / "unused.manifest.json"),
                "--out",
                str(out),
                "--repeat",
                "1",
                *extra,
            ]
        )
        == 0
    )
    return json.loads((out / "metrics.json").read_text())


def test_the_case_image_reaches_the_detector_with_its_alpha(case):
    tmp_path, detector = case

    run(tmp_path)

    roi = detector.seen[0]
    assert roi.shape == (HEIGHT, WIDTH, 4), "the detector was handed RGB; the alpha was dropped"
    assert (roi[:TRANSPARENT_ROWS, :, 3] == 0).all()


def test_the_candidate_mask_excludes_transparent_pixels(case):
    """Requirement 7 on the path that actually runs, not only inside `candidate_mask`."""
    tmp_path, _ = case

    report = run(tmp_path)

    mask = cv2.imread(str(tmp_path / "out" / "alpha_case_auto_mask.png"), cv2.IMREAD_GRAYSCALE)
    assert (mask[:TRANSPARENT_ROWS, :] == 0).all()
    assert (mask[TRANSPARENT_ROWS:, :] == 255).all()

    metrics = report["rows"][0]["mask"]
    assert metrics["candidate_on_transparent"] == 0
    assert metrics["protection_intrusion"] == 0
    assert metrics["transparent_pixels"] == TRANSPARENT_ROWS * WIDTH
    assert metrics["candidate_pixels"] == (HEIGHT - TRANSPARENT_ROWS) * WIDTH


def test_both_restoration_rows_share_the_same_opaque_allowance(case):
    """Requirement 10: the mask source is the only difference between the manual and auto rows."""
    tmp_path, _ = case

    report = run(tmp_path, "--engine", "opencv-telea")

    restores = [row for row in report["rows"] if row["stage"] == "restore"]
    assert [row["mask_source"] for row in restores] == ["manual", "auto"]
    for row in restores:
        assert row["protection_violations"] == 0
    # The automatic mask covers the whole opaque region; the manual one covers one small blob.
    # Neither may repaint a pixel the photograph does not have.
    auto = next(row for row in restores if row["mask_source"] == "auto")
    assert auto["changed_pixels"] <= (HEIGHT - TRANSPARENT_ROWS) * WIDTH


def test_a_case_image_without_alpha_still_evaluates(case):
    """The alpha is optional, and its absence is not silently treated as "all transparent"."""
    tmp_path, _ = case
    opaque = np.full((HEIGHT, WIDTH, 3), 180, dtype=np.uint8)
    cv2.imwrite(str(tmp_path / "face.png"), opaque)

    report = run(tmp_path)

    metrics = report["rows"][0]["mask"]
    assert metrics["transparent_pixels"] is None
    assert metrics["candidate_pixels"] == HEIGHT * WIDTH


def test_the_loader_keeps_alpha_and_leaves_the_rgb_contract_alone(tmp_path):
    rgba = np.dstack(
        (
            np.full((4, 6), 10, np.uint8),
            np.full((4, 6), 20, np.uint8),
            np.full((4, 6), 30, np.uint8),
            np.full((4, 6), 255, np.uint8),
        )
    )
    rgba[0, 0, 3] = 0
    cv2.imwrite(str(tmp_path / "rgba.png"), cv2.cvtColor(rgba, cv2.COLOR_RGBA2BGRA))

    rgb, alpha = detect_eval.load_rgba(tmp_path / "rgba.png")

    assert rgb.shape == (4, 6, 3)
    # RGB, not BGR: `cv2.imread` hands back BGRA and the loader is what turns it round.
    assert tuple(rgb[1, 1]) == (10, 20, 30)
    assert alpha is not None and alpha[0, 0] == 0 and alpha[1, 1] == 255
