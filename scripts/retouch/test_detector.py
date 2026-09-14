"""SR1-B detector mechanics, per specs/skin_retouch_validation.md §4 and `work/tasks.md`.

Weight-free by construction: every test here feeds a hand-built tensor to the decoder or a
hand-built ROI to the preprocessor. Nothing loads the checkpoint, the ONNX, or the network, so
these run in the same `pytest` invocation as the SR1-A adapter tests.

    python3 -m pytest scripts/retouch

What they do **not** show is detection quality. Passing here means the port is wired the way the
manifest says; whether the model finds acne is `detect_eval.py` on the licensed photo set.
"""

from __future__ import annotations

import numpy as np
import pytest

from detector import (
    ACNE_CLASS_IDS,
    DECODE_SEMANTICS,
    LETTERBOX_PAD_VALUE,
    PREPROCESS_SEMANTICS,
    SUPPORTED_MANIFEST_VERSION,
    Detection,
    DetectorContract,
    ManifestContractError,
    candidate_mask,
    decode_detections,
    flatten_alpha,
    letterbox_transform,
    preprocess,
    resize_bilinear,
)

SIZE = 640

CONTRACT = DetectorContract(
    input_name="images",
    input_size=SIZE,
    output_name="output0",
    rows=5,
    anchors=4,
    num_classes=1,
)


def raw_output(*rows: tuple[float, float, float, float, float]) -> np.ndarray:
    """`[1, 5, anchors]` from `(cx, cy, w, h, score)` tuples, one anchor each."""
    out = np.zeros((1, 5, len(rows)), dtype=np.float32)
    for index, (cx, cy, w, h, score) in enumerate(rows):
        out[0, :, index] = (cx, cy, w, h, score)
    return out


def decode(raw: np.ndarray, transform, **options) -> list[Detection]:
    """`decode_detections` with the contract taken from the tensor's own shape."""
    contract = DetectorContract(
        input_name="images",
        input_size=SIZE,
        output_name="output0",
        rows=int(raw.shape[1]),
        anchors=int(raw.shape[2]),
        num_classes=int(raw.shape[1]) - 4,
    )
    return decode_detections(raw, contract, transform, **options)


def full_allowance(width: int, height: int) -> np.ndarray:
    return np.full((height, width), 255, dtype=np.uint8)


# ---------------------------------------------------------------------------------------------
# Preprocessing
# ---------------------------------------------------------------------------------------------


def test_a_square_roi_is_scaled_with_no_padding_at_all():
    transform = letterbox_transform(320, 320, SIZE)

    assert (transform.pad_left, transform.pad_top) == (0, 0)
    assert (transform.pad_right, transform.pad_bottom) == (0, 0)
    assert transform.scale == 2.0


def test_a_wide_roi_is_padded_top_and_bottom_only():
    transform = letterbox_transform(640, 320, SIZE)

    assert (transform.scaled_width, transform.scaled_height) == (640, 320)
    assert (transform.pad_left, transform.pad_right) == (0, 0)
    assert (transform.pad_top, transform.pad_bottom) == (160, 160)


def test_an_odd_remainder_goes_to_the_bottom_and_the_right():
    """The rule the manifest states and the Kotlin adapter repeats: floor to the top and left.

    An implementation that rounded the other way would put every box half a pixel off, in a
    direction that depends on the ROI's parity — which is exactly the bug that never shows up
    until a photo happens to be an odd size.
    """
    transform = letterbox_transform(640, 319, SIZE)

    assert transform.scaled_height == 319
    assert transform.pad_top == 160
    assert transform.pad_bottom == 161
    assert transform.pad_top + transform.scaled_height + transform.pad_bottom == SIZE


def test_a_tall_roi_is_padded_left_and_right():
    transform = letterbox_transform(300, 600, SIZE)

    assert transform.scaled_height == 640
    assert transform.scaled_width == 320
    assert (transform.pad_left, transform.pad_right) == (160, 160)


def test_a_small_roi_is_scaled_up_rather_than_floated_in_grey():
    """A 200px face is the smallest the pipeline offers (§3); refusing to upscale would hand the
    model a 640px canvas that is 90% padding."""
    transform = letterbox_transform(200, 200, SIZE)

    assert transform.scale == 3.2
    assert (transform.scaled_width, transform.scaled_height) == (640, 640)


