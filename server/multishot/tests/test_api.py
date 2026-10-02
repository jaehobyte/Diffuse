"""POST /v1/multishot end to end with the fake extractor: order, slots, pixels, metadata, failures."""

from __future__ import annotations

import io
import json
import math

import numpy as np
import pytest
from PIL import Image

from conftest import (
    GREY,
    PALETTE,
    FakeExtractor,
    auth,
    client,
    decode_png,
    encode,
    image_parts,
    multipart,
    parse_multipart_response,
    photo,
    post,
)

W, H = 120, 60
# Hero: blue, anchor (60, 50) = (0.5, 5/6). A: red, anchor (15, 45). B: green, anchor (95, 30).
HERO = photo(W, H, [(55, 30, 65, 50, "blue")])
A = photo(W, H, [(10, 25, 20, 45, "red")])
B = photo(W, H, [(90, 10, 100, 30, "green")])


def over(colour, opacity, background=GREY):
    """Source-over of an opaque colour at [opacity] (8-bit paint) onto an opaque background."""
    paint = math.floor(opacity * 255 + 0.5) / 255
    return tuple(math.floor(c * paint + b * (1 - paint) + 0.5) for c, b in zip(colour, background))


def ok(response):
    assert response.status_code == 200, response.text
    metadata, png = parse_multipart_response(response)
    return metadata, decode_png(png)


def run(images, metadata=None, names=None, extractor=None, **overrides):
    extractor = extractor or FakeExtractor()
    with client(extractor, **overrides) as c:
        response = post(c, image_parts([encode(i) if isinstance(i, np.ndarray) else i for i in images], names, metadata))
    return response, extractor


# -- counts, order, slots ------------------------------------------------------------------------


@pytest.mark.parametrize("count", [3, 4, 5, 6])
def test_three_to_six_images_are_accepted_and_the_last_is_background_and_hero(count):
    images = [A] * (count - 1) + [HERO]
    response, extractor = run(images, metadata={"spacing": 0.5})
    metadata, result = ok(response)
    assert metadata["input_count"] == count and metadata["hero_index"] == count - 1
    assert (metadata["width"], metadata["height"]) == (W, H)
    assert [s["input_index"] for s in metadata["shots"]] == list(range(count))
    assert [s["is_hero"] for s in metadata["shots"]] == [False] * (count - 1) + [True]
    assert len(extractor.calls) == count
    assert (result[40, 60, :3] == PALETTE["blue"]).all()


@pytest.mark.parametrize("count", [0, 1, 2, 7])
def test_other_counts_are_refused_before_segmentation(count):
    response, extractor = run([A] * count, metadata={} if count == 0 else None)
    assert response.status_code == 422
    assert response.json() == {"error": "invalid_image_count", "request_id": response.json()["request_id"]}
    assert extractor.calls == []


def test_multipart_order_is_kept_whatever_the_file_names_capture_times_or_duplicate_bytes():
    def stamped(array, when):
        exif = Image.Exif()
        exif[0x0132] = when  # DateTime
        buf = io.BytesIO()
        Image.fromarray(array, "RGBA").save(buf, format="PNG", exif=exif)
        return buf.getvalue()

    inputs = [stamped(B, "2026:10:01 12:00:03"), stamped(A, "2026:10:01 12:00:01"), stamped(HERO, "2026:10:01 12:00:02")]
    for names in (["same.png"] * 3, ["c.png", "b.png", "a.png"]):
        response, extractor = run(inputs, names=names)
        metadata, _ = ok(response)
        assert [call.colours for call in extractor.calls] == [["green"], ["red"], ["blue"]]
        assert [s["slot_index"] for s in metadata["shots"]] == [0, 2, 1]

    # The very same bytes three times are three images.
    response, extractor = run([encode(HERO)] * 3)
    metadata, _ = ok(response)
    assert metadata["input_count"] == 3 and len(extractor.calls) == 3


