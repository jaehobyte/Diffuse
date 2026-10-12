"""HTTP surface of wire contract v1: `GET /health` and `POST /v1/retouch`.

`create_app(settings, engines_factory)` is the only way to build the app. Tests inject fake
engines through [engines_factory]; `app.serve` always passes `build_real_engines`.
"""

from __future__ import annotations

import asyncio
import hmac
import json
import logging
import threading
import time
from contextlib import asynccontextmanager
from typing import Callable

import numpy as np
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response
from starlette.concurrency import run_in_threadpool
from starlette.requests import ClientDisconnect

from app import wire
from app.config import CONTRACT_VERSION, KINDS, MAX_BODY_BYTES, RETRY_AFTER_S, Settings
from app.engines.base import Engine, EngineOutput, Guaranteed, enforce
from app.wire import WireError

log = logging.getLogger("retouch")

EnginesFactory = Callable[[Settings], dict[str, Engine]]


def error_response(status: int, code: str, request_id: str | None = None, headers: dict | None = None) -> JSONResponse:
    content: dict = {"error": code}
    if request_id is not None:
        content["request_id"] = request_id
    return JSONResponse(status_code=status, content=content, headers=headers)


class Ticket:
    """One admitted request's place in [Runtime.in_flight].

    The handler holds it from before the body is read until its response is built; an engine thread
    that is still running when the handler gives up (deadline, disconnect) holds it too, so the
    request's pixels count against the capacity until that thread lets go of them.
    """

    def __init__(self, runtime: "Runtime") -> None:
        self._runtime = runtime
        self._holders = 1

    def retain(self) -> None:
        self._holders += 1

    def release(self) -> None:
        self._holders -= 1
        if self._holders == 0:
            self._runtime.in_flight -= 1


class Runtime:
    """Engines, their load state, and request admission. One per app."""

    def __init__(self, settings: Settings, engines_factory: EnginesFactory) -> None:
        self.settings = settings
        self.engines_factory = engines_factory
        self.engines: dict[str, Engine] = {}
        self.state: dict[str, str] = {kind: "loading" for kind in settings.loaded_kinds}
        self.versions: dict[str, str] = {}
        self.capacity = settings.max_running + settings.max_queued
        self.in_flight = 0
        self.slots = asyncio.Semaphore(settings.max_running)
        self._loader: threading.Thread | None = None

    # -- loading ----------------------------------------------------------------------------

    def start_loading(self) -> None:
        if self._loader is None:
            self._loader = threading.Thread(target=self._load_all, name="retouch-loader", daemon=True)
            self._loader.start()

    def _load_all(self) -> None:
        try:
            engines = self.engines_factory(self.settings)
        except Exception as exc:  # noqa: BLE001
            log.error("engine construction failed: %s", exc.__class__.__name__)
            for kind in self.state:
                self.state[kind] = "failed"
            return
        for kind in self.settings.loaded_kinds:
            engine = engines.get(kind)
            if engine is None:
                self.state[kind] = "failed"
                log.error("engine %s: not provided", kind)
                continue
            started = time.perf_counter()
            try:
                engine.load()
                loaded = time.perf_counter()
                engine.warm_up()
            except Exception as exc:  # noqa: BLE001
                self.state[kind] = "failed"
                log.error("engine %s failed to load: %s: %s", kind, exc.__class__.__name__, exc)
                continue
            self.engines[kind] = engine
            self.versions[kind] = engine.version
            self.state[kind] = "ready"
            log.info(
                "engine %s ready version=%s load_ms=%.0f warmup_ms=%.0f",
                kind,
                engine.version,
                (loaded - started) * 1000.0,
                (time.perf_counter() - loaded) * 1000.0,
            )

    def status(self) -> str:
        states = set(self.state.values())
        if "loading" in states:
            return "loading"
        if "failed" in states:
            return "failed"
        return "ready"

    # -- admission --------------------------------------------------------------------------

    def admit(self) -> Ticket:
        """A place for one request, taken before its body is read. Raises WireError 429."""
        if self.in_flight >= self.capacity:
            raise WireError(429, "overloaded")
        self.in_flight += 1
        return Ticket(self)

    async def run_job(
        self,
        request: Request,
        ticket: Ticket,
        work: Callable[[threading.Event], EngineOutput | None],
        request_id: str,
    ) -> tuple[EngineOutput | None, float] | None:
        """Run [work] under the GPU slot. None if the client left. Raises WireError 504."""
        stop = threading.Event()
        entered = time.perf_counter()
        queue_ms: list[float] = []

        async def queued() -> EngineOutput | None:
            await self.slots.acquire()
            queue_ms.append((time.perf_counter() - entered) * 1000.0)
            ticket.retain()
            task = asyncio.ensure_future(asyncio.to_thread(work, stop))

            def release(finished: asyncio.Future) -> None:
                self.slots.release()
                ticket.release()
                if not finished.cancelled():
                    finished.exception()  # retrieved here so an abandoned failure is not re-logged

            task.add_done_callback(release)
            return await asyncio.shield(task)

        job = asyncio.ensure_future(queued())
        gone = asyncio.ensure_future(_disconnected(request))
        remaining = self.settings.job_deadline_s
        try:
            done, _ = await asyncio.wait({job, gone}, timeout=remaining, return_when=asyncio.FIRST_COMPLETED)
        finally:
            gone.cancel()
        if job in done:
            return job.result(), (queue_ms[0] if queue_ms else 0.0)
        # Timed out or the client left: a queued job gives its place back; a running one keeps the
        # slot until its thread returns (the release hangs off that task), and its result is dropped.
        stop.set()
        job.cancel()
        try:
            await job
        except BaseException:  # noqa: BLE001 - cancelled on purpose
            pass
        if gone in done:
            return None
        raise WireError(504, "timeout", request_id)


