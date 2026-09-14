"""SR1-A adapter mechanics, per specs/skin_retouch_validation.md §4 (MI-GAN adapter).

These run without model weights: what is checked here is the wiring the weights sit behind —
mask polarity, patch planning, the crop/resize round trip, the untouched input, and the
protection guard. Model quality is a different question and is not decided by this file.

    python3 -m pytest scripts/retouch/test_retouch.py
"""

from __future__ import annotations

import numpy as np
import pytest

from patches import Patch, plan_patches
from restore import RestoreRun, to_model_mask

SIZE = 256


def blank_image() -> np.ndarray:
    image = np.zeros((SIZE, SIZE, 3), dtype=np.uint8)
    image[:, :] = (200, 170, 150)
    return image


def defect_at(*boxes: tuple[int, int, int, int]) -> np.ndarray:
    mask = np.zeros((SIZE, SIZE), dtype=np.uint8)
    for x0, y0, x1, y1 in boxes:
        mask[y0:y1, x0:x1] = 255
    return mask


class RecordingEngine:
    """Stands in for MI-GAN: paints the whole patch magenta and counts the calls."""

    name = "recording"
    version = "test"

    def __init__(self) -> None:
        self.calls: list[tuple[int, int]] = []

    def restore(self, patch_rgb: np.ndarray, patch_model_mask: np.ndarray) -> np.ndarray:
        self.calls.append(patch_rgb.shape[:2])
        assert patch_model_mask.shape == patch_rgb.shape[:2]
        out = patch_rgb.copy()
        out[:, :] = (255, 0, 255)
        return out


def test_defect_mask_is_inverted_for_the_model():
    """The app says 255 = change; MI-GAN reads 255 = keep."""
    defect = defect_at((10, 10, 20, 20))

    model_mask = to_model_mask(defect)

    assert model_mask[15, 15] == 0
    assert model_mask[0, 0] == 255
    assert set(np.unique(model_mask)) <= {0, 255}


def test_a_half_lit_defect_pixel_is_still_a_defect():
    """Anything non-zero is a region to restore; the model input stays strictly binary."""
    defect = np.zeros((4, 4), dtype=np.uint8)
    defect[1, 1] = 7

    assert to_model_mask(defect)[1, 1] == 0


def test_an_empty_defect_mask_never_reaches_the_model():
    engine = RecordingEngine()
    image = blank_image()

    run = RestoreRun(engine).apply(image, defect=np.zeros((SIZE, SIZE), np.uint8))

    assert engine.calls == []
    assert run.changed_pixels == 0
    assert np.array_equal(run.image, image)


def test_the_input_image_is_never_modified():
    engine = RecordingEngine()
    image = blank_image()
    before = image.copy()

    RestoreRun(engine).apply(image, defect=defect_at((100, 100, 110, 110)))

    assert np.array_equal(image, before)


def test_only_the_defect_pixels_change():
    """The engine repaints its whole patch; everything outside the support goes back."""
    engine = RecordingEngine()
    image = blank_image()
    defect = defect_at((100, 100, 110, 110))

    run = RestoreRun(engine).apply(image, defect=defect)

    assert (run.image[defect == 255] == (255, 0, 255)).all()
    assert np.array_equal(run.image[defect == 0], image[defect == 0])
    assert run.changed_pixels == 100


def test_the_protected_region_is_restored_even_inside_a_defect():
    """A defect mask that strays onto an eye must not be able to edit it."""
    engine = RecordingEngine()
    image = blank_image()
    defect = defect_at((100, 100, 120, 120))
    allowed = np.full((SIZE, SIZE), 255, np.uint8)
    allowed[100:110, 100:120] = 0

    run = RestoreRun(engine).apply(image, defect=defect, allowed=allowed)

    assert np.array_equal(run.image[100:110, 100:120], image[100:110, 100:120])
    assert (run.image[110:120, 100:120] == (255, 0, 255)).all()
    assert run.protection_violations == 0


def test_a_patch_is_cropped_around_the_defect_and_pasted_back_where_it_came_from():
    engine = RecordingEngine()
    image = blank_image()
    defect = defect_at((200, 40, 210, 50))

    run = RestoreRun(engine).apply(image, defect=defect)

    assert len(engine.calls) == 1
    assert (run.image[40:50, 200:210] == (255, 0, 255)).all()
    assert run.patches[0].box.contains_box(run.patches[0].defect_box)


def test_far_apart_defects_get_their_own_patches():
    """One bbox around both would shrink each blemish away at the model's fixed size."""
    engine = RecordingEngine()

    RestoreRun(engine).apply(blank_image(), defect=defect_at((10, 10, 16, 16), (220, 220, 226, 226)))

    assert len(engine.calls) == 2