def test_an_empty_roi_is_rejected():
    with pytest.raises(ValueError):
        letterbox_transform(0, 100, SIZE)


def test_the_tensor_is_rgb_float_nchw_in_zero_to_one():
    roi = np.zeros((8, 8, 3), dtype=np.uint8)
    roi[:, :] = (255, 0, 0)

    tensor, _ = preprocess(roi, 8)

    assert tensor.shape == (1, 3, 8, 8)
    assert tensor.dtype == np.float32
    # Red in channel 0, not channel 2: a BGR tensor is the mistake requirement 5 names.
    assert tensor[0, 0, 4, 4] == pytest.approx(1.0)
    assert tensor[0, 2, 4, 4] == pytest.approx(0.0)


def test_padding_uses_the_fixed_grey_and_the_image_is_placed_inside_it():
    roi = np.zeros((4, 8, 3), dtype=np.uint8)
    roi[:, :] = (10, 20, 30)

    tensor, transform = preprocess(roi, 8)

    grey = LETTERBOX_PAD_VALUE[0] / 255.0
    assert tensor[0, 0, 0, 0] == pytest.approx(grey)
    assert tensor[0, 0, transform.pad_top, 0] == pytest.approx(10 / 255.0)


def test_transparent_pixels_are_composited_over_the_fixed_background():
    rgba = np.zeros((2, 2, 4), dtype=np.uint8)
    rgba[:, :, :3] = (0, 0, 0)
    rgba[0, 0, 3] = 0
    rgba[0, 1, 3] = 255

    flattened = flatten_alpha(rgba)

    assert tuple(flattened[0, 0]) == pytest.approx(LETTERBOX_PAD_VALUE)
    assert tuple(flattened[0, 1]) == pytest.approx((0.0, 0.0, 0.0))


def test_a_half_transparent_pixel_is_composited_in_float_not_quantised():
    """`work/tasks.md` requirement 5. Rounding the composite back to 8 bits before resampling
    loses up to half a level, and Android composites in float — so this is where the two
    implementations would silently disagree on a ROI with soft edges."""
    rgba = np.zeros((1, 1, 4), dtype=np.uint8)
    rgba[0, 0] = (0, 0, 0, 128)

    flattened = flatten_alpha(rgba)

    exact = 114.0 * (1.0 - 128.0 / 255.0)
    assert flattened.dtype == np.float32
    assert flattened[0, 0, 0] == pytest.approx(exact, abs=1e-4)
    # The uint8 round trip this replaces would have landed on 57.0 instead.
    assert abs(flattened[0, 0, 0] - 57.0) > 0.2


def test_flattening_alpha_does_not_touch_the_caller_s_pixels():
    rgba = np.zeros((2, 2, 4), dtype=np.uint8)
    rgba[:, :, :3] = 200
    before = rgba.copy()

    flatten_alpha(rgba)

    assert np.array_equal(rgba, before)


def test_bilinear_resize_uses_half_pixel_centres():
    """Two source pixels to four destination ones: the interior samples land at 1/4 and 3/4, and
    the edges clamp. Written out because Android and OpenCV each have their own convention and
    requirement 5 wants one."""
    source = np.array([[[0.0, 0, 0], [100.0, 100, 100]]], dtype=np.float32)

    resized = resize_bilinear(source, 4, 1)

    assert [round(float(v), 3) for v in resized[0, :, 0]] == [0.0, 25.0, 75.0, 100.0]


def test_preprocessing_rejects_a_bgr_shaped_but_wrong_dtype_roi():
    with pytest.raises(ValueError):
        preprocess(np.zeros((4, 4, 3), dtype=np.float32), 8)


# ---------------------------------------------------------------------------------------------
# The contract
# ---------------------------------------------------------------------------------------------


def graph(shape_in=(1, 3, 640, 640), shape_out=(1, 5, 8400)):
    return (
        [{"name": "images", "shape": list(shape_in), "dtype": "tensor(float)"}],
        [{"name": "output0", "shape": list(shape_out), "dtype": "tensor(float)"}],
    )


def test_the_contract_is_read_off_the_graph():
    contract = DetectorContract.from_graph(*graph(), num_classes=1)

    assert (contract.input_size, contract.rows, contract.anchors) == (640, 5, 8400)


