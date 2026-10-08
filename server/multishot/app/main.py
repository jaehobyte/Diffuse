"""HTTP surface of API contract v1: `GET /health` and `POST /v1/multishot` (specs/multishot_api.md).

`create_app(settings, extractor_factory)` is the only way to build the app. Tests inject a fake
extractor; `app.serve` always passes the real SAM 3 adapter.

Order per request: auth → admission → bounded body read and parse → input checks → the worker
(validate, extract, composite). The admission ticket is held by the worker thread too, so a worker
that outlives its request (deadline, disconnect) keeps its place until it returns.
"""

from __future__ import annotations

import asyncio
import hmac
import io
import json
import logging
import secrets
import threading
import time
import uuid
from contextlib import asynccontextmanager, suppress
from typing import Callable

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response
from starlette.concurrency import run_in_threadpool
from starlette.requests import ClientDisconnect

from app import pipeline
from app.config import CONTRACT_VERSION, MIN_IMAGES, RETRY_AFTER_S, Settings
from app.errors import ApiError
from app.options import parse_options
from app.sam import Extractor, JobContext
from app.upload import Spool, Upload, UploadParser, boundary_of

log = logging.getLogger("multishot")

ExtractorFactory = Callable[[Settings], Extractor]
FEED_BYTES = 1024 * 1024


def error_response(error: ApiError, request_id: str) -> JSONResponse:
    content: dict = {"error": error.code, "request_id": request_id}
    if error.image_index is not None:
        content["image_index"] = error.image_index
    headers = None
    if error.status == 429:
        headers = {"Retry-After": str(RETRY_AFTER_S)}
    elif error.status == 401:
        headers = {"WWW-Authenticate": "Bearer"}
    return JSONResponse(status_code=error.status, content=content, headers=headers)


class Ticket:
    """One admitted request's place; released when both the handler and its worker let go."""

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
    """Admission (running + waiting, default 1 + 0) and the worker. One per app."""

    def __init__(self, settings: Settings, extractor: Extractor) -> None:
        self.settings = settings
        self.extractor = extractor
        self.capacity = settings.max_running + settings.max_queued
        self.in_flight = 0
        self.slots = asyncio.Semaphore(settings.max_running)

    def admit(self) -> Ticket:
        if self.in_flight >= self.capacity:
            raise ApiError(429, "overloaded")
        self.in_flight += 1
        return Ticket(self)

    async def run(self, request: Request, ticket: Ticket, spool: Spool, job: pipeline.Job) -> pipeline.Outcome | None:
        """The worker under a slot. None if the client left; ApiError 504 at the deadline."""

        async def queued() -> pipeline.Outcome:
            await self.slots.acquire()
            ticket.retain()
            spool.retain()
            task = asyncio.ensure_future(asyncio.to_thread(pipeline.run, job, self.extractor))

            def release(finished: asyncio.Future) -> None:
                self.slots.release()
                spool.release()
                ticket.release()
                if not finished.cancelled():
                    finished.exception()  # retrieved so an abandoned failure is not re-logged

            task.add_done_callback(release)
            return await asyncio.shield(task)

        work = asyncio.ensure_future(queued())
        gone = asyncio.ensure_future(_disconnected(request))
        try:
            done, _ = await asyncio.wait(
                {work, gone}, timeout=max(0.0, job.ctx.remaining()), return_when=asyncio.FIRST_COMPLETED
            )
        finally:
            gone.cancel()
        if work in done:
            return work.result()
        # The handler stops waiting: a waiting job gives its place back, a running one is told to stop
        # before its next step and keeps the slot until its thread returns; its result is dropped.
        job.ctx.stop.set()
        work.cancel()
        with suppress(BaseException):
            await work
        if gone in done:
            return None
        raise ApiError(504, "timeout")


async def _disconnected(request: Request) -> None:
    while True:
        message = await request.receive()
        if message["type"] == "http.disconnect":
            return


def _authorized(request: Request, token: str) -> bool:
    header = request.headers.get("authorization") or ""
    scheme, _, credentials = header.partition(" ")
    if scheme.lower() != "bearer" or not credentials.strip():
        return False
    return hmac.compare_digest(credentials.strip().encode("utf-8"), token.encode("utf-8"))


