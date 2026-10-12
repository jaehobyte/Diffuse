"""Subject extraction: the `Extractor` seam and the SAM 3 HTTP adapter (specs/multishot_api.md §3.2).

The adapter speaks the SAM 3 service's v1 wire (`/home/jaeho/sam3-server/specs/api.md` on the
reference host): upload → one prompt (`text` or `points`, `format=png`) → DELETE, one session per
image. It returns every mask the service answered with; choosing among them is the pipeline's job.
"""

from __future__ import annotations

import base64
import binascii
import io
import logging
import re
import threading
import time
from dataclasses import dataclass
from typing import Protocol

import httpx
import numpy as np
from PIL import Image

from app.config import Settings
from app.errors import ApiError, Cancelled

log = logging.getLogger("multishot")

TEXT_PROMPT = "person"
TEXT_THRESHOLD = 0.5
TEXT_MAX_INSTANCES = 20
IMAGE_ID = re.compile(r"[A-Za-z0-9_-]{1,128}")
UNAVAILABLE = {401, 403, 429}


@dataclass
class JobContext:
    """One request's identity, stop signal and absolute deadline (`time.monotonic()`)."""

    request_id: str
    stop: threading.Event
    deadline: float

    def remaining(self) -> float:
        return self.deadline - time.monotonic()

    def check(self) -> None:
        """Raise before starting a step the handler no longer waits for."""
        if self.stop.is_set():
            raise Cancelled()
        if self.remaining() <= 0:
            raise ApiError(504, "timeout")


class Extractor(Protocol):
    def ready(self) -> bool: ...

    def extract(
        self, png: bytes, size: tuple[int, int], point: tuple[float, float] | None, ctx: JobContext
    ) -> list[np.ndarray]:
        """Candidate masks (bool, h×w = [size]) for one image. Raises ApiError / Cancelled."""
        ...


class _SessionExpired(Exception):
    pass


def unavailable() -> ApiError:
    return ApiError(503, "segmentation_unavailable")


def bad_upstream() -> ApiError:
    return ApiError(502, "invalid_upstream_response")