def test_three_inputs_take_slots_0_2_1_and_the_pixels_follow_input_order():
    response, _ = run([A, B, HERO])
    metadata, result = ok(response)
    assert [(s["input_index"], s["slot_index"]) for s in metadata["shots"]] == [(0, 0), (1, 2), (2, 1)]
    assert [s["opacity"] for s in metadata["shots"]] == [0.25, 0.7, 1.0]
    assert metadata["shots"][0]["anchor"] == pytest.approx([1 / 6, 5 / 6], abs=1e-6)
    assert metadata["shots"][1]["anchor"] == pytest.approx([5 / 6, 5 / 6], abs=1e-6)
    assert metadata["shots"][2]["anchor"] == pytest.approx([0.5, 5 / 6], abs=1e-6)
    # A (red, 25 %) moved +5/+5 px onto slot 0; B (green, 70 %) +5/+20 px onto slot 2.
    assert tuple(result[40, 20, :3]) == over(PALETTE["red"], 0.25)
    assert tuple(result[40, 100, :3]) == over(PALETTE["green"], 0.7)
    # Their original places are background again; the hero is exactly itself; nothing is translucent.
    assert tuple(result[35, 12, :3]) == GREY and tuple(result[20, 95, :3]) == GREY
    assert (result[32:48, 57:63, :3] == PALETTE["blue"]).all()
    assert (result[..., 3] == 255).all()

    # Swapping the first two swaps their slots and opacities.
    response, _ = run([B, A, HERO])
    _, swapped = ok(response)
    assert tuple(swapped[40, 20, :3]) == over(PALETTE["green"], 0.25)
    assert tuple(swapped[40, 100, :3]) == over(PALETTE["red"], 0.7)


def test_six_inputs_take_slots_0_1_3_4_5_and_the_hero_slot_2():
    colours = ["red", "green", "yellow", "magenta", "cyan"]
    images = [photo(W, H, [(55, 30, 65, 50, c)]) for c in colours] + [HERO]
    response, extractor = run(images, metadata={"spacing": 0.8})
    metadata, _ = ok(response)
    assert [s["slot_index"] for s in metadata["shots"]] == [0, 1, 3, 4, 5, 2]
    assert [call.colours[0] for call in extractor.calls] == [*colours, "blue"]
    xs = [s["anchor"][0] for s in metadata["shots"]]
    assert xs == pytest.approx([0.2333333, 0.3666667, 0.6333333, 0.7666667, 0.9, 0.5], abs=1e-6)
    assert [s["opacity"] for s in metadata["shots"]] == pytest.approx([0.25, 0.3625, 0.475, 0.5875, 0.7, 1.0])
    assert metadata["warnings"] == []


def test_overlap_order_hero_protection_and_a_feathered_hero_edge():
    big_a = photo(W, H, [(40, 10, 80, 50, "red")])
    big_b = photo(W, H, [(45, 20, 75, 50, "green")])
    response, _ = run([big_a, big_b, HERO], metadata={"spacing": 0})
    _, result = ok(response)
    red_then_green = over(PALETTE["green"], 0.7, over(PALETTE["red"], 0.25))
    assert tuple(result[35, 50, :3]) == red_then_green
    assert (result[32:48, 57:63, :3] == PALETTE["blue"]).all()  # hero interior, exactly
    # x = 55 is the hero mask's first column: its feathered weight is 153 of 255. Under it, the
    # afterimages were drawn over the background photo's own (blue) pixels.
    t = 153 / 255
    composite = over(PALETTE["green"], 0.7, over(PALETTE["red"], 0.25, PALETTE["blue"]))
    expected = [round(c * (1 - t) + h * t) for c, h in zip(composite, PALETTE["blue"])]
    assert np.abs(result[40, 55, :3].astype(int) - expected).max() <= 1

    response, _ = run([big_b, big_a, HERO], metadata={"spacing": 0})
    _, swapped = ok(response)
    assert tuple(swapped[35, 50, :3]) == over(PALETTE["red"], 0.7, over(PALETTE["green"], 0.25))


def test_a_translucent_background_keeps_its_alpha_and_composites_over_it():
    hero = photo(W, H, [(55, 30, 65, 50, "blue")], alpha=100)
    response, _ = run([A, B, hero])
    _, result = ok(response)
    assert result[5, 5, 3] == 100 and result[40, 60, 3] == 255
    paint = 64 / 255
    assert abs(int(result[40, 20, 3]) - round(255 * paint + 100 * (1 - paint))) <= 1


