import json
import struct
import zlib

import numpy as np
import pytest
from fastapi.testclient import TestClient

import app.main as main_module
from app.config import MAX_BODY_BYTES
from app.engines.base import EngineOutput
from app.main import create_app
from conftest import (
    FakeEngine,
    TOKEN,
    auth,
    decode_png,
    factory_of,
    full_mask,
    metadata,
    multipart,
    parse_response_parts,
    png_color_type,
    png_gray,
    png_rgb,
    png_rgba,
    post,
    sample_image,
    settings,
    standard_parts,
    wait_ready,
)


def _client_with(behavior):
    engine = FakeEngine("blemish", behavior=behavior)
    app = create_app(settings(enabled_kinds=("blemish",)), factory_of({"blemish": engine}))
    return engine, app


def test_corrected_response_shape_and_request_id_echo(client):
    image = sample_image()
    mask = full_mask()
    mask[:, 16:] = 0
    response = post(client, standard_parts(meta=metadata(request_id="Abc_-09"), image=image, mask=mask))
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("multipart/form-data; boundary=")
    parts = parse_response_parts(response)
    assert list(parts) == ["metadata", "candidate", "change_support"]
    content_type, raw = parts["metadata"]
    assert content_type == "application/json"
    meta = json.loads(raw)
    assert meta["request_id"] == "Abc_-09"
    assert meta["contract_version"] == 1
    assert meta["kind"] == "blemish"
    assert meta["engine_version"] == "blemish/fake@1"
    assert meta["outcome"] == "corrected"
    assert (meta["width"], meta["height"]) == (32, 24)
    assert isinstance(meta["timing_ms"], dict) and "total" in meta["timing_ms"]

    assert parts["candidate"][0] == "image/png"
    assert png_color_type(parts["candidate"][1]) == (8, 6)
    assert png_color_type(parts["change_support"][1]) == (8, 0)
    candidate = decode_png(parts["candidate"][1])
    support = decode_png(parts["change_support"][1])
    assert candidate.shape == (24, 32, 4) and support.shape == (24, 32)
    assert set(np.unique(support)) <= {0, 255}
    assert np.array_equal(support == 255, mask == 255)
    assert np.array_equal(candidate[mask == 0], image[mask == 0])
    assert np.array_equal(candidate[:, :, 3], image[:, :, 3])


def test_no_change_has_only_metadata():
    _, app = _client_with(lambda image, allowed, stop: None)
    with TestClient(app) as client:
        wait_ready(client)
        response = post(client, standard_parts())
        assert response.status_code == 200
        parts = parse_response_parts(response)
        assert list(parts) == ["metadata"]
        meta = json.loads(parts["metadata"][1])
        assert meta["outcome"] == "no_change"
        assert meta["engine_version"] == "blemish/fake@1"


def test_empty_allowed_mask_never_calls_the_engine(client, blemish_engine):
    response = post(client, standard_parts(mask=full_mask(value=0)))
    assert response.status_code == 200
    assert list(parse_response_parts(response)) == ["metadata"]
    assert json.loads(parse_response_parts(response)["metadata"][1])["outcome"] == "no_change"
    assert blemish_engine.calls == 0


def test_fully_transparent_allowance_is_no_change(client):
    image = sample_image()
    image[:, :, 3] = 0
    response = post(client, standard_parts(image=image))
    assert json.loads(parse_response_parts(response)["metadata"][1])["outcome"] == "no_change"


# ------------------------------------------------------------------------------------------
# Central guarantees
# ------------------------------------------------------------------------------------------


def test_engine_output_is_clamped_to_allowed_alpha_and_support():
    def rogue(image, allowed, stop):
        candidate = np.zeros_like(image)  # changes everything, including alpha
        support = np.ones(allowed.shape, dtype=bool)  # claims everything
        return EngineOutput(candidate, support)

    _, app = _client_with(rogue)
    image = sample_image()
    image[:4, :, 3] = 0  # transparent strip
    image[4:8, :, 3] = 128  # part-transparent strip
    mask = full_mask()
    mask[:, :10] = 0
    with TestClient(app) as client:
        wait_ready(client)
        parts = parse_response_parts(post(client, standard_parts(image=image, mask=mask)))
    candidate = decode_png(parts["candidate"][1])
    support = decode_png(parts["change_support"][1]) == 255
    assert not np.any(support & (mask == 0))
    assert not np.any(support & (image[:, :, 3] == 0))
    assert np.array_equal(candidate[~support], image[~support])
    assert np.array_equal(candidate[:, :, 3], image[:, :, 3])


