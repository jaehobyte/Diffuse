"""Shine / dark circles / shaving shadow mechanics on synthetic skin (no weights needed).

These pin the *mechanics* (support-bounded edits, texture preservation, protected structures),
not quality: synthetic patches are not faces and say nothing about the SR1-C quality gate.
"""

import threading

import cv2
import numpy as np
import pytest

from app.engines.common import lab_to_rgb, rgb_to_lab
from app.engines.dark_circles import DarkCirclesEngine
from app.engines.shaving_shadow import ShavingShadowEngine
from app.engines.shine import ShineEngine

SIZE = 200
YY, XX = np.mgrid[:SIZE, :SIZE]


def skin(seed=0, base=(205, 160, 135), noise=3.0):
    rng = np.random.default_rng(seed)
    rgb = np.clip(np.array(base, np.float32) + rng.normal(0, noise, (SIZE, SIZE, 1)), 0, 255)
    return np.concatenate([rgb, np.full((SIZE, SIZE, 1), 255, np.float32)], 2).astype(np.uint8)


def with_lab(image, edit):
    lab = rgb_to_lab(image)
    edit(lab)
    out = image.copy()
    out[:, :, :3] = lab_to_rgb(lab)
    return out


def highpass(image, sigma=1.5):
    L = rgb_to_lab(image)[:, :, 0]
    return L - cv2.GaussianBlur(L, (0, 0), sigma)


def core_of(support, px=9):
    return support & (cv2.erode(support.astype(np.uint8), np.ones((px, px), np.uint8)) > 0)


def run(engine, image, allowed=None):
    allowed = np.ones(image.shape[:2], bool) if allowed is None else allowed
    before, allowed_before = image.copy(), allowed.copy()
    out = engine.run(image, allowed, threading.Event())
    assert np.array_equal(image, before) and np.array_equal(allowed, allowed_before), "input mutated"
    return out


def assert_support_bounded(out, image, allowed=None):
    assert out.candidate.shape == image.shape and out.candidate.dtype == np.uint8
    assert out.support.dtype == bool and out.support.any()
    if allowed is not None:
        assert not np.any(out.support & ~allowed)
    assert np.array_equal(out.candidate[~out.support], image[~out.support])
    assert np.array_equal(out.candidate[:, :, 3], image[:, :, 3])


@pytest.mark.parametrize("engine", [ShineEngine(), DarkCirclesEngine(), ShavingShadowEngine()])
def test_flat_skin_is_no_change(engine):
    assert run(engine, skin()) is None


@pytest.mark.parametrize("engine", [ShineEngine(), DarkCirclesEngine(), ShavingShadowEngine()])
def test_empty_or_transparent_allowance_is_no_change(engine):
    image = skin()
    assert run(engine, image, np.zeros((SIZE, SIZE), bool)) is None
    image[:, :, 3] = 0
    assert run(engine, image) is None


def test_versions_carry_params_version():
    assert ShineEngine().version == "shine/tone@1"
    assert DarkCirclesEngine().version == "dark_circles/tone@1"
    assert ShavingShadowEngine().version == "shaving_shadow/tone@1"


def _shiny():
    blob = np.exp(-((YY - 100) ** 2 + (XX - 100) ** 2) / (2 * 15**2))

    def edit(lab):
        lab[:, :, 0] += 25 * blob
        lab[:, :, 1] *= 1 - 0.6 * blob
        lab[:, :, 2] *= 1 - 0.6 * blob

    return with_lab(skin(), edit)


def test_shine_reduces_only_the_detected_highlight_and_keeps_texture():
    image = _shiny()
    out = run(ShineEngine(), image)
    assert_support_bounded(out, image)
    L0, L1 = rgb_to_lab(image)[:, :, 0], rgb_to_lab(out.candidate)[:, :, 0]
    assert L0[100, 100] - L1[100, 100] > 10
    assert not out.support[10, 10] and not out.support[190, 30]  # far skin untouched
    assert out.support.mean() < 0.15  # not the whole face
    core = core_of(out.support)
    ratio = highpass(out.candidate)[core].std() / highpass(image)[core].std()
    assert 0.85 < ratio < 1.15


def test_shine_ignores_a_smooth_lighting_gradient():
    image = with_lab(skin(), lambda lab: lab.__setitem__((slice(None), slice(None), 0), lab[:, :, 0] + (XX - 100) * 0.08))
    assert run(ShineEngine(), image) is None


def test_shine_respects_the_allowance():
    image = _shiny()
    allowed = np.ones((SIZE, SIZE), bool)
    allowed[:, 100:] = False
    out = run(ShineEngine(), image, allowed)
    assert_support_bounded(out, image, allowed)


def _under_eye():
    band = np.exp(-((YY - 100) ** 2) / (2 * 20**2)) * (np.abs(XX - 100) < 70)
    lash = (np.abs(YY - 40) <= 1) & (np.abs(XX - 100) < 60)

    def edit(lab):
        lab[:, :, 0] -= 10 * band
        lab[:, :, 2] -= 6 * band
        lab[:, :, 0][lash] = 12

    return with_lab(skin(), edit), lash


def test_dark_circles_lighten_the_band_keep_lashes_and_texture():
    image, lash = _under_eye()
    out = run(DarkCirclesEngine(), image)
    assert_support_bounded(out, image)
    assert not np.any(out.support & lash)
    assert np.array_equal(out.candidate[lash], image[lash])
    lab0, lab1 = rgb_to_lab(image), rgb_to_lab(out.candidate)
    assert lab1[100, 100, 0] - lab0[100, 100, 0] > 4
    assert lab1[100, 100, 2] > lab0[100, 100, 2]  # less blue
    core = core_of(out.support)
    ratio = highpass(out.candidate)[core].std() / highpass(image)[core].std()
    assert 0.85 < ratio < 1.15


def _jaw():
    stubble = (YY > 60) & (YY < 120) & (XX > 30) & (XX < 110)
    beard = (YY > 130) & (YY < 190) & (XX > 120) & (XX < 190)
    hair = beard & (np.random.default_rng(3).random((SIZE, SIZE)) < 0.5)

    def edit(lab):
        lab[:, :, 0][stubble] -= 6
        lab[:, :, 2][stubble] -= 7
        lab[:, :, 1][stubble] -= 2
        lab[:, :, 0][hair] = 15
        lab[:, :, 1][hair] = 2
        lab[:, :, 2][hair] = 3

    return with_lab(skin(), edit), stubble, beard


def test_shaving_shadow_corrects_stubble_cast_and_preserves_a_dense_beard():
    image, stubble, beard = _jaw()
    out = run(ShavingShadowEngine(), image)
    assert_support_bounded(out, image)
    assert not np.any(out.support & beard)
    assert np.array_equal(out.candidate[beard], image[beard])
    assert (out.support & stubble).sum() > 0.8 * stubble.sum()
    lab0, lab1 = rgb_to_lab(image), rgb_to_lab(out.candidate)
    assert lab1[90, 70, 2] - lab0[90, 70, 2] > 2  # warmer (less blue)
    core = core_of(out.support)
    ratio = highpass(out.candidate)[core].std() / highpass(image)[core].std()
    assert 0.85 < ratio < 1.15


def test_shaving_shadow_leaves_a_beard_only_region_alone():
    image, _, beard = _jaw()
    allowed = np.zeros((SIZE, SIZE), bool)
    allowed[125:195, 115:195] = True
    out = run(ShavingShadowEngine(), image, allowed)
    assert out is None or not np.any(out.support & beard)
