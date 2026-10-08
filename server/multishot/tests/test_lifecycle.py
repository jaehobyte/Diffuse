"""Auth, health, admission, the deadline, disconnects and cleanup of the spool."""

from __future__ import annotations

import asyncio
import threading

import httpx

from app.errors import ApiError
from app.main import create_app
from conftest import (
    TOKEN,
    FakeExtractor,
    auth,
    blocking,
    client,
    encode,
    factory_of,
    image_parts,
    multipart,
    photo,
    raises,
    settings,
    wait_for,
)

W, H = 120, 60
IMAGES = [
    photo(W, H, [(10, 25, 20, 45, "red")]),
    photo(W, H, [(90, 10, 100, 30, "green")]),
    photo(W, H, [(55, 30, 65, 50, "blue")]),
]


def _body():
    return multipart(image_parts([encode(i) for i in IMAGES]))


def _args():
    body, content_type = _body()
    return dict(content=body, headers={**auth(), "Content-Type": content_type})


async def _raw_call(app, headers, body: bytes, disconnect: asyncio.Event, received: list | None = None):
    """Drive the ASGI app directly so a test can count body reads and 'disconnect'."""
    scope = {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": "POST",
        "scheme": "http",
        "path": "/v1/multishot",
        "raw_path": b"/v1/multishot",
        "query_string": b"",
        "root_path": "",
        "headers": [(k.lower().encode(), v.encode()) for k, v in headers.items()],
        "client": ("127.0.0.1", 1),
        "server": ("test", 80),
    }
    sent = False
    messages = []

    async def receive():
        nonlocal sent
        if received is not None:
            received.append(1)
        if not sent:
            sent = True
            return {"type": "http.request", "body": body, "more_body": False}
        await disconnect.wait()
        return {"type": "http.disconnect"}

    async def send(message):
        messages.append(message)

    await app(scope, receive, send)
    return messages


def _status(messages) -> int:
    return [m for m in messages if m["type"] == "http.response.start"][0]["status"]


# -- auth and health -----------------------------------------------------------------------------


def test_missing_or_wrong_token_is_401_before_any_body_is_read():
    async def scenario():
        extractor = FakeExtractor()
        app = create_app(settings(), factory_of(extractor))
        body, content_type = _body()
        for header in ({}, {"Authorization": "Bearer wrong"}, {"Authorization": f"Basic {TOKEN}"}, {"Authorization": "Bearer"}):
            received: list = []
            headers = {"Content-Type": content_type, "Content-Length": str(len(body)), **header}
            messages = await _raw_call(app, headers, body, asyncio.Event(), received)
            assert _status(messages) == 401
            start = [m for m in messages if m["type"] == "http.response.start"][0]
            assert (b"www-authenticate", b"Bearer") in start["headers"]
            assert received == []  # the body was never consumed
        assert extractor.calls == []
        assert app.state.runtime.in_flight == 0

    asyncio.run(scenario())


def test_health_needs_auth_and_reflects_segmentation_readiness():
    with client(FakeExtractor(is_ready=True)) as c:
        assert c.get("/health").status_code == 401
        assert c.get("/health", headers={"Authorization": "Bearer nope"}).status_code == 401
        response = c.get("/health", headers=auth())
        assert response.status_code == 200 and response.json() == {"contract_version": 1, "status": "ready"}
    with client(FakeExtractor(is_ready=False)) as c:
        response = c.get("/health", headers=auth())
        assert response.status_code == 503 and response.json()["status"] == "unavailable"
        assert "sam3" not in response.text and TOKEN not in response.text


# -- admission, deadline, disconnect --------------------------------------------------------------


def test_a_second_request_while_one_runs_is_429_with_retry_after(tmp_path):
    async def scenario():
        release, started = threading.Event(), threading.Event()
        extractor = FakeExtractor(behavior=blocking(release, started))
        app = create_app(settings(tmp_dir=str(tmp_path)), factory_of(extractor))
        runtime = app.state.runtime
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t", timeout=30) as c:
            first = asyncio.ensure_future(c.post("/v1/multishot", **_args()))
            await wait_for(started.is_set)
            second = await c.post("/v1/multishot", **_args())
            assert second.status_code == 429 and second.json()["error"] == "overloaded"
            assert int(second.headers["retry-after"]) > 0
            assert (await c.get("/health", headers=auth())).status_code == 200
            release.set()
            assert (await first).status_code == 200
        assert len(extractor.calls) == 3
        assert runtime.in_flight == 0
        assert list(tmp_path.iterdir()) == []

    asyncio.run(scenario())