def test_engine_that_mutates_its_input_cannot_reach_the_original():
    def mutating(image, allowed, stop):
        image[:] = 7  # scribble over the array it was handed
        allowed[:] = True
        support = np.zeros(allowed.shape, dtype=bool)
        support[10:12, 10:12] = True
        return EngineOutput(image, support)

    _, app = _client_with(mutating)
    original = sample_image()
    mask = full_mask()
    mask[:, 20:] = 0
    with TestClient(app) as client:
        wait_ready(client)
        parts = parse_response_parts(post(client, standard_parts(image=original, mask=mask)))
    candidate = decode_png(parts["candidate"][1])
    support = decode_png(parts["change_support"][1]) == 255
    assert support.sum() == 4
    assert np.array_equal(candidate[~support], original[~support])
    assert np.array_equal(candidate[:, :, 3], original[:, :, 3])


def test_corrected_with_empty_support_or_no_pixel_change_is_no_change():
    for behavior in (
        lambda image, allowed, stop: EngineOutput(np.zeros_like(image), np.zeros(allowed.shape, bool)),
        lambda image, allowed, stop: EngineOutput(image.copy(), allowed.copy()),
    ):
        _, app = _client_with(behavior)
        with TestClient(app) as client:
            wait_ready(client)
            parts = parse_response_parts(post(client, standard_parts()))
        assert list(parts) == ["metadata"]


def test_engine_failure_is_500_without_details():
    def broken(image, allowed, stop):
        raise RuntimeError("secret detail")

    _, app = _client_with(broken)
    with TestClient(app) as client:
        wait_ready(client)
        response = post(client, standard_parts())
    assert response.status_code == 500
    assert response.json() == {"error": "internal_error"}


# ------------------------------------------------------------------------------------------
# 400 invalid_request / unsupported_contract
# ------------------------------------------------------------------------------------------


def _replace(parts, name, new):
    return [new if p[0] == name else p for p in parts]


def _bad(client, parts, code="invalid_request", request_id="req-1"):
    response = post(client, parts)
    assert response.status_code == 400, response.text
    expected = {"error": code}
    if request_id is not None:
        expected["request_id"] = request_id
    assert response.json() == expected
    return response


def test_missing_duplicate_and_unknown_parts(client, blemish_engine):
    parts = standard_parts()
    _bad(client, parts[:2], request_id=None)
    _bad(client, parts + [parts[1]], request_id=None)
    _bad(client, parts + [("extra", "text/plain", b"x")], request_id=None)
    _bad(client, _replace(parts, "image", ("image", "image/jpeg", parts[1][2])), request_id=None)
    assert blemish_engine.calls == 0


def test_request_that_is_not_multipart(client):
    response = client.post("/v1/retouch", content=b"{}", headers={**auth(), "Content-Type": "application/json"})
    assert response.status_code == 400
    assert response.json() == {"error": "invalid_request"}