def _found(result, colour, opacity, tolerance):
    target = np.array(over(PALETTE[colour], opacity))
    hits = np.abs(result[..., :3].astype(int) - target).max(axis=-1) <= tolerance
    rows, cols = np.nonzero(hits)
    assert rows.size > 0, colour
    return (cols.min() + cols.max() + 1) / 2.0, rows.max() + 1.0


def test_transformed_anchors_land_on_d088_slots_for_mixed_sizes_aspects_and_exif():
    canvas = (600, 300)
    hero = photo(*canvas, [(170, 150, 190, 250, "blue")])  # off-centre: anchor (0.3, 5/6)
    upright = photo(300, 150, [(140, 40, 150, 100, "orange")])
    rotated = encode(np.ascontiguousarray(np.rot90(upright, k=1)), "JPEG", orientation=6, quality=95)
    inputs = [
        (photo(300, 300, [(100, 100, 120, 200, "red")]), 1.0),
        (photo(400, 100, [(300, 20, 313, 80, "green")]), 1.5),
        (photo(200, 400, [(20, 50, 46, 380, "yellow")]), 0.75),
        (rotated, 2.0),
        (photo(900, 450, [(450, 100, 480, 300, "magenta")]), 2 / 3),
    ]
    response, _ = run([i for i, _ in inputs] + [hero], metadata={"spacing": 0.8})
    metadata, result = ok(response)
    assert (metadata["width"], metadata["height"]) == canvas
    hero_y = 250 / 300
    for index, (shot, slot) in enumerate(zip(metadata["shots"], [0, 1, 3, 4, 5])):
        assert shot["slot_index"] == slot
        assert shot["anchor"] == pytest.approx([0.3 + (slot - 2) * 0.8 / 6, hero_y], abs=1e-6)
    assert metadata["shots"][-1]["anchor"] == pytest.approx([0.3, hero_y], abs=1e-6)
    # The actual pixels: each subject's bottom centre is on its slot, at the hero's height.
    colours = ["red", "green", "yellow", "orange", "magenta"]
    for index, ((_, scale), colour) in enumerate(zip(inputs, colours)):
        shot = metadata["shots"][index]
        tolerance = 10 if colour == "orange" else 2  # the JPEG
        x, bottom = _found(result, colour, shot["opacity"], tolerance)
        assert abs(x - shot["anchor"][0] * canvas[0]) <= 1.5, colour
        assert 0 <= hero_y * canvas[1] - bottom <= 2 * scale + 2, colour
    assert (result[160:240, 175:185, :3] == PALETTE["blue"]).all()


def test_the_subject_past_the_edge_is_announced_on_its_input_index():
    images = [photo(W, H, [(55, 30, 65, 50, c)]) for c in ["red", "green", "yellow", "magenta", "cyan"]]
    response, _ = run([*images, HERO], metadata={"spacing": 1})
    metadata, result = ok(response)
    assert metadata["shots"][4]["anchor"][0] == pytest.approx(1.0)
    assert metadata["warnings"] == [{"code": "subject_clipped", "input_index": 4}]
    assert tuple(result[40, 118, :3]) == over(PALETTE["cyan"], 0.7)  # its left half, cut at the edge
    response, _ = run([*images, HERO], metadata={"spacing": 0.8})
    assert ok(response)[0]["warnings"] == []
    response, _ = run([*images, HERO], metadata={"spacing": 0})
    assert ok(response)[0]["warnings"] == []


def test_inputs_are_shrunk_to_4096_and_the_result_has_no_exif():
    hero = photo(5000, 50, [(2000, 10, 2600, 40, "blue")])
    exif = Image.Exif()
    exif[0x0132] = "2026:10:01 12:00:00"
    buf = io.BytesIO()
    Image.fromarray(hero, "RGBA").save(buf, format="PNG", exif=exif)
    response, extractor = run([A, B, buf.getvalue()])
    metadata, _ = ok(response)
    assert (metadata["width"], metadata["height"]) == (4096, 41)
    assert extractor.calls[-1].size == (1080, 11)
    _, png = parse_multipart_response(response)
    with Image.open(io.BytesIO(png)) as img:
        assert img.size == (4096, 41) and img.mode == "RGBA"
        assert "exif" not in img.info and not img.getexif()


# -- explicit extraction failures ----------------------------------------------------------------


