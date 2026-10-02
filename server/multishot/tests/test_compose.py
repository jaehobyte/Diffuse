"""Layout, feather, source-over and hero protection against independent expectations.

The numbers are the ones `MultiShotLayoutTest`, `MultiShotSubjectTest` and `MultiShotRenderTest`
pin for the app (specs/multishot.md §4.3, §5, §6), recomputed here from first principles.
"""

from __future__ import annotations

import itertools

import numpy as np
import pytest

from app import compose

THIRD = 1 / 3


# -- D088 slots and targets ----------------------------------------------------------------------


@pytest.mark.parametrize(
    "total, hero, taken",
    [(2, 0, [1]), (3, 1, [0, 2]), (4, 1, [0, 2, 3]), (5, 2, [0, 1, 3, 4]), (6, 2, [0, 1, 3, 4, 5])],
)
def test_hero_slot_is_the_left_middle_and_the_others_take_the_rest_in_order(total, hero, taken):
    assert compose.hero_slot(total) == hero
    assert compose.slots(total) == taken


def _targets(total, spacing, hero=(0.5, 0.8)):
    return [compose.slot_target(hero, spacing, slot, total)[0] for slot in compose.slots(total)]


def test_contract_examples_at_a_centred_hero():
    assert _targets(3, 1.0) == pytest.approx([1 / 6, 5 / 6])
    assert _targets(5, 1.0) == pytest.approx([0.1, 0.3, 0.7, 0.9])
    assert _targets(6, 1.0) == pytest.approx([1 / 6, THIRD, 2 * THIRD, 5 / 6, 1.0])
    assert _targets(6, 0.8) == pytest.approx([0.2333333, 0.3666667, 0.6333333, 0.7666667, 0.9])
    assert _targets(6, 0.0) == pytest.approx([0.5] * 5)


def test_an_off_centre_hero_shifts_the_group_unclamped():
    assert _targets(3, 1.0, hero=(0.1, 0.5)) == pytest.approx([0.1 - THIRD, 0.1 + THIRD])
    assert _targets(6, 1.0, hero=(0.9, 0.5))[-1] == pytest.approx(0.9 + 3 / 6)


def test_every_target_is_on_the_heros_height():
    for total, spacing in itertools.product(range(3, 7), (0.0, 0.8, 1.0)):
        assert {compose.slot_target((0.4, 0.73), spacing, s, total)[1] for s in compose.slots(total)} == {0.73}


def test_opacity_profile_runs_from_a_quarter_to_seventy_percent_in_input_order():
    assert [compose.opacity(i, 2) for i in range(2)] == pytest.approx([0.25, 0.7])
    assert [compose.opacity(i, 5) for i in range(5)] == pytest.approx([0.25, 0.3625, 0.475, 0.5875, 0.7])


@pytest.mark.parametrize("size", [(100, 100), (640, 480), (300, 900), (1000, 120)])
@pytest.mark.parametrize("canvas", [(800, 600), (600, 800)])
def test_placement_puts_the_photos_anchor_on_its_target_for_any_size_and_aspect(size, canvas):
    for anchor, target in [((0.5, 1.0), (1 / 6, 0.8)), ((0.2, 0.7), (0.9, 0.3)), ((0.95, 0.4), (0.0, 1.0))]:
        offset = compose.placement(anchor, target, size, canvas)
        assert compose.on_canvas(anchor, offset, size, canvas) == pytest.approx(target, abs=1e-12)
        # From scratch every time: repeating it gives the same offset.
        assert compose.placement(anchor, target, size, canvas) == offset


def test_clip_check_uses_the_transformed_bounds():
    size = canvas = (100, 100)
    box = (0.4, 0.2, 0.6, 1.0)
    inside = compose.placement(compose.anchor_of(box), (0.5, 1.0), size, canvas)
    assert not compose.is_clipped(box, inside, size, canvas)
    edge = compose.placement(compose.anchor_of(box), (1.0, 1.0), size, canvas)
    assert compose.is_clipped(box, edge, size, canvas)


def test_bounds_count_a_one_pixel_club_and_the_anchor_is_their_bottom_centre():
    mask = np.zeros((10, 20), bool)
    mask[2:8, 4:8] = True
    mask[8, 15] = True  # one pixel
    assert compose.bounds(mask) == (4 / 20, 2 / 10, 16 / 20, 9 / 10)
    assert compose.anchor_of(compose.bounds(mask)) == pytest.approx((0.5, 0.9))
    assert compose.bounds(np.zeros((3, 3), bool)) is None


