"""Real-model tests. Skipped by default (`-m "not model"` in pytest.ini); run with `-m model`.

They need `acne_640_fp32.onnx` + manifest and `migan_pipeline_v2.onnx` in RETOUCH_MODEL_DIR
(default ~/.cache/vibe-retouch). They are mechanical checks on a face-free fixture, not quality.
"""

import os
import re
import threading
from pathlib import Path

import cv2
import numpy as np
import pytest

from app.engines.base import enforce
from app.engines.blemish import BlemishEngine, EngineError, restore_patches

pytestmark = pytest.mark.model

MODEL_DIR = Path(os.environ.get("RETOUCH_MODEL_DIR", "~/.cache/vibe-retouch")).expanduser()
FIXTURE = Path(__file__).resolve().parents[3] / "fixtures" / "photo_512.png"


@pytest.fixture(scope="module")
def engine():
    if not (MODEL_DIR / "acne_640_fp32.manifest.json").exists() or not (MODEL_DIR / "migan_pipeline_v2.onnx").exists():
        pytest.skip(f"model files not found in {MODEL_DIR}")
    eng = BlemishEngine(MODEL_DIR, os.environ.get("RETOUCH_EXECUTION_PROVIDER", "auto"))
    eng.load()
    eng.warm_up()
    return eng


def _fixture_rgba():
    bgr = cv2.imread(str(FIXTURE), cv2.IMREAD_COLOR)
    rgba = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGBA)
    return rgba


def test_version_encodes_both_digests(engine):
    assert re.fullmatch(r"blemish/acne-yolov8m-640@18fad8c5\+migan512@6f1f3530/c\d+", engine.version)


def test_real_migan_restores_inside_the_hole_only_after_the_guard(engine):
    rgba = _fixture_rgba()[100:228, 100:228].copy()
    yy, xx = np.mgrid[:128, :128]
    support = (yy - 64) ** 2 + (xx - 64) ** 2 <= 10**2
    restored, count = restore_patches(rgba, support, engine.restore)
    assert count == 1
    changed = np.any(restored != rgba[:, :, :3], axis=2)
    assert changed.sum() > 0.5 * support.sum()
    assert not np.any(changed & ~support)

    # Inverted polarity restores everything except the hole — the mistake to_model_mask prevents.
    raw_inverted = engine.restore(rgba[:, :, :3].copy(), np.where(support, 255, 0).astype(np.uint8))
    assert np.any(raw_inverted != rgba[:, :, :3], axis=2)[~support].sum() > 10 * support.sum()


def test_full_engine_on_the_fixture_obeys_the_guarantees(engine):
    rgba = _fixture_rgba()
    allowed = np.zeros(rgba.shape[:2], bool)
    allowed[60:320, 80:440] = True
    before = rgba.copy()
    out = engine.run(rgba, allowed, threading.Event())
    assert np.array_equal(rgba, before)
    guaranteed = enforce(rgba, allowed, out)
    if out is not None:
        assert np.array_equal(out.candidate[~out.support], rgba[~out.support])
        assert not np.any(out.support & ~allowed)
    if guaranteed.corrected:
        assert np.array_equal(guaranteed.candidate[:, :, 3], rgba[:, :, 3])


def test_wrong_digest_fails_to_load(tmp_path):
    if not (MODEL_DIR / "acne_640_fp32.manifest.json").exists():
        pytest.skip("model files not found")
    (tmp_path / "acne_640_fp32.manifest.json").write_text((MODEL_DIR / "acne_640_fp32.manifest.json").read_text())
    (tmp_path / "acne_640_fp32.onnx").symlink_to(MODEL_DIR / "acne_640_fp32.onnx")
    (tmp_path / "migan_pipeline_v2.onnx").write_bytes(b"not the model")
    with pytest.raises(EngineError):
        BlemishEngine(tmp_path, "cpu").load()
