"""Admission (1 running + N waiting), the job deadline, and client disconnects."""

import asyncio
import json
import threading
import time

import httpx

from app import wire
from app.engines.base import EngineOutput
from app.main import create_app
from conftest import FakeEngine, auth, factory_of, metadata, multipart, settings, standard_parts


def _blocking_engine(release: threading.Event, started: threading.Semaphore | None = None):
    def behavior(image, allowed, stop):
        if started is not None:
            started.release()
        release.wait(10)
        candidate = image.copy()
        candidate[allowed, 0] ^= 1
        return EngineOutput(candidate, allowed.copy())

    return FakeEngine("blemish", behavior=behavior)


async def _ready_app(engine, **overrides):
    app = create_app(settings(enabled_kinds=("blemish",), **overrides), factory_of({"blemish": engine}))
    runtime = app.state.runtime
    runtime.start_loading()
    for _ in range(500):
        if runtime.status() == "ready":
            break
        await asyncio.sleep(0.01)
    assert runtime.status() == "ready"
    return app, runtime


def _request_args(request_id="req-1"):
    body, content_type = multipart(standard_parts(meta=metadata(request_id=request_id)))
    return dict(content=body, headers={**auth(), "Content-Type": content_type})


async def _wait_for(predicate, timeout=5.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        await asyncio.sleep(0.01)
    raise AssertionError("condition not reached")


def test_overload_is_429_with_retry_after():
    async def scenario():
        release = threading.Event()
        engine = _blocking_engine(release)
        app, runtime = await _ready_app(engine, max_running=1, max_queued=1)
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test", timeout=30) as client:
            first = asyncio.ensure_future(client.post("/v1/retouch", **_request_args("a")))
            await _wait_for(lambda: engine.calls == 1)
            second = asyncio.ensure_future(client.post("/v1/retouch", **_request_args("b")))
            await _wait_for(lambda: runtime.in_flight == 2)
            third = await client.post("/v1/retouch", **_request_args("c"))
            assert third.status_code == 429
            assert third.json() == {"error": "overloaded"}  # refused before its body was read
            assert int(third.headers["retry-after"]) > 0
            # Health still answers while the GPU slot is busy.
            assert (await client.get("/health", headers=auth())).status_code == 200
            release.set()
            assert (await first).status_code == 200
            assert (await second).status_code == 200
        assert engine.calls == 2
        assert runtime.in_flight == 0

    asyncio.run(scenario())


def test_job_deadline_is_504_and_slot_is_released_when_the_thread_returns():
    async def scenario():
        release = threading.Event()
        engine = _blocking_engine(release)
        app, runtime = await _ready_app(engine, job_deadline_s=0.3)
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test", timeout=30) as client:
            running = asyncio.ensure_future(client.post("/v1/retouch", **_request_args("run")))
            await _wait_for(lambda: engine.calls == 1)
            queued = await client.post("/v1/retouch", **_request_args("wait"))
            assert queued.status_code == 504
            assert queued.json() == {"error": "timeout", "request_id": "wait"}
            response = await running
            assert response.status_code == 504
            assert engine.stops[0].is_set()  # the running job was told its result is discarded
            # The running thread still holds the slot until it returns.
            assert runtime.in_flight == 1
            release.set()
            await _wait_for(lambda: runtime.in_flight == 0)
        assert engine.calls == 1  # the queued job never ran

    asyncio.run(scenario())


async def _raw_call(app, body: bytes, content_type: str, disconnect: asyncio.Event):
    """Drive the ASGI app directly so the client can 'disconnect' after sending its body."""
    scope = {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": "POST",
        "scheme": "http",
        "path": "/v1/retouch",
        "raw_path": b"/v1/retouch",
        "query_string": b"",
        "root_path": "",
        "headers": [
            (b"authorization", auth()["Authorization"].encode()),
            (b"content-type", content_type.encode()),
            (b"content-length", str(len(body)).encode()),
        ],
        "client": ("127.0.0.1", 1),
        "server": ("test", 80),
    }
    sent_body = False
    messages = []

    async def receive():
        nonlocal sent_body
        if not sent_body:
            sent_body = True
            return {"type": "http.request", "body": body, "more_body": False}
        await disconnect.wait()
        return {"type": "http.disconnect"}

    async def send(message):
        messages.append(message)

    await app(scope, receive, send)
    return messages


def test_disconnect_while_queued_drops_the_job():
    async def scenario():
        release = threading.Event()
        engine = _blocking_engine(release)
        app, runtime = await _ready_app(engine)
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test", timeout=30) as client:
            running = asyncio.ensure_future(client.post("/v1/retouch", **_request_args("run")))
            await _wait_for(lambda: engine.calls == 1)

            body, content_type = multipart(standard_parts())
            gone = asyncio.Event()
            queued = asyncio.ensure_future(_raw_call(app, body, content_type, gone))
            await _wait_for(lambda: runtime.in_flight == 2)
            gone.set()
            await asyncio.wait_for(queued, 5)
            assert runtime.in_flight == 1  # its place was given back while the first still runs

            release.set()
            assert (await running).status_code == 200
        await _wait_for(lambda: runtime.in_flight == 0)
        assert engine.calls == 1  # the disconnected job never reached the engine

    asyncio.run(scenario())


def test_disconnect_while_running_discards_the_result():
    async def scenario():
        release = threading.Event()
        engine = _blocking_engine(release)
        app, runtime = await _ready_app(engine)
        body, content_type = multipart(standard_parts())
        gone = asyncio.Event()
        call = asyncio.ensure_future(_raw_call(app, body, content_type, gone))
        await _wait_for(lambda: engine.calls == 1)
        gone.set()
        messages = await asyncio.wait_for(call, 5)
        assert engine.stops[0].is_set()
        start = [m for m in messages if m["type"] == "http.response.start"]
        assert start and start[0]["status"] == 499
        body_bytes = b"".join(m.get("body", b"") for m in messages if m["type"] == "http.response.body")
        assert b"candidate" not in body_bytes
        assert runtime.in_flight == 1
        release.set()
        await _wait_for(lambda: runtime.in_flight == 0)

    asyncio.run(scenario())


def test_decoded_input_given_to_engine_is_a_private_copy():
    seen = {}

    def behavior(image, allowed, stop):
        seen["writeable"] = image.flags.writeable and allowed.flags.writeable
        return None

    async def scenario():
        engine = FakeEngine("blemish", behavior=behavior)
        app, _ = await _ready_app(engine)
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.post("/v1/retouch", **_request_args())
        assert response.status_code == 200
        assert seen["writeable"] is True
        assert json.loads(response.content.split(b"\r\n\r\n", 1)[1].split(b"\r\n--", 1)[0])["outcome"] == "no_change"

    asyncio.run(scenario())


def _parts_counter(monkeypatch):
    """Counts multipart parses, i.e. bodies that were read to the end."""
    parsed = []
    real = wire.parse_multipart

    def counting(body, boundary):
        parsed.append(len(body))
        return real(body, boundary)

    monkeypatch.setattr(wire, "parse_multipart", counting)
    return parsed


def _gated(monkeypatch, name: str):
    """Holds every call of wire.[name] until the returned event is set; records how many overlap."""
    gate = threading.Event()
    active = {"now": 0, "peak": 0}
    lock = threading.Lock()
    real = getattr(wire, name)

    def held(*args, **kwargs):
        with lock:
            active["now"] += 1
            active["peak"] = max(active["peak"], active["now"])
        try:
            gate.wait(10)
            return real(*args, **kwargs)
        finally:
            with lock:
                active["now"] -= 1

    monkeypatch.setattr(wire, name, held)
    return gate, active


def test_requests_over_capacity_are_refused_before_their_body_is_read_or_decoded(monkeypatch):
    async def scenario():
        engine = FakeEngine("blemish")
        app, runtime = await _ready_app(engine, max_running=1, max_queued=0)
        parsed = _parts_counter(monkeypatch)
        gate, decoding = _gated(monkeypatch, "decode_request_images")
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test", timeout=30) as client:
            first = asyncio.ensure_future(client.post("/v1/retouch", **_request_args("first")))
            await _wait_for(lambda: decoding["now"] == 1)
            others = await asyncio.gather(
                *(client.post("/v1/retouch", **_request_args(f"r{n}")) for n in range(5))
            )
            assert [r.status_code for r in others] == [429] * 5
            assert len(parsed) == 1 and decoding["peak"] == 1 and runtime.in_flight == 1
            gate.set()
            assert (await first).status_code == 200
            assert runtime.in_flight == 0
            # The capacity is back: the next request is admitted.
            assert (await client.post("/v1/retouch", **_request_args("after"))).status_code == 200
        assert engine.calls == 2 and runtime.in_flight == 0

    asyncio.run(scenario())


def test_encoding_after_the_engine_still_holds_the_place(monkeypatch):
    async def scenario():
        engine = FakeEngine("blemish")
        app, runtime = await _ready_app(engine, max_running=1, max_queued=0)
        gate, encoding = _gated(monkeypatch, "encode_rgba_png")
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test", timeout=30) as client:
            first = asyncio.ensure_future(client.post("/v1/retouch", **_request_args("first")))
            await _wait_for(lambda: encoding["now"] == 1)
            assert runtime.slots.locked() is False  # the GPU slot is free, the request is not done
            refused = await client.post("/v1/retouch", **_request_args("second"))
            assert refused.status_code == 429
            gate.set()
            assert (await first).status_code == 200
        assert engine.calls == 1 and runtime.in_flight == 0

    asyncio.run(scenario())


def test_failed_and_abandoned_requests_give_their_place_back():
    async def scenario():
        engine = FakeEngine("blemish")
        app, runtime = await _ready_app(engine, max_running=1, max_queued=0)
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test", timeout=30) as client:
            # Rejected after admission: corrupt image, unknown kind, not multipart.
            body, content_type = multipart([*standard_parts()[:1], ("image", "image/png", b"not a png"),
                                            standard_parts()[2]])
            bad = await client.post("/v1/retouch", content=body, headers={**auth(), "Content-Type": content_type})
            assert bad.status_code == 400 and runtime.in_flight == 0
            unknown = await client.post(
                "/v1/retouch", **_request_args_with(metadata(kind="shine", expected_engine_version="shine/fake@1"))
            )
            assert unknown.status_code == 422 and runtime.in_flight == 0
            plain = await client.post("/v1/retouch", content=b"x", headers={**auth(), "Content-Type": "text/plain"})
            assert plain.status_code == 400 and runtime.in_flight == 0

        # The client leaves while its body is still arriving.
        pending = [{"type": "http.request", "body": b"--testboundary123\r\n", "more_body": True},
                   {"type": "http.disconnect"}]

        async def receive():
            return pending.pop(0) if pending else {"type": "http.disconnect"}

        sent = []

        async def send(message):
            sent.append(message)

        scope = {
            "type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1", "method": "POST",
            "scheme": "http", "path": "/v1/retouch", "raw_path": b"/v1/retouch", "query_string": b"",
            "root_path": "", "client": ("127.0.0.1", 1), "server": ("test", 80),
            "headers": [(b"authorization", auth()["Authorization"].encode()),
                        (b"content-type", b"multipart/form-data; boundary=testboundary123")],
        }
        await app(scope, receive, send)
        assert runtime.in_flight == 0
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test", timeout=30) as client:
            assert (await client.post("/v1/retouch", **_request_args("after"))).status_code == 200
        assert runtime.in_flight == 0

    asyncio.run(scenario())


def _request_args_with(meta):
    body, content_type = multipart(standard_parts(meta=meta))
    return dict(content=body, headers={**auth(), "Content-Type": content_type})