def test_an_output_that_is_not_four_plus_nc_is_rejected():
    """Requirement 6: the decoder does not guess. A head with objectness, or with more classes
    than the checkpoint declares, needs its own decoder rather than this one pointed at it."""
    with pytest.raises(ValueError, match="4 \\+ nc"):
        DetectorContract.from_graph(*graph(shape_out=(1, 6, 8400)), num_classes=1)


def test_a_non_square_or_multi_output_graph_is_rejected():
    with pytest.raises(ValueError):
        DetectorContract.from_graph(*graph(shape_in=(1, 3, 640, 480)), num_classes=1)
    inputs, outputs = graph()
    with pytest.raises(ValueError):
        DetectorContract.from_graph(inputs, outputs * 2, num_classes=1)


def test_a_graph_that_is_not_float32_is_rejected():
    """Requirement 2: FP32 is the exported contract. An FP16 or quantised graph of exactly the
    same shape is a different numerical contract, and this preprocessor writes float32 into it."""
    inputs, outputs = graph()
    with pytest.raises(ValueError, match="tensor\\(float\\)"):
        DetectorContract.from_graph(
            [dict(inputs[0], dtype="tensor(float16)")], outputs, num_classes=1
        )
    with pytest.raises(ValueError, match="tensor\\(float\\)"):
        DetectorContract.from_graph(
            inputs, [dict(outputs[0], dtype="tensor(uint8)")], num_classes=1
        )


# ---------------------------------------------------------------------------------------------
# The manifest's execution semantics
# ---------------------------------------------------------------------------------------------


def manifest(**overrides) -> dict:
    """A manifest that agrees with this implementation, so a test can change one field.

    Every case below keeps the model digest and the I/O shapes identical and moves only what the
    run **means** — which is exactly the mismatch a digest check cannot see.
    """
    inputs, outputs = graph()
    base = {
        "manifest_version": SUPPORTED_MANIFEST_VERSION,
        "model": {"file": "acne_640_fp32.onnx", "sha256": "00" * 32, "bytes": 11},
        "io": {"inputs": inputs, "outputs": outputs},
        "preprocess": dict(PREPROCESS_SEMANTICS),
        "decode": {
            **DECODE_SEMANTICS,
            "rows": 5,
            "anchors": 8400,
            "num_classes": 1,
            "acne_class_ids": [0],
        },
        "postprocess_defaults": {"confidence": 0.25, "nms_iou": 0.45, "max_detections": 100},
    }
    for dotted, value in overrides.items():
        section, _, key = dotted.partition("__")
        if key:
            base[section] = {**base[section], key: value}
        else:
            base[section] = value
    return base


def test_a_manifest_that_agrees_with_this_implementation_is_read():
    contract = DetectorContract.from_manifest(manifest())

    assert (contract.input_size, contract.rows, contract.anchors) == (640, 5, 8400)


@pytest.mark.parametrize(
    "field, value",
    [
        ("preprocess__color", "BGR"),
        ("preprocess__layout", "NHWC"),
        ("preprocess__interpolation", "nearest"),
        ("preprocess__pad_value", [0, 0, 0]),
        ("preprocess__transparent_background", [0, 0, 0]),
        ("preprocess__scale_up", False),
        ("preprocess__scale", 1.0),
        ("decode__box_format", "xyxy"),
        ("decode__box_units", "normalised 0..1"),
        ("decode__objectness", True),
        ("decode__embedded_nms", True),
        ("decode__class_activation", "apply sigmoid"),
        ("decode__layout", "[1, anchors, 4 + num_classes]"),
    ],
)
def test_a_manifest_whose_run_means_something_else_is_refused(field, value):
    """Requirement 3 of `work/REVIEW.md`, and `work/tasks.md` requirements 2, 5 and 6.

    The digest matches, the shapes match, and the export means something this code does not do:
    `cxcywh` read as `xyxy` gives well-formed boxes in the wrong place, BGR gives the model a
    picture with its channels swapped, and `embedded_nms` gets suppressed a second time. Every one
    of these is a wrong result that nothing downstream could notice, so it is refused up front.
    """
    with pytest.raises(ManifestContractError):
        DetectorContract.from_manifest(manifest(**{field: value}))


def test_a_manifest_from_a_later_version_of_the_tool_is_refused():
    with pytest.raises(ManifestContractError, match="manifest version"):
        DetectorContract.from_manifest(manifest(manifest_version=2))


