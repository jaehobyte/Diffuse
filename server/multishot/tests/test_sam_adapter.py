"""The real SAM 3 adapter against a mocked HTTP wire (httpx.MockTransport), and through the app."""

from __future__ import annotations

import base64
import io
import json
import logging
import threading
import time

import httpx
import numpy as np
import pytest
from PIL import Image

from app.errors import ApiError, Cancelled
from app.main import create_app
from app.sam import JobContext, Sam3Extractor
from conftest import auth, encode, image_parts, multipart, parse_multipart_response, photo, settings

SIZE = (40, 30)  # width, height


def _mask_png(mask: np.ndarray, mode: str = "L") -> str:
    img = Image.fromarray(np.where(mask, 255, 0).astype(np.uint8), "L")
    if mode != "L":
        img = img.convert(mode)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode()


def _box(x0, y0, x1, y1, size=SIZE) -> np.ndarray:
    mask = np.zeros((size[1], size[0]), bool)
    mask[y0:y1, x0:x1] = True
    return mask


class Sam:
    """A scripted SAM 3: records every request; [prompts] answers prompt calls in turn."""

    def __init__(self, prompts=None, upload=None, delete_status=204, delete_error=None):
        self.requests: list[httpx.Request] = []
        self.prompts = list(prompts or [])
        self.upload = upload
        self.delete_status = delete_status
        self.delete_error = delete_error
        self.uploads = 0

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        path = request.url.path
        if request.method == "GET" and path == "/healthz":
            return httpx.Response(200, json={"status": "ok"})
        if request.method == "GET" and path == "/v1/meta":
            ok = request.headers.get("authorization") == "Bearer sam3-token-not-a-secret"
            return httpx.Response(200 if ok else 401, json={})
        if request.method == "POST" and path == "/v1/images":
            self.uploads += 1
            if self.upload is not None:
                return self.upload(request)
            return httpx.Response(
                201, json={"image_id": f"img-{self.uploads}", "width": SIZE[0], "height": SIZE[1], "expires_at": "x"}
            )
        if request.method == "POST" and path.startswith("/v1/images/") and "/segment/" in path:
            answer = self.prompts.pop(0)
            if isinstance(answer, Exception):
                raise answer
            if isinstance(answer, httpx.Response):
                return answer
            return httpx.Response(200, json={"image_id": path.split("/")[3], "masks": answer})
        if request.method == "DELETE":
            if self.delete_error is not None:
                raise self.delete_error
            return httpx.Response(self.delete_status)
        return httpx.Response(404)

    def calls(self) -> list[str]:
        return [f"{r.method} {r.url.path}" for r in self.requests]


def _adapter(sam: Sam, **overrides) -> Sam3Extractor:
    return Sam3Extractor(settings(**overrides), transport=httpx.MockTransport(sam))


def _ctx(budget: float = 300.0) -> JobContext:
    return JobContext("req-1", threading.Event(), time.monotonic() + budget)


PNG = encode(photo(*SIZE, [(5, 5, 15, 25, "red")]))


def test_text_extraction_uploads_prompts_reads_brightness_and_deletes():
    sam = Sam(prompts=[[{"score": 0.9, "bbox": [5, 5, 10, 20], "png": _mask_png(_box(5, 5, 15, 25))}]])
    masks = _adapter(sam).extract(PNG, SIZE, None, _ctx())
    assert sam.calls() == ["POST /v1/images", "POST /v1/images/img-1/segment/text", "DELETE /v1/images/img-1"]
    upload, prompt = sam.requests[0], sam.requests[1]
    assert upload.headers["authorization"] == "Bearer sam3-token-not-a-secret"
    assert b'name="file"' in upload.content and PNG in upload.content
    assert json.loads(prompt.content) == {"prompt": "person", "threshold": 0.5, "max_instances": 20, "format": "png"}
    assert len(masks) == 1 and masks[0].dtype == bool
    np.testing.assert_array_equal(masks[0], _box(5, 5, 15, 25))


def test_point_extraction_sends_one_foreground_point_without_multimask():
    sam = Sam(prompts=[[{"score": 0.9, "bbox": [0, 0, 1, 1], "png": _mask_png(_box(0, 0, 4, 4))}]])
    _adapter(sam).extract(PNG, SIZE, (0.25, 0.75), _ctx())
    assert sam.calls()[1] == "POST /v1/images/img-1/segment/points"
    assert json.loads(sam.requests[1].content) == {
        "points": [[0.25, 0.75]],
        "labels": [1],
        "multimask": False,
        "format": "png",
    }


def test_every_answered_mask_is_returned_for_the_pipeline_to_judge():
    many = [{"score": 0.9, "bbox": [0, 0, 1, 1], "png": _mask_png(_box(i, 0, i + 2, 2))} for i in range(3)]
    sam = Sam(prompts=[many, []])
    assert len(_adapter(sam).extract(PNG, SIZE, None, _ctx())) == 3
    assert _adapter(sam).extract(PNG, SIZE, None, _ctx()) == []