def encode_multipart(parts: list[tuple[str, str, bytes]]) -> tuple[bytes, str]:
    """[(name, content_type, body)] → (body, Content-Type header value)."""
    boundary = "multishot-" + secrets.token_hex(16)
    out = io.BytesIO()
    for name, content_type, body in parts:
        out.write(f"--{boundary}\r\n".encode())
        out.write(f'Content-Disposition: form-data; name="{name}"\r\n'.encode())
        out.write(f"Content-Type: {content_type}\r\n\r\n".encode())
        out.write(body)
        out.write(b"\r\n")
    out.write(f"--{boundary}--\r\n".encode())
    return out.getvalue(), f"multipart/form-data; boundary={boundary}"


def create_app(settings: Settings, extractor_factory: ExtractorFactory) -> FastAPI:
    extractor = extractor_factory(settings)
    runtime = Runtime(settings, extractor)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        yield
        close = getattr(extractor, "close", None)
        if close is not None:
            close()

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.runtime = runtime

    @app.get("/health")
    async def health(request: Request):
        request_id = uuid.uuid4().hex
        if not _authorized(request, settings.auth_token):
            return error_response(ApiError(401, "unauthorized"), request_id)
        ready = await run_in_threadpool(extractor.ready)
        return JSONResponse(
            status_code=200 if ready else 503,
            content={"contract_version": CONTRACT_VERSION, "status": "ready" if ready else "unavailable"},
        )

    @app.post("/v1/multishot")
    async def multishot(request: Request):
        started = time.monotonic()
        request_id = uuid.uuid4().hex
        seen: dict[str, int] = {}
        try:
            response = await _multishot(request, request_id, started, seen)
        except ApiError as exc:
            response = error_response(exc, request_id)
        except ClientDisconnect:
            response = Response(status_code=499)
        except Exception as exc:  # noqa: BLE001
            log.error("request_id=%s internal_error=%s", request_id, exc.__class__.__name__)
            response = error_response(ApiError(500, "internal_error"), request_id)
        error = None
        if response.status_code != 200 and isinstance(response, JSONResponse):
            error = json.loads(response.body).get("error")
        log.info(
            "request_id=%s status=%d error=%s images=%s total_ms=%.0f",
            request_id,
            response.status_code,
            error or "-",
            seen.get("images", "-"),
            (time.monotonic() - started) * 1000.0,
        )
        return response

    async def _multishot(request: Request, request_id: str, started: float, seen: dict[str, int]) -> Response:
        # Nothing of the body is read before auth and admission.
        if not _authorized(request, settings.auth_token):
            raise ApiError(401, "unauthorized")
        ticket = runtime.admit()
        try:
            declared = request.headers.get("content-length")
            if declared is not None and declared.strip().isdigit() and int(declared) > settings.max_body_bytes:
                raise ApiError(413, "too_large")
            ctx = JobContext(request_id, threading.Event(), started + settings.deadline_s)
            spool = Spool(settings.tmp_dir)
            try:
                upload = await _receive(request, spool, ctx)
                count = seen["images"] = len(upload.images)
                if count < MIN_IMAGES:
                    raise ApiError(422, "invalid_image_count")
                options = parse_options(upload.metadata, count)
                job = pipeline.Job(request_id, spool.path, upload.images, options, ctx, settings.max_source_pixels)
                outcome = await runtime.run(request, ticket, spool, job)
            finally:
                spool.release()
        finally:
            ticket.release()
        if outcome is None:
            return Response(status_code=499)
        payload, content_type = encode_multipart(
            [
                ("metadata", "application/json", json.dumps(outcome.metadata).encode("utf-8")),
                ("image", "image/png", outcome.png),
            ]
        )
        return Response(content=payload, media_type=content_type)

    async def _receive(request: Request, spool: Spool, ctx: JobContext) -> Upload:
        parser = UploadParser(boundary_of(request.headers.get("content-type")), spool, settings)

        async def read() -> Upload:
            received = 0
            pending = bytearray()
            async for chunk in request.stream():
                received += len(chunk)
                if received > settings.max_body_bytes:
                    raise ApiError(413, "too_large")
                pending += chunk
                if len(pending) >= FEED_BYTES:
                    await run_in_threadpool(parser.feed, bytes(pending))
                    pending.clear()
            if pending:
                await run_in_threadpool(parser.feed, bytes(pending))
            return await run_in_threadpool(parser.finish)

        try:
            return await asyncio.wait_for(read(), timeout=max(0.0, ctx.remaining()))
        except TimeoutError:
            raise ApiError(504, "timeout") from None
        finally:
            parser.close()

    return app