def test_a_manifest_missing_a_semantics_field_is_refused_rather_than_defaulted():
    incomplete = manifest()
    del incomplete["preprocess"]["pad_placement"]

    with pytest.raises(ManifestContractError, match="pad_placement"):
        DetectorContract.from_manifest(incomplete)


def test_a_manifest_allowlist_naming_no_class_this_model_has_is_refused():
    with pytest.raises(ManifestContractError, match="allowlist"):
        DetectorContract.from_manifest(manifest(decode__acne_class_ids=[7]))
    with pytest.raises(ManifestContractError):
        DetectorContract.from_manifest(manifest(decode__acne_class_ids=[]))


def test_the_exported_manifest_is_one_this_implementation_accepts():
    """The writer and the reader are the same two dicts; this is the round trip that proves it."""
    contract = DetectorContract.from_graph(*graph(), num_classes=1)
    written = {
        "manifest_version": SUPPORTED_MANIFEST_VERSION,
        "io": {"inputs": graph()[0], "outputs": graph()[1]},
        "preprocess": dict(PREPROCESS_SEMANTICS),
        "decode": contract.as_manifest(),
    }

    assert DetectorContract.from_manifest(written) == contract
    assert written["decode"]["acne_class_ids"] == list(ACNE_CLASS_IDS)


# ---------------------------------------------------------------------------------------------
# Decoding
# ---------------------------------------------------------------------------------------------


def test_a_box_is_mapped_back_through_the_letterbox_into_roi_pixels():
    transform = letterbox_transform(320, 160, SIZE)  # scale 2, 160px of padding top and bottom
    raw = raw_output((200.0, 160.0 + 100.0, 40.0, 20.0, 0.9))

    (detection,) = decode(raw, transform)

    assert detection.box == pytest.approx((90.0, 45.0, 110.0, 55.0))
    assert detection.score == pytest.approx(0.9)
    assert detection.class_id == 0


def test_a_below_threshold_anchor_is_not_a_detection():
    transform = letterbox_transform(SIZE, SIZE, SIZE)
    raw = raw_output((100.0, 100.0, 10.0, 10.0, 0.24))

    assert decode(raw, transform, confidence=0.25) == []


def test_no_detection_is_a_normal_empty_result_and_not_an_error():
    transform = letterbox_transform(SIZE, SIZE, SIZE)

    assert decode(np.zeros((1, 5, 4), dtype=np.float32), transform) == []


def test_overlapping_boxes_of_one_class_are_suppressed_to_the_higher_score():
    transform = letterbox_transform(SIZE, SIZE, SIZE)
    raw = raw_output(
        (100.0, 100.0, 40.0, 40.0, 0.9),
        (104.0, 104.0, 40.0, 40.0, 0.8),
    )

    detections = decode(raw, transform)

    assert len(detections) == 1
    assert detections[0].score == pytest.approx(0.9)


def test_boxes_far_enough_apart_both_survive():
    transform = letterbox_transform(SIZE, SIZE, SIZE)
    raw = raw_output(
        (100.0, 100.0, 20.0, 20.0, 0.9),
        (400.0, 400.0, 20.0, 20.0, 0.8),
    )

    assert len(decode(raw, transform)) == 2


def test_nms_is_applied_once_and_only_by_us():
    """Two boxes at exactly the NMS threshold's far side stay. If something in the graph had
    already suppressed, or if this ran twice, the count would drop."""
    transform = letterbox_transform(SIZE, SIZE, SIZE)
    # IoU of two 20px boxes 15px apart is 25/175 = 0.14, below 0.45.
    raw = raw_output(
        (100.0, 100.0, 20.0, 20.0, 0.9),
        (115.0, 100.0, 20.0, 20.0, 0.7),
        (130.0, 100.0, 20.0, 20.0, 0.5),
    )

    assert len(decode(raw, transform)) == 3


def test_equal_scores_are_ordered_deterministically():
    transform = letterbox_transform(SIZE, SIZE, SIZE)
    raw = raw_output(
        (400.0, 400.0, 20.0, 20.0, 0.5),
        (100.0, 100.0, 20.0, 20.0, 0.5),
    )

    first = decode(raw, transform)
    second = decode(raw, transform)

    assert [d.box for d in first] == [d.box for d in second]
    # The tie breaks on anchor index, so anchor 0 comes first however the scores compare.
    assert first[0].box[0] == pytest.approx(390.0)