def test_a_410_is_recovered_once_by_upload_and_replay():
    good = [{"score": 0.9, "bbox": [0, 0, 1, 1], "png": _mask_png(_box(1, 1, 3, 3))}]
    sam = Sam(prompts=[httpx.Response(410, json={"error": "session_expired"}), good])
    masks = _adapter(sam).extract(PNG, SIZE, None, _ctx())
    assert len(masks) == 1
    assert sam.calls() == [
        "POST /v1/images",
        "POST /v1/images/img-1/segment/text",
        "DELETE /v1/images/img-1",
        "POST /v1/images",
        "POST /v1/images/img-2/segment/text",
        "DELETE /v1/images/img-2",
    ]
    assert sam.requests[1].content == sam.requests[4].content  # the same prompt replayed


def test_a_second_410_is_503_and_both_sessions_are_deleted():
    gone = httpx.Response(410, json={"error": "session_expired"})
    sam = Sam(prompts=[gone, httpx.Response(410, json={"error": "session_expired"})])
    with pytest.raises(ApiError) as error:
        _adapter(sam).extract(PNG, SIZE, None, _ctx())
    assert (error.value.status, error.value.code) == (503, "segmentation_unavailable")
    assert sam.uploads == 2 and [c for c in sam.calls() if c.startswith("DELETE")] == [
        "DELETE /v1/images/img-1",
        "DELETE /v1/images/img-2",
    ]


@pytest.mark.parametrize(
    "answer, status, code",
    [
        (httpx.Response(503, json={"error": "not_ready"}), 503, "segmentation_unavailable"),
        (httpx.Response(503, json={"error": "out_of_memory"}), 503, "segmentation_unavailable"),
        (httpx.Response(429, json={"error": "busy"}), 503, "segmentation_unavailable"),
        (httpx.Response(401, json={"error": "unauthorized"}), 503, "segmentation_unavailable"),
        (httpx.Response(500, text="boom"), 503, "segmentation_unavailable"),
        (httpx.Response(400, json={"error": "invalid_prompt"}), 502, "invalid_upstream_response"),
        (httpx.Response(200, text="not json"), 502, "invalid_upstream_response"),
        (httpx.Response(200, json={"masks": "nope"}), 502, "invalid_upstream_response"),
        ([{"score": 1, "bbox": [0, 0, 1, 1]}], 502, "invalid_upstream_response"),
        ([{"score": 1, "bbox": [0, 0, 1, 1], "png": "***"}], 502, "invalid_upstream_response"),
        ([{"score": 1, "bbox": [0, 0, 1, 1], "png": _mask_png(_box(0, 0, 2, 2, (20, 15)), "L")}], 502, "invalid_upstream_response"),
        ([{"score": 1, "bbox": [0, 0, 1, 1], "png": _mask_png(_box(0, 0, 2, 2), "LA")}], 502, "invalid_upstream_response"),
        ([{"score": 1, "bbox": [0, 0, 1, 1], "png": _mask_png(_box(0, 0, 2, 2), "RGBA")}], 502, "invalid_upstream_response"),
        (httpx.ConnectError("refused"), 503, "segmentation_unavailable"),
        (httpx.ReadTimeout("slow"), 504, "timeout"),
        (httpx.RemoteProtocolError("cut"), 503, "segmentation_unavailable"),
    ],
)
def test_upstream_failures_are_classified_and_the_session_is_still_deleted(answer, status, code):
    sam = Sam(prompts=[answer])
    with pytest.raises(ApiError) as error:
        _adapter(sam).extract(PNG, SIZE, None, _ctx())
    assert (error.value.status, error.value.code) == (status, code)
    assert sam.calls()[-1] == "DELETE /v1/images/img-1"
    assert sam.uploads == 1  # no retry for anything but a 410


def test_a_grey_mask_value_is_not_a_valid_mask():
    grey = np.zeros((SIZE[1], SIZE[0]), np.uint8)
    grey[2:4, 2:4] = 128
    buf = io.BytesIO()
    Image.fromarray(grey, "L").save(buf, format="PNG")
    sam = Sam(prompts=[[{"score": 1, "bbox": [0, 0, 1, 1], "png": base64.b64encode(buf.getvalue()).decode()}]])
    with pytest.raises(ApiError) as error:
        _adapter(sam).extract(PNG, SIZE, None, _ctx())
    assert error.value.code == "invalid_upstream_response"