def test_no_subject_and_several_people_fail_on_that_input_and_never_skip_it():
    nobody = photo(W, H, [])
    two = photo(W, H, [(10, 25, 20, 45, "red"), (90, 10, 100, 30, "green")])
    response, extractor = run([A, nobody, HERO])
    assert response.status_code == 422
    assert response.json()["error"] == "subject_not_found" and response.json()["image_index"] == 1
    assert len(extractor.calls) == 2  # nothing after the failing image

    response, _ = run([two, B, HERO])
    assert response.status_code == 422
    assert response.json()["error"] == "ambiguous_subject" and response.json()["image_index"] == 0

    # A point picks one of the two; the others keep text extraction.
    response, extractor = run([two, B, HERO], metadata={"subject_points": [[0.79, 0.33], None, None]})
    metadata, result = ok(response)
    assert [call.point for call in extractor.calls] == [(0.79, 0.33), None, None]
    assert tuple(result[40, 20, :3]) == over(PALETTE["green"], 0.25)  # the green one, now at slot 0
    assert tuple(result[26, 12, :3]) == GREY  # the red one is not composited

    # A point on the background finds nothing.
    response, _ = run([two, B, HERO], metadata={"subject_points": [[0.01, 0.01], None, None]})
    assert response.json()["error"] == "subject_not_found" and response.json()["image_index"] == 0


def test_the_hero_needs_a_subject_too():
    response, _ = run([A, B, photo(W, H, [])])
    assert response.status_code == 422 and response.json()["image_index"] == 2


# -- invalid input, all refused before SAM 3 -----------------------------------------------------


def _apng() -> bytes:
    frames = [Image.fromarray(A, "RGBA"), Image.fromarray(B, "RGBA")]
    buf = io.BytesIO()
    frames[0].save(buf, format="PNG", save_all=True, append_images=frames[1:])
    return buf.getvalue()


def _other(fmt: str) -> bytes:
    buf = io.BytesIO()
    Image.fromarray(A, "RGBA").convert("RGB").save(buf, format=fmt)
    return buf.getvalue()


@pytest.mark.parametrize(
    "bad, status, code",
    [
        (b"", 422, "invalid_image"),
        (b"definitely not an image", 422, "invalid_image"),
        (encode(A)[:200], 422, "invalid_image"),
        (encode(A, "JPEG")[:300], 422, "invalid_image"),
        (_other("GIF"), 415, "unsupported_media_type"),
        (_other("BMP"), 415, "unsupported_media_type"),
        (_other("WEBP"), 415, "unsupported_media_type"),
        (_apng(), 415, "unsupported_media_type"),
    ],
)
def test_bad_images_are_refused_with_their_index(bad, status, code):
    response, extractor = run([encode(A), bad, encode(HERO)])
    assert response.status_code == status
    body = response.json()
    assert body["error"] == code and body["image_index"] == 1 and set(body) == {"error", "request_id", "image_index"}
    assert extractor.calls == []


def test_mime_type_and_extension_are_not_trusted():
    parts = [("images", _other("GIF"), "photo.png", "image/png"), *image_parts([encode(A), encode(HERO)])]
    with client(FakeExtractor()) as c:
        assert post(c, parts).json()["error"] == "unsupported_media_type"


def test_size_limits_are_413_too_large():
    response, extractor = run([A, B, HERO], max_source_pixels=W * H - 1)
    assert (response.status_code, response.json()["error"], response.json()["image_index"]) == (413, "too_large", 0)
    big = encode(np.random.default_rng(1).integers(0, 256, (60, 120, 4), dtype=np.uint8))
    response, _ = run([A, big, HERO], max_image_bytes=len(big) - 1)
    assert (response.status_code, response.json()["image_index"]) == (413, 1)
    assert extractor.calls == []


def test_a_body_over_the_limit_is_cut_off_even_without_content_length():
    body, content_type = multipart(image_parts([encode(A), encode(B), encode(HERO)]))
    limit = len(body) - 10
    extractor = FakeExtractor()
    with client(extractor, max_body_bytes=limit) as c:

        def chunks():
            for start in range(0, len(body), 1000):
                yield body[start : start + 1000]

        response = c.post("/v1/multishot", content=chunks(), headers={**auth(), "Content-Type": content_type})
        assert "content-length" not in {k.lower() for k in response.request.headers}
        assert (response.status_code, response.json()["error"]) == (413, "too_large")
        declared = c.post(
            "/v1/multishot",
            content=body,
            headers={**auth(), "Content-Type": content_type, "Content-Length": str(limit + 1)},
        )
        assert declared.status_code == 413
    assert extractor.calls == []