def test_the_detection_cap_is_honoured():
    transform = letterbox_transform(SIZE, SIZE, SIZE)
    rows = [(20.0 + 30 * i, 20.0, 10.0, 10.0, 0.9) for i in range(10)]
    raw = raw_output(*rows)

    assert len(decode(raw, transform, max_detections=3)) == 3


def test_a_class_outside_the_allowlist_is_not_acne():
    """Requirement 6: an unverified class is not treated as a blemish. With `nc=1` the allowlist
    is `{0}`; asking for class 1 is a configuration error, not a silent empty result."""
    transform = letterbox_transform(SIZE, SIZE, SIZE)
    raw = raw_output((100.0, 100.0, 20.0, 20.0, 0.9))

    assert ACNE_CLASS_IDS == (0,)
    with pytest.raises(ValueError):
        decode(raw, transform, class_ids=(1,))


def test_a_box_that_lies_entirely_in_the_padding_is_dropped():
    transform = letterbox_transform(640, 320, SIZE)  # 160px of padding top and bottom
    raw = raw_output((320.0, 40.0, 20.0, 20.0, 0.9))

    assert decode(raw, transform) == []


def test_a_box_hanging_over_the_edge_is_clipped_to_the_roi():
    transform = letterbox_transform(SIZE, SIZE, SIZE)
    raw = raw_output((10.0, 10.0, 60.0, 60.0, 0.9))

    (detection,) = decode(raw, transform)

    assert detection.box[0] == 0.0
    assert detection.box[1] == 0.0
    assert detection.box[2] == pytest.approx(40.0)


def test_a_degenerate_box_is_dropped():
    transform = letterbox_transform(SIZE, SIZE, SIZE)
    raw = raw_output((100.0, 100.0, 0.0, 20.0, 0.9))

    assert decode(raw, transform) == []


def test_nan_and_inf_anchors_are_dropped_rather_than_masking_the_whole_face():
    transform = letterbox_transform(SIZE, SIZE, SIZE)
    raw = raw_output(
        (float("nan"), 100.0, 20.0, 20.0, 0.99),
        (float("inf"), 100.0, 20.0, 20.0, 0.99),
        (300.0, 300.0, 20.0, 20.0, 0.9),
    )

    detections = decode(raw, transform)

    assert len(detections) == 1
    assert detections[0].box[0] == pytest.approx(290.0)


def test_an_output_of_the_wrong_shape_is_rejected():
    transform = letterbox_transform(SIZE, SIZE, SIZE)

    with pytest.raises(ValueError):
        decode_detections(np.zeros((1, 6, 4), dtype=np.float32), CONTRACT, transform)


def test_a_non_square_roi_round_trips_through_preprocessing_and_decoding():
    """The whole path on one odd shape: preprocess a 301x173 ROI, put a box at the model-space
    position of a known ROI rectangle, and get that rectangle back."""
    roi = np.zeros((173, 301, 3), dtype=np.uint8)
    _, transform = preprocess(roi, SIZE)
    x0, y0, x1, y1 = 50.0, 30.0, 90.0, 70.0
    cx = (x0 + x1) / 2 * transform.scale + transform.pad_left
    cy = (y0 + y1) / 2 * transform.scale + transform.pad_top
    raw = raw_output((cx, cy, (x1 - x0) * transform.scale, (y1 - y0) * transform.scale, 0.9))

    (detection,) = decode(raw, transform)

    assert detection.box == pytest.approx((x0, y0, x1, y1), abs=1e-3)


# ---------------------------------------------------------------------------------------------
# Boxes to a candidate mask
# ---------------------------------------------------------------------------------------------


def test_the_candidate_mask_is_the_box_interior_and_nothing_more():
    """Requirement 7's baseline: rasterise, do not dilate. A pixel is in when its centre is."""
    detections = [Detection(box=(2.0, 3.0, 6.0, 7.0), score=0.9, class_id=0)]

    mask = candidate_mask(detections, full_allowance(10, 10))

    assert set(np.unique(mask)) <= {0, 255}
    assert mask[3:7, 2:6].all()
    assert mask.sum() == 255 * 16