def test_deadline_is_504_and_the_live_worker_keeps_its_slot_and_stops_before_the_next_image(tmp_path):
    async def scenario():
        release, started = threading.Event(), threading.Event()
        extractor = FakeExtractor(behavior=blocking(release, started))
        app = create_app(settings(deadline_s=0.5, tmp_dir=str(tmp_path)), factory_of(extractor))
        runtime = app.state.runtime
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t", timeout=30) as c:
            response = await c.post("/v1/multishot", **_args())
            assert response.status_code == 504 and response.json()["error"] == "timeout"
            assert started.is_set()
            # The worker thread is still inside extraction: its place and its files are kept.
            assert runtime.in_flight == 1
            assert len(list(tmp_path.iterdir())) == 1
            assert (await c.post("/v1/multishot", **_args())).status_code == 429
            release.set()
            await wait_for(lambda: runtime.in_flight == 0)
            await wait_for(lambda: list(tmp_path.iterdir()) == [])
        assert len(extractor.calls) == 1  # the second image was never sent

    asyncio.run(scenario())


def test_disconnect_while_running_discards_the_result_and_stops_extraction(tmp_path):
    async def scenario():
        release, started = threading.Event(), threading.Event()
        extractor = FakeExtractor(behavior=blocking(release, started))
        app = create_app(settings(tmp_dir=str(tmp_path)), factory_of(extractor))
        runtime = app.state.runtime
        body, content_type = _body()
        gone = asyncio.Event()
        call = asyncio.ensure_future(
            _raw_call(app, {**auth(), "Content-Type": content_type, "Content-Length": str(len(body))}, body, gone)
        )
        await wait_for(started.is_set)
        gone.set()
        messages = await asyncio.wait_for(call, 5)
        assert _status(messages) == 499
        assert b"image/png" not in b"".join(m.get("body", b"") for m in messages if m["type"] == "http.response.body")
        assert runtime.in_flight == 1
        release.set()
        await wait_for(lambda: runtime.in_flight == 0)
        await wait_for(lambda: list(tmp_path.iterdir()) == [])
        assert len(extractor.calls) == 1

    asyncio.run(scenario())


def test_a_slow_upload_counts_against_the_deadline(tmp_path):
    async def scenario():
        extractor = FakeExtractor()
        app = create_app(settings(deadline_s=0.3, tmp_dir=str(tmp_path)), factory_of(extractor))
        body, content_type = _body()

        async def slow():
            yield body[:100]
            await asyncio.sleep(1.0)
            yield body[100:]

        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t", timeout=30) as c:
            response = await c.post("/v1/multishot", content=slow(), headers={**auth(), "Content-Type": content_type})
        assert response.status_code == 504
        assert extractor.calls == []
        assert app.state.runtime.in_flight == 0
        assert list(tmp_path.iterdir()) == []

    asyncio.run(scenario())


def test_upstream_failures_end_the_request_without_a_partial_result_and_clean_up(tmp_path):
    cases = [
        (ApiError(503, "segmentation_unavailable"), 503),
        (ApiError(502, "invalid_upstream_response"), 502),
        (ApiError(504, "timeout"), 504),
    ]
    for error, status in cases:
        extractor = FakeExtractor(behavior=raises(error, at=1))
        with client(extractor, tmp_dir=str(tmp_path)) as c:
            body, content_type = _body()
            response = c.post("/v1/multishot", content=body, headers={**auth(), "Content-Type": content_type})
            assert response.status_code == status and response.json()["error"] == error.code
            assert "image_index" not in response.json()
            assert c.app.state.runtime.in_flight == 0
        assert len(extractor.calls) == 2
        assert list(tmp_path.iterdir()) == []


def test_requests_do_not_share_images_or_spools(tmp_path):
    extractor = FakeExtractor()
    with client(extractor, tmp_dir=str(tmp_path)) as c:
        first = c.post("/v1/multishot", **_args())
        other = [photo(W, H, [(55, 30, 65, 50, c)]) for c in ("yellow", "magenta", "cyan")]
        body, content_type = multipart(image_parts([encode(i) for i in other]))
        second = c.post("/v1/multishot", content=body, headers={**auth(), "Content-Type": content_type})
    assert first.status_code == second.status_code == 200
    assert [call.colours for call in extractor.calls] == [["red"], ["green"], ["blue"], ["yellow"], ["magenta"], ["cyan"]]
    assert list(tmp_path.iterdir()) == []