def test_truncated_multipart(client):
    body, content_type = multipart(standard_parts())
    response = client.post(
        "/v1/retouch", content=body[: len(body) // 2], headers={**auth(), "Content-Type": content_type}
    )
    assert response.status_code == 400


@pytest.mark.parametrize(
    "raw",
    [
        b"not json",
        b"[]",
        json.dumps(metadata(request_id="has space")).encode(),
        json.dumps(metadata(request_id="x" * 65)).encode(),
        json.dumps({k: v for k, v in metadata().items() if k != "request_id"}).encode(),
    ],
)
def test_bad_metadata_without_usable_request_id(client, raw):
    _bad(client, _replace(standard_parts(), "metadata", ("metadata", "application/json", raw)), request_id=None)


@pytest.mark.parametrize(
    "overrides",
    [
        {"contract_version": "1"},
        {"contract_version": True},
        {"kind": 3},
        {"expected_engine_version": None},
        {"width": 0},
        {"height": -1},
        {"width": 32.0},
    ],
)
def test_bad_metadata_fields(client, overrides):
    meta = metadata(**overrides)
    parts = _replace(standard_parts(), "metadata", ("metadata", "application/json", json.dumps(meta).encode()))
    _bad(client, parts)


def test_unsupported_contract_version(client):
    meta = metadata(contract_version=2)
    parts = _replace(standard_parts(), "metadata", ("metadata", "application/json", json.dumps(meta).encode()))
    _bad(client, parts, code="unsupported_contract")


def test_image_must_be_rgba_and_mask_gray(client, blemish_engine):
    image = sample_image()
    parts = standard_parts()
    _bad(client, _replace(parts, "image", ("image", "image/png", png_rgb(image[:, :, :3].copy()))))
    _bad(client, _replace(parts, "image", ("image", "image/png", png_gray(image[:, :, 0].copy()))))
    _bad(client, _replace(parts, "allowed_mask", ("allowed_mask", "image/png", png_rgba(image))))
    # 16-bit grayscale mask
    import cv2

    ok, buf = cv2.imencode(".png", np.full((24, 32), 65535, np.uint16))
    _bad(client, _replace(parts, "allowed_mask", ("allowed_mask", "image/png", buf.tobytes())))
    assert blemish_engine.calls == 0


def test_non_binary_mask(client, blemish_engine):
    mask = full_mask()
    mask[0, 0] = 128
    _bad(client, standard_parts(mask=mask))
    assert blemish_engine.calls == 0


def test_size_mismatch(client):
    parts = standard_parts()
    _bad(client, _replace(parts, "image", ("image", "image/png", png_rgba(sample_image(31, 24)))))
    _bad(client, _replace(parts, "allowed_mask", ("allowed_mask", "image/png", png_gray(full_mask(32, 23)))))
    meta = metadata(width=33)
    _bad(client, _replace(parts, "metadata", ("metadata", "application/json", json.dumps(meta).encode())))


def test_corrupt_png(client):
    good = png_rgba(sample_image())
    truncated = good[: len(good) - 40]
    _bad(client, _replace(standard_parts(), "image", ("image", "image/png", truncated)))
    flipped = bytearray(good)
    flipped[60] ^= 0xFF  # inside IDAT: CRC or zlib failure
    _bad(client, _replace(standard_parts(), "image", ("image", "image/png", bytes(flipped))))
    _bad(client, _replace(standard_parts(), "image", ("image", "image/png", b"GIF89a....")))


# ------------------------------------------------------------------------------------------
# 409 / 413 / 422
# ------------------------------------------------------------------------------------------


def test_engine_mismatch(client, blemish_engine):
    response = post(client, standard_parts(meta=metadata(expected_engine_version="blemish/other@2")))
    assert response.status_code == 409
    assert response.json() == {"error": "engine_mismatch", "request_id": "req-1"}
    assert blemish_engine.calls == 0


def test_unknown_kind_is_422(client):
    response = post(client, standard_parts(meta=metadata(kind="wrinkles")))
    assert response.status_code == 422
    assert response.json() == {"error": "unsupported_kind", "request_id": "req-1"}


def test_body_limit_constant_matches_contract():
    assert MAX_BODY_BYTES == 94_371_840


def test_declared_content_length_over_limit_is_413_before_reading(client):
    body, content_type = multipart(standard_parts())
    response = client.post(
        "/v1/retouch",
        content=body,
        headers={**auth(), "Content-Type": content_type, "Content-Length": str(MAX_BODY_BYTES + 1)},
    )
    assert response.status_code == 413
    assert response.json() == {"error": "too_large"}


def test_streamed_body_over_limit_is_413(client, monkeypatch):
    monkeypatch.setattr(main_module, "MAX_BODY_BYTES", 4096)
    body, content_type = multipart(standard_parts(image=sample_image(64, 64), mask=full_mask(64, 64)))
    assert len(body) > 4096

    def chunks():
        for i in range(0, len(body), 1000):
            yield body[i : i + 1000]

    response = client.post("/v1/retouch", content=chunks(), headers={**auth(), "Content-Type": content_type})
    assert "content-length" not in {k.lower() for k in response.request.headers}
    assert response.status_code == 413
    assert response.json() == {"error": "too_large"}


def _png_with_header_only(width: int, height: int, color_type: int = 6) -> bytes:
    ihdr = struct.pack(">IIBBBBB", width, height, 8, color_type, 0, 0, 0)
    chunk = struct.pack(">I", 13) + b"IHDR" + ihdr + struct.pack(">I", zlib.crc32(b"IHDR" + ihdr))
    # Deliberately no decodable image data: a 413 proves the limit was applied before decoding.
    return b"\x89PNG\r\n\x1a\n" + chunk + b"\x00" * 64


@pytest.mark.parametrize("size", [(4097, 16), (16, 5000)])
def test_png_header_over_pixel_limit_is_413_before_decode(client, size):
    meta = metadata(width=32, height=24)
    parts = _replace(standard_parts(meta=meta), "image", ("image", "image/png", _png_with_header_only(*size)))
    response = post(client, parts)
    assert response.status_code == 413
    assert response.json() == {"error": "too_large", "request_id": "req-1"}


def test_mask_header_over_pixel_limit_is_413(client):
    parts = _replace(
        standard_parts(), "allowed_mask", ("allowed_mask", "image/png", _png_with_header_only(8000, 8, 0))
    )
    assert post(client, parts).status_code == 413


@pytest.mark.parametrize("dims", [(4097, 1), (1, 4097)])
def test_metadata_dimensions_over_limit_are_413(client, dims):
    meta = metadata(width=dims[0], height=dims[1])
    parts = _replace(standard_parts(), "metadata", ("metadata", "application/json", json.dumps(meta).encode()))
    response = post(client, parts)
    assert response.status_code == 413


def test_token_is_not_accepted_in_other_places(client):
    response = client.post("/v1/retouch", content=b"", headers={"X-Token": TOKEN})
    assert response.status_code == 401