METADATA_CASES = [
    b"{",
    b"[]",
    b'{"spacing": NaN}',
    b'{"spacing": Infinity}',
    b'{"spacing": -Infinity}',
    b'{"spacing": 1e999}',
    b'{"spacing": true}',
    b'{"spacing": "0.5"}',
    b'{"spacing": 1.01}',
    b'{"spacing": -0.1}',
    b'{"spacing": 0.5, "spacing": 0.6}',
    b'{"unknown": 1}',
    b'{"subject_points": [null, null]}',
    b'{"subject_points": [null, null, null, null]}',
    b'{"subject_points": "none"}',
    b'{"subject_points": [null, null, [0.5]]}',
    b'{"subject_points": [null, null, [0.5, 1.5]]}',
    b'{"subject_points": [null, null, [true, 0.5]]}',
    b'{"subject_points": [null, null, [0.5, NaN]]}',
    b'{"subject_points": [null, null, {"x": 0.5, "y": 0.5}]}',
]


@pytest.mark.parametrize("raw", METADATA_CASES)
def test_bad_metadata_is_400(raw):
    response, extractor = run([A, B, HERO], metadata=raw)
    assert (response.status_code, response.json()["error"]) == (400, "invalid_metadata")
    assert extractor.calls == []


def test_valid_metadata_edges_are_accepted():
    for meta in ({"spacing": 0}, {"spacing": 1}, {"subject_points": [None, [0.79, 0.33], None]}, {}):
        response, _ = run([A, B, HERO], metadata=meta)
        assert response.status_code == 200, (meta, response.text)


@pytest.mark.parametrize(
    "parts",
    [
        image_parts([encode(A), encode(B), encode(HERO)], metadata={}) + [("metadata", b"{}", None, None)],
        image_parts([encode(A), encode(B), encode(HERO)]) + [("extra", b"x", None, None)],
        image_parts([encode(A), encode(B), encode(HERO)]) + [("metadata", b"{}", None, "text/plain")],
    ],
    ids=["duplicate-metadata", "unknown-part", "metadata-content-type"],
)
def test_bad_parts_are_400(parts):
    extractor = FakeExtractor()
    with client(extractor) as c:
        response = post(c, parts)
    assert (response.status_code, response.json()["error"]) == (400, "invalid_request")
    assert extractor.calls == []


def test_malformed_multipart_is_400():
    body, content_type = multipart(image_parts([encode(A), encode(B), encode(HERO)]))
    extractor = FakeExtractor()
    with client(extractor) as c:
        for payload, ctype in [
            (body[: len(body) // 2], content_type),  # never ends
            (body, "multipart/form-data"),  # no boundary
            (body, "application/json"),
            (b"garbage", content_type),
        ]:
            response = c.post("/v1/multishot", content=payload, headers={**auth(), "Content-Type": ctype})
            assert (response.status_code, response.json()["error"]) == (400, "invalid_request"), ctype
    assert extractor.calls == []


def test_error_bodies_carry_no_internals():
    def boom(index, rgb, size, point, ctx):
        raise RuntimeError("secret detail /srv/path token=abc")

    response, _ = run([A, B, HERO], extractor=FakeExtractor(behavior=boom))
    assert response.status_code == 500
    assert set(response.json()) == {"error", "request_id"} and response.json()["error"] == "internal_error"
    assert "secret" not in response.text and "Traceback" not in response.text


def test_metadata_part_order_does_not_matter_and_shots_are_diagnostic_only():
    parts = [("metadata", json.dumps({"spacing": 0.5}).encode(), None, None), *image_parts([encode(A), encode(B), encode(HERO)])]
    with client(FakeExtractor()) as c:
        metadata, _ = ok(post(c, parts))
    assert metadata["spacing"] == 0.5 and metadata["contract_version"] == 1 and len(metadata["request_id"]) == 32
    assert set(metadata) == {
        "contract_version", "request_id", "input_count", "hero_index", "width", "height", "spacing", "shots", "warnings",
    }
    assert set(metadata["shots"][0]) == {"input_index", "slot_index", "anchor", "opacity", "is_hero"}