class Sam3Extractor:
    """The real adapter. `transport` is for tests (httpx.MockTransport); serving never passes one."""

    def __init__(self, settings: Settings, transport: httpx.BaseTransport | None = None) -> None:
        self._settings = settings
        self._client = httpx.Client(
            base_url=settings.sam3_url,
            headers={"Authorization": f"Bearer {settings.sam3_token}"},
            transport=transport,
            follow_redirects=False,
        )

    def close(self) -> None:
        self._client.close()

    # -- health -----------------------------------------------------------------------------

    def ready(self) -> bool:
        timeout = self._settings.sam3_cleanup_timeout_s
        try:
            health = self._client.get("/healthz", timeout=timeout)
            if health.status_code != 200 or health.json().get("status") != "ok":
                return False
            # Authenticated: a wrong token is "not ready" too.
            return self._client.get("/v1/meta", timeout=timeout).status_code == 200
        except Exception:  # noqa: BLE001 - any failure is "not ready"
            return False

    # -- extraction -------------------------------------------------------------------------

    def extract(
        self, png: bytes, size: tuple[int, int], point: tuple[float, float] | None, ctx: JobContext
    ) -> list[np.ndarray]:
        # A 410 lets this image be uploaded again and its prompt replayed once; a second is a failure.
        for attempt in range(2):
            ctx.check()
            image_id = self._upload(png, size, ctx)
            try:
                ctx.check()
                return self._prompt(image_id, size, point, ctx)
            except _SessionExpired:
                if attempt == 1:
                    raise unavailable() from None
            finally:
                self._delete(image_id, ctx)
        raise unavailable()  # not reached

    def _timeout(self, ctx: JobContext) -> httpx.Timeout:
        remaining = ctx.remaining()
        if remaining <= 0:
            raise ApiError(504, "timeout")
        connect = min(self._settings.sam3_connect_timeout_s, remaining)
        read = min(self._settings.sam3_read_timeout_s, remaining)
        return httpx.Timeout(connect=connect, read=read, write=read, pool=connect)

    def _send(self, ctx: JobContext, method: str, path: str, **kwargs) -> httpx.Response:
        try:
            return self._client.request(method, path, timeout=self._timeout(ctx), **kwargs)
        except (httpx.ConnectError, httpx.ConnectTimeout):
            if ctx.remaining() <= 0:
                raise ApiError(504, "timeout") from None
            raise unavailable() from None
        except httpx.TimeoutException:
            raise ApiError(504, "timeout") from None
        except httpx.HTTPError:
            raise unavailable() from None

    @staticmethod
    def _check_status(response: httpx.Response, expected: tuple[int, ...]) -> None:
        if response.status_code in expected:
            return
        if response.status_code == 410:
            raise _SessionExpired()
        if response.status_code in UNAVAILABLE or response.status_code >= 500:
            raise unavailable()
        raise bad_upstream()

    @staticmethod
    def _json(response: httpx.Response) -> dict:
        try:
            data = response.json()
        except ValueError:
            raise bad_upstream() from None
        if not isinstance(data, dict):
            raise bad_upstream()
        return data

    def _upload(self, png: bytes, size: tuple[int, int], ctx: JobContext) -> str:
        response = self._send(ctx, "POST", "/v1/images", files={"file": ("image.png", png, "image/png")})
        try:
            self._check_status(response, (200, 201))
        except _SessionExpired:
            raise bad_upstream() from None
        data = self._json(response)
        image_id = data.get("image_id")
        if not isinstance(image_id, str) or not IMAGE_ID.fullmatch(image_id):
            raise bad_upstream()
        if (data.get("width"), data.get("height")) != size:
            self._delete(image_id, ctx)
            raise bad_upstream()
        return image_id

    def _prompt(
        self, image_id: str, size: tuple[int, int], point: tuple[float, float] | None, ctx: JobContext
    ) -> list[np.ndarray]:
        if point is None:
            path = f"/v1/images/{image_id}/segment/text"
            body = {
                "prompt": TEXT_PROMPT,
                "threshold": TEXT_THRESHOLD,
                "max_instances": TEXT_MAX_INSTANCES,
                "format": "png",
            }
        else:
            path = f"/v1/images/{image_id}/segment/points"
            body = {"points": [[point[0], point[1]]], "labels": [1], "multimask": False, "format": "png"}
        response = self._send(ctx, "POST", path, json=body)
        self._check_status(response, (200,))
        masks = self._json(response).get("masks")
        if not isinstance(masks, list):
            raise bad_upstream()
        return [decode_mask(mask, size) for mask in masks]

    def _delete(self, image_id: str, ctx: JobContext) -> None:
        """Best effort, its own short timeout; a failure is logged and never replaces the outcome."""
        try:
            response = self._client.delete(f"/v1/images/{image_id}", timeout=self._settings.sam3_cleanup_timeout_s)
            if response.status_code not in (200, 204):
                log.warning("request_id=%s sam3_delete_failed=http_%d", ctx.request_id, response.status_code)
        except Exception as exc:  # noqa: BLE001
            log.warning("request_id=%s sam3_delete_failed=%s", ctx.request_id, exc.__class__.__name__)


def decode_mask(mask: object, size: tuple[int, int]) -> np.ndarray:
    """A `format=png` mask: base64 8-bit grayscale PNG whose **brightness** is the mask, 0 or 255."""
    if not isinstance(mask, dict) or not isinstance(mask.get("png"), str):
        raise bad_upstream()
    try:
        data = base64.b64decode(mask["png"], validate=True)
        with Image.open(io.BytesIO(data), formats=["PNG"]) as img:
            if img.size != size or img.mode not in ("L", "1"):
                raise bad_upstream()
            values = np.asarray(img.convert("L"), dtype=np.uint8)
    except ApiError:
        raise
    except (binascii.Error, ValueError, OSError, Image.DecompressionBombError):
        raise bad_upstream() from None
    if np.any((values != 0) & (values != 255)):
        raise bad_upstream()
    return values == 255
