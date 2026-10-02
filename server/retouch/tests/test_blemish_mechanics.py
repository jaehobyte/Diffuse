"""Blemish adapter mechanics without weights: polarity, patch mapping, guard, feather, detector port."""

import threading

import numpy as np

from app.engines.blemish import BlemishEngine, defect_mask, restore_patches, to_model_mask
from app.engines.common import blend_rgb, feather_alpha
from app.engines.detector import (
    LETTERBOX_PAD_VALUE,
    Detection,
    DetectorContract,
    decode_detections,
    letterbox_transform,
    preprocess,
)
from app.engines.patches import plan_patches
from conftest import sample_image


def test_migan_mask_polarity_is_inverted():
    defect = np.zeros((4, 4), dtype=bool)
    defect[1, 2] = True
    model = to_model_mask(defect)
    assert model.dtype == np.uint8
    assert model[1, 2] == 0
    assert np.all(model[~defect] == 255)


def _two_far_defects():
    support = np.zeros((200, 300), dtype=bool)
    support[20:30, 20:28] = True
    support[150:160, 250:262] = True
    return support


def test_patches_map_back_to_their_roi_coordinates_and_guard_outside_support():
    base = sample_image(300, 200)
    support = _two_far_defects()
    calls = []

    def restorer(patch_rgb, model_mask):
        calls.append((patch_rgb.shape, int((model_mask == 0).sum())))
        # A different answer everywhere, including outside the hole (like the wrapper's blending).
        return 255 - patch_rgb

    restored, count = restore_patches(base, support, restorer)
    assert count == 2 == len(calls)
    assert sum(holes for _, holes in calls) == support.sum()
    assert np.array_equal(restored[support], 255 - base[:, :, :3][support])
    assert np.array_equal(restored[~support], base[:, :, :3][~support])


def test_merged_patches_overlap_deterministically():
    support = np.zeros((120, 120), dtype=bool)
    support[40:46, 40:46] = True
    support[40:46, 56:62] = True  # 10 px apart: merged into one patch
    patches = plan_patches(np.where(support, 255, 0).astype(np.uint8))
    assert len(patches) == 1
    assert patches[0].box.width >= 64 and patches[0].box.height >= 64
    base = sample_image(120, 120)
    first, _ = restore_patches(base, support, lambda p, m: 255 - p)
    second, _ = restore_patches(base, support, lambda p, m: 255 - p)
    assert np.array_equal(first, second)


def test_restorer_that_writes_into_its_input_cannot_reach_the_base():
    base = sample_image(100, 100)
    original = base.copy()
    support = np.zeros((100, 100), dtype=bool)
    support[40:50, 40:50] = True

    def scribbler(patch_rgb, model_mask):
        patch_rgb[:] = 0
        return patch_rgb

    restore_patches(base, support, scribbler)
    assert np.array_equal(base, original)


def test_stop_between_patches():
    base = sample_image(300, 200)
    stop = threading.Event()
    stop.set()
    try:
        restore_patches(base, _two_far_defects(), lambda p, m: p, stop)
    except RuntimeError:
        pass
    else:
        raise AssertionError("a stopped run must not complete")


def test_empty_detections_never_reach_the_restorer():
    engine = BlemishEngine.__new__(BlemishEngine)
    restored = []
    engine.detect = lambda rgba: []
    engine.restore = lambda p, m: restored.append(1) or p
    image = sample_image(64, 64)
    assert engine.run(image, np.ones((64, 64), bool), threading.Event()) is None
    assert restored == []


def test_detections_outside_allowance_never_reach_the_restorer():
    engine = BlemishEngine.__new__(BlemishEngine)
    restored = []
    engine.detect = lambda rgba: [Detection((10.0, 10.0, 20.0, 20.0), 0.9, 0)]
    engine.restore = lambda p, m: restored.append(1) or p
    allowed = np.ones((64, 64), bool)
    allowed[:32, :32] = False
    assert engine.run(sample_image(64, 64), allowed, threading.Event()) is None
    assert restored == []


def test_defect_mask_is_an_ellipse_inside_allowed_and_opaque():
    allowed = np.ones((60, 60), dtype=bool)
    allowed[:, 30:] = False
    alpha = np.full((60, 60), 255, np.uint8)
    alpha[:5] = 0
    mask = defect_mask([Detection((0.0, 0.0, 40.0, 40.0), 0.9, 0)], allowed, alpha)
    assert mask.any()
    assert not np.any(mask & ~allowed)
    assert not np.any(mask[:5])
    # Inscribed ellipse: the box corner is not part of the defect.
    corner = defect_mask([Detection((10.0, 10.0, 30.0, 30.0), 0.9, 0)], np.ones((60, 60), bool), np.full((60, 60), 255, np.uint8))
    assert corner[20, 20] and not corner[10, 10]