# -- MultiShotSubject ----------------------------------------------------------------------------


def _reference_alpha(photo_alpha: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """Pixel by pixel, as the Kotlin loops read: stretch, 5×5 clamped box, integer rounding."""
    height, width = photo_alpha.shape
    mh, mw = mask.shape
    stretched = [[1 if mask[y * mh // height, x * mw // width] else 0 for x in range(width)] for y in range(height)]
    out = np.zeros_like(photo_alpha)
    for y in range(height):
        for x in range(width):
            total = sum(
                stretched[min(max(y + dy, 0), height - 1)][min(max(x + dx, 0), width - 1)]
                for dy in range(-2, 3)
                for dx in range(-2, 3)
            )
            coverage = (total * 255 + 12) // 25
            out[y, x] = (int(photo_alpha[y, x]) * coverage + 127) // 255
    return out


def _half_mask(width, height):
    mask = np.zeros((height, width), bool)
    mask[:, : width // 2] = True
    return mask


def test_subject_keeps_rgb_and_ramps_the_edge_over_two_pixels():
    photo = np.zeros((20, 20, 4), np.uint8)
    photo[...] = (240, 120, 20, 255)
    subject = compose.subject(photo, _half_mask(20, 20))
    alpha = subject[..., 3]
    assert alpha[10, 5] == 255 and alpha[10, 7] == 255
    assert alpha[10, 15] == 0 and alpha[10, 12] == 0
    ramp = [int(a) for a in alpha[10, 8:12]]
    assert ramp == [204, 153, 102, 51]
    assert (subject[..., :3] == photo[..., :3]).all()  # colour is the photo's, never darkened
    np.testing.assert_array_equal(alpha, _reference_alpha(photo[..., 3], _half_mask(20, 20)))


def test_photo_alpha_is_multiplied_in_and_a_small_mask_is_stretched():
    photo = np.zeros((20, 20, 4), np.uint8)
    photo[...] = (10, 200, 30, 120)
    mask = _half_mask(10, 10)
    alpha = compose.subject(photo, mask)[..., 3]
    assert alpha[3, 3] == 120 and alpha[3, 17] == 0
    np.testing.assert_array_equal(alpha, _reference_alpha(photo[..., 3], mask))


def test_feather_matches_the_reference_on_an_irregular_mask_and_alpha():
    rng = np.random.default_rng(7)
    mask = rng.random((9, 13)) > 0.5
    photo_alpha = rng.integers(0, 256, (17, 23), dtype=np.uint8)
    np.testing.assert_array_equal(compose.subject_alpha(photo_alpha, mask), _reference_alpha(photo_alpha, mask))


# -- MultiShotOp.draw ----------------------------------------------------------------------------


def _solid(size, rgba):
    array = np.zeros((size, size, 4), np.uint8)
    array[...] = rgba
    return array


def _draw_centre(background, subject_rgba, opacity):
    canvas = compose.premultiply(_solid(20, background))
    compose.draw(canvas, _solid(20, subject_rgba), (0.0, 0.0), opacity)
    return compose.unpremultiply(canvas)[10, 10]


def test_translucent_subject_over_an_opaque_background_is_source_over():
    pixel = _draw_centre((0, 0, 255, 255), (255, 0, 0, 255), 0.5)
    assert tuple(pixel) == (128, 0, 127, 255)


def test_over_a_translucent_background_the_destination_alpha_is_composited():
    pixel = _draw_centre((0, 255, 0, 128), (255, 0, 0, 255), 0.5)
    assert pixel[3] == 192
    assert abs(int(pixel[0]) - 170) <= 3 and abs(int(pixel[1]) - 85) <= 3


def test_a_soft_subject_alpha_is_multiplied_by_opacity_once_without_a_halo():
    assert _draw_centre((0, 0, 0, 0), (255, 255, 255, 100), 1.0)[3] == 100
    half = _draw_centre((0, 0, 0, 0), (255, 255, 255, 100), 0.5)
    assert abs(int(half[3]) - 50) <= 1
    assert half[0] >= 251


def test_opacity_zero_changes_nothing_and_a_clear_subject_keeps_the_background_alpha():
    canvas = compose.premultiply(_solid(20, (0, 255, 0, 60)))
    before = canvas.copy()
    compose.draw(canvas, _solid(20, (255, 0, 0, 255)), (0.0, 0.0), 0.0)
    np.testing.assert_array_equal(canvas, before)
    subject = np.zeros((20, 20, 4), np.uint8)
    subject[:5, :5] = (255, 0, 0, 255)
    compose.draw(canvas, subject, (0.0, 0.0), 1.0)
    out = compose.unpremultiply(canvas)
    assert out[18, 18, 3] == 60 and out[2, 2, 3] == 255


def test_later_draws_are_on_top_and_an_integer_shift_is_exact():
    canvas = compose.premultiply(_solid(20, (0, 0, 255, 255)))
    compose.draw(canvas, _solid(20, (255, 0, 0, 255)), (0.0, 0.0), 1.0)
    compose.draw(canvas, _solid(20, (0, 255, 0, 255)), (0.0, 0.0), 1.0)
    assert tuple(compose.unpremultiply(canvas)[10, 10]) == (0, 255, 0, 255)

    canvas = compose.premultiply(_solid(20, (0, 0, 0, 255)))
    dot = np.zeros((20, 20, 4), np.uint8)
    dot[4, 6] = (255, 255, 255, 255)
    compose.draw(canvas, dot, (5 / 20, -3 / 20), 1.0)  # +5 px, −3 px
    out = compose.unpremultiply(canvas)
    assert tuple(out[1, 11]) == (255, 255, 255, 255)
    assert int(out[..., 0].sum()) == 255


def test_a_subject_past_the_edge_is_cut_by_the_canvas():
    canvas = compose.premultiply(_solid(20, (0, 0, 0, 255)))
    compose.draw(canvas, _solid(20, (255, 0, 0, 255)), (0.75, 0.0), 1.0)
    out = compose.unpremultiply(canvas)
    assert (out[:, 15:, 0] == 255).all() and (out[:, :15, 0] == 0).all()


def test_contain_fit_scales_another_aspect_into_the_canvas():
    canvas = compose.premultiply(_solid(40, (0, 0, 0, 255)))
    wide = np.zeros((10, 80, 4), np.uint8)
    wide[...] = (255, 0, 0, 255)
    compose.draw(canvas, wide, (0.0, 0.0), 1.0)  # 80×10 → 40×5, centred
    out = compose.unpremultiply(canvas)
    rows = np.flatnonzero(out[..., 0].max(axis=1) > 0)
    assert rows.min() >= 17 and rows.max() <= 22
    assert tuple(out[20, 20]) == (255, 0, 0, 255)


def test_drawing_is_deterministic():
    rng = np.random.default_rng(3)
    subject = rng.integers(0, 256, (33, 47, 4), dtype=np.uint8)
    first = compose.premultiply(_solid(40, (50, 60, 70, 200)))
    second = first.copy()
    for canvas in (first, second):
        compose.draw(canvas, subject, (0.137, -0.21), 0.6)
    np.testing.assert_array_equal(first, second)


# -- MultiShotOp.protect -------------------------------------------------------------------------


def _reference_lerp(frm, to, weight):
    """Premultiplied lerp in floats, un-premultiplied."""
    fa, ta = frm[3] / 255.0, to[3] / 255.0
    t = weight / 255.0
    alpha = fa * (1 - t) + ta * t
    if alpha == 0:
        return (0, 0, 0, 0)
    colour = [(frm[i] * fa * (1 - t) + to[i] * ta * t) / alpha for i in range(3)]
    return (*colour, alpha * 255)


@pytest.mark.parametrize("hero_pixel", [(0, 0, 0, 0), (0, 255, 0, 100), (20, 40, 60, 255)])
@pytest.mark.parametrize("composite_pixel", [(255, 0, 0, 255), (255, 0, 0, 128), (9, 9, 9, 0)])
def test_protect_is_a_premultiplied_lerp_and_exact_at_zero_and_full(hero_pixel, composite_pixel):
    composite = np.array([[composite_pixel] * 5], np.uint8)
    hero = np.array([[hero_pixel] * 5], np.uint8)
    weights = np.array([[0, 64, 128, 192, 255]], np.uint8)
    out = compose.protect(composite, hero, weights)
    assert tuple(out[0, 0]) == composite_pixel
    assert tuple(out[0, 4]) == hero_pixel
    for i, weight in enumerate((64, 128, 192)):
        expected = _reference_lerp(composite_pixel, hero_pixel, weight)
        assert np.abs(out[0, i + 1].astype(float) - np.array(expected)).max() <= 1.0, (weight, out[0, i + 1])