def test_upload_answers_are_checked():
    for upload, status in [
        (lambda r: httpx.Response(201, json={"image_id": "a/b", "width": 40, "height": 30}), 502),
        (lambda r: httpx.Response(201, json={"image_id": "img-x", "width": 41, "height": 30}), 502),
        (lambda r: httpx.Response(413, json={"error": "image_too_large"}), 502),
        (lambda r: httpx.Response(503, json={"error": "not_ready"}), 503),
    ]:
        sam = Sam(upload=upload)
        with pytest.raises(ApiError) as error:
            _adapter(sam).extract(PNG, SIZE, None, _ctx())
        assert error.value.status == status
        assert not any("/segment/" in c for c in sam.calls())


def test_delete_failure_never_replaces_the_outcome_and_logs_only_id_and_kind(caplog):
    good = [{"score": 0.9, "bbox": [0, 0, 1, 1], "png": _mask_png(_box(1, 1, 3, 3))}]
    caplog.set_level(logging.WARNING, logger="multishot")
    sam = Sam(prompts=[good], delete_error=httpx.ConnectError("down"))
    assert len(_adapter(sam).extract(PNG, SIZE, None, _ctx())) == 1
    sam = Sam(prompts=[httpx.Response(400, json={})], delete_status=500)
    with pytest.raises(ApiError) as error:
        _adapter(sam).extract(PNG, SIZE, None, _ctx())
    assert error.value.code == "invalid_upstream_response"
    text = caplog.text
    assert "req-1" in text and "ConnectError" in text and "http_500" in text
    assert "token" not in text.lower() and "sam3.test" not in text


def test_a_stop_between_upload_and_prompt_deletes_and_sends_no_prompt():
    ctx = _ctx()

    def upload(request):
        ctx.stop.set()
        return httpx.Response(201, json={"image_id": "img-1", "width": SIZE[0], "height": SIZE[1]})

    sam = Sam(upload=upload)
    with pytest.raises(Cancelled):
        _adapter(sam).extract(PNG, SIZE, None, ctx)
    assert sam.calls() == ["POST /v1/images", "DELETE /v1/images/img-1"]


def test_timeouts_are_bounded_by_the_remaining_budget():
    good = [{"score": 0.9, "bbox": [0, 0, 1, 1], "png": _mask_png(_box(1, 1, 3, 3))}]
    sam = Sam(prompts=[good])
    _adapter(sam).extract(PNG, SIZE, None, _ctx(budget=2.0))
    timeout = sam.requests[0].extensions["timeout"]
    assert timeout["connect"] <= 2.0 and timeout["read"] <= 2.0
    sam = Sam(prompts=[good])
    _adapter(sam).extract(PNG, SIZE, None, _ctx())
    timeout = sam.requests[0].extensions["timeout"]
    assert timeout["connect"] == 10.0 and timeout["read"] == 60.0
    assert sam.requests[-1].extensions["timeout"]["read"] == 5.0  # DELETE has its own short timeout
    with pytest.raises(ApiError) as error:
        _adapter(Sam()).extract(PNG, SIZE, None, _ctx(budget=-1))
    assert error.value.code == "timeout"


def test_ready_needs_health_and_an_accepted_token():
    assert _adapter(Sam()).ready()
    assert not _adapter(Sam(), sam3_token="wrong").ready()

    def down(request):
        raise httpx.ConnectError("refused")

    assert not Sam3Extractor(settings(), transport=httpx.MockTransport(down)).ready()


def test_the_app_with_the_real_adapter_over_a_mocked_wire():
    """Three photos → three sessions in input order, each deleted; a decodable composite."""
    width, height = 120, 60
    rects = [(10, 25, 20, 45), (90, 10, 100, 30), (55, 30, 65, 50)]
    images = [encode(photo(width, height, [(*r, c)])) for r, c in zip(rects, ["red", "green", "blue"])]
    sam = Sam(prompts=[[{"score": 0.9, "bbox": [0, 0, 1, 1], "png": _mask_png(_box(*r, (width, height)))}] for r in rects])
    sam.upload = lambda r: httpx.Response(201, json={"image_id": f"img-{sam.uploads}", "width": width, "height": height})
    app = create_app(settings(), lambda s: Sam3Extractor(s, transport=httpx.MockTransport(sam)))
    from fastapi.testclient import TestClient

    with TestClient(app) as c:
        body, content_type = multipart(image_parts(images))
        response = c.post("/v1/multishot", content=body, headers={**auth(), "Content-Type": content_type})
    assert response.status_code == 200, response.text
    metadata, png = parse_multipart_response(response)
    assert [s["slot_index"] for s in metadata["shots"]] == [0, 2, 1]
    assert sam.calls() == [
        call
        for i in (1, 2, 3)
        for call in ("POST /v1/images", f"POST /v1/images/img-{i}/segment/text", f"DELETE /v1/images/img-{i}")
    ]
    with Image.open(io.BytesIO(png)) as img:
        assert img.size == (width, height) and img.mode == "RGBA"