def test_the_candidate_mask_never_leaves_the_allowance():
    allowed = np.zeros((10, 10), dtype=np.uint8)
    allowed[0:5, 0:5] = 255
    detections = [Detection(box=(0.0, 0.0, 10.0, 10.0), score=0.9, class_id=0)]

    mask = candidate_mask(detections, allowed)

    assert not (mask & ~allowed).any()
    assert mask.sum() == 255 * 25


def test_the_candidate_mask_excludes_transparent_pixels():
    alpha = np.full((10, 10), 255, dtype=np.uint8)
    alpha[0:3, :] = 0
    detections = [Detection(box=(0.0, 0.0, 10.0, 10.0), score=0.9, class_id=0)]

    mask = candidate_mask(detections, full_allowance(10, 10), alpha=alpha)

    assert not mask[0:3, :].any()
    assert mask[3:, :].all()


def test_an_empty_allowance_gives_an_empty_candidate_mask():
    detections = [Detection(box=(0.0, 0.0, 10.0, 10.0), score=0.9, class_id=0)]

    mask = candidate_mask(detections, np.zeros((10, 10), dtype=np.uint8))

    assert not mask.any()


def test_no_detections_gives_an_empty_mask_rather_than_the_whole_allowance():
    mask = candidate_mask([], full_allowance(10, 10))

    assert not mask.any()


def test_the_allowance_is_not_modified():
    allowed = full_allowance(10, 10)
    before = allowed.copy()

    candidate_mask([Detection(box=(1.0, 1.0, 5.0, 5.0), score=0.9, class_id=0)], allowed)

    assert np.array_equal(allowed, before)


def test_a_box_outside_the_roi_contributes_nothing():
    mask = candidate_mask(
        [Detection(box=(20.0, 20.0, 30.0, 30.0), score=0.9, class_id=0)], full_allowance(10, 10)
    )

    assert not mask.any()


def test_adjacent_boxes_do_not_both_claim_the_shared_edge():
    """Half-open on the right, so the pixel column at x=5 belongs to the second box only."""
    left = Detection(box=(0.0, 0.0, 5.0, 10.0), score=0.9, class_id=0)
    right = Detection(box=(5.0, 0.0, 10.0, 10.0), score=0.8, class_id=0)

    both = candidate_mask([left, right], full_allowance(10, 10))
    only_left = candidate_mask([left], full_allowance(10, 10))

    assert both.sum() == 255 * 100
    assert only_left.sum() == 255 * 50


def test_an_allowance_of_the_wrong_dtype_is_rejected():
    with pytest.raises(ValueError):
        candidate_mask([], np.zeros((10, 10), dtype=np.float32))


# ---------------------------------------------------------------------------------------------
# The cross-language preprocessing fixture
# ---------------------------------------------------------------------------------------------


def test_preprocessing_matches_the_shared_fixture():
    """`work/tasks.md` requirement 5: one contract, read by both implementations.

    The same file is asserted against by `core/ai`'s `DetectorPreprocessParityTest`, so changing
    either preprocessor without regenerating the fixture (`acne_model.py fixture`) fails here,
    and regenerating it without changing the Kotlin side fails there.
    """
    import json
    from pathlib import Path

    from acne_model import fixture_roi

    path = (
        Path(__file__).resolve().parents[2]
        / "core/ai/src/test/resources/retouch/preprocess_parity.json"
    )
    fixture = json.loads(path.read_text())
    size = fixture["model_size"]
    assert list(LETTERBOX_PAD_VALUE) == fixture["pad_value"]

    for case in fixture["cases"]:
        width, height = case["roi"]
        tensor, transform = preprocess(fixture_roi(width, height, case["alpha"]), size)
        box = case["letterbox"]
        assert [transform.scaled_width, transform.scaled_height] == box["scaled"], case["roi"]
        assert transform.pad_left == box["pad_left"], case["roi"]
        assert transform.pad_top == box["pad_top"], case["roi"]
        assert transform.pad_right == box["pad_right"], case["roi"]
        assert transform.pad_bottom == box["pad_bottom"], case["roi"]
        assert transform.scale == pytest.approx(box["scale"])
        for sample in case["samples"]:
            assert tensor[0, sample["c"], sample["y"], sample["x"]] == pytest.approx(
                sample["v"], abs=fixture["tolerance"]["sample_abs"]
            ), (case["roi"], sample)
        assert float(np.asarray(tensor[0], dtype=np.float64).sum()) == pytest.approx(
            case["sum"], rel=fixture["tolerance"]["sum_rel"]
        )