async def _disconnected(request: Request) -> None:
    while True:
        message = await request.receive()
        if message["type"] == "http.disconnect":
            return


def _authorize(request: Request, token: str) -> JSONResponse | None:
    header = request.headers.get("authorization")
    scheme, _, credentials = (header or "").partition(" ")
    if header is None or scheme.lower() != "bearer" or not credentials.strip():
        return error_response(401, "unauthorized", headers={"WWW-Authenticate": "Bearer"})
    if not hmac.compare_digest(credentials.strip().encode("utf-8"), token.encode("utf-8")):
        return error_response(403, "forbidden")
    return None


def _ms(started: float) -> float:
    return round((time.perf_counter() - started) * 1000.0, 1)


def create_app(settings: Settings, engines_factory: EnginesFactory) -> FastAPI:
    runtime = Runtime(settings, engines_factory)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        runtime.start_loading()
        yield

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.runtime = runtime

    @app.get("/health")
    async def health(request: Request):
        denied = _authorize(request, settings.auth_token)
        if denied is not None:
            return denied
        status = runtime.status()
        if status != "ready":
            return JSONResponse(
                status_code=503,
                content={
                    "contract_version": CONTRACT_VERSION,
                    "status": status,
                    "supported_kinds": [],
                    "engines": {},
                    "evaluation_engines": {},
                },
            )
        kinds = [k for k in KINDS if k in settings.enabled_kinds]
        evaluation = [k for k in KINDS if k in settings.evaluation_kinds]
        return {
            "contract_version": CONTRACT_VERSION,
            "status": "ready",
            "supported_kinds": kinds,
            "engines": {k: runtime.versions[k] for k in kinds},
            "evaluation_engines": {k: runtime.versions[k] for k in evaluation},
        }

    @app.post("/v1/retouch")
    async def retouch(request: Request):
        started = time.perf_counter()
        request_id: str | None = None
        kind: str | None = None
        try:
            response, request_id, kind, outcome, timings = await _retouch(request, started)
        except WireError as exc:
            request_id = exc.request_id or request_id
            headers = {"Retry-After": str(RETRY_AFTER_S)} if exc.status == 429 else None
            if exc.status == 401:
                headers = {"WWW-Authenticate": "Bearer"}
            response, outcome, timings = error_response(exc.status, exc.code, exc.request_id, headers), None, {}
            kind = getattr(exc, "kind", None)
        except ClientDisconnect:
            response, outcome, timings = Response(status_code=499), None, {}
        except Exception as exc:  # noqa: BLE001
            log.error("internal error: %s", exc.__class__.__name__)
            response, outcome, timings = error_response(500, "internal_error"), None, {}
        timings = {**timings, "total": _ms(started)}
        log.info(
            "request_id=%s kind=%s status=%d outcome=%s timing_ms=%s",
            request_id or "-",
            kind or "-",
            response.status_code,
            outcome or "-",
            json.dumps(timings, separators=(",", ":")),
        )
        return response

    async def _retouch(request: Request, started: float):
        denied = _authorize(request, settings.auth_token)
        if denied is not None:
            return denied, None, None, None, {}

        declared = request.headers.get("content-length")
        if declared is not None and declared.strip().isdigit() and int(declared) > MAX_BODY_BYTES:
            raise WireError(413, "too_large")
        # Admitted before a byte of the body is read: receive, parse, decode, the engine, enforce and
        # encode all happen under this ticket, so a refused request never allocates its image.
        ticket = runtime.admit()
        try:
            return await _admitted(request, started, ticket)
        finally:
            ticket.release()

    async def _admitted(request: Request, started: float, ticket: Ticket):
        body = bytearray()
        async for chunk in request.stream():
            body += chunk
            if len(body) > MAX_BODY_BYTES:
                raise WireError(413, "too_large")

        timings: dict[str, float] = {"receive": _ms(started)}
        boundary = wire.boundary_of(request.headers.get("content-type"))
        stage = time.perf_counter()
        parts = await run_in_threadpool(wire.parse_multipart, bytes(body), boundary)
        del body
        meta = wire.parse_metadata(parts["metadata"])
        request_id = meta.request_id

        def fail(status: int, code: str) -> WireError:
            err = WireError(status, code, request_id)
            err.kind = meta.kind  # type: ignore[attr-defined]
            return err

        if runtime.status() != "ready":
            raise fail(503, "not_ready")
        if meta.kind not in runtime.engines:
            raise fail(422, "unsupported_kind")
        engine = runtime.engines[meta.kind]
        if meta.expected_engine_version != engine.version:
            raise fail(409, "engine_mismatch")
        try:
            image, allowed = await run_in_threadpool(wire.decode_request_images, parts, meta)
        except WireError as exc:
            raise fail(exc.status, exc.code) from None
        del parts
        timings["decode"] = _ms(stage)

        guaranteed = Guaranteed(None, None)
        if allowed.any():

            engine_ms: list[float] = []

            def work(stop: threading.Event) -> EngineOutput | None:
                began = time.perf_counter()
                try:
                    return engine.run(np.array(image, copy=True), np.array(allowed, copy=True), stop)
                finally:
                    engine_ms.append(_ms(began))

            try:
                result = await runtime.run_job(request, ticket, work, request_id)
            except WireError as exc:
                raise fail(exc.status, exc.code) from None
            if result is None:
                return Response(status_code=499), request_id, meta.kind, None, timings
            output, queue_ms = result
            timings["queue"] = round(queue_ms, 1)
            timings["engine"] = engine_ms[0] if engine_ms else 0.0
            if output is not None:
                timings.update({f"engine_{k}": v for k, v in output.timings.items()})
            stage = time.perf_counter()
            guaranteed = await run_in_threadpool(enforce, image, allowed, output)
            timings["enforce"] = _ms(stage)

        stage = time.perf_counter()
        outcome = "corrected" if guaranteed.corrected else "no_change"
        metadata = {
            "request_id": request_id,
            "contract_version": CONTRACT_VERSION,
            "kind": meta.kind,
            "engine_version": engine.version,
            "outcome": outcome,
            "width": meta.width,
            "height": meta.height,
        }
        images: list[tuple[str, str, bytes]] = []
        if guaranteed.corrected:
            candidate_png, support_png = await run_in_threadpool(
                lambda: (wire.encode_rgba_png(guaranteed.candidate), wire.encode_mask_png(guaranteed.support))
            )
            images = [("candidate", "image/png", candidate_png), ("change_support", "image/png", support_png)]
        timings["encode"] = _ms(stage)
        metadata["timing_ms"] = {**timings, "total": _ms(started)}
        parts_out = [("metadata", "application/json", json.dumps(metadata).encode("utf-8")), *images]
        payload, content_type = wire.encode_multipart(parts_out)
        return Response(content=payload, media_type=content_type), request_id, meta.kind, outcome, timings

    return app