def test_feather_is_zero_outside_support_and_ramps_inside():
    support = np.zeros((50, 50), dtype=bool)
    support[10:40, 10:40] = True
    alpha = feather_alpha(support, 4.0)
    assert np.all(alpha[~support] == 0)
    assert 0 < alpha[10, 25] < 0.5
    assert alpha[25, 25] == 1.0
    base = sample_image(50, 50)
    corrected = np.zeros((50, 50, 3), np.uint8)
    blended = blend_rgb(base, corrected, alpha)
    assert np.array_equal(blended[~support], base[~support])
    assert np.array_equal(blended[:, :, 3], base[:, :, 3])
    assert np.all(blended[25, 25, :3] == 0)


def test_blemish_run_output_changes_only_inside_support():
    engine = BlemishEngine.__new__(BlemishEngine)
    engine.detect = lambda rgba: [Detection((20.0, 20.0, 32.0, 30.0), 0.9, 0)]
    engine.restore = lambda p, m: np.full_like(p, 128)
    image = sample_image(96, 96)
    original = image.copy()
    out = engine.run(image, np.ones((96, 96), bool), threading.Event())
    assert out is not None and out.support.any()
    assert np.array_equal(out.candidate[~out.support], image[~out.support])
    assert np.array_equal(image, original)


def _reference_preprocess(rgba, size):
    """scripts/retouch/detector.py's flatten_alpha + resize_bilinear, restated for the parity check."""
    alpha = rgba[:, :, 3:4].astype(np.float32) / 255.0
    rgb = rgba[:, :, :3].astype(np.float32) * alpha + np.array(LETTERBOX_PAD_VALUE, np.float32) * (1 - alpha)
    t = letterbox_transform(rgba.shape[1], rgba.shape[0], size)
    src_h, src_w = rgb.shape[:2]

    def axis(dst, src):
        c = np.clip((np.arange(dst, dtype=np.float32) + 0.5) * (src / dst) - 0.5, 0, src - 1.0)
        lo = np.floor(c).astype(np.int64)
        return lo, np.minimum(lo + 1, src - 1), (c - lo).astype(np.float32)

    y0, y1, wy = axis(t.scaled_height, src_h)
    x0, x1, wx = axis(t.scaled_width, src_w)
    top = rgb[y0][:, x0] * (1 - wx)[None, :, None] + rgb[y0][:, x1] * wx[None, :, None]
    bottom = rgb[y1][:, x0] * (1 - wx)[None, :, None] + rgb[y1][:, x1] * wx[None, :, None]
    scaled = top * (1 - wy)[:, None, None] + bottom * wy[:, None, None]
    canvas = np.full((size, size, 3), LETTERBOX_PAD_VALUE, np.float32)
    canvas[t.pad_top : t.pad_top + t.scaled_height, t.pad_left : t.pad_left + t.scaled_width] = scaled
    return np.transpose(canvas / 255.0, (2, 0, 1))[None].astype(np.float32)


def test_preprocess_matches_the_evaluation_reference_on_part_transparent_rois():
    for w, h in ((301, 173), (64, 128), (700, 500)):
        rgba = sample_image(w, h, seed=w)
        rgba[:, :, 3] = np.random.default_rng(h).integers(0, 256, (h, w), dtype=np.uint8)
        tensor, _ = preprocess(rgba, 640)
        assert tensor.shape == (1, 3, 640, 640)
        assert np.allclose(tensor, _reference_preprocess(rgba, 640), atol=1e-5)


def test_decode_maps_a_model_box_back_to_the_roi():
    contract = DetectorContract("images", 640, "output0", 5, 3, 1)
    transform = letterbox_transform(320, 160, 640)  # scale 2, pad_top 160
    raw = np.zeros((1, 5, 3), np.float32)
    raw[0, :, 0] = [100, 260, 40, 40, 0.9]  # ROI box (40,40)-(60,60)
    raw[0, :, 1] = [102, 262, 40, 40, 0.8]  # suppressed by NMS
    raw[0, :, 2] = [np.inf, 0, 1, 1, 0.99]  # non-finite: dropped
    detections = decode_detections(raw, contract, transform, confidence=0.25, iou=0.45, max_detections=100)
    assert len(detections) == 1
    assert np.allclose(detections[0].box, (40.0, 40.0, 60.0, 60.0))