def test_neighbouring_defects_share_one_patch():
    engine = RecordingEngine()

    RestoreRun(engine).apply(blank_image(), defect=defect_at((100, 100, 106, 106), (110, 100, 116, 106)))

    assert len(engine.calls) == 1


def test_patches_are_planned_in_a_deterministic_order():
    defect = defect_at((200, 200, 206, 206), (10, 10, 16, 16), (10, 150, 16, 156))

    order = [(p.defect_box.y0, p.defect_box.x0) for p in plan_patches(defect)]

    assert order == sorted(order)


def test_a_patch_never_leaves_the_image():
    defect = defect_at((0, 0, 4, 4), (SIZE - 4, SIZE - 4, SIZE, SIZE))

    for patch in plan_patches(defect):
        assert patch.box.x0 >= 0 and patch.box.y0 >= 0
        assert patch.box.x1 <= SIZE and patch.box.y1 <= SIZE


def test_a_resized_patch_comes_back_at_its_original_size():
    """The model runs at a fixed size; the crop/resize round trip must land where it started."""
    engine = RecordingEngine()
    image = blank_image()
    defect = defect_at((100, 100, 130, 106))

    run = RestoreRun(engine, model_size=64).apply(image, defect=defect)

    assert engine.calls == [(64, 64)]
    assert (run.image[100:106, 100:130] == (255, 0, 255)).all()
    assert np.array_equal(run.image[defect == 0], image[defect == 0])


def test_overlapping_patches_do_not_reprocess_already_corrected_pixels():
    """Every patch reads the same immutable base, so the result cannot depend on patch order."""
    engine = RecordingEngine()
    image = blank_image()
    defect = defect_at((100, 100, 106, 106), (10, 10, 16, 16))

    forward = RestoreRun(engine).apply(image, defect=defect)
    reverse = RestoreRun(engine).apply(image, defect=defect[::-1, ::-1].copy())

    assert np.array_equal(forward.image, reverse.image[::-1, ::-1])


class InPlaceEngine:
    """Stands in for an engine that uses the buffer it was handed as scratch space."""

    name = "in-place"
    version = "test"

    def restore(self, patch_rgb: np.ndarray, patch_model_mask: np.ndarray) -> np.ndarray:
        patch_rgb[:, :] = (255, 0, 255)
        return patch_rgb


class WrongSizeEngine:
    """Stands in for a model whose output does not correspond to the patch it was asked about."""

    name = "wrong-size"
    version = "test"

    def restore(self, patch_rgb: np.ndarray, patch_model_mask: np.ndarray) -> np.ndarray:
        return np.zeros((7, 11, 3), dtype=np.uint8)


def small_image(size: int = 64) -> np.ndarray:
    image = np.zeros((size, size, 3), dtype=np.uint8)
    image[:, :] = (200, 170, 150)
    return image


def test_a_defect_mask_of_the_wrong_size_is_rejected():
    with pytest.raises(ValueError, match="defect mask"):
        RestoreRun(RecordingEngine()).apply(small_image(), defect=np.full((1, 64), 255, np.uint8))


def test_an_allowed_mask_of_the_wrong_size_is_rejected():
    """A row broadcasts over the whole image, so a protection mask of the wrong shape would
    silently protect nothing and still report `protection_violations=0`."""
    image = small_image()
    defect = np.full((64, 64), 255, np.uint8)

    with pytest.raises(ValueError, match="allowed mask"):
        RestoreRun(RecordingEngine()).apply(image, defect, allowed=np.full((1, 64), 255, np.uint8))


def test_an_engine_output_of_the_wrong_size_is_rejected():
    """specs/skin_retouch_pipeline.md §2: only the adapter's own resize may be inverted."""
    image = small_image()
    defect = np.zeros((64, 64), np.uint8)
    defect[30:34, 30:34] = 255

    with pytest.raises(ValueError, match="engine returned shape"):
        RestoreRun(WrongSizeEngine()).apply(image, defect)


def test_an_engine_that_writes_into_its_input_cannot_reach_the_original():
    """The patch here is the whole image, which is where a view would have shared memory."""
    engine = InPlaceEngine()
    image = small_image()
    before = image.copy()
    defect = np.zeros((64, 64), np.uint8)
    defect[30:34, 30:34] = 255

    run = RestoreRun(engine).apply(image, defect)

    assert np.array_equal(image, before)
    assert run.patches[0].box.width == 64 and run.patches[0].box.height == 64
    assert run.changed_pixels == 16
    assert run.protection_violations == 0
    assert (run.image[30:34, 30:34] == (255, 0, 255)).all()
    assert np.array_equal(run.image[defect == 0], before[defect == 0])


def test_patch_boxes_report_containment():
    box = Patch.box_of(10, 10, 50, 50)

    assert box.contains_box(Patch.box_of(20, 20, 30, 30))
    assert not box.contains_box(Patch.box_of(0, 20, 30, 30))


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__]))
